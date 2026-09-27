package dev.pocketrun.license

import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Offline license check. Everything here is a pure function of the key text and
 * the clock, which is what makes it testable on the JVM without a device.
 *
 * There is no server and no device fingerprint: a key that verifies is valid
 * everywhere. Revocation is therefore impossible by design; a revoked key simply
 * stops working once the build that embeds a newer public key ships.
 */
object LicenseVerifier {

    sealed interface Outcome {
        data class Valid(
            val claims: LicenseFormat.Claims,
            val keyFingerprint: String,
        ) : Outcome

        /** The key parsed and the signature is good, but the clock says no. */
        data class Expired(val claims: LicenseFormat.Claims) : Outcome

        data class NotYetValid(val claims: LicenseFormat.Claims) : Outcome

        /** The key is not a PocketRun key, or the signature does not match. */
        data class Rejected(val reason: String) : Outcome
    }

    data class License(
        val key: String,
        val claims: LicenseFormat.Claims,
        val fingerprint: String,
    )

    fun verify(key: String, publicKeyBase64: String, nowEpochSeconds: Long): Outcome {
        if (publicKeyBase64.isBlank() || publicKeyBase64.startsWith("DEMO")) {
            return Outcome.Rejected(
                "This build has no license public key baked in. Put the key from " +
                    "tools/keygen genkey into android/license.pubkey and rebuild.",
            )
        }

        val (payload, signature) = try {
            LicenseFormat.split(key)
        } catch (e: IllegalArgumentException) {
            return Outcome.Rejected(e.message ?: "malformed key")
        }

        val publicKey = try {
            Ed25519.publicKeyFromRaw(Base64.getUrlDecoder().decode(publicKeyBase64.trim()))
        } catch (_: Exception) {
            return Outcome.Rejected("embedded public key is corrupt")
        }

        val valid = Ed25519.verify(
            publicKey,
            payload.toByteArray(StandardCharsets.UTF_8),
            signature,
        )
        if (!valid) {
            return Outcome.Rejected("signature does not match this build")
        }

        val claims = try {
            LicenseFormat.decodePayload(payload)
        } catch (e: IllegalArgumentException) {
            return Outcome.Rejected(e.message ?: "unreadable claims")
        }

        return when {
            claims.notBefore > nowEpochSeconds ->
                Outcome.NotYetValid(claims)
            claims.isTimeLimited && claims.expiresAt <= nowEpochSeconds ->
                Outcome.Expired(claims)
            else -> Outcome.Valid(claims, fingerprint(signature))
        }
    }

    /** Short stable id for the key, so the UI can show "this device is seat X". */
    fun fingerprint(signature: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(signature)
        return digest.take(6).joinToString("") { "%02x".format(it) }
    }

    fun daysRemaining(claims: LicenseFormat.Claims, nowEpochSeconds: Long): Long? {
        if (!claims.isTimeLimited) return null
        return ((claims.expiresAt - nowEpochSeconds) / 86_400L).coerceAtLeast(0)
    }
}
