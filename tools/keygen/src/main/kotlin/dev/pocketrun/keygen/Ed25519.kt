package dev.pocketrun.keygen

import net.i2p.crypto.eddsa.EdDSAPrivateKey
import net.i2p.crypto.eddsa.EdDSAPublicKey
import net.i2p.crypto.eddsa.EdDSASecurityProvider
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec
import java.security.KeyFactory
import java.security.KeyPair
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature

/**
 * Thin wrapper over the pure-Java Ed25519 implementation. The app uses the same
 * dependency so that verification behaves identically on every Android version
 * (the platform only gained a native Ed25519 provider in API 33).
 */
object Ed25519 {
    private const val PROVIDER = "EdDSASecurityProvider"

    /** eddsa 0.3.0 registers its signature service under this JCA name. */
    private const val SIGNATURE_ALGORITHM = "NONEwithEdDSA"
    private val PROVIDER_INSTANCE = EdDSASecurityProvider()
    private val spec = EdDSANamedCurveTable.getByName("Ed25519")
    private val random = SecureRandom()

    fun generateKeyPair(): KeyPair = keyPairFromSeed(randomSeed())

    /**
     * Returns the 32-byte seed together with its keypair. The seed is what gets
     * stored in license.key; [java.security.PrivateKey.getEncoded] is not usable
     * for that, so the seed is threaded through explicitly.
     */
    fun generate(): Pair<ByteArray, KeyPair> {
        val seed = randomSeed()
        return seed to keyPairFromSeed(seed)
    }

    fun randomSeed(): ByteArray = ByteArray(32).also { random.nextBytes(it) }

    fun keyPairFromSeed(seed: ByteArray): KeyPair {
        require(seed.size == 32) { "Ed25519 seed must be 32 bytes" }
        val privateKey = KeyFactory
            .getInstance("EdDSA", PROVIDER_INSTANCE)
            .generatePrivate(EdDSAPrivateKeySpec(seed, spec)) as EdDSAPrivateKey
        val publicKey = publicKeyOf(privateKey)
        return KeyPair(publicKey, privateKey)
    }

    fun publicKeyOf(privateKey: PrivateKey): PublicKey = KeyFactory
        .getInstance("EdDSA", PROVIDER_INSTANCE)
        .generatePublic(EdDSAPublicKeySpec((privateKey as EdDSAPrivateKey).a, spec))

    fun publicKeyFromRaw(encoded: ByteArray): PublicKey = KeyFactory
        .getInstance("EdDSA", PROVIDER_INSTANCE)
        .generatePublic(EdDSAPublicKeySpec(encoded, spec))

    /**
     * The 32 raw key bytes. [java.security.PublicKey.getEncoded] would give the
     * 44-byte X.509 SubjectPublicKeyInfo, which is not what gets embedded in the
     * app and not what [publicKeyFromRaw] accepts.
     */
    fun encodePublicKey(publicKey: PublicKey): ByteArray = (publicKey as EdDSAPublicKey).abyte

    fun sign(privateKey: PrivateKey, message: ByteArray): ByteArray =
        Signature.getInstance(SIGNATURE_ALGORITHM, PROVIDER_INSTANCE).run {
            initSign(privateKey)
            update(message)
            sign()
        }

    fun verify(publicKey: PublicKey, message: ByteArray, signature: ByteArray): Boolean = try {
        Signature.getInstance(SIGNATURE_ALGORITHM, PROVIDER_INSTANCE).run {
            initVerify(publicKey)
            update(message)
            verify(signature)
        }
    } catch (_: Exception) {
        false
    }
}
