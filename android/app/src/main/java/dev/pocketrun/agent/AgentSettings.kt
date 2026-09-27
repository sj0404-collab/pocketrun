package dev.pocketrun.agent

import android.content.Context

/**
 * Agent configuration, stored in SharedPreferences. Works with any
 * OpenAI-compatible `/chat/completions` endpoint: OpenAI, OpenRouter, Groq,
 * Together, a local llama.cpp/vLLM server on the Wi-Fi network, …
 */
object AgentSettings {

    const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
    const val DEFAULT_MODEL = "gpt-4o-mini"

    /** opencode Zen: the curated OpenAI-compatible gateway from opencode.ai/auth. */
    const val ZEN_BASE_URL = "https://opencode.ai/zen/v1"
    const val ZEN_AUTH_URL = "https://opencode.ai/auth"

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
    ) {
        /**
         * The API key is optional: local servers (llama.cpp, vLLM) need none,
         * and for Zen the server itself answers 403 without one — which the
         * agent surfaces with a hint. Only URL and model are required.
         */
        val isReady: Boolean get() = baseUrl.isNotBlank() && model.isNotBlank()
        val endpoint: String get() = baseUrl.trimEnd('/') + "/chat/completions"
        val isZen: Boolean get() = baseUrl.contains("opencode.ai/zen")
    }

    private const val PREFS = "agent"

    fun read(context: Context): Config {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Config(
            baseUrl = prefs.getString("baseUrl", DEFAULT_BASE_URL)!!.trim().trimEnd('/').ifBlank { DEFAULT_BASE_URL },
            apiKey = prefs.getString("apiKey", "")!!.trim(),
            model = prefs.getString("model", DEFAULT_MODEL)!!.trim().ifBlank { DEFAULT_MODEL },
        )
    }

    fun save(context: Context, config: Config) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString("baseUrl", config.baseUrl.trim().trimEnd('/'))
            .putString("apiKey", config.apiKey.trim())
            .putString("model", config.model.trim())
            .apply()
    }
}
