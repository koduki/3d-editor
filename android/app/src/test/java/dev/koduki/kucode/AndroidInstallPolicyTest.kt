package dev.koduki.kucode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AndroidInstallPolicyTest {
    @Test
    fun stableAndRunningHostAreNeverInstallTargets() {
        assertThrows(IllegalArgumentException::class.java) {
            AndroidInstallPolicy.checkTarget("dev.koduki.kucode", "dev.koduki.kucode.debug")
        }
        assertThrows(IllegalArgumentException::class.java) {
            AndroidInstallPolicy.checkTarget("dev.koduki.kucode.debug", "dev.koduki.kucode.debug")
        }
        AndroidInstallPolicy.checkTarget("dev.koduki.kucode.debug", "dev.koduki.kucode")
        AndroidInstallPolicy.checkTarget("com.example.test", "dev.koduki.kucode")
    }

    @Test
    fun onlyWorkspaceOrHomeApksAreAccepted() {
        val base = Files.createTempDirectory("apk-policy").toFile()
        try {
            val apk = File(base, "workspace/app.apk").apply { parentFile.mkdirs(); writeText("fixture") }
            assertEquals(apk.canonicalFile, AndroidInstallPolicy.resolveApk("/workspace/app.apk", base))
            assertThrows(IllegalArgumentException::class.java) {
                AndroidInstallPolicy.resolveApk("/tmp/app.apk", base)
            }
            assertThrows(IllegalArgumentException::class.java) {
                AndroidInstallPolicy.resolveApk("/workspace/missing.apk", base)
            }
        } finally {
            deleteTreeNoFollow(base)
        }
    }
}
