package dev.pocketrun.runtime.npm

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The slice of the npm registry HTTP API the app needs: package metadata and
 * tarball downloads. Works against registry.npmjs.org; the same endpoints are
 * served by any npm mirror (GPR, Verdaccio, …) by changing [baseUrl].
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

    /** Downloads the tarball at [url] (a `dist.tarball` value) into [dest]. */
    fun downloadTarball(url: String, dest: File) {
        http(url, dest)
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
        val input = conn.inputStream
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
}
