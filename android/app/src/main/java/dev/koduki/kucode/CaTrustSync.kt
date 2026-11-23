package dev.koduki.kucode

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64

internal data class CaCertificateEntry(
    val alias: String,
    val derEncoded: ByteArray,
    val isUserAdded: Boolean,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CaCertificateEntry) return false
        return alias == other.alias &&
            isUserAdded == other.isUserAdded &&
            derEncoded.contentEquals(other.derEncoded)
    }

    override fun hashCode(): Int {
        var result = alias.hashCode()
        result = 31 * result + derEncoded.contentHashCode()
        result = 31 * result + isUserAdded.hashCode()
        return result
    }
}

/**
 * Exports trusted root CA certificates from Android's trust store (system Conscrypt APEX
 * roots plus user-installed CAs) into the Debian guest so `gh`, `apt` (HTTPS), `curl`,
 * `git`, `openssl`, `pip`, and Node work immediately without requiring manual setup.
 *
 * Designed as pure JVM + JCA so every formatting, deduplication, Debian `ca-certificates`
 * coexistence, and cleanup invariant is unit-testable on the host JVM.
 */
internal object CaTrustSync {

    private const val PEM_BEGIN = "-----BEGIN CERTIFICATE-----"
    private const val PEM_END = "-----END CERTIFICATE-----"
    private const val USER_CA_FILE_PREFIX = "kucode-android-user-"
    private const val USER_BLOCK_BEGIN = "# KUCODE-ANDROID-USER-CA-BEGIN"
    private const val USER_BLOCK_END = "# KUCODE-ANDROID-USER-CA-END"

    private val PEM_BLOCK_REGEX = Regex(
        "-----BEGIN CERTIFICATE-----\\s*([A-Za-z0-9+/=\\r\\n\\s]+?)\\s*-----END CERTIFICATE-----",
    )

    private val DEFAULT_SYSTEM_CA_DIRS = listOf(
        File("/apex/com.android.conscrypt/cacerts"),
        File("/system/etc/security/cacerts"),
    )

    private val DEFAULT_USER_CA_DIRS = listOf(
        File("/data/misc/user/0/cacerts-added"),
    )

    data class SyncSummary(
        val totalCount: Int,
        val systemCount: Int,
        val userCount: Int,
        val managedByDebianCaCerts: Boolean,
    )

    fun loadAndroidTrustStoreCertificates(
        systemFallbackDirs: List<File> = DEFAULT_SYSTEM_CA_DIRS,
        userFallbackDirs: List<File> = DEFAULT_USER_CA_DIRS,
    ): List<CaCertificateEntry> {
        val fromKeyStore = loadFromAndroidKeyStore()
        if (fromKeyStore.isNotEmpty()) {
            return deduplicateAndSort(fromKeyStore)
        }
        return deduplicateAndSort(loadFromCaDirectories(systemFallbackDirs, userFallbackDirs))
    }

    private fun loadFromAndroidKeyStore(): List<CaCertificateEntry> = runCatching {
        val ks = KeyStore.getInstance("AndroidCAStore")
        ks.load(null)
        val entries = mutableListOf<CaCertificateEntry>()
        val aliases = ks.aliases()
        while (aliases.hasMoreElements()) {
            val alias = aliases.nextElement() ?: continue
            val cert = ks.getCertificate(alias) as? X509Certificate ?: continue
            val der = runCatching { cert.encoded }.getOrNull()?.takeIf { it.isNotEmpty() } ?: continue
            entries.add(
                CaCertificateEntry(
                    alias = alias,
                    derEncoded = der,
                    isUserAdded = alias.startsWith("user:"),
                ),
            )
        }
        entries
    }.getOrDefault(emptyList())

    internal fun loadFromCaDirectories(
        systemDirs: List<File>,
        userDirs: List<File>,
    ): List<CaCertificateEntry> {
        val entries = mutableListOf<CaCertificateEntry>()
        val activeSystemDir = systemDirs.firstOrNull { dir ->
            dir.isDirectory && (dir.listFiles()?.any { it.isFile } == true)
        }
        if (activeSystemDir != null) {
            entries.addAll(readCertsFromDir(activeSystemDir, isUserAdded = false))
        }
        for (userDir in userDirs) {
            if (userDir.isDirectory) {
                entries.addAll(readCertsFromDir(userDir, isUserAdded = true))
            }
        }
        return entries
    }

    private fun readCertsFromDir(dir: File, isUserAdded: Boolean): List<CaCertificateEntry> {
        val files = dir.listFiles()?.sortedBy { it.name } ?: return emptyList()
        val prefix = if (isUserAdded) "user:" else "system:"
        val result = mutableListOf<CaCertificateEntry>()
        for (file in files) {
            if (!file.isFile) continue
            val certs = parseCertificatesFromFile(file)
            certs.forEachIndexed { idx, der ->
                val alias = if (certs.size == 1) "$prefix${file.name}" else "$prefix${file.name}#$idx"
                result.add(CaCertificateEntry(alias = alias, derEncoded = der, isUserAdded = isUserAdded))
            }
        }
        return result
    }

    internal fun parseCertificatesFromFile(file: File): List<ByteArray> = runCatching {
        val raw = file.readBytes()
        if (raw.isEmpty()) return emptyList()
        val factory = CertificateFactory.getInstance("X.509")
        val text = raw.toString(Charsets.ISO_8859_1)
        val pemMatches = PEM_BLOCK_REGEX.findAll(text).toList()
        if (pemMatches.isNotEmpty()) {
            pemMatches.mapNotNull { match ->
                val blockBytes = match.value.toByteArray(Charsets.US_ASCII)
                runCatching {
                    (factory.generateCertificate(ByteArrayInputStream(blockBytes)) as? X509Certificate)?.encoded
                }.getOrNull()?.takeIf { it.isNotEmpty() }
            }
        } else {
            runCatching {
                factory.generateCertificates(ByteArrayInputStream(raw))
                    .mapNotNull { (it as? X509Certificate)?.encoded }
                    .filter { it.isNotEmpty() }
            }.getOrDefault(emptyList())
        }
    }.getOrDefault(emptyList())

    internal fun deduplicateAndSort(entries: List<CaCertificateEntry>): List<CaCertificateEntry> {
        val byFingerprint = linkedMapOf<String, CaCertificateEntry>()
        for (entry in entries) {
            if (entry.derEncoded.isEmpty()) continue
            val fp = sha256Hex(entry.derEncoded)
            val existing = byFingerprint[fp]
            if (existing == null || (!existing.isUserAdded && entry.isUserAdded)) {
                byFingerprint[fp] = entry
            }
        }
        return byFingerprint.entries
            .sortedWith(compareBy<Map.Entry<String, CaCertificateEntry>>({ it.value.isUserAdded }, { it.value.alias }, { it.key }))
            .map { it.value }
    }

    internal fun formatPem(derEncoded: ByteArray): String {
        val encoded = Base64.getMimeEncoder(64, byteArrayOf('\n'.code.toByte())).encodeToString(derEncoded)
        return "$PEM_BEGIN\n$encoded\n$PEM_END\n"
    }

    internal fun buildPemBundle(entries: List<CaCertificateEntry>): String {
        val unique = deduplicateAndSort(entries)
        if (unique.isEmpty()) return ""
        val sb = StringBuilder(unique.size * 1400)
        for (entry in unique) {
            sb.append(formatPem(entry.derEncoded))
        }
        return sb.toString()
    }

    internal fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val hex = CharArray(digest.size * 2)
        val digits = "0123456789abcdef"
        for (i in digest.indices) {
            val v = digest[i].toInt() and 0xFF
            hex[i * 2] = digits[v ushr 4]
            hex[i * 2 + 1] = digits[v and 0x0F]
        }
        return String(hex)
    }

    internal fun extractPemFingerprints(pemText: String): Set<String> {
        val decoder = Base64.getMimeDecoder()
        val result = linkedSetOf<String>()
        for (match in PEM_BLOCK_REGEX.findAll(pemText)) {
            val base64Body = match.groupValues[1]
            val der = runCatching { decoder.decode(base64Body) }.getOrNull() ?: continue
            if (der.isNotEmpty()) {
                result.add(sha256Hex(der))
            }
        }
        return result
    }

    internal fun countCertificatesInPem(pemText: String): Int =
        PEM_BLOCK_REGEX.findAll(pemText).count()

    internal fun stripManagedUserCaBlock(content: String): String {
        val startIdx = content.indexOf(USER_BLOCK_BEGIN)
        if (startIdx < 0) return content
        val endMarkerIdx = content.indexOf(USER_BLOCK_END, startIdx)
        val endIdx = if (endMarkerIdx >= 0) {
            val afterEnd = endMarkerIdx + USER_BLOCK_END.length
            if (afterEnd < content.length && content[afterEnd] == '\n') afterEnd + 1 else afterEnd
        } else {
            content.length
        }
        val before = content.substring(0, startIdx)
        val after = content.substring(endIdx)
        return (before + after).let { combined ->
            if (combined.isEmpty() || combined.endsWith("\n")) combined else "$combined\n"
        }
    }

    fun syncIntoRootfs(
        paths: PosixRuntimeConfig.Paths,
        entries: List<CaCertificateEntry>,
        onLog: (String) -> Unit = {},
    ): SyncSummary {
        val sslDir = File(paths.rootfsDir, "etc/ssl")
        val certsDir = File(sslDir, "certs")
        val localCaDir = File(paths.rootfsDir, "usr/local/share/ca-certificates")
        ensureDirectoryWithMode(sslDir, 0b111_101_101)
        ensureDirectoryWithMode(certsDir, 0b111_101_101)
        ensureDirectoryWithMode(localCaDir, 0b111_101_101)

        val uniqueEntries = deduplicateAndSort(entries)
        val userEntries = uniqueEntries.filter { it.isUserAdded }
        val systemEntries = uniqueEntries.filter { !it.isUserAdded }

        // 1. Always mirror Android user-installed CAs into /usr/local/share/ca-certificates/
        //    so Debian's `update-ca-certificates` includes them automatically, and into a dedicated
        //    bundle outside /etc/ssl/certs/ for Node's NODE_EXTRA_CA_CERTS.
        val expectedUserFiles = linkedSetOf<String>()
        for (entry in userEntries) {
            val fileName = "$USER_CA_FILE_PREFIX${sha256Hex(entry.derEncoded).take(16)}.crt"
            expectedUserFiles.add(fileName)
            val target = File(localCaDir, fileName)
            val pem = formatPem(entry.derEncoded)
            val needsWrite = !Files.isRegularFile(target.toPath(), LinkOption.NOFOLLOW_LINKS) ||
                runCatching { target.readText(Charsets.UTF_8) }.getOrNull() != pem
            if (needsWrite) {
                writeTextAtomically(target, pem, Charsets.UTF_8, worldReadable = true)
            } else {
                TarGzExtractor.DefaultPosixFsOps.chmod(target, 0b110_100_100)
            }
        }
        localCaDir.listFiles()?.forEach { file ->
            if (file.name.startsWith(USER_CA_FILE_PREFIX) &&
                file.name.endsWith(".crt") &&
                file.name !in expectedUserFiles
            ) {
                Files.deleteIfExists(file.toPath())
            }
        }

        val userBundleFile = paths.androidUserCaBundleFile
        if (userEntries.isNotEmpty()) {
            val userBundle = buildPemBundle(userEntries)
            val needsWrite = !Files.isRegularFile(userBundleFile.toPath(), LinkOption.NOFOLLOW_LINKS) ||
                runCatching { userBundleFile.readText(Charsets.UTF_8) }.getOrNull() != userBundle
            if (needsWrite) {
                writeTextAtomically(userBundleFile, userBundle, Charsets.UTF_8, worldReadable = true)
            } else {
                TarGzExtractor.DefaultPosixFsOps.chmod(userBundleFile, 0b110_100_100)
            }
        } else {
            Files.deleteIfExists(userBundleFile.toPath())
        }

        // 2. Sync /etc/ssl/certs/ca-certificates.crt
        val caBundleFile = paths.guestCaBundleHostFile
        val debianManaged = File(paths.rootfsDir, "usr/sbin/update-ca-certificates").isFile
        if (Files.isSymbolicLink(caBundleFile.toPath()) && !Files.exists(caBundleFile.toPath())) {
            Files.deleteIfExists(caBundleFile.toPath())
        }
        val hasExistingRegularBundle =
            Files.isRegularFile(caBundleFile.toPath(), LinkOption.NOFOLLOW_LINKS) && caBundleFile.length() > 0L

        if (!debianManaged || !hasExistingRegularBundle) {
            if (uniqueEntries.isNotEmpty()) {
                val bundle = buildPemBundle(uniqueEntries)
                if (Files.isSymbolicLink(caBundleFile.toPath()) ||
                    Files.isDirectory(caBundleFile.toPath(), LinkOption.NOFOLLOW_LINKS)
                ) {
                    deleteTreeNoFollow(caBundleFile)
                }
                val existing = if (hasExistingRegularBundle) {
                    runCatching { caBundleFile.readText(Charsets.UTF_8) }.getOrNull()
                } else {
                    null
                }
                if (existing != bundle) {
                    writeTextAtomically(caBundleFile, bundle, Charsets.UTF_8, worldReadable = true)
                } else {
                    TarGzExtractor.DefaultPosixFsOps.chmod(caBundleFile, 0b110_100_100)
                }
                onLog(
                    "[runtime] Synced ${uniqueEntries.size} CA certificates from Android trust store " +
                        "(system=${systemEntries.size}, user=${userEntries.size})",
                )
            }
        } else {
            // Preserve Debian's update-ca-certificates bundle, keep it world-readable, and
            // seamlessly append/remove any Android user-added CAs via delimited markers.
            TarGzExtractor.DefaultPosixFsOps.chmod(caBundleFile, 0b110_100_100)
            val original = runCatching { caBundleFile.readText(Charsets.UTF_8) }.getOrNull()
            if (original != null) {
                val baseContent = stripManagedUserCaBlock(original)
                val baseFingerprints = extractPemFingerprints(baseContent)
                val missingUser = userEntries.filter { sha256Hex(it.derEncoded) !in baseFingerprints }
                val updated = if (missingUser.isNotEmpty()) {
                    val prefix = if (baseContent.endsWith("\n") || baseContent.isEmpty()) baseContent else "$baseContent\n"
                    prefix + USER_BLOCK_BEGIN + "\n" + buildPemBundle(missingUser) + USER_BLOCK_END + "\n"
                } else {
                    baseContent
                }
                if (updated != original) {
                    writeTextAtomically(caBundleFile, updated, Charsets.UTF_8, worldReadable = true)
                    if (missingUser.isNotEmpty()) {
                        onLog("[runtime] Appended ${missingUser.size} Android user CA certificate(s) to Debian ca-certificates.crt")
                    }
                }
            }
        }

        // 3. Provide /etc/ssl/cert.pem -> /etc/ssl/certs/ca-certificates.crt for tools checking cert.pem
        val certPemLink = File(sslDir, "cert.pem")
        if (Files.isRegularFile(caBundleFile.toPath(), LinkOption.NOFOLLOW_LINKS) &&
            !Files.exists(certPemLink.toPath(), LinkOption.NOFOLLOW_LINKS)
        ) {
            runCatching {
                TarGzExtractor.DefaultPosixFsOps.symlink(PosixRuntimeConfig.GUEST_CA_BUNDLE, certPemLink)
            }
        }

        val finalCount = if (Files.isRegularFile(caBundleFile.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            runCatching { countCertificatesInPem(caBundleFile.readText(Charsets.UTF_8)) }.getOrDefault(0)
        } else {
            0
        }
        return SyncSummary(
            totalCount = finalCount,
            systemCount = systemEntries.size,
            userCount = userEntries.size,
            managedByDebianCaCerts = debianManaged && hasExistingRegularBundle,
        )
    }
}
