package dev.pocketrun.keygen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

/**
 * Golden values. The app carries a copy of LicenseFormat and Ed25519 and asserts
 * the exact same constants in
 * android/app/src/test/java/dev/pocketrun/license/LicenseFormatTest.kt.
 * If either side drifts, one of the two builds fails.
 */
object Vector {
    const val SEED = "MC4CAQAwBQYDK2VwBCIEIAsSGSAnLjU8Q0pRWF9mbXR7gomQl56lrLO6wcjP1t3k"
    const val PUBLIC_KEY = "MCowBQYDK2VwAyEA14W0lYPuc2xmz1Xch0dPTzN8lxv8PWnLoeIeZ3Y2U68"
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
        val decoded = LicenseFormat.decodePayload(LicenseFormat.encodePayload(Vector.CLAIMS))
        assertEquals(Vector.CLAIMS, decoded)
    }

    @Test
    fun `values with newlines and backslashes survive a round trip`() {
        val awkward = Vector.CLAIMS.copy(name = "Ann\\Bob\nSecond line")
        assertEquals(awkward, LicenseFormat.decodePayload(LicenseFormat.encodePayload(awkward)))
    }

    @Test
    fun `signing the golden claims reproduces the golden key`() {
        val pair = Ed25519.keyPairFromSeed(Base64.getUrlDecoder().decode(Vector.SEED))
        val payload = LicenseFormat.encodePayload(Vector.CLAIMS)
        val signature = Ed25519.sign(pair.private, payload.toByteArray(StandardCharsets.UTF_8))
        assertEquals(Vector.LICENSE_KEY, LicenseFormat.assemble(payload, signature))
    }

    @Test
    fun `normalization strips whitespace the user pasted in`() {
        val mangled = buildString {
            append("  PRK1.")
            append(Vector.LICENSE_KEY.removePrefix("PRK1.").chunked(37).joinToString("\n "))
        }
        assertEquals(Vector.LICENSE_KEY, LicenseFormat.normalize(mangled))
        assertEquals(Vector.LICENSE_KEY, LicenseFormat.toDisplayBlocks(mangled).replace(" ", ""))
    }

    @Test
    fun `truncated keys are rejected`() {
        listOf("", "nope", "PRK1", "PRK1.", "PRK1.abc", "PRK1.abc.def.ghi").forEach { bad ->
            val thrown = runCatching { LicenseFormat.split(bad) }.exceptionOrNull()
            assertTrue("expected rejection for '$bad'", thrown is IllegalArgumentException)
        }
    }

    @Test
    fun `a tampered payload no longer verifies`() {
        val pair = Ed25519.keyPairFromSeed(Base64.getUrlDecoder().decode(Vector.SEED))
        val payload = LicenseFormat.encodePayload(Vector.CLAIMS)
        val signature = Ed25519.sign(pair.private, payload.toByteArray(StandardCharsets.UTF_8))
        val forged = LicenseFormat.assemble(payload.replace("pro", "max"), signature)
        val (forgedPayload, forgedSignature) = LicenseFormat.split(forged)
        assertTrue(Ed25519.verify(pair.public, forgedPayload.toByteArray(StandardCharsets.UTF_8), forgedSignature))
    }
}

class Ed25519Test {

    @Test
    fun `seed derives the golden public key`() {
        val pair = Ed25519.keyPairFromSeed(Base64.getUrlDecoder().decode(Vector.SEED))
        assertEquals(
            Vector.PUBLIC_KEY,
            Base64.getUrlEncoder().withoutPadding().encodeToString(Ed25519.encodePublicKey(pair.public)),
        )
    }

    @Test
    fun `generated keys differ each time`() {
        val a = Base64.getUrlEncoder().withoutPadding().encodeToString(
            Ed25519.encodePublicKey(Ed25519.generateKeyPair().public),
        )
        val b = Base64.getUrlEncoder().withoutPadding().encodeToString(
            Ed25519.encodePublicKey(Ed25519.generateKeyPair().public),
        )
        assertNotEquals(a, b)
    }

    @Test
    fun `signature from one key does not verify under another`() {
        val signer = Ed25519.keyPairFromSeed(ByteArray(32) { 1 })
        val other = Ed25519.keyPairFromSeed(ByteArray(32) { 2 })
        val message = "pocketrun".toByteArray(StandardCharsets.UTF_8)
        val signature = Ed25519.sign(signer.private, message)
        assertTrue(Ed25519.verify(signer.public, message, signature))
        assertTrue(!Ed25519.verify(other.public, message, signature))
    }

    @Test
    fun `a flipped signature byte fails verification`() {
        val pair = Ed25519.keyPairFromSeed(ByteArray(32) { 9 })
        val message = "pocketrun".toByteArray(StandardCharsets.UTF_8)
        val signature = Ed25519.sign(pair.private, message)
        signature[0] = (signature[0].toInt() xor 0x01).toByte()
        assertTrue(!Ed25519.verify(pair.public, message, signature))
    }
}
