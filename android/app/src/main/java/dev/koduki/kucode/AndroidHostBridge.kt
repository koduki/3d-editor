package dev.koduki.kucode

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import org.json.JSONObject
import java.io.File
import java.nio.channels.Channels
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal data class ParsedApkInfo(
    val packageName: String,
    val signerDigests: Set<String>,
    val historyDigests: Set<String> = signerDigests,
)

internal data class InstalledPackageSigners(
    val currentDigests: Set<String>,
    val historyDigests: Set<String> = currentDigests,
)

internal interface AndroidBridgePlatform {
    val ownPackageName: String
    val filesDir: File
    val stageDir: File
    fun canRequestPackageInstalls(): Boolean
    fun areNotificationsEnabled(): Boolean
    fun isInstallChannelEnabled(): Boolean
    fun parseApk(apk: File): ParsedApkInfo?
    fun getInstalledSigners(packageName: String): InstalledPackageSigners?
    fun createSession(packageName: String, sizeBytes: Long): Int
    fun writeAndCommitSession(sessionId: Int, apk: File, operationId: String)
    fun abandonSession(sessionId: Int)
    fun abandonOwnedSessions()
    fun notifyPendingUserAction(sessionId: Int, packageName: String, confirmIntent: Any)
    fun cancelNotification(sessionId: Int)
}

/**
 * Pure-JVM state machine & request router for the authenticated Android host bridge API.
 * All policy checks, staging limits, signature verification, cancellation, and expiry
 * transitions are unit-tested without an Android runtime.
 */
internal class AndroidHostBridgeCore(
    private val platform: AndroidBridgePlatform,
    private val worker: Executor,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val maxApkBytes: Long = MAX_APK_BYTES,
) {
    private data class Operation(
        val id: String,
        val createdAt: Long,
        var state: String = "staging",
        var packageName: String = "",
        var message: String = "",
        var sessionId: Int? = null,
    ) {
        fun json() = JSONObject().put("version", 1).put("operationId", id)
            .put("state", state).put("packageName", packageName).put("message", message)
    }

    private val ops = linkedMapOf<String, Operation>()

    fun cleanupAbandonedOnStartup() {
        worker.execute {
            runCatching { platform.abandonOwnedSessions() }
            runCatching { deleteTreeNoFollow(platform.stageDir) }
        }
    }

    fun handle(method: String, path: String, body: String, launch: (String) -> Boolean): Pair<Int, String> {
        fun answer(code: Int, state: String, message: String = "") =
            code to JSONObject().put("version", 1).put("state", state).put("message", message).toString()
        return try {
            val data = if (body.isBlank()) JSONObject() else JSONObject(body)
            when {
                method == "GET" && path == "/api/v1/android/capabilities" -> {
                    val notifOk = platform.areNotificationsEnabled() && platform.isInstallChannelEnabled()
                    200 to JSONObject().put("version", 1)
                        .put("canInstall", platform.canRequestPackageInstalls())
                        .put("notifications", notifOk)
                        .put("packageName", platform.ownPackageName).toString()
                }
                method == "POST" && path == "/api/v1/android/install" -> {
                    if (!platform.canRequestPackageInstalls()) {
                        return answer(403, "failed", "Allow KuCode to install unknown apps in Android Settings.")
                    }
                    if (!platform.areNotificationsEnabled() || !platform.isInstallChannelEnabled()) {
                        return answer(403, "failed", "Enable KuCode notifications to receive the Android confirmation prompt.")
                    }
                    val source = AndroidInstallPolicy.resolveApk(data.getString("path"), platform.filesDir)
                    val expected = data.optString("packageName").takeIf { it.isNotEmpty() }
                    if (expected != null) AndroidInstallPolicy.checkTarget(expected, platform.ownPackageName)
                    val operation = synchronized(ops) {
                        val now = clockMs()
                        ops.entries.removeAll { (_, value) ->
                            value.state in TERMINAL && now - value.createdAt > 30 * 60_000L
                        }
                        if (ops.size >= 64 || ops.values.count { it.state !in TERMINAL } >= 2) {
                            return answer(409, "failed", "Too many pending operations; wait or cancel.")
                        }
                        Operation(UUID.randomUUID().toString(), createdAt = now).also { ops[it.id] = it }
                    }
                    worker.execute { install(operation, source, expected) }
                    synchronized(ops) { 202 to operation.json().toString() }
                }
                method == "GET" && path.startsWith("/api/v1/android/operations/") -> {
                    val id = path.removePrefix("/api/v1/android/operations/")
                    if (id.isEmpty() || '/' in id) {
                        return answer(404, "interrupted", "Unknown operation (host may have restarted).")
                    }
                    synchronized(ops) {
                        ops[id]?.let { 200 to it.json().toString() }
                            ?: answer(404, "interrupted", "Unknown operation (host may have restarted).")
                    }
                }
                method == "POST" && path == "/api/v1/android/cancel" -> {
                    val id = data.getString("operationId")
                    synchronized(ops) {
                        val op = ops[id] ?: return answer(404, "interrupted", "Unknown operation (host may have restarted).")
                        if (op.state !in TERMINAL) {
                            val session = op.sessionId
                            if (session != null && runCatching { platform.abandonSession(session) }.isFailure) {
                                return answer(409, "failed", "Android could not cancel this session; query its current status.")
                            }
                            finish(op, "cancelled", "Cancelled by caller")
                        }
                        200 to op.json().toString()
                    }
                }
                method == "POST" && path == "/api/v1/android/launch" -> {
                    val pkg = data.getString("packageName")
                    AndroidInstallPolicy.checkTarget(pkg, platform.ownPackageName, allowDevSelf = true, forLaunch = true)
                    if (launch(pkg)) answer(200, "launched")
                    else answer(409, "failed", "Keep KuCode foreground and ensure the app has a launcher activity.")
                }
                else -> answer(404, "failed", "Unknown Android bridge endpoint")
            }
        } catch (e: Exception) {
            answer(400, "failed", LogRedactor.redact(e.message ?: "Invalid request").take(240))
        }
    }

    private fun install(op: Operation, source: File, expected: String?) {
        var staged: File? = null
        try {
            if (synchronized(ops) { op.state in TERMINAL }) return
            platform.stageDir.mkdirs()
            val apk = File.createTempFile("install-", ".apk", platform.stageDir)
            staged = apk
            // The private staged copy, not the caller's mutable input, is parsed and streamed.
            Files.newByteChannel(source.toPath(), setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)).use { channel ->
                Channels.newInputStream(channel).use { input ->
                    apk.outputStream().use { out ->
                        val buffer = ByteArray(128 * 1024)
                        var total = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            total += n
                            require(total <= maxApkBytes) { "APK exceeds staging limit" }
                            out.write(buffer, 0, n)
                        }
                    }
                }
            }
            val archive = platform.parseApk(apk) ?: error("APK is unreadable")
            val pkg = archive.packageName
            AndroidInstallPolicy.checkTarget(pkg, platform.ownPackageName)
            require(expected == null || expected == pkg) { "APK package does not match expected package" }
            require(archive.signerDigests.isNotEmpty()) { "APK is unsigned" }
            val installed = platform.getInstalledSigners(pkg)
            if (installed != null) {
                require(installed.currentDigests.isNotEmpty()) { "Installed app signing info unavailable" }
                val matchesDirectly = installed.currentDigests == archive.signerDigests
                val matchesRotationLineage = archive.historyDigests.containsAll(installed.currentDigests)
                require(matchesDirectly || matchesRotationLineage) {
                    "APK signature differs from existing installation"
                }
            }
            val sessionId = synchronized(ops) {
                if (op.state in TERMINAL) return
                platform.createSession(pkg, apk.length()).also {
                    op.sessionId = it
                    op.packageName = pkg
                    op.state = "submitting"
                }
            }
            platform.writeAndCommitSession(sessionId, apk, op.id)
            synchronized(ops) {
                if (op.state !in TERMINAL) {
                    op.state = "submitted"
                }
            }
        } catch (e: Exception) {
            finish(op, "failed", LogRedactor.redact(e.message ?: "Install failed").take(240), abandon = true)
        } finally {
            staged?.delete()
        }
    }

    fun onResult(
        action: String?,
        operationId: String?,
        sessionId: Int,
        status: Int,
        statusMessage: String?,
        confirmIntent: Any?,
    ) {
        if (action != RESULT_ACTION) return
        val id = operationId ?: return
        val op = synchronized(ops) { ops[id] } ?: return
        if (sessionId != op.sessionId) return
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                if (confirmIntent == null) {
                    finish(op, "failed", "Android did not supply confirmation UI", abandon = true)
                    return
                }
                synchronized(ops) {
                    if (op.state in TERMINAL) return
                    op.state = "awaiting_user"
                    op.message = "Open the KuCode notification to approve in Android."
                    val activeSession = op.sessionId ?: return
                    try {
                        require(platform.areNotificationsEnabled() && platform.isInstallChannelEnabled()) {
                            "Enable Android development install notifications."
                        }
                        platform.notifyPendingUserAction(activeSession, op.packageName, confirmIntent)
                    } catch (e: Exception) {
                        finish(op, "failed", LogRedactor.redact(e.message ?: "Cannot show Android confirmation"), abandon = true)
                    }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> finish(op, "succeeded", "Installed")
            PackageInstaller.STATUS_FAILURE_ABORTED -> finish(op, "cancelled", "Android installation declined or aborted")
            else -> finish(
                op,
                "failed",
                "Android installer status $status: " + LogRedactor.redact(statusMessage.orEmpty()).take(200),
            )
        }
    }

    fun expire() {
        val now = clockMs()
        val expired = synchronized(ops) {
            ops.values.filter { it.state !in TERMINAL && now - it.createdAt > EXPIRY_MS }
        }
        expired.forEach { finish(it, "timed_out", "Install operation expired", abandon = true) }
    }

    private fun finish(op: Operation, state: String, message: String, abandon: Boolean = false) {
        val sessionId = synchronized(ops) {
            if (op.state in TERMINAL) return
            op.state = state
            op.message = message
            op.sessionId
        }
        if (sessionId != null) {
            if (abandon) runCatching { platform.abandonSession(sessionId) }
            runCatching { platform.cancelNotification(sessionId) }
        }
    }

    companion object {
        internal const val RESULT_ACTION = "dev.koduki.kucode.INSTALL_RESULT"
        internal const val MAX_APK_BYTES = 1024L * 1024L * 1024L
        internal const val EXPIRY_MS = 15 * 60_000L
        private val TERMINAL = setOf("succeeded", "failed", "cancelled", "timed_out")
    }
}

/** The bridge uses only the existing authenticated loopback server and OS install UI. */
internal class AndroidHostBridge private constructor(private val context: Context) {
    private val pm = context.packageManager
    private val installer = pm.packageInstaller
    private val worker = Executors.newSingleThreadExecutor()
    private val cleanup = Executors.newSingleThreadScheduledExecutor()
    private val notifications = requireNotNull(context.getSystemService(NotificationManager::class.java))

    private val platform = object : AndroidBridgePlatform {
        override val ownPackageName: String get() = context.packageName
        override val filesDir: File get() = context.filesDir
        override val stageDir: File = File(context.cacheDir, "android-install")

        override fun canRequestPackageInstalls(): Boolean = pm.canRequestPackageInstalls()

        override fun areNotificationsEnabled(): Boolean = notifications.areNotificationsEnabled()

        override fun isInstallChannelEnabled(): Boolean {
            ensureInstallChannel()
            return notifications.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
        }

        override fun parseApk(apk: File): ParsedApkInfo? {
            val archive = pm.getPackageArchiveInfo(
                apk.absolutePath,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
            ) ?: return null
            val signingInfo = archive.signingInfo ?: return ParsedApkInfo(archive.packageName, emptySet(), emptySet())
            val current = signingInfo.apkContentsSigners?.let(::fingerprints).orEmpty()
            val history = signingInfo.signingCertificateHistory?.let(::fingerprints).orEmpty() + current
            return ParsedApkInfo(archive.packageName, current, history)
        }

        override fun getInstalledSigners(packageName: String): InstalledPackageSigners? {
            val installed = runCatching {
                pm.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
                )
            }.getOrNull() ?: return null
            val signingInfo = installed.signingInfo ?: return InstalledPackageSigners(emptySet(), emptySet())
            val current = signingInfo.apkContentsSigners?.let(::fingerprints).orEmpty()
            val history = signingInfo.signingCertificateHistory?.let(::fingerprints).orEmpty() + current
            return InstalledPackageSigners(current, history)
        }

        override fun createSession(packageName: String, sizeBytes: Long): Int {
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(packageName)
                setSize(sizeBytes)
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
            }
            return installer.createSession(params)
        }

        override fun writeAndCommitSession(sessionId: Int, apk: File, operationId: String) {
            installer.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { output ->
                    apk.inputStream().use { it.copyTo(output) }
                    session.fsync(output)
                }
                val callback = Intent(context, AndroidInstallResultReceiver::class.java)
                    .setAction(AndroidHostBridgeCore.RESULT_ACTION)
                    .putExtra("operationId", operationId)
                val pending = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    callback,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                session.commit(pending.intentSender)
            }
        }

        override fun abandonSession(sessionId: Int) {
            installer.abandonSession(sessionId)
        }

        override fun abandonOwnedSessions() {
            installer.mySessions.forEach { installer.abandonSession(it.sessionId) }
        }

        override fun notifyPendingUserAction(sessionId: Int, packageName: String, confirmIntent: Any) {
            val confirm = confirmIntent as Intent
            val show = PendingIntent.getActivity(
                context,
                sessionId,
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            ensureInstallChannel()
            notifications.notify(
                CHANNEL,
                sessionId,
                Notification.Builder(context, CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle("${context.getString(R.string.app_name_variant)}: confirm APK installation")
                    .setContentText(packageName)
                    .setContentIntent(show)
                    .setAutoCancel(true)
                    .build(),
            )
        }

        override fun cancelNotification(sessionId: Int) {
            notifications.cancel(CHANNEL, sessionId)
        }

        private fun ensureInstallChannel() {
            notifications.createNotificationChannel(
                NotificationChannel(CHANNEL, "Android development installs", NotificationManager.IMPORTANCE_HIGH),
            )
        }

        private fun fingerprints(signatures: Array<android.content.pm.Signature>): Set<String> =
            signatures.map {
                MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) }
            }.toSet()
    }

    private val core = AndroidHostBridgeCore(platform, worker)

    init {
        // An operation cannot survive a host process restart. Release sessions
        // owned by this installer only; the caller receives interrupted/not found.
        core.cleanupAbandonedOnStartup()
        cleanup.scheduleAtFixedRate({ core.expire() }, 1, 1, TimeUnit.MINUTES)
    }

    fun handle(method: String, path: String, body: String, launch: (String) -> Boolean): Pair<Int, String> =
        core.handle(method, path, body, launch)

    fun onResult(intent: Intent) {
        core.onResult(
            action = intent.action,
            operationId = intent.getStringExtra("operationId"),
            sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1),
            status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE),
            statusMessage = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE),
            confirmIntent = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java),
        )
    }

    companion object {
        private const val CHANNEL = "kucode-android-install"
        @Volatile private var instance: AndroidHostBridge? = null
        fun get(context: Context): AndroidHostBridge = instance ?: synchronized(this) {
            instance ?: AndroidHostBridge(context.applicationContext).also { instance = it }
        }
    }
}

class AndroidInstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AndroidHostBridge.get(context).onResult(intent)
    }
}

internal object AndroidInstallPolicy {
    private val packagePattern = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
    fun checkTarget(
        packageName: String,
        ownPackage: String,
        allowDevSelf: Boolean = false,
        forLaunch: Boolean = false,
    ) {
        require(packagePattern.matches(packageName)) { "Invalid package name" }
        require(packageName != "dev.koduki.kucode") {
            if (forLaunch) "Stable KuCode cannot be launched through this bridge"
            else "Stable KuCode cannot be changed through this bridge"
        }
        require(allowDevSelf || packageName != ownPackage) { "Cannot replace the running host through this bridge" }
    }

    fun resolveApk(guestPath: String, filesDir: File): File {
        require(guestPath.length < 4096 && '\u0000' !in guestPath && guestPath.endsWith(".apk")) { "Expected an APK path" }
        val root = when {
            guestPath.startsWith("/workspace/") -> File(filesDir, "workspace")
            guestPath.startsWith("/root/") -> File(filesDir, "home")
            else -> throw IllegalArgumentException("APK must be under /workspace or HOME")
        }.canonicalFile
        val relative = guestPath.substringAfter('/', "").substringAfter('/', "")
        val input = File(root, relative)
        require(!Files.isSymbolicLink(input.toPath())) { "APK path must be a regular file" }
        val resolved = input.canonicalFile
        require(resolved.toPath().startsWith(root.toPath()) && resolved.isFile) { "APK path is outside the permitted directory or missing" }
        return resolved
    }
}
