package dev.pocketrun.agent

import android.content.Context

/**
 * Agent configuration, stored in SharedPreferences. Works with any
 * OpenAI-compatible `/chat/completions` endpoint: OpenAI, OpenRouter, Groq,
 * Together, a local llama.cpp/vLLM server on the Wi-Fi network, …
 *
 * Also carries the agent-behaviour knobs (permission mode, max rounds) and
 * the optional GitHub token used by the `github` tool.
 */
object AgentSettings {

    const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
    const val DEFAULT_MODEL = "gpt-4o-mini"

    /** opencode Zen: the curated OpenAI-compatible gateway from opencode.ai/auth. */
    const val ZEN_BASE_URL = "https://opencode.ai/zen/v1"
    const val ZEN_AUTH_URL = "https://opencode.ai/auth"

    /** Where the user creates a GitHub PAT (scopes: repo, workflow). */
    const val GITHUB_TOKEN_URL = "https://github.com/settings/tokens/new?scopes=repo,workflow&description=PocketRun"
    const val GITHUB_API = "https://api.github.com"

    /** Permission modes for tool execution. */
    const val MODE_AUTO = "auto"     // everything runs without asking
    const val MODE_MANUAL = "manual" // mutations need a tap; reads run freely
    const val MODE_ASK = "ask"       // the agent asks clarifying questions first
    val MODES = listOf(MODE_AUTO, MODE_MANUAL, MODE_ASK)
    val MODE_LABELS = mapOf(MODE_AUTO to "Авто", MODE_MANUAL to "Вручную", MODE_ASK to "Вопросы")

    const val DEFAULT_MAX_STEPS = 12
    const val MAX_STEPS_LIMIT = 60

    /** Ready-made presets shown in the model settings dialog. */
    data class Preset(val label: String, val baseUrl: String, val model: String)

    val PRESETS = listOf(
        Preset("OpenCode Zen", ZEN_BASE_URL, "claude-sonnet-4-5"),
        Preset("OpenAI", "https://api.openai.com/v1", "gpt-4o-mini"),
        Preset("OpenRouter", "https://openrouter.ai/api/v1", "anthropic/claude-sonnet-4.5"),
        Preset("Groq", "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile"),
    )

    data class Config(
        val baseUrl: String = DEFAULT_BASE_URL,
        val apiKey: String = "",
        val model: String = DEFAULT_MODEL,
        val githubToken: String = "",
        val confirmMode: String = MODE_AUTO,
        val maxSteps: Int = DEFAULT_MAX_STEPS,
    ) {
        /**
         * The API key is optional: local servers (llama.cpp, vLLM) need none,
         * and for Zen the server itself answers 403 without one — which the
         * agent surfaces with a hint. Only URL and model are required.
         */
        val isReady: Boolean get() = baseUrl.isNotBlank() && model.isNotBlank()
        val endpoint: String get() = baseUrl.trimEnd('/') + "/chat/completions"
        val isZen: Boolean get() = baseUrl.contains("opencode.ai/zen")
        val hasGitHub: Boolean get() = githubToken.isNotBlank()
        val safeMaxSteps: Int get() = maxSteps.coerceIn(1, MAX_STEPS_LIMIT)
    }

    private const val PREFS = "agent"

    fun read(context: Context): Config {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Config(
            baseUrl = prefs.getString("baseUrl", DEFAULT_BASE_URL)!!.trim().trimEnd('/').ifBlank { DEFAULT_BASE_URL },
            apiKey = prefs.getString("apiKey", "")!!.trim(),
            model = prefs.getString("model", DEFAULT_MODEL)!!.trim().ifBlank { DEFAULT_MODEL },
            githubToken = prefs.getString("githubToken", "")!!.trim(),
            confirmMode = prefs.getString("confirmMode", MODE_AUTO)!!.trim().let { if (it in MODES) it else MODE_AUTO },
            maxSteps = prefs.getString("maxSteps", DEFAULT_MAX_STEPS.toString())!!.trim().toIntOrNull() ?: DEFAULT_MAX_STEPS,
        )
    }

    fun save(context: Context, config: Config) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString("baseUrl", config.baseUrl.trim().trimEnd('/'))
            .putString("apiKey", config.apiKey.trim())
            .putString("model", config.model.trim())
            .putString("githubToken", config.githubToken.trim())
            .putString("confirmMode", config.confirmMode)
            .putString("maxSteps", config.safeMaxSteps.toString())
            .apply()
    }

    // ---------------------------------------------------------------- ui state

    /**
     * Which session tab was active and which tabs were open, so the agent
     * chat comes back exactly as it was after the app is closed. Returns null
     * when nothing was saved yet (first launch → continue the latest session).
     */
    data class UiState(val lastSessionId: String?, val openTabs: List<String>)

    fun readUiState(context: Context): UiState? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains("uiOpenTabs")) return null
        val last = prefs.getString("uiLastSessionId", "")!!.takeIf { it.isNotBlank() }
        val tabs = prefs.getString("uiOpenTabs", "")!!.split(',').filter { it.isNotBlank() }
        return UiState(last, tabs)
    }

    fun saveUiState(context: Context, lastSessionId: String?, openTabs: List<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString("uiLastSessionId", lastSessionId ?: "")
            .putString("uiOpenTabs", openTabs.joinToString(","))
            .apply()
    }
}
