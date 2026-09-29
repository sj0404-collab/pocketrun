package dev.pocketrun.license

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The key every build carries has to verify against the public key of that same
 * build. When the two files in `android/` ever drift apart, the app falls back to
 * the activation screen for every user and there is nothing on a device to
 * explain why - so the build fails here instead.
 */
class SharedLicenseTest {

    private fun androidDir(): File {
        var dir = File("").absoluteFile
        while (dir.parentFile != null) {
            if (File(dir, "license.pubkey").isFile) return dir
            dir = dir.parentFile
        }
        throw AssertionError("license.pubkey not found above ${File("").absolutePath}")
    }

    @Test
    fun `the key the build carries verifies against the build's own public key`() {
        val dir = androidDir()
        val publicKey = File(dir, "license.pubkey").readText().trim()
        val shared = File(dir, "shared-license.key").readText().trim()

        assertTrue("shared-license.key is empty", shared.isNotEmpty())
        val key = LicenseFormat.normalize(shared)
        assertTrue("not a PRK1 key: $key", key.startsWith("PRK1."))

        val outcome = LicenseVerifier.verify(key, publicKey, System.currentTimeMillis() / 1000)
        assertTrue("shared key rejected: $outcome", outcome is LicenseVerifier.Outcome.Valid)

        val valid = outcome as LicenseVerifier.Outcome.Valid
        assertEquals("pro", valid.claims.plan)
        assertFalse("a shared key that expires is nobody's key", valid.claims.isTimeLimited)
    }
}
