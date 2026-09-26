package dev.pocketrun.keygen

import java.io.File
import java.nio.charset.StandardCharsets
import java.security.PrivateKey
import java.time.Instant
import java.util.Base64

private val B64 = Base64.getUrlEncoder().withoutPadding()

private const val USAGE = """
pocketrun keygen - offline license keys for PocketRun

Commands:
  genkey [--out DIR] [--force]
      Creates a new Ed25519 signing keypair.
      Writes DIR/license.key   (private seed - keep secret, never commit)
      Writes DIR/license.pubkey (public key, paste into the app build)

  issue --name NAME --seat SEAT [--plan pro] [--days N] [--forever]
         [--key-file PATH] [--from EPOCH] [--exp EPOCH]
      Prints a signed license key.

  verify <KEY> [--key-file PATH]
      Verifies a key against the public key derived from the private seed.

  inspect <KEY>
      Decodes a key and prints its claims without verifying the signature.

  public-key --key-file PATH
      Prints the public key in the format the app build expects.
"""

fun main(args: Array<String>) {
    val options = Options.parse(args)
    if (options.positional.isEmpty() || options.flag("help")) {
        println(USAGE.trim())
        return
    }
    when (options.positional[0]) {
        "genkey" -> genKey(options)
        "issue" -> issue(options)
        "verify" -> verify(options)
        "inspect" -> inspect(options)
        "public-key" -> publicKey(options)
        else -> {
            System.err.println("unknown command: ${options.positional[0]}")
            println(USAGE.trim())
            kotlin.system.exitProcess(2)
        }
    }
}

private fun genKey(options: Options) {
    val out = File(options.value("out") ?: ".")
    val privateFile = File(out, "license.key")
    val publicFile = File(out, "license.pubkey")
    if (!options.flag("force") && (privateFile.exists() || publicFile.exists())) {
        System.err.println("refusing to overwrite existing key; pass --force")
        kotlin.system.exitProcess(1)
    }
    out.mkdirs()
    val pair = Ed25519.generateKeyPair()
    privateFile.writeText(B64.encodeToString(pair.private.encoded))
    publicFile.writeText(B64.encodeToString(Ed25519.encodePublicKey(pair.public)))
    println("private key -> ${privateFile.absolutePath}")
    println("public  key -> ${publicFile.absolutePath}")
    println()
    println("Put the public key into android/license.pubkey so the app verifies against it.")
}

private fun issue(options: Options) {
    val name = options.value("name") ?: error("--name is required")
    val seat = options.value("seat") ?: error("--seat is required")
    val plan = options.value("plan") ?: "pro"
    val notBefore = options.value("from")?.toLongOrNull() ?: Instant.now().epochSecond
    val expiresAt = when {
        options.flag("forever") -> 0L
        options.value("exp")?.toLongOrNull() != null -> options.value("exp")!!.toLong()
        else -> notBefore + (options.value("days")?.toLongOrNull() ?: 30L) * 86_400L
    }
    val privateKey = loadPrivateKey(options)
    val claims = LicenseFormat.Claims(
        name = name,
        seat = seat,
        plan = plan,
        notBefore = notBefore,
        expiresAt = expiresAt,
    )
    val payload = LicenseFormat.encodePayload(claims)
    val signature = Ed25519.sign(privateKey, payload.toByteArray(StandardCharsets.UTF_8))
    val key = LicenseFormat.assemble(payload, signature)
    println(LicenseFormat.toDisplayBlocks(key))
}

private fun verify(options: Options) {
    val key = options.positional.getOrNull(1) ?: error("verify needs a key argument")
    val publicKey = Ed25519.publicKeyOf(loadPrivateKey(options))
    val (payload, signature) = LicenseFormat.split(key)
    if (!Ed25519.verify(publicKey, payload.toByteArray(StandardCharsets.UTF_8), signature)) {
        System.err.println("INVALID: signature does not match")
        kotlin.system.exitProcess(1)
    }
    println("signature OK")
    printClaims(LicenseFormat.decodePayload(payload))
}

private fun inspect(options: Options) {
    val key = options.positional.getOrNull(1) ?: error("inspect needs a key argument")
    val (payload, _) = LicenseFormat.split(key)
    println("payload:")
    payload.lines().filter { it.isNotEmpty() }.forEach { println("  $it") }
    println()
    printClaims(LicenseFormat.decodePayload(payload))
}

private fun publicKey(options: Options) {
    println(B64.encodeToString(Ed25519.encodePublicKey(Ed25519.publicKeyOf(loadPrivateKey(options)))))
}

private fun printClaims(claims: LicenseFormat.Claims) {
    println("name   = ${claims.name}")
    println("seat   = ${claims.seat}")
    println("plan   = ${claims.plan}")
    println("valid  = ${Instant.ofEpochSecond(claims.notBefore)}")
    val expiry = if (claims.isTimeLimited) Instant.ofEpochSecond(claims.expiresAt).toString() else "never"
    println("expires= $expiry")
}

private fun loadPrivateKey(options: Options): PrivateKey {
    val file = File(options.value("key-file") ?: "license.key")
    check(file.isFile) { "private key not found: ${file.absolutePath}" }
    val seed = Base64.getUrlDecoder().decode(file.readText().trim().toByteArray(StandardCharsets.UTF_8))
    return Ed25519.keyPairFromSeed(seed).private
}

private class Options(val positional: List<String>, val named: Map<String, String?>) {
    fun value(name: String): String? = named[name]
    fun flag(name: String): Boolean = named.containsKey(name)

    companion object {
        fun parse(args: Array<String>): Options {
            val positional = ArrayList<String>()
            val named = LinkedHashMap<String, String?>()
            var i = 0
            while (i < args.size) {
                val arg = args[i]
                if (arg.startsWith("--")) {
                    val name = arg.removePrefix("--")
                    val next = args.getOrNull(i + 1)
                    if (next != null && !next.startsWith("--")) {
                        named[name] = next
                        i += 2
                    } else {
                        named[name] = null
                        i += 1
                    }
                } else {
                    positional += arg
                    i += 1
                }
            }
            return Options(positional, named)
        }
    }
}
