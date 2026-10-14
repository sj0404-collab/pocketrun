package dev.pocketrun.agent.opencode

import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * A small client for the public models.dev catalog (https://models.dev/api.json):
 * the same source opencode uses to know which providers and models exist, with
 * their capabilities. The catalog is a few megabytes, so it is fetched on
 * demand, cached for a day, and parsed lazily into a compact list.
 */
object ModelsDev {

    const val URL = "https://models.dev/api.json"
    private const val MAX_BYTES = 8 * 1024 * 1024
    private const val MAX_AGE_MS = 24L * 60 * 60 * 1000

    data class ModelInfo(
        val id: String,
        val name: String,
        val reasoning: Boolean,
        val toolCall: Boolean,
        val attachment: Boolean,
    )

    data class ProviderInfo(val id: String, val name: String, val models: List<ModelInfo>)

    /** Returns the catalog, using [cacheDir] as a one-day cache. Empty on failure. */
    fun load(cacheDir: File): Map<String, ProviderInfo> {
        val cache = File(cacheDir, "models-dev.json")
        val fresh = cache.isFile && System.currentTimeMillis() - cache.lastModified() < MAX_AGE_MS
        val text = if (fresh) runCatching { cache.readText(Charsets.UTF_8) }.getOrNull()
        else download()?.also { body -> runCatching { cache.parentFile?.mkdirs(); cache.writeText(body, Charsets.UTF_8) } }
            ?: runCatching { cache.readText(Charsets.UTF_8) }.getOrNull()
        return parse(text ?: return emptyMap())
    }

    private fun download(): String? = try {
        val conn = (URL(URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "PocketRun/1.3")
        }
        val status = conn.responseCode
        if (status !in 200..299) null
        else conn.inputStream.bufferedReader(Charsets.UTF_8).use { r ->
            val buf = CharArray(64 * 1024)
            val sb = StringBuilder()
            var n: Int
            while (r.read(buf).also { n = it } >= 0) {
                sb.append(buf, 0, n)
                if (sb.length > MAX_BYTES) break
            }
            sb.toString()
        }
    } catch (t: Throwable) {
        null
    }

    fun parse(text: String): Map<String, ProviderInfo> {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return emptyMap()
        val out = LinkedHashMap<String, ProviderInfo>()
        val providers = root.keys()
        while (providers.hasNext()) {
            val pid = providers.next()
            val p = root.optJSONObject(pid) ?: continue
            val models = mutableListOf<ModelInfo>()
            p.optJSONObject("models")?.let { m ->
                val names = m.keys()
                while (names.hasNext()) {
                    val mid = names.next()
                    val info = m.optJSONObject(mid) ?: continue
                    models += ModelInfo(
                        id = info.optString("id").ifBlank { mid },
                        name = info.optString("name").ifBlank { mid },
                        reasoning = info.optBoolean("reasoning", false),
                        toolCall = info.optBoolean("tool_call", false),
                        attachment = info.optBoolean("attachment", false),
                    )
                }
            }
            out[pid] = ProviderInfo(pid, p.optString("name").ifBlank { pid }, models.sortedBy { it.name.lowercase() })
        }
        return out
    }
}
