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

    data class Config(
        val baseUrl: String = DEFAULT_BASE_URL,
        val apiKey: String = "",
        val model: String = DEFAULT_MODEL,
    ) {
        val isReady: Boolean get() = apiKey.isNotBlank() && baseUrl.isNotBlank() && model.isNotBlank()
        val endpoint: String get() = baseUrl.trimEnd('/') + "/chat/completions"
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
