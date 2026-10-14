package dev.pocketrun.agent.opencode

import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import java.util.Locale

/**
 * The `github` tool backend: a compact GitHub REST client. It lets the agent
 * manage repositories and **GitHub Actions runners** entirely from the app:
 * create a repo, commit files (including workflow files under
 * `.github/workflows`) in a single atomic commit, dispatch a workflow, block
 * until the run finishes, read logs and download artifacts. Inside an
 * `ubuntu-latest` runner anything can run — npm packages, opencode itself,
 * real compilers — which is the "heavy work" escape hatch of the mobile
 * sandbox.
 */
class GitHubClient(private val token: String) {

    companion object {
        private const val API = "https://api.github.com"
        private const val MAX_OUTPUT = 16 * 1024
        private const val MAX_ZIP_TEXT = 24 * 1024
        const val WAIT_POLL_MS = 10_000L
        const val MAX_WAIT_SEC = 1_800

        /** github ops that only read — auto-approved in manual confirmation mode. */
        val READ_OPS = setOf("repo_list", "file_get", "runs", "run_wait", "logs", "artifacts", "me")
    }

    class GitHubException(message: String) : Exception(message)

    /** A REST answer: status plus body (JSON text or raw). */
    private class Answer(val status: Int, val text: String)

    /** One path in a commit: new content, or a deletion. */
    private data class Change(val path: String, val content: String?, val delete: Boolean = false)

    // ---------------------------------------------------------------- core

    private fun conn(method: String, path: String, body: String?, raw: Boolean, followRedirects: Boolean): HttpURLConnection =
        (URL("$API$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 90_000
            instanceFollowRedirects = followRedirects
            setRequestProperty("Accept", if (raw) "application/vnd.github.raw" else "application/vnd.github+json")
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("User-Agent", "PocketRun-Agent/1.5")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }

    private fun call(
        method: String,
        path: String,
        body: String? = null,
        raw: Boolean = false,
        followRedirects: Boolean = true,
    ): Answer {
        val c = conn(method, path, body, raw, followRedirects)
        try {
            if (body != null) c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = c.responseCode
            val text = (if (status in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return Answer(status, text)
        } finally {
            c.disconnect()
        }
    }

    private fun stream(method: String, path: String): InputStream {
        val c = conn(method, path, null, raw = false, followRedirects = true).apply { readTimeout = 120_000 }
        val status = c.responseCode
        if (status !in 200..299) {
            val err = c.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            c.disconnect()
            throw GitHubException("HTTP $status: ${err.take(300)}")
        }
        return c.inputStream
    }

    private fun ok(answer: Answer, what: String): String {
        if (answer.status !in 200..299) {
            val hint = when (answer.status) {
                401 -> " (проверьте GitHub-токен: нужны права repo и workflow)"
                403 -> " (токен без нужных прав или лимит API)"
                404 -> " (нет такого репозитория/файла — он приватный и токен без доступа?)"
                409 -> " (ветка изменилась — повторите, новые коммиты не тронуты)"
                422 -> " (файл уже существует или невалидные данные)"
                else -> ""
            }
            throw GitHubException("$what: HTTP ${answer.status}$hint: ${answer.text.take(300)}")
        }
        return answer.text
    }

    private fun get(path: String, what: String, raw: Boolean = false): String =
        ok(call("GET", path, raw = raw), what)

    private fun pretty(text: String): String {
        val t = try {
            if (text.startsWith("[")) JSONArray(text).toString(2) else JSONObject(text).toString(2)
        } catch (t: Throwable) {
            text
        }
        return t.take(MAX_OUTPUT) + if (t.length > MAX_OUTPUT) "\n… (обрезано)" else ""
    }

    private fun branchName(repo: String, requested: String?): String {
        if (!requested.isNullOrBlank()) return requested
        val repoJson = JSONObject(get("/repos/$repo", "repo info"))
        return repoJson.optString("default_branch", "main").ifEmpty { "main" }
    }

    // ---------------------------------------------------------------- ops

    fun me(): String {
        val o = JSONObject(get("/user", "user"))
        return "login=${o.optString("login")} name=${o.optString("name")}"
    }

    fun repoList(): String {
        val arr = JSONArray(get("/user/repos?sort=updated&per_page=30", "repo_list"))
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
        val o = JSONObject(ok(call("POST", "/user/repos", body.toString()), "repo_create"))
        return "создан ${o.optString("full_name")} → ${o.optString("html_url")} (default branch: ${o.optString("default_branch", "main")})"
    }

    /**
     * Commits files in ONE commit through the git data API: blobs → tree →
     * commit → ref update. The contents API would make a separate commit per
     * file (a workflow and its sources would land as five commits and could
     * trigger five runs), and it needs a GET per file to find the sha.
     */
    fun pushFiles(repo: String, files: List<Pair<String, String>>, message: String, branch: String?): String {
        if (files.isEmpty()) return "(нечего коммитить)"
        return commit(repo, files.map { Change(it.first, it.second) }, message, branch)
    }

    /** Deletes paths in one commit. */
    fun deleteFiles(repo: String, paths: List<String>, message: String, branch: String?): String {
        if (paths.isEmpty()) return "(нечего удалять)"
        return commit(repo, paths.map { Change(it, null, delete = true) }, message, branch)
    }

    private fun commit(repo: String, changes: List<Change>, message: String, branch: String?): String {
        val ref = branchName(repo, branch)
        return try {
            gitCommit(repo, changes, message, ref)
        } catch (e: GitHubException) {
            // The git data API can be unavailable for some tokens/scopes; the
            // contents API is the slower but wider path.
            if (changes.any { it.delete }) {
                changes.forEach { contentsDelete(repo, it.path, message) }
                return "удалено в $ref: ${changes.joinToString(", ") { it.path }}"
            }
            contentsCommit(repo, changes, message, ref)
        }
    }

    private fun gitCommit(repo: String, changes: List<Change>, message: String, branch: String): String {
        // One retry covers the case where someone pushed to the branch between
        // our read of the ref and our update of it.
        repeat(2) { attempt ->
            val head = headCommit(repo, branch)
            val baseTree = JSONObject(get("/repos/$repo/git/commits/$head", "git commit"))
                .optJSONObject("tree")?.optString("sha").orEmpty()
            val tree = JSONArray()
            for (c in changes) {
                val entry = JSONObject().put("path", c.path).put("mode", "100644").put("type", "blob")
                if (c.delete) {
                    entry.put("sha", JSONObject.NULL)
                } else {
                    val blob = JSONObject()
                        .put("content", Base64.getEncoder().encodeToString((c.content ?: "").toByteArray(Charsets.UTF_8)))
                        .put("encoding", "base64")
                    val sha = JSONObject(ok(call("POST", "/repos/$repo/git/blobs", blob.toString()), "git blob"))
                        .optString("sha")
                    entry.put("sha", sha)
                }
                tree.put(entry)
            }
            val treeSha = JSONObject(
                ok(
                    call(
                        "POST", "/repos/$repo/git/trees",
                        JSONObject().put("base_tree", baseTree).put("tree", tree).toString(),
                    ),
                    "git tree",
                ),
            ).optString("sha")
            val commitSha = JSONObject(
                ok(
                    call(
                        "POST", "/repos/$repo/git/commits",
                        JSONObject()
                            .put("message", message.ifBlank { "update" })
                            .put("tree", treeSha)
                            .put("parents", JSONArray().put(head))
                            .toString(),
                    ),
                    "git commit create",
                ),
            ).optString("sha")
            val refPath = "/repos/$repo/git/refs/heads/$branch"
            val update = call("PATCH", refPath, JSONObject().put("sha", commitSha).put("force", false).toString())
            if (update.status in 200..299) {
                return "коммит ${commitSha.take(7)} → $branch (${changes.size} " +
                    "${plural(changes.size, "файл", "файла", "файлов")}): " +
                    changes.joinToString(", ") { it.path }.take(600)
            }
            if (update.status == 422 && attempt == 0) return@repeat
            ok(update, "обновление ветки $branch")
        }
        throw GitHubException("ветка $branch всё время меняется — повторите push")
    }

    private fun headCommit(repo: String, branch: String): String =
        JSONObject(get("/repos/$repo/git/ref/heads/$branch", "ветка $branch"))
            .optJSONObject("object")?.optString("sha").orEmpty()
            .ifEmpty { throw GitHubException("ветка $branch не найдена") }

    /** Fallback path: one contents-API commit per file. */
    private fun contentsCommit(repo: String, changes: List<Change>, message: String, branch: String): String {
        val sb = StringBuilder()
        for (c in changes) {
            val existing = call("GET", "/repos/$repo/contents/${c.path}?ref=$branch")
            val sha = if (existing.status == 200) JSONObject(existing.text).optString("sha").takeIf { it.isNotEmpty() } else null
            val body = JSONObject()
                .put("message", message.ifBlank { "update" })
                .put("content", Base64.getEncoder().encodeToString((c.content ?: "").toByteArray(Charsets.UTF_8)))
                .put("branch", branch)
            if (sha != null) body.put("sha", sha)
            val answer = ok(call("PUT", "/repos/$repo/contents/${c.path}", body.toString()), "push ${c.path}")
            val commit = JSONObject(answer).optJSONObject("commit")?.optString("sha")?.take(7) ?: "?"
            sb.append("коммит $commit · ${c.path}\n")
        }
        return sb.toString().trim()
    }

    private fun contentsDelete(repo: String, path: String, message: String): String {
        val existing = call("GET", "/repos/$repo/contents/$path")
        if (existing.status == 404) return "нет такого файла: $path"
        val sha = JSONObject(existing.text).optString("sha")
        ok(
            call("DELETE", "/repos/$repo/contents/$path", JSONObject().put("message", message).put("sha", sha).toString()),
            "удаление $path",
        )
        return "удалён $path"
    }

    fun fileGet(repo: String, path: String, ref: String?): String {
        val q = if (ref.isNullOrBlank()) "" else "?ref=$ref"
        val t = get("/repos/$repo/contents/$path$q", "file_get", raw = true)
        return t.take(MAX_OUTPUT) + if (t.length > MAX_OUTPUT) "\n… (обрезано)" else ""
    }

    fun workflowCreate(repo: String, filename: String, yaml: String): String =
        pushFiles(repo, listOf(".github/workflows/$filename" to yaml), "workflow: $filename", null)

    /** Triggers workflow_dispatch. `workflow` is the file name, e.g. run.yml. */
    fun dispatch(repo: String, workflow: String, ref: String?): String {
        val branch = ref ?: branchName(repo, null)
        val body = JSONObject().put("ref", branch)
        ok(call("POST", "/repos/$repo/actions/workflows/$workflow/dispatches", body.toString()), "dispatch")
        return "workflow $workflow запущен (branch $branch). Дождаться результата: github run_wait {\"repo\":\"$repo\",\"timeout_sec\":600}"
    }

    fun runs(repo: String, limit: Int): String {
        val arr = JSONObject(get("/repos/$repo/actions/runs?per_page=${limit.coerceIn(1, 30)}", "runs"))
            .optJSONArray("workflow_runs") ?: JSONArray()
        val sb = StringBuilder()
        for (i in 0 until arr.length()) {
            sb.append(runLine(arr.getJSONObject(i))).append('\n')
        }
        return sb.toString().ifEmpty { "(запусков нет)" }
    }

    private fun runLine(r: JSONObject): String {
        val conclusion = r.optString("conclusion").takeIf { it.isNotEmpty() && it != "null" } ?: "…"
        return "run #${r.optLong("run_number")} id=${r.optLong("id")} ${r.optString("status")}/$conclusion " +
            "${r.optString("name")} (${r.optString("event")}, ${r.optString("created_at")})"
    }

    /**
     * Blocks until the run finishes — one tool call instead of a polling loop
     * that burns a round every few seconds. Reports the conclusion, the jobs
     * and, when something failed, the tail of the log, which is exactly what
     * "сборка упала, почему?" needs.
     */
    fun runWait(
        repo: String,
        runId: Long?,
        timeoutSec: Int,
        isCancelled: () -> Boolean = { false },
        logLines: Int = 40,
    ): String {
        val id = runId ?: latestRunId(repo)
        val budget = timeoutSec.coerceIn(15, MAX_WAIT_SEC) * 1000L
        val deadline = System.currentTimeMillis() + budget
        var run: JSONObject
        var waited = 0
        while (true) {
            run = JSONObject(get("/repos/$repo/actions/runs/$id", "запуск $id"))
            if (run.optString("status") == "completed") break
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) {
                return "запуск ${run.optLong("run_number")} (id=$id) всё ещё ${run.optString("status")} " +
                    "через ${timeoutSec}с — он продолжается на раннере; вызови github run_wait ещё раз " +
                    "или отмени его: github run_cancel {\"repo\":\"$repo\",\"run_id\":$id}"
            }
            if (isCancelled()) return "ожидание отменено пользователем (запуск $id продолжается на раннере)"
            val napMs = minOf(WAIT_POLL_MS, left)
            waited += napMs.toInt()
            Thread.sleep(napMs)
        }
        val sb = StringBuilder()
        val conclusion = run.optString("conclusion")
        val shown = run.optString("conclusion").takeIf { it.isNotEmpty() && it != "null" } ?: "…"
        sb.append("запуск #${run.optLong("run_number")} id=$id: ${run.optString("status")}/$shown «${run.optString("name")}»\n")
        sb.append("длительность: ${durationSec(run)}с, ожидание: ${waited / 1000}с\n")
        sb.append("ссылка: ${run.optString("html_url")}\n")
        jobsSummary(repo, id)?.let { sb.append(it) }
        if (!conclusion.equals("success", ignoreCase = true)) {
            sb.append("— логи (хвост) —\n")
            sb.append(logTail(repo, id, logLines))
        }
        return sb.toString()
    }

    private fun latestRunId(repo: String): Long {
        val arr = JSONObject(get("/repos/$repo/actions/runs?per_page=1", "runs"))
            .optJSONArray("workflow_runs") ?: JSONArray()
        if (arr.length() == 0) throw GitHubException("в $repo ещё не было запусков — сначала github dispatch")
        return arr.getJSONObject(0).optLong("id")
    }

    private fun durationSec(run: JSONObject): Long {
        val created = run.optString("created_at")
        val updated = run.optString("updated_at")
        return if (created.isEmpty() || updated.isEmpty()) 0 else seconds(created, updated)
    }

    /** ISO-8601 like 2026-09-27T10:00:00Z — parsed without java.time (API 26). */
    private fun seconds(from: String, to: String): Long = try {
        val format = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        format.timeZone = java.util.TimeZone.getTimeZone("UTC")
        format.parse(to).time / 1000 - format.parse(from).time / 1000
    } catch (t: Throwable) {
        0
    }

    private fun jobsSummary(repo: String, runId: Long): String? {
        val arr = JSONObject(get("/repos/$repo/actions/runs/$runId/jobs", "jobs"))
            .optJSONArray("jobs") ?: return null
        if (arr.length() == 0) return null
        val sb = StringBuilder("задачи:\n")
        for (i in 0 until arr.length()) {
            val job = arr.getJSONObject(i)
            val conclusion = job.optString("conclusion").takeIf { it.isNotEmpty() && it != "null" } ?: job.optString("status")
            sb.append("  ${job.optString("name")}: $conclusion")
            val steps = job.optJSONArray("steps")
            if (steps != null && !conclusion.equals("success", ignoreCase = true)) {
                val failed = mutableListOf<String>()
                for (s in 0 until steps.length()) {
                    val step = steps.getJSONObject(s)
                    val sc = step.optString("conclusion")
                    if (sc.isNotEmpty() && sc != "success" && sc != "skipped") failed += step.optString("name")
                }
                if (failed.isNotEmpty()) sb.append(" (упали шаги: ${failed.joinToString(", ")})")
            }
            sb.append('\n')
        }
        return sb.toString()
    }

    private fun logTail(repo: String, runId: Long, lines: Int): String = try {
        val text = stream("GET", "/repos/$repo/actions/runs/$runId/logs").use { ins -> zipToText(ins, MAX_ZIP_TEXT) }
        val all = text.lines().filter { it.isNotBlank() }
        if (all.size <= lines) all.joinToString("\n").ifEmpty { "(логи пусты)" }
        else all.takeLast(lines).joinToString("\n").let { "…\n$it" }
    } catch (t: Throwable) {
        "не удалось прочитать логи: ${t.message ?: t.javaClass.simpleName}"
    }

    fun runCancel(repo: String, runId: Long): String {
        ok(call("POST", "/repos/$repo/actions/runs/$runId/cancel", "{}"), "отмена запуска")
        return "запуск $runId отменён"
    }

    /** Run logs come as a zip of per-job text files. */
    fun logs(repo: String, runId: Long): String {
        stream("GET", "/repos/$repo/actions/runs/$runId/logs").use { ins ->
            return zipToText(ins, MAX_ZIP_TEXT).ifEmpty { "(логи пусты)" }
        }
    }

    fun artifacts(repo: String, runId: Long?): String {
        val q = if (runId != null) "?run_id=$runId" else ""
        val arr = JSONObject(get("/repos/$repo/actions/artifacts$q", "artifacts"))
            .optJSONArray("artifacts") ?: JSONArray()
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
        val answer = call(m, path, body?.takeIf { it.isNotBlank() })
        val text = ok(answer, "api $m $path")
        return if (text.isBlank()) "(пустой ответ ${answer.status})" else pretty(text)
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

    private fun plural(n: Int, one: String, few: String, many: String): String = when {
        n % 10 == 1 && n % 100 != 11 -> one
        n % 10 in 2..4 && n % 100 !in 12..14 -> few
        else -> many
    }
}
