package dev.pocketrun.agent.opencode

import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

/**
 * The `github` tool backend: a compact GitHub REST client. It lets the agent
 * manage repositories and **GitHub Actions runners** entirely from the app:
 * create a repo, push files (including workflow files under `.github/workflows`), dispatch a
 * workflow, poll runs, read logs and download artifacts. Inside an
 * `ubuntu-latest` runner anything can run — npm packages, opencode itself,
 * real compilers — which is the "heavy work" escape hatch of the mobile
 * sandbox.
 */
class GitHubClient(private val token: String) {

    companion object {
        private const val API = "https://api.github.com"
        private const val MAX_OUTPUT = 16 * 1024
        private const val MAX_ZIP_TEXT = 24 * 1024

        /** github ops that only read — auto-approved in manual confirmation mode. */
        val READ_OPS = setOf("repo_list", "file_get", "runs", "logs", "artifacts", "me")
    }

    class GitHubException(message: String) : Exception(message)

    // ---------------------------------------------------------------- core

    private fun call(
        method: String,
        path: String,
        body: String? = null,
        raw: Boolean = false,
        followRedirects: Boolean = true,
    ): Pair<Int, String> {
        val conn = (URL("$API$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = followRedirects
            setRequestProperty("Accept", if (raw) "application/vnd.github.raw" else "application/vnd.github+json")
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("User-Agent", "PocketRun-Agent/1.4")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        if (body != null) conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val status = conn.responseCode
        val text = (if (status in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        return status to text
    }

    private fun stream(method: String, path: String): InputStream {
        val conn = (URL("$API$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 120_000
            instanceFollowRedirects = true // logs/artifacts 302 to a signed URL
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("User-Agent", "PocketRun-Agent/1.4")
        }
        val status = conn.responseCode
        if (status !in 200..299) {
            val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            throw GitHubException("HTTP $status: ${err.take(300)}")
        }
        return conn.inputStream
    }

    private fun ok(status: Int, text: String, what: String): String {
        if (status !in 200..299) {
            val hint = when (status) {
                401 -> " (проверьте GitHub-токен: нужны права repo и workflow)"
                403 -> " (токен без нужных прав или лимит API)"
                404 -> " (нет такого репозитория/файла — он приватный и токен без доступа?)"
                422 -> " (файл уже существует или невалидные данные)"
                else -> ""
            }
            throw GitHubException("$what: HTTP $status$hint: ${text.take(300)}")
        }
        return text
    }

    private fun pretty(text: String): String {
        val t = try {
            if (text.startsWith("[")) JSONArray(text).toString(2) else JSONObject(text).toString(2)
        } catch (t: Throwable) {
            text
        }
        return t.take(MAX_OUTPUT) + if (t.length > MAX_OUTPUT) "\n… (обрезано)" else ""
    }

    // ---------------------------------------------------------------- ops

    fun me(): String {
        val (s, t) = call("GET", "/user")
        ok(s, t, "user")
        val o = JSONObject(t)
        return "login=${o.optString("login")} name=${o.optString("name")}"
    }

    fun repoList(): String {
        val (s, t) = call("GET", "/user/repos?sort=updated&per_page=30")
        ok(s, t, "repo_list")
        val arr = JSONArray(t)
        val sb = StringBuilder()
        for (i in 0 until arr.length()) {
            val r = arr.getJSONObject(i)
            sb.append("${r.optString("full_name")}  ${if (r.optBoolean("private")) "[private]" else "[public]"}  → ${r.optString("html_url")}\n")
        }
        return sb.toString().ifEmpty { "(репозиториев нет)" }
    }

    fun repoCreate(name: String, private: Boolean, description: String?): String {
        val body = JSONObject().put("name", name).put("private", private)
        if (!description.isNullOrBlank()) body.put("description", description)
        val (s, t) = call("POST", "/user/repos", body.toString())
        ok(s, t, "repo_create")
        val o = JSONObject(t)
        return "создан ${o.optString("full_name")} → ${o.optString("html_url")} (default branch: ${o.optString("default_branch", "main")})"
    }

    /** Creates or updates files on a branch, one contents-API commit per file. */
    fun pushFiles(repo: String, files: List<Pair<String, String>>, message: String, branch: String?): String {
        val sb = StringBuilder()
        for ((path, content) in files) {
            val b = if (branch.isNullOrBlank()) "" else "?ref=$branch"
            val (gs, gt) = call("GET", "/repos/$repo/contents/$path$b")
            var sha: String? = null
            if (gs == 200) sha = JSONObject(gt).optString("sha").takeIf { it.isNotEmpty() }
            val body = JSONObject()
                .put("message", message)
                .put("content", Base64.getEncoder().encodeToString(content.toByteArray(Charsets.UTF_8)))
            if (sha != null) body.put("sha", sha)
            if (!branch.isNullOrBlank()) body.put("branch", branch)
            val (s, t) = call("PUT", "/repos/$repo/contents/$path", body.toString())
            ok(s, t, "push $path")
            sb.append("pushed $path (commit ${JSONObject(t).optJSONObject("commit")?.optString("sha")?.take(7) ?: "?"})\n")
        }
        return sb.toString().trim()
    }

    fun fileGet(repo: String, path: String, ref: String?): String {
        val q = if (ref.isNullOrBlank()) "" else "?ref=$ref"
        val (s, t) = call("GET", "/repos/$repo/contents/$path$q", raw = true)
        ok(s, t, "file_get")
        return t.take(MAX_OUTPUT) + if (t.length > MAX_OUTPUT) "\n… (обрезано)" else ""
    }

    fun workflowCreate(repo: String, filename: String, yaml: String): String =
        pushFiles(repo, listOf(".github/workflows/$filename" to yaml), "workflow: $filename", null)

    /** Triggers workflow_dispatch. `workflow` is the file name, e.g. run.yml. */
    fun dispatch(repo: String, workflow: String, ref: String?): String {
        val body = JSONObject().put("ref", ref ?: "main")
        val (s, t) = call("POST", "/repos/$repo/actions/workflows/$workflow/dispatches", body.toString())
        ok(s, t, "dispatch")
        return "workflow $workflow запущен (branch ${ref ?: "main"}). Статус: github runs {\"repo\":\"$repo\"}"
    }

    fun runs(repo: String, limit: Int): String {
        val (s, t) = call("GET", "/repos/$repo/actions/runs?per_page=${limit.coerceIn(1, 30)}")
        ok(s, t, "runs")
        val arr = JSONObject(t).optJSONArray("workflow_runs") ?: JSONArray()
        val sb = StringBuilder()
        for (i in 0 until arr.length()) {
            val r = arr.getJSONObject(i)
            sb.append(
                "run #${r.optLong("run_number")} id=${r.optLong("id")} ${r.optString("status")}" +
                    "/${r.optString("conclusion").takeIf { it != "null" } ?: "…"} ${r.optString("name")} " +
                    "(${r.optString("event")}, ${r.optString("created_at")})\n",
            )
        }
        return sb.toString().ifEmpty { "(запусков нет)" }
    }

    /** Run logs come as a zip of per-job text files. */
    fun logs(repo: String, runId: Long): String {
        stream("GET", "/repos/$repo/actions/runs/$runId/logs").use { ins ->
            return zipToText(ins, MAX_ZIP_TEXT).ifEmpty { "(логи пусты)" }
        }
    }

    fun artifacts(repo: String, runId: Long?): String {
        val q = if (runId != null) "?run_id=$runId" else ""
        val (s, t) = call("GET", "/repos/$repo/actions/artifacts$q")
        ok(s, t, "artifacts")
        val arr = JSONObject(t).optJSONArray("artifacts") ?: JSONArray()
        val sb = StringBuilder()
        for (i in 0 until arr.length()) {
            val a = arr.getJSONObject(i)
            sb.append("id=${a.optLong("id")} ${a.optString("name")} ${a.optLong("size_in_bytes")} bytes (run ${a.optLong("run_id")})\n")
        }
        return sb.toString().ifEmpty { "(артефактов нет)" }
    }

    /** Lists an artifact's files and inlines the small text ones. */
    fun artifactDownload(repo: String, artifactId: Long): String {
        stream("GET", "/repos/$repo/actions/artifacts/$artifactId/zip").use { ins ->
            return zipToText(ins, MAX_ZIP_TEXT).ifEmpty { "(пусто)" }
        }
    }

    /** Escape hatch: any GitHub REST call, e.g. PRs, issues, releases. */
    fun api(method: String, path: String, body: String?): String {
        val m = method.uppercase()
        if (m !in setOf("GET", "POST", "PATCH", "PUT", "DELETE")) throw GitHubException("метод $m не поддерживается")
        if (!path.startsWith("/")) throw GitHubException("path должен начинаться с /, например /repos/owner/name")
        val (s, t) = call(m, path, body?.takeIf { it.isNotBlank() })
        ok(s, t, "api $m $path")
        return if (t.isBlank()) "(пустой ответ $s)" else pretty(t)
    }

    // ---------------------------------------------------------------- zip

    /** Reads a zip stream: lists entries and inlines the small text ones. */
    private fun zipToText(ins: InputStream, cap: Int): String {
        val sb = StringBuilder()
        java.util.zip.ZipInputStream(ins).use { zip ->
            var entry = zip.nextEntry
            val buf = ByteArray(8192)
            while (entry != null && sb.length < cap) {
                if (!entry.isDirectory) {
                    sb.append("── ").append(entry.name).append('\n')
                    if (looksTexty(entry.name)) {
                        val text = StringBuilder()
                        while (true) {
                            val n = zip.read(buf)
                            if (n <= 0 || text.length >= cap - sb.length) break
                            text.append(String(buf, 0, n, Charsets.UTF_8))
                        }
                        sb.append(text).append('\n')
                    }
                }
                entry = zip.nextEntry
            }
        }
        return sb.toString().take(cap)
    }

    private fun looksTexty(name: String): Boolean {
        if (name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jar") || name.endsWith(".apk") ||
            name.endsWith(".zip") || name.endsWith(".dex") || name.endsWith(".so") || name.endsWith(".bin")
        ) return false
        return true
    }
}
