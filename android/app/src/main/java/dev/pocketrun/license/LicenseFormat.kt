package dev.pocketrun.license

import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Wire format for PocketRun license keys.
 *
 * A key looks like:
 *
 *     PRK1.<base64url(payload)>.<base64url(ed25519-signature)>
 *
 * The payload is a canonical line-based UTF-8 document, not JSON, so that
 * signing is byte-for-byte reproducible:
 *
 *     PRK1
 *     name=Jane Doe
 *     seat=work-laptop
 *     plan=pro
 *     nbf=1750000000
 *     exp=1760000000
 *
 * The signature covers the raw payload bytes. The verifier therefore never has
 * to re-serialize anything: it decodes the payload, verifies the signature over
 * those exact bytes, and only then parses the claims.
 *
 * This file is duplicated in the signing tool at
 * tools/keygen/src/main/kotlin/dev/pocketrun/keygen/LicenseFormat.kt.
 * Both copies are pinned to the same test vector in
 * src/test/java/dev/pocketrun/license/LicenseFormatTest.kt and
 * tools/keygen/src/test/kotlin/dev/pocketrun/keygen/LicenseFormatTest.kt,
 * so a change on one side that breaks the other fails the build.
 */
object LicenseFormat {
    const val PREFIX = "PRK1"
    const val VERSION = "PRK1"

    private const val KEY_SEPARATOR = '.'
    private val WHITESPACE = Regex("\\s+")

    private val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder: Base64.Decoder = Base64.getUrlDecoder()

    data class Claims(
        val name: String,
        val seat: String,
        val plan: String,
        val notBefore: Long,
        val expiresAt: Long,
    ) {
        val isTimeLimited: Boolean get() = expiresAt > 0
    }

    fun encodePayload(claims: Claims): String = buildString {
        append(VERSION).append('\n')
        append("name=").append(escape(claims.name)).append('\n')
        append("seat=").append(escape(claims.seat)).append('\n')
        append("plan=").append(escape(claims.plan)).append('\n')
        append("nbf=").append(claims.notBefore).append('\n')
        append("exp=").append(claims.expiresAt).append('\n')
    }

    fun decodePayload(payload: String): Claims {
        val lines = payload.split('\n')
        require(lines.isNotEmpty() && lines[0] == VERSION) {
            "unsupported license payload version"
        }
        val fields = HashMap<String, String>()
        for (line in lines.drop(1)) {
            if (line.isEmpty()) continue
            val eq = line.indexOf('=')
            require(eq > 0) { "malformed license field: $line" }
            fields[line.substring(0, eq)] = unescape(line.substring(eq + 1))
        }
        return Claims(
            name = fields["name"].orEmpty(),
            seat = fields["seat"].orEmpty(),
            plan = fields["plan"] ?: "trial",
            notBefore = fields["nbf"]?.toLongOrNull() ?: 0L,
            expiresAt = fields["exp"]?.toLongOrNull() ?: 0L,
        )
    }

    fun assemble(payload: String, signature: ByteArray): String {
        val body = encoder.encodeToString(payload.toByteArray(StandardCharsets.UTF_8))
        return PREFIX + KEY_SEPARATOR + body + KEY_SEPARATOR + encoder.encodeToString(signature)
    }

    /** Splits a key into its payload text and signature bytes. */
    fun split(key: String): Pair<String, ByteArray> {
        val clean = normalize(key)
        require(clean.startsWith("$PREFIX$KEY_SEPARATOR")) { "not a PocketRun license key" }
        val rest = clean.removePrefix("$PREFIX$KEY_SEPARATOR")
        val parts = rest.split(KEY_SEPARATOR)
        require(parts.size == 2 && parts.all { it.isNotEmpty() }) { "truncated license key" }
        val payload = String(decoder.decode(parts[0]), StandardCharsets.UTF_8)
        return payload to decoder.decode(parts[1])
    }

    /** Users paste keys with spaces and line breaks; the signature is over the exact bytes. */
    fun normalize(key: String): String = key.replace(WHITESPACE, "")

    fun toDisplayBlocks(key: String, blockSize: Int = 8): String =
        normalize(key).chunked(blockSize).joinToString(" ")

    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r")

    private fun unescape(value: String): String {
        val out = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '\\' && i + 1 < value.length) {
                when (value[i + 1]) {
                    'n' -> out.append('\n')
                    'r' -> out.append('\r')
                    '\\' -> out.append('\\')
                    else -> out.append(c).append(value[i + 1])
                }
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }
}
