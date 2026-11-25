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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** The bridge uses only the existing authenticated loopback server and OS install UI. */
internal class AndroidHostBridge private constructor(private val context: Context) {
    private data class Operation(
        val id: String,
        val createdAt: Long = System.currentTimeMillis(),
        var state: String = "staging",
        var packageName: String = "",
        var message: String = "",
        var sessionId: Int? = null,
    ) {
        fun json() = JSONObject().put("version", 1).put("operationId", id)
            .put("state", state).put("packageName", packageName).put("message", message)
    }

    private val pm = context.packageManager
    private val installer = pm.packageInstaller
    private val ops = linkedMapOf<String, Operation>()
    private val worker = Executors.newSingleThreadExecutor()
    private val cleanup = Executors.newSingleThreadScheduledExecutor()
    private val notifications = requireNotNull(context.getSystemService(NotificationManager::class.java))
    private val stageDir = File(context.cacheDir, "android-install")

    init {
        // An operation cannot survive a host process restart. Release sessions
        // owned by this installer only; the caller receives interrupted/not found.
        worker.execute {
            runCatching { installer.mySessions.forEach { installer.abandonSession(it.sessionId) } }
            runCatching { deleteTreeNoFollow(stageDir) }
        }
        cleanup.scheduleAtFixedRate({ expire() }, 1, 1, TimeUnit.MINUTES)
    }

    fun handle(method: String, path: String, body: String, launch: (String) -> Boolean): Pair<Int, String> {
        fun answer(code: Int, state: String, message: String = "") =
            code to JSONObject().put("version", 1).put("state", state).put("message", message).toString()
        return try {
            val data = if (body.isBlank()) JSONObject() else JSONObject(body)
            when {
                method == "GET" && path == "/api/v1/android/capabilities" ->
                    200 to JSONObject().put("version", 1)
                        .put("canInstall", pm.canRequestPackageInstalls())
                        .put("notifications", notifications.areNotificationsEnabled())
                        .put("packageName", context.packageName).toString()
                method == "POST" && path == "/api/v1/android/install" -> {
                    if (!pm.canRequestPackageInstalls()) return answer(403, "failed", "Allow KuCode to install unknown apps in Android Settings.")
                    if (!notifications.areNotificationsEnabled()) return answer(403, "failed", "Enable KuCode notifications to receive the Android confirmation prompt.")
                    val source = AndroidInstallPolicy.resolveApk(data.getString("path"), context.filesDir)
                    val expected = data.optString("packageName").takeIf { it.isNotEmpty() }
                    if (expected != null) AndroidInstallPolicy.checkTarget(expected, context.packageName)
                    val operation = synchronized(ops) {
                        ops.entries.removeAll { (_, value) ->
                            value.state in TERMINAL && System.currentTimeMillis() - value.createdAt > 30 * 60_000L
                        }
                        if (ops.size >= 64 || ops.values.count { it.state !in TERMINAL } >= 2)
                            return answer(409, "failed", "Too many pending operations; wait or cancel.")
                        Operation(UUID.randomUUID().toString()).also { ops[it.id] = it }
                    }
                    worker.execute { install(operation, source, expected) }
                    synchronized(ops) { 202 to operation.json().toString() }
                }
                method == "GET" && path.startsWith("/api/v1/android/operations/") -> {
                    val id = path.substringAfterLast('/')
                    synchronized(ops) {
                        ops[id]?.let { 200 to it.json().toString() }
                            ?: answer(404, "interrupted", "Unknown operation (host may have restarted).")
                    }
                }
                method == "POST" && path == "/api/v1/android/cancel" -> {
                    val id = data.getString("operationId")
                    val op = synchronized(ops) { ops[id] } ?: return answer(404, "interrupted")
                    finish(op, "cancelled", "Cancelled by caller", abandon = true)
                    synchronized(ops) { 200 to op.json().toString() }
                }
                method == "POST" && path == "/api/v1/android/launch" -> {
                    val pkg = data.getString("packageName")
                    AndroidInstallPolicy.checkTarget(pkg, context.packageName, allowDevSelf = true)
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
            stageDir.mkdirs()
            val apk = File.createTempFile("install-", ".apk", stageDir)
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
                            require(total <= MAX_APK_BYTES) { "APK exceeds staging limit" }
                            out.write(buffer, 0, n)
                        }
                    }
                }
            }
            val archive = pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
                ?: error("APK is unreadable")
            val pkg = archive.packageName
            AndroidInstallPolicy.checkTarget(pkg, context.packageName)
            require(expected == null || expected == pkg) { "APK package does not match expected package" }
            val signing = archive.signingInfo?.apkContentsSigners ?: error("APK signature information is missing")
            require(signing.isNotEmpty()) { "APK is unsigned" }
            val installed = runCatching { pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())) }.getOrNull()
            if (installed != null) {
                val current = installed.signingInfo?.apkContentsSigners ?: error("Installed app signing info unavailable")
                fun fingerprint(signatures: Array<android.content.pm.Signature>) = signatures.map {
                    MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) }
                }.toSet()
                require(fingerprint(current) == fingerprint(signing)) { "APK signature differs from existing installation" }
            }
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(pkg)
                setSize(apk.length())
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
            }
            val sessionId = synchronized(ops) {
                if (op.state in TERMINAL) return
                installer.createSession(params).also {
                    op.sessionId = it
                    op.packageName = pkg
                    op.state = "submitting"
                }
            }
            installer.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { output ->
                    apk.inputStream().use { it.copyTo(output) }
                    session.fsync(output)
                }
                synchronized(ops) {
                    if (op.state in TERMINAL) return
                    val callback = Intent(context, AndroidInstallResultReceiver::class.java)
                        .setAction(RESULT_ACTION)
                        .putExtra("operationId", op.id)
                    val pending = PendingIntent.getBroadcast(context, sessionId, callback,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                    session.commit(pending.intentSender)
                    op.state = "submitted"
                }
            }
        } catch (e: Exception) {
            finish(op, "failed", LogRedactor.redact(e.message ?: "Install failed").take(240), abandon = true)
        } finally {
            staged?.delete()
        }
    }

    fun onResult(intent: Intent) {
        if (intent.action != RESULT_ACTION) return
        val id = intent.getStringExtra("operationId") ?: return
        val op = synchronized(ops) { ops[id] } ?: return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1) != op.sessionId) return
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                if (confirm == null) {
                    finish(op, "failed", "Android did not supply confirmation UI", abandon = true)
                    return
                }
                synchronized(ops) {
                    if (op.state in TERMINAL) return
                    op.state = "awaiting_user"
                    op.message = "Open the KuCode notification to approve in Android."
                    val sessionId = op.sessionId ?: return
                    val show = PendingIntent.getActivity(context, sessionId, confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                    notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Android development installs", NotificationManager.IMPORTANCE_HIGH))
                    notifications.notify(sessionId, Notification.Builder(context, CHANNEL)
                        .setSmallIcon(android.R.drawable.stat_sys_download_done)
                        .setContentTitle("KuCode: confirm APK installation")
                        .setContentText(op.packageName)
                        .setContentIntent(show).setAutoCancel(true).build())
                }
            }
            PackageInstaller.STATUS_SUCCESS -> finish(op, "succeeded", "Installed")
            PackageInstaller.STATUS_FAILURE_ABORTED -> finish(op, "cancelled", "Android installation declined or aborted")
            else -> finish(op, "failed", "Android installer status $status: " +
                LogRedactor.redact(intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()).take(200))
        }
    }

    private fun finish(op: Operation, state: String, message: String, abandon: Boolean = false) {
        val sessionId = synchronized(ops) {
            if (op.state in TERMINAL) return
            op.state = state
            op.message = message
            op.sessionId
        }
        if (sessionId != null) {
            if (abandon) runCatching { installer.abandonSession(sessionId) }
            notifications.cancel(sessionId)
        }
    }

    private fun expire() {
        val expired = synchronized(ops) {
            ops.values.filter { it.state !in TERMINAL && System.currentTimeMillis() - it.createdAt > 15 * 60_000L }
        }
        expired.forEach { finish(it, "timed_out", "Install operation expired", abandon = true) }
    }

    companion object {
        private const val RESULT_ACTION = "dev.koduki.kucode.INSTALL_RESULT"
        private const val CHANNEL = "kucode-android-install"
        private const val MAX_APK_BYTES = 1024L * 1024L * 1024L
        private val TERMINAL = setOf("succeeded", "failed", "cancelled", "timed_out")
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
    fun checkTarget(packageName: String, ownPackage: String, allowDevSelf: Boolean = false) {
        require(packagePattern.matches(packageName)) { "Invalid package name" }
        require(packageName != "dev.koduki.kucode") { "Stable KuCode cannot be changed through this bridge" }
        require(allowDevSelf || packageName != ownPackage) { "Cannot replace the running host through this bridge" }
    }

    fun resolveApk(guestPath: String, filesDir: File): File {
        require(guestPath.length < 4096 && guestPath.endsWith(".apk")) { "Expected an APK path" }
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
