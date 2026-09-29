package dev.pocketrun.update

import org.json.JSONObject

/** A published release and the one file the app installs from it. */
data class ReleaseInfo(
    val tag: String,
    val version: String,
    val notes: String,
    val asset: Asset,
) {
    data class Asset(val name: String, val url: String, val size: Long)
}

/**
 * Reading a GitHub release and deciding whether it is newer than the build that
 * is running. No Android and no networking here on purpose: this is the part
 * that has to be right, so it is the part that is unit-tested.
 */
object AppUpdate {

    /** The asset an install comes from; the debug one is a different app id. */
    const val APK_SUFFIX = "-release.apk"

    class ParseException(message: String) : Exception(message)

    fun parse(json: String): ReleaseInfo {
        val release = try {
            JSONObject(json)
        } catch (e: Exception) {
            throw ParseException("ответ GitHub не разобран")
        }
        if (release.optBoolean("draft")) throw ParseException("релиз помечен как черновик")
        val tag = release.optString("tag_name").ifBlank {
            throw ParseException("в релизе нет тега")
        }
        val assets = release.optJSONArray("assets")
            ?: throw ParseException("в релизе нет вложений")
        var chosen: JSONObject? = null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name")
            if (!name.endsWith(".apk")) continue
            if (name.endsWith(APK_SUFFIX)) {
                chosen = asset
                break
            }
            // A release whose file is named differently is still installable;
            // a debug build is not, it is a separate app.
            if (chosen == null && !name.contains("debug")) chosen = asset
        }
        val asset = chosen ?: throw ParseException("в релизе нет APK для установки")
        val url = asset.optString("browser_download_url").ifBlank { asset.optString("url") }
        if (url.isBlank()) throw ParseException("у APK в релизе нет ссылки")
        return ReleaseInfo(
            tag = tag,
            version = version(tag),
            notes = release.optString("body").trim(),
            asset = ReleaseInfo.Asset(
                name = asset.optString("name"),
                url = url,
                size = asset.optLong("size", 0L),
            ),
        )
    }

    /**
     * The version a tag names: `v1.5.0` and `1.5.0-debug` are both `1.5.0`. The
     * build's own versionName comes from the nearest tag (build.gradle.kts), so
     * comparing the two needs nothing but this.
     */
    fun version(tag: String): String = tag.trim()
        .removePrefix("v")
        .removePrefix("V")
        .substringBefore('-')
        .substringBefore('+')
        .trim()

    /**
     * True when [candidate] is strictly newer than [installed]. A prerelease
     * suffix (`-rc1`) is not read as a number and does not make anything newer
     * than the release it precedes; GitHub keeps prereleases out of /latest
     * anyway.
     */
    fun isNewer(candidate: String, installed: String): Boolean {
        val left = parts(candidate)
        val right = parts(installed)
        val size = maxOf(left.size, right.size)
        for (i in 0 until size) {
            val a = left.getOrElse(i) { 0L }
            val b = right.getOrElse(i) { 0L }
            if (a != b) return a > b
        }
        return false
    }

    private fun parts(version: String): List<Long> = version.split('.')
        .map { it.takeWhile(Char::isDigit) }
        .map { if (it.isEmpty()) 0L else it.toLong() }
}
