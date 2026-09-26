package dev.pocketrun.license

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

/**
 * Golden values. The keygen tool carries a copy of LicenseFormat and Ed25519 and
 * asserts the exact same constants in
 * tools/keygen/src/test/kotlin/dev/pocketrun/keygen/LicenseFormatTest.kt.
 * If either side drifts, one of the two builds fails.
 */
object Vector {
    const val SEED = "MC4CAQAwBQYDK2VwBCIEIAsSGSAnLjU8Q0pRWF9mbXR7gomQl56lrLO6wcjP1t3k"
    const val PUBLIC_KEY = "14W0lYPuc2xmz1Xch0dPTzN8lxv8PWnLoeIeZ3Y2U68"
    const val PAYLOAD_SHA256 = "Kp-KE-mFSu3_E8uWyTPGuv8yNWimNLw2gP_qKVUBAY0"
    const val LICENSE_KEY =
        "PRK1.UFJLMQpuYW1lPVRlc3QgVXNlcgpzZWF0PXNlYXQtMQpwbGFuPXBybwpuYmY9MTcwMDAwMDAw" +
            "MApleHA9MTcwMjU5MjAwMAo.8ElLaKW6l-OlhICkZjKk1RAT3eHladzQsbS6m7AjvzwbmrGdYEfU3" +
            "Mxk26CipFL-wRaQehGzz7v6KonMLdPTBg"

    val CLAIMS = LicenseFormat.Claims(
        name = "Test User",
        seat = "seat-1",
        plan = "pro",
        notBefore = 1_700_000_000L,
        expiresAt = 1_700_000_000L + 30L * 86_400L,
    )
}

class LicenseFormatTest {

    @Test
    fun `payload encoding matches the golden payload`() {
        val payload = LicenseFormat.encodePayload(Vector.CLAIMS)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(payload.toByteArray(StandardCharsets.UTF_8))
        assertEquals(
            Vector.PAYLOAD_SHA256,
            Base64.getUrlEncoder().withoutPadding().encodeToString(digest),
        )
    }

    @Test
    fun `claims survive a round trip`() {
        assertEquals(
            Vector.CLAIMS,
            LicenseFormat.decodePayload(LicenseFormat.encodePayload(Vector.CLAIMS)),
        )
    }

    @Test
    fun `values with newlines and backslashes survive a round trip`() {
        val awkward = Vector.CLAIMS.copy(name = "Ann\\Bob\nSecond line")
        assertEquals(awkward, LicenseFormat.decodePayload(LicenseFormat.encodePayload(awkward)))
    }

    @Test
    fun `truncated keys are rejected`() {
        listOf("", "nope", "PRK1", "PRK1.", "PRK1.abc", "PRK1.abc.def.ghi").forEach { bad ->
            val thrown = runCatching { LicenseFormat.split(bad) }.exceptionOrNull()
            assertTrue("expected rejection for '$bad'", thrown is IllegalArgumentException)
        }
    }

    @Test
    fun `normalization strips whitespace the user pasted in`() {
        val mangled = buildString {
            append("  PRK1.")
            append(Vector.LICENSE_KEY.removePrefix("PRK1.").chunked(37).joinToString("\n "))
        }
        assertEquals(Vector.LICENSE_KEY, LicenseFormat.normalize(mangled))
    }
}

class LicenseVerifierTest {

    private val key = Vector.LICENSE_KEY
    private val pub = Vector.PUBLIC_KEY
    private val insideWindow = Vector.CLAIMS.notBefore + 86_400L

    @Test
    fun `a genuine key is accepted inside its window`() {
        val outcome = LicenseVerifier.verify(key, pub, insideWindow)
        assertTrue("expected Valid, got $outcome", outcome is LicenseVerifier.Outcome.Valid)
        outcome as LicenseVerifier.Outcome.Valid
        assertEquals(Vector.CLAIMS, outcome.claims)
        assertEquals(12, outcome.keyFingerprint.length)
    }

    @Test
    fun `a key is rejected after expiry`() {
        val outcome = LicenseVerifier.verify(key, pub, Vector.CLAIMS.expiresAt + 1)
        assertTrue(outcome is LicenseVerifier.Outcome.Expired)
    }

    @Test
    fun `a key is rejected before its start date`() {
        val outcome = LicenseVerifier.verify(key, pub, Vector.CLAIMS.notBefore - 1)
        assertTrue(outcome is LicenseVerifier.Outcome.NotYetValid)
    }

    @Test
    fun `a key signed by another keypair is rejected`() {
        val other = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(Ed25519.encodePublicKey(Ed25519.publicKeyFromSeed(ByteArray(32) { 3 })))
        val outcome = LicenseVerifier.verify(key, other, insideWindow)
        assertTrue(outcome is LicenseVerifier.Outcome.Rejected)
    }

    @Test
    fun `a demo build refuses every key and says why`() {
        val outcome = LicenseVerifier.verify(key, "DEMO0000", insideWindow)
        assertTrue(outcome is LicenseVerifier.Outcome.Rejected)
        assertTrue((outcome as LicenseVerifier.Outcome.Rejected).reason.contains("license.pubkey"))
    }

    @Test
    fun `garbage input is rejected without throwing`() {
        listOf("", "   ", "hello", "PRK1.!!!.???", "PRK1." + "!".repeat(200)).forEach { bad ->
            val outcome = LicenseVerifier.verify(bad, pub, insideWindow)
            assertTrue("expected rejection for '$bad'", outcome is LicenseVerifier.Outcome.Rejected)
        }
    }

    @Test
    fun `a corrupt embedded public key is reported, not crashed on`() {
        val outcome = LicenseVerifier.verify(key, "not base64 at all !!", insideWindow)
        assertTrue(outcome is LicenseVerifier.Outcome.Rejected)
    }

    @Test
    fun `days remaining counts down and never goes negative`() {
        assertEquals(29L, LicenseVerifier.daysRemaining(Vector.CLAIMS, insideWindow))
        assertEquals(0L, LicenseVerifier.daysRemaining(Vector.CLAIMS, Vector.CLAIMS.expiresAt + 99_999))
        assertEquals(null, LicenseVerifier.daysRemaining(Vector.CLAIMS.copy(expiresAt = 0), insideWindow))
    }
}
