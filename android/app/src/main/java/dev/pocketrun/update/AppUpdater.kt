package dev.pocketrun.update

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches the newest release from GitHub and downloads its APK.
 *
 * The repository is public, so this works without a token; a configured one is
 * sent anyway, which raises the API rate limit for someone who set it up for
 * the agent.
 */
class AppUpdater(private val repo: String, private val token: String = "") {

    class UpdateException(message: String) : IOException(message)

    /** The newest published release. Throws [UpdateException] with a readable reason. */
    fun latest(): ReleaseInfo {
        val body = try {
            read(URL("$API/repos/$repo/releases/latest"), "application/vnd.github+json")
        } catch (e: UpdateException) {
            throw e
        } catch (e: Exception) {
            throw UpdateException("GitHub недоступен: ${e.message ?: e.javaClass.simpleName}")
        }
        return try {
            AppUpdate.parse(body)
        } catch (e: AppUpdate.ParseException) {
            throw UpdateException(e.message ?: "релиз не разобран")
        }
    }

    /**
     * Downloads [url] to [target] through a `.part` file, so an interrupted
     * download is never mistaken for a finished one. [onProgress] gets
     * (downloaded, total); total is 0 when the server did not say.
     */
    fun download(url: String, target: File, onProgress: (Long, Long) -> Unit): File {
        target.parentFile?.mkdirs()
        val partial = File(target.parentFile, target.name + ".part")
        partial.delete()
        target.delete()
        val connection = try {
            open(URL(url), "application/octet-stream")
        } catch (e: Exception) {
            throw UpdateException("не удалось начать загрузку: ${e.message ?: e.javaClass.simpleName}")
        }
        try {
            val total = connection.contentLengthLong
            if (total > MAX_APK_BYTES) {
                throw UpdateException("файл больше, чем приложение готово скачать")
            }
            var done = 0L
            connection.inputStream.use { input ->
                partial.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        onProgress(done, if (total > 0) total else 0L)
                    }
                }
            }
            if (total > 0 && done != total) throw UpdateException("загрузка не завершена")
            if (!partial.renameTo(target)) {
                throw UpdateException("не удалось сохранить файл")
            }
            return target
        } catch (e: UpdateException) {
            partial.delete()
            throw e
        } catch (e: Exception) {
            partial.delete()
            throw UpdateException("загрузка не удалась: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            connection.disconnect()
        }
    }

    private fun read(url: URL, accept: String): String {
        val connection = open(url, accept)
        try {
            val status = connection.responseCode
            if (status != 200) {
                throw UpdateException(
                    when (status) {
                        404 -> "релиз не найден: $repo"
                        403, 429 -> "GitHub не отдаёт релиз (лимит запросов)"
                        else -> "GitHub ответил HTTP $status"
                    },
                )
            }
            return connection.inputStream.bufferedReader().readText()
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: URL, accept: String): HttpURLConnection =
        (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", accept)
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
        }

    companion object {
        private const val API = "https://api.github.com"
        private const val USER_AGENT = "PocketRun-Updater"
        private const val MAX_APK_BYTES = 512L * 1024 * 1024
    }
}
