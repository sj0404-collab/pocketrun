package dev.pocketrun.agent.opencode

import dev.pocketrun.agent.LlmClient
import dev.pocketrun.core.Workspace
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The opencode agent loop: user message in, tool calls out, tool results back
 * in, repeat until a plain-text answer (or the step budget). The system prompt
 * is assembled opencode-style: base agent prompt + AGENTS.md instructions +
 * current todos; skills are advertised through the `skill` tool description.
 */
class OpenCodeAgent(
    private val workspace: Workspace,
    private val tools: OpenCodeTools,
    private val clientFactory: () -> LlmClient,
    private val projectDir: File?,
    private val maxSteps: Int = 12,
    private val askMode: Boolean = false,
    private val hasGitHub: Boolean = false,
) {

    sealed class Event {
        data class AssistantText(val text: String) : Event()
        data class ToolStart(val name: String, val args: String) : Event()
        data class ToolDone(val name: String, val result: String) : Event()
        data class Question(val questions: List<OpenCodeTools.Question>) : Event()
        data class Todos(val todos: List<Sessions.Todo>) : Event()
        data class Failed(val message: String) : Event()
    }

    @Volatile
    var cancelled: Boolean = false

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

        var newMessages = JSONArray()
        try {
            for (step in 0 until maxSteps) {
                if (cancelled) {
                    onEvent(Event.Failed("отменено пользователем"))
                    break
                }
                val response = clientFactory().chat(full, tools.definitions())

                if (response.toolCalls.isEmpty()) {
                    val text = response.content.orEmpty()
                    full.put(JSONObject().put("role", "assistant").put("content", text))
                    if (text.isNotBlank()) onEvent(Event.AssistantText(text))
                    newMessages = stripSystem(full)
                    return newMessages
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

                for (call in response.toolCalls) {
                    if (cancelled) break
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
            }
            if (!cancelled) {
                onEvent(Event.Failed("достигнут лимит шагов ($maxSteps) без финального ответа — уточните запрос"))
            }
        } catch (t: Throwable) {
            onEvent(Event.Failed(t.message ?: t.javaClass.simpleName))
        }
        return stripSystem(full)
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
            appendLine("  - `node` runs a Node.js-compatible subset (require/fs/timers/Buffer/http work; no class/async-await/import syntax);")
            appendLine("  - `npx <pkg> [args]` installs and runs pure-JS npm packages (no native addons; prefer older releases of big packages, e.g. cowsay@1.4.0);")
            appendLine("  - `npm init -y`, `npm install <pkg>`, `npm run <script>`, `npm ls` manage a real node project: package.json is created in the project, installed packages are visible to require(), scripts run through the shell. Pure-JS packages only (no native addons).")
            appendLine("- `webfetch` can read documentation pages.")
            appendLine()
            appendLine("# How to work")
            appendLine("- Explore first (list, read, glob, grep), then act (edit, write, apply_patch, bash), then verify by running code.")
            appendLine("- Prefer precise edits over rewriting whole files.")
            appendLine("- Use todowrite for multi-step tasks; keep the list current.")
            appendLine("- Ask the user with the question tool when requirements are ambiguous.")
            appendLine("- If a tool fails, read the error and adjust; do not repeat the same failing call more than twice.")
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
                appendLine("1. repo_create (private by default) → 2. push code + .github/workflows/run.yml → 3. dispatch → 4. poll runs (every call takes a few seconds; ask the user to wait or poll a few times) → 5. logs → 6. artifacts/artifact_download.")
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
