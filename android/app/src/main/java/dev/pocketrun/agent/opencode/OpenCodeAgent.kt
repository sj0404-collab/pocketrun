package dev.pocketrun.agent.opencode

import dev.pocketrun.agent.LlmClient
import dev.pocketrun.core.Workspace
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The opencode agent loop: user message in, tool calls out, tool results back
 * in, repeat until a plain-text answer (or the round budget). The system prompt
 * is assembled opencode-style: base agent prompt + AGENTS.md instructions +
 * current todos; skills are advertised through the `skill` tool description.
 *
 * Long turns are the normal case, not the exception, so the loop is built for
 * them: the answer is streamed (so the connection never goes idle and ⏹ works),
 * the history is trimmed before every request (so the prompt stays inside the
 * model's context), and a turn that runs out of rounds is saved instead of lost
 * — the user continues it with `/continue`.
 */
class OpenCodeAgent(
    private val workspace: Workspace,
    private val tools: OpenCodeTools,
    private val clientFactory: () -> LlmClient,
    private val projectDir: File?,
    private val maxSteps: Int = 12,
    private val askMode: Boolean = false,
    private val hasGitHub: Boolean = false,
    /** Polled before every request and between tool calls: this is what ⏹ does. */
    private val isCancelled: () -> Boolean = { false },
    private val maxHistoryChars: Int = HistoryTrimmer.DEFAULT_MAX_CHARS,
) {

    sealed class Event {
        /** A new model round has started (1-based, with the budget). */
        data class Round(val step: Int, val maxSteps: Int) : Event()
        /** The model is streaming an answer; [preview] is what it has written so far. */
        data class Thinking(val preview: String) : Event()
        data class AssistantText(val text: String) : Event()
        data class ToolStart(val name: String, val args: String) : Event()
        data class ToolDone(val name: String, val result: String) : Event()
        /** Something worth telling the user without failing the turn (a retry, a trim). */
        data class Note(val text: String) : Event()
        data class Failed(val message: String) : Event()
    }

    companion object {
        const val CANCELLED = "остановлено пользователем"

        /** Sent when the model answers with nothing at all. */
        const val CONTINUE_NUDGE =
            "Your previous answer was empty. Continue the task, then finish with a short summary of what you did."

        /** Sent by the `/continue` chat command. */
        const val CONTINUE_PROMPT = "Продолжай с того места, где остановился."
    }

    /**
     * Runs one user turn against [messages] (the session history WITHOUT the
     * system message) and returns the extended history.
     */
    fun run(userText: String, messages: JSONArray, onEvent: (Event) -> Unit): JSONArray {
        val full = JSONArray()
        full.put(systemMessage())
        for (i in 0 until messages.length()) full.put(messages.get(i))
        if (userText.isNotBlank()) {
            full.put(JSONObject().put("role", "user").put("content", userText))
        }

        var budget = maxSteps
        var trimmed = false
        try {
            while (budget > 0) {
                if (isCancelled()) {
                    onEvent(Event.Failed(CANCELLED))
                    break
                }
                budget--
                onEvent(Event.Round(maxSteps - budget, maxSteps))

                // Keep the prompt inside the model's context: old tool output is
                // excerpted and, if needed, old rounds are dropped.
                val request = HistoryTrimmer.trim(full, maxHistoryChars)
                if (request !== full && !trimmed) {
                    trimmed = true
                    onEvent(Event.Note("История хода сокращена, чтобы запрос уложился в контекст модели"))
                }

                val preview = StringBuilder()
                var lastEmit = 0L
                val response = try {
                    clientFactory().chat(request, tools.definitions(), isCancelled) { delta ->
                        preview.append(delta)
                        val now = System.currentTimeMillis()
                        if (now - lastEmit > 400) {
                            lastEmit = now
                            onEvent(Event.Thinking(preview.toString()))
                        }
                    }
                } finally {
                    onEvent(Event.Thinking(""))
                }

                if (response.toolCalls.isEmpty() && response.content.isNullOrBlank()) {
                    // Nothing usable came back. Nudge once instead of ending the
                    // turn in silence; the budget still counts this round.
                    full.put(JSONObject().put("role", "assistant").put("content", "(пустой ответ)"))
                    full.put(JSONObject().put("role", "user").put("content", CONTINUE_NUDGE))
                    continue
                }

                if (response.toolCalls.isEmpty()) {
                    val text = response.content.orEmpty()
                    full.put(JSONObject().put("role", "assistant").put("content", text))
                    if (text.isNotBlank()) onEvent(Event.AssistantText(text))
                    return finish(full)
                }

                val assistantMsg = JSONObject().put("role", "assistant")
                if (response.content != null) {
                    assistantMsg.put("content", response.content)
                    if (response.content.isNotBlank()) onEvent(Event.AssistantText(response.content))
                }
                val calls = JSONArray()
                response.toolCalls.forEach { call ->
                    calls.put(
                        JSONObject().apply {
                            put("id", call.id)
                            put("type", "function")
                            put("function", JSONObject().put("name", call.name).put("arguments", call.arguments))
                        },
                    )
                }
                assistantMsg.put("tool_calls", calls)
                full.put(assistantMsg)

                var interrupted = false
                for (call in response.toolCalls) {
                    if (isCancelled()) {
                        interrupted = true
                        break
                    }
                    onEvent(Event.ToolStart(call.name, prettyArgs(call.arguments)))
                    val result = tools.execute(call.name, call.arguments)
                    onEvent(Event.ToolDone(call.name, result))
                    full.put(
                        JSONObject().apply {
                            put("role", "tool")
                            put("tool_call_id", call.id)
                            put("content", result)
                        },
                    )
                }
                if (interrupted) {
                    // The calls that never ran are answered by finish(), which
                    // repairs the tail of the history before it is saved.
                    onEvent(Event.Failed(CANCELLED))
                    break
                }
            }
            if (!isCancelled() && budget <= 0) {
                onEvent(
                    Event.Failed(
                        "достигнут лимит раундов ($maxSteps) — файлы и история сохранены, " +
                            "напишите /continue, и агент продолжит с этого места",
                    ),
                )
            }
        } catch (t: Throwable) {
            onEvent(Event.Failed(t.message ?: t.javaClass.simpleName))
        }
        return finish(full)
    }

    /**
     * Ends the turn: answers any tool call that never got a result (a stopped
     * or failed round) and drops the system message. A history with an
     * unanswered tool call is rejected by strict providers, and this history is
     * what the next request and the next session are built from.
     */
    private fun finish(messages: JSONArray): JSONArray {
        val answered = mutableSetOf<String>()
        for (i in 0 until messages.length()) {
            val m = messages.optJSONObject(i) ?: continue
            if (m.optString("role") == "tool") answered += m.optString("tool_call_id")
        }
        for (i in messages.length() - 1 downTo 0) {
            val m = messages.optJSONObject(i) ?: continue
            if (m.optString("role") != "assistant") continue
            val calls = m.optJSONArray("tool_calls") ?: return stripSystem(messages)
            for (c in 0 until calls.length()) {
                val id = calls.getJSONObject(c).optString("id")
                if (id.isEmpty() || id in answered) continue
                messages.put(
                    JSONObject()
                        .put("role", "tool")
                        .put("tool_call_id", id)
                        .put("content", "(выполнение прервано — ход остановлен)"),
                )
            }
            break
        }
        return stripSystem(messages)
    }

    // ---------------------------------------------------------------- prompt

    private fun systemMessage(): JSONObject {
        // instructions (AGENTS.md etc.) for the current project
        val instr = Instructions(workspace).collect(projectDir)
        val todosBlock = if (tools.todos.isEmpty()) {
            "No todos yet."
        } else {
            tools.todos.joinToString("\n") { "  - [${it.status}] ${it.content}" }
        }

        val prompt = buildString {
            appendLine("You are opencode, an AI coding agent running inside the PocketRun app on Android. You help with code and files in the user's sandbox.")
            appendLine()
            appendLine("# Environment")
            appendLine("- Project directory: ${projectDir?.relativeToOrNull(workspace.root)?.path ?: "."} (relative paths in tools resolve there)")
            appendLine("- Sandbox root: the workspace; nothing outside it is accessible.")
            appendLine("- The `bash` tool is a restricted mini-shell: ${MiniShell.COMMANDS.joinToString(", ")}.")
            appendLine("  - `python` runs CPython 3.13 (standard library only, no pip);")
            appendLine("  - `node` runs a Node.js-compatible layer (require/fs/timers/Buffer/http work; no class/async-await/import syntax);")
            appendLine("  - `npx <pkg> [args]` installs and runs pure-JS npm packages (no native addons; prefer older releases of big packages, e.g. cowsay@1.4.0);")
            appendLine("  - `npm init -y`, `npm install <pkg>`, `npm run <script>`, `npm ls` manage a real node project: package.json is created in the project, installed packages are visible to require(), scripts run through the shell. Pure-JS packages only (no native addons).")
            appendLine("  - commands may run up to ${OpenCodeTools.BASH_TIMEOUT_MS / 60_000} minutes each; `sleep` up to ${MiniShell.MAX_SLEEP_SECONDS} seconds.")
            appendLine("- `webfetch` can read documentation pages.")
            appendLine()
            appendLine("# How to work")
            appendLine("- Explore first (list, read, glob, grep), then act (edit, write, apply_patch, bash), then verify by running code.")
            appendLine("- Prefer precise edits over rewriting whole files.")
            appendLine("- Use todowrite for multi-step tasks; keep the list current.")
            appendLine("- Ask the user with the question tool when requirements are ambiguous.")
            appendLine("- If a tool fails, read the error and adjust; do not repeat the same failing call more than twice.")
            appendLine("- Keep going until the task is actually done: you have up to $maxSteps model rounds for this request, and long builds and downloads are normal — plan for them instead of stopping early.")
            appendLine("- When you must wait for something (a CI run, a build), block on a single waiting call (github run_wait) instead of polling in a loop: polling wastes rounds.")
            appendLine("- For workflows the user repeats, suggest saving a skill: a folder .opencode/skills/<name>/SKILL.md with `name` and `description` in YAML frontmatter — you can create it with apply_patch yourself, then load it with the skill tool.")
            appendLine("- Answer in the user's language (usually Russian). Be concise; show what you did.")
            if (askMode) {
                appendLine()
                appendLine("# Clarification mode (user preference)")
                appendLine("Before any non-trivial task, ask the user 1-3 short clarifying questions with the question tool (each with concrete answer options). Only proceed automatically when the request is completely unambiguous or you already asked in this session.")
            }
            if (hasGitHub) {
                appendLine()
                appendLine("# GitHub and Actions runners")
                appendLine("The `github` tool talks to the GitHub REST API with the user's token. GitHub Actions runners are full Linux machines (ubuntu-latest): use them for anything the mobile sandbox cannot run — npm packages, opencode itself, compilers, heavy tests.")
                appendLine("Runner pattern:")
                appendLine("1. repo_create (private by default) → 2. push code + .github/workflows/run.yml (one commit, atomically) → 3. dispatch → 4. github run_wait {repo, timeout_sec} — blocks until the run finishes and returns the conclusion, the jobs and the log tail → 5. artifacts/artifact_download.")
                appendLine("Never poll `runs` in a loop: run_wait is one round instead of ten, and it works for anything that takes minutes.")
                appendLine("Example workflow the user can adapt:")
                appendLine("```yaml")
                appendLine("name: agent-runner")
                appendLine("on: workflow_dispatch")
                appendLine("jobs:")
                appendLine("  run:")
                appendLine("    runs-on: ubuntu-latest")
                appendLine("    steps:")
                appendLine("      - uses: actions/checkout@v4")
                appendLine("      - uses: actions/setup-node@v4")
                appendLine("        with: { node-version: 22 }")
                appendLine("      - run: npm install")
                appendLine("      # opencode inside the runner:")
                appendLine("      - run: npm i -g opencode-ai && opencode run --model opencode/claude-sonnet-4-5 \"<task>\"")
                appendLine("        env: { OPENCODE_API_KEY: '${'$'}{{ secrets.ZEN_KEY }}' }")
                appendLine("      - uses: actions/upload-artifact@v4")
                appendLine("        with: { name: out, path: out/ }")
                appendLine("```")
                appendLine("Never print or commit the user's tokens; reference secrets like \${{ secrets.NAME }} instead.")
            }
            appendLine()
            appendLine("# Current todos")
            appendLine(todosBlock)
            if (instr.text.isNotEmpty()) {
                appendLine()
                appendLine("# Instructions")
                appendLine(instr.text)
            }
        }
        return JSONObject().put("role", "system").put("content", prompt.trim())
    }

    private fun stripSystem(messages: JSONArray): JSONArray {
        val out = JSONArray()
        for (i in 0 until messages.length()) {
            val m = messages.get(i)
            if (m is JSONObject && m.optString("role") == "system") continue
            out.put(m)
        }
        return out
    }

    private fun prettyArgs(argsJson: String): String = try {
        val obj = JSONObject(argsJson)
        if (obj.length() == 0) "" else obj.toString().take(200)
    } catch (t: Throwable) {
        argsJson.take(200)
    }
}
