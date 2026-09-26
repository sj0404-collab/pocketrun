package dev.pocketrun.license

import net.i2p.crypto.eddsa.EdDSAPrivateKey
import net.i2p.crypto.eddsa.EdDSASecurityProvider
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature

/**
 * Pure-Java Ed25519. Android only gained a platform Ed25519 provider in API 33,
 * so the app carries its own implementation and behaves the same on every
 * supported device. The signing tool uses the same library.
 */
internal object Ed25519 {
    private const val SIGNATURE_ALGORITHM = "NONEwithEdDSA"
    private val provider = EdDSASecurityProvider()
    private val spec = EdDSANamedCurveTable.getByName("Ed25519")

    fun publicKeyFromRaw(encoded: ByteArray): PublicKey = KeyFactory
        .getInstance("EdDSA", provider)
        .generatePublic(EdDSAPublicKeySpec(encoded, spec))

    fun encodePublicKey(publicKey: PublicKey): ByteArray = publicKey.encoded

    fun publicKeyOf(privateKey: PrivateKey): PublicKey = KeyFactory
        .getInstance("EdDSA", provider)
        .generatePublic(EdDSAPublicKeySpec((privateKey as EdDSAPrivateKey).a, spec))

    /** Derives the matching public key from a 32-byte seed. Used by tests. */
    fun publicKeyFromSeed(seed: ByteArray): PublicKey {
        require(seed.size == 32) { "Ed25519 seed must be 32 bytes" }
        val privateKey = KeyFactory
            .getInstance("EdDSA", provider)
            .generatePrivate(EdDSAPrivateKeySpec(seed, spec))
        return publicKeyOf(privateKey)
    }

    fun verify(publicKey: PublicKey, message: ByteArray, signature: ByteArray): Boolean = try {
        Signature.getInstance(SIGNATURE_ALGORITHM, provider).run {
            initVerify(publicKey)
            update(message)
            verify(signature)
        }
    } catch (_: Exception) {
        false
    }
}
