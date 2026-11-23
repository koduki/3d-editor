package dev.koduki.kucode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

class CaTrustSyncTest {

    @Test
    fun syncIntoRootfs_populatesWorldReadableCaBundleAndCertPemSymlinkWhenDebianCaCertsAbsent() {
        val base = Files.createTempDirectory("kucode-ca-fresh").toFile()
        try {
            val paths = testPaths(base)
            val logs = mutableListOf<String>()
            val sysCert = CaCertificateEntry("system:11111111.0", byteArrayOf(0x30, 0x01, 0x02, 0x03), isUserAdded = false)
            val dupSysCert = CaCertificateEntry("system:dup.0", byteArrayOf(0x30, 0x01, 0x02, 0x03), isUserAdded = false)
            val userCert = CaCertificateEntry("user:22222222.0", byteArrayOf(0x30, 0x09, 0x08, 0x07), isUserAdded = true)

            val summary = CaTrustSync.syncIntoRootfs(paths, listOf(sysCert, dupSysCert, userCert), logs::add)

            assertEquals(2, summary.totalCount)
            assertEquals(1, summary.systemCount)
            assertEquals(1, summary.userCount)
            assertFalse(summary.managedByDebianCaCerts)

            val caBundle = paths.guestCaBundleHostFile
            assertTrue(caBundle.isFile)
            val bundleText = caBundle.readText(Charsets.UTF_8)
            assertEquals(2, CaTrustSync.countCertificatesInPem(bundleText))
            assertTrue(bundleText.contains(CaTrustSync.formatPem(sysCert.derEncoded)))
            assertTrue(bundleText.contains(CaTrustSync.formatPem(userCert.derEncoded)))

            val perms = Files.getPosixFilePermissions(caBundle.toPath())
            assertTrue(PosixFilePermission.OTHERS_READ in perms)
            assertTrue(PosixFilePermission.GROUP_READ in perms)

            val certPem = File(paths.rootfsDir, "etc/ssl/cert.pem").toPath()
            assertTrue(Files.isSymbolicLink(certPem))
            assertEquals(PosixRuntimeConfig.GUEST_CA_BUNDLE, Files.readSymbolicLink(certPem).toString())

            assertTrue(paths.androidUserCaBundleFile.isFile)
            assertEquals(1, CaTrustSync.countCertificatesInPem(paths.androidUserCaBundleFile.readText(Charsets.UTF_8)))

            val localUserFiles = File(paths.rootfsDir, "usr/local/share/ca-certificates").listFiles()!!.toList()
            assertEquals(1, localUserFiles.size)
            assertTrue(localUserFiles[0].name.startsWith("kucode-android-user-"))
            assertTrue(localUserFiles[0].name.endsWith(".crt"))
            assertTrue(logs.any { it.contains("Synced 2 CA certificates") })
        } finally {
            deleteTreeNoFollow(base)
        }
    }

    @Test
    fun syncIntoRootfs_preservesDebianManagedBundleAndTracksUserCaLifecycle() {
        val base = Files.createTempDirectory("kucode-ca-debian").toFile()
        try {
            val paths = testPaths(base)
            val updateScript = File(paths.rootfsDir, "usr/sbin/update-ca-certificates")
            updateScript.parentFile!!.mkdirs()
            updateScript.writeText("#!/bin/sh\n")

            val debianCertDer = byteArrayOf(0x30, 0x10, 0x20, 0x30)
            val androidSysDer = byteArrayOf(0x30, 0x11, 0x22, 0x33)
            val userCertDer = byteArrayOf(0x30, 0x44, 0x55, 0x66)
            val debianPem = CaTrustSync.formatPem(debianCertDer)

            paths.guestCaBundleHostFile.parentFile!!.mkdirs()
            paths.guestCaBundleHostFile.writeText(debianPem)

            // 1. With Debian ca-certificates present + Android user CA -> preserves Debian cert and appends user CA
            val withUser = CaTrustSync.syncIntoRootfs(
                paths = paths,
                entries = listOf(
                    CaCertificateEntry("system:a.0", androidSysDer, isUserAdded = false),
                    CaCertificateEntry("user:corp.0", userCertDer, isUserAdded = true),
                ),
            )
            assertTrue(withUser.managedByDebianCaCerts)
            assertEquals(2, withUser.totalCount)
            val afterAdd = paths.guestCaBundleHostFile.readText(Charsets.UTF_8)
            assertTrue(afterAdd.contains(debianPem))
            assertTrue(afterAdd.contains(CaTrustSync.formatPem(userCertDer)))
            assertFalse("Should not overwrite Debian bundle with Android system cert", afterAdd.contains(CaTrustSync.formatPem(androidSysDer)))
            assertTrue(paths.androidUserCaBundleFile.isFile)

            // 2. Removing the Android user CA cleanly strips the managed block and removes user CA files
            val withoutUser = CaTrustSync.syncIntoRootfs(
                paths = paths,
                entries = listOf(
                    CaCertificateEntry("system:a.0", androidSysDer, isUserAdded = false),
                ),
            )
            assertTrue(withoutUser.managedByDebianCaCerts)
            assertEquals(1, withoutUser.totalCount)
            assertEquals(debianPem, paths.guestCaBundleHostFile.readText(Charsets.UTF_8))
            assertFalse(paths.androidUserCaBundleFile.exists())
            assertTrue(File(paths.rootfsDir, "usr/local/share/ca-certificates").listFiles()!!.isEmpty())
        } finally {
            deleteTreeNoFollow(base)
        }
    }

    @Test
    fun syncIntoRootfs_repairsMissingOrEmptyBundleEvenWhenUpdateCaCertificatesExists() {
        val base = Files.createTempDirectory("kucode-ca-repair").toFile()
        try {
            val paths = testPaths(base)
            val updateScript = File(paths.rootfsDir, "usr/sbin/update-ca-certificates")
            updateScript.parentFile!!.mkdirs()
            updateScript.writeText("#!/bin/sh\n")

            // Empty existing bundle should be healed from Android trust store
            paths.guestCaBundleHostFile.parentFile!!.mkdirs()
            paths.guestCaBundleHostFile.writeText("")

            val sysCert = CaCertificateEntry("system:root.0", byteArrayOf(0x30, 0x55, 0x66), isUserAdded = false)
            val summary = CaTrustSync.syncIntoRootfs(paths, listOf(sysCert))

            assertEquals(1, summary.totalCount)
            assertEquals(CaTrustSync.formatPem(sysCert.derEncoded), paths.guestCaBundleHostFile.readText(Charsets.UTF_8))
        } finally {
            deleteTreeNoFollow(base)
        }
    }

    @Test
    @Category(MediumTest::class)
    fun loadFromCaDirectories_prefersFirstNonEmptySystemDirAndIgnoresTrailingOpenSslText() {
        val base = Files.createTempDirectory("kucode-ca-dirs").toFile()
        try {
            val apexDir = File(base, "apex").apply { mkdirs() }
            val legacyDir = File(base, "legacy").apply { mkdirs() }
            val userDir = File(base, "user").apply { mkdirs() }

            // Generate real self-signed X.509 cert via openssl if present; otherwise verify directory preference
            val certA = File(base, "a.pem")
            val certB = File(base, "b.pem")
            val keyA = File(base, "a.key")
            val keyB = File(base, "b.key")
            val okA = runCatching {
                ProcessBuilder(
                    "openssl", "req", "-x509", "-newkey", "rsa:2048",
                    "-keyout", keyA.absolutePath, "-out", certA.absolutePath,
                    "-days", "1", "-nodes", "-subj", "/CN=ApexRoot",
                ).redirectErrorStream(true).start().waitFor()
            }.getOrDefault(-1)
            val okB = runCatching {
                ProcessBuilder(
                    "openssl", "req", "-x509", "-newkey", "rsa:2048",
                    "-keyout", keyB.absolutePath, "-out", certB.absolutePath,
                    "-days", "1", "-nodes", "-subj", "/CN=UserCorpRoot",
                ).redirectErrorStream(true).start().waitFor()
            }.getOrDefault(-1)
            if (okA != 0 || okB != 0) return

            // Android Conscrypt / system .0 files often include human-readable OpenSSL dump after END CERTIFICATE
            File(apexDir, "9a5ba575.0").writeText(
                certA.readText(Charsets.UTF_8) + "\nCertificate:\n    Data:\n        Subject: CN=ApexRoot\n",
            )
            File(legacyDir, "old.0").writeText(certB.readText(Charsets.UTF_8))
            File(userDir, "corp.0").writeText(certB.readText(Charsets.UTF_8))

            val loaded = CaTrustSync.loadFromCaDirectories(
                systemDirs = listOf(apexDir, legacyDir),
                userDirs = listOf(userDir),
            )
            assertEquals(2, loaded.size)
            assertEquals(1, loaded.count { !it.isUserAdded })
            assertEquals(1, loaded.count { it.isUserAdded })
            assertTrue(loaded.any { it.alias == "system:9a5ba575.0" })
            assertTrue(loaded.any { it.alias == "user:corp.0" })
        } finally {
            deleteTreeNoFollow(base)
        }
    }

    private fun testPaths(base: File): PosixRuntimeConfig.Paths = PosixRuntimeConfig.Paths(
        runtimeRoot = File(base, "runtime"),
        workspaceDir = File(base, "workspace"),
        homeDir = File(base, "home"),
        nativeLibDir = File(base, "native"),
        androidSharedDir = File(base, "shared"),
    )
}
