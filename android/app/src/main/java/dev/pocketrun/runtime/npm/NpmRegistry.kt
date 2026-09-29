package dev.pocketrun.runtime.npm

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Base64

/**
 * The slice of the npm registry HTTP API the app needs: package metadata and
 * tarball downloads. Works against registry.npmjs.org; the same endpoints are
 * served by any npm mirror (GPR, Verdaccio, …) by changing [baseUrl].
 *
 * A registry is a code source: whoever answers it chooses the JavaScript the
 * app evaluates. Two things are therefore checked on every tarball — the digest
 * the metadata promised, and that the download really comes from the registry
 * the metadata came from. Metadata without a digest is refused, exactly like
 * npm does.
 */
class NpmRegistry(private val baseUrl: String = "https://registry.npmjs.org/") {

    class RegistryException(message: String) : Exception(message)

    /** Full metadata document for [name]; scoped names are URL-encoded. */
    fun metadata(name: String): JSONObject {
        val encoded = URLEncoder.encode(name, "UTF-8")
        val text = http(baseUrl + encoded) { conn ->
            conn.setRequestProperty("Accept", "application/json")
        }
        return try {
            JSONObject(text)
        } catch (t: Throwable) {
            throw RegistryException("registry вернул не-JSON ответ для $name")
        }
    }

    /**
     * Downloads the tarball at [url] into [dest] and proves it is the one the
     * metadata described.
     *
     * @param integrity the `dist.integrity` value (`sha512-<base64>`, possibly
     *   several space-separated digests). Required: a packument without it
     *   cannot be trusted to be the real package.
     */
    fun downloadTarball(url: String, dest: File, integrity: String?) {
        requireTrusted(url)
        val expected = requireIntegrity(integrity, url)
        http(url, dest)
        val actual = digestOf(dest, expected.algorithm)
        if (actual != expected.b64) {
            // Never leave a tarball that failed its check on disk: the caller
            // would happily extract it.
            dest.delete()
            val detail = expected.algorithms.joinToString(", ")
            throw RegistryException(
                "несовпадение контрольной суммы tarball (ожидалась $detail, получено " +
                    "${actual.take(12)}…) — пакет отклонён",
            )
        }
    }

    // ------------------------------------------------------------- digests

    /** One expected digest: the algorithm and the base64 it must equal. */
    private data class Expected(val algorithm: String, val b64: String, val algorithms: List<String>)

    private fun requireIntegrity(integrity: String?, url: String): Expected {
        val digests = integrity?.split(' ', '\t')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        val parsed = digests.mapNotNull { parseDigest(it) }
        if (parsed.isEmpty()) {
            throw RegistryException(
                "в метаданных нет integrity для ${url.take(120)} — пакет без контрольной суммы не ставится",
            )
        }
        // The strongest digest is the one that is checked; npm does the same.
        val strongest = parsed.maxBy { it.first.length }
        return Expected(strongest.first, strongest.second, parsed.map { it.first }.distinct())
    }

    private fun parseDigest(value: String): Pair<String, String>? {
        val dash = value.indexOf('-')
        if (dash <= 0) return null
        val algorithm = value.substring(0, dash).lowercase()
        if (algorithm !in SUPPORTED) return null
        val b64 = value.substring(dash + 1)
        return runCatching { Base64.getDecoder().decode(b64) }.map { algorithm to b64 }.getOrNull()
    }

    private fun digestOf(file: File, algorithm: String): String {
        val digest = MessageDigest.getInstance(algorithm)
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return Base64.getEncoder().encodeToString(digest.digest())
    }

    /**
     * A tarball must be https and must live on the registry the metadata came
     * from — otherwise a compromised packument can point the app at an
     * arbitrary host and the integrity check would only prove that *that* host
     * serves what it said.
     */
    private fun requireTrusted(url: String) {
        val parsed = runCatching { URL(url) }.getOrElse {
            throw RegistryException("некорректный tarball URL: ${url.take(120)}")
        }
        if (parsed.protocol != "https") {
            throw RegistryException("tarball должен скачиваться по https, а не ${parsed.protocol}")
        }
        val registry = runCatching { URL(baseUrl).host }.getOrElse { "" }
        val host = parsed.host.lowercase()
        val allowed = registry.lowercase()
        if (allowed.isNotEmpty() && host != allowed && !host.endsWith(".$allowed")) {
            throw RegistryException(
                "tarball лежит на $host, а метаданные пришли с $allowed — пакет отклонён",
            )
        }
    }

    // ---------------------------------------------------------------- http

    private inline fun http(url: String, prepare: (HttpURLConnection) -> Unit = {}): String {
        val conn = open(url)
        prepare(conn)
        val status = conn.responseCode
        val text = streamText(conn, status)
        if (status !in 200..299) {
            throw RegistryException("HTTP $status для ${url.take(120)}: ${text.take(200)}")
        }
        return text
    }

    private fun http(url: String, dest: File) {
        val conn = open(url)
        val status = conn.responseCode
        if (status !in 200..299) {
            val text = streamText(conn, status)
            throw RegistryException("HTTP $status для ${url.take(120)}: ${text.take(200)}")
        }
        copy(conn.inputStream, dest)
    }

    private fun copy(input: InputStream, dest: File) {
        FileOutputStream(dest).use { out ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
        }
    }

    private fun open(url: String): HttpURLConnection {
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            throw RegistryException("поддерживаются только http(s) URL")
        }
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 120_000
        conn.instanceFollowRedirects = true
        // npmjs.org rejects requests without a user agent.
        conn.setRequestProperty("User-Agent", "pocketrun-npm/1.2 (Rhino)")
        return conn
    }

    private fun streamText(conn: HttpURLConnection, status: Int): String {
        val stream = if (status in 200..299) conn.inputStream else conn.errorStream
        if (stream == null) return ""
        // Success bodies (package metadata) can be megabytes; only error text is capped.
        return if (status in 200..299) {
            stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } else {
            stream.bufferedReader(Charsets.UTF_8).use { it.readText().take(8_192) }
        }
    }

    companion object {
        private val SUPPORTED = setOf("sha512", "sha384", "sha256", "sha1")
    }
}
