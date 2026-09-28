package dev.pocketrun.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.json.JSONObject
import org.junit.Test

/**
 * Agent config semantics: the API key is optional (local servers need none;
 * Zen's 403 is surfaced by the agent itself), presets point at real endpoints.
 */
class AgentSettingsTest {

    @Test
    fun keyIsOptionalForReadiness() {
        // local llama.cpp: no key at all
        val local = AgentSettings.Config("http://192.168.1.5:8080/v1", "", "qwen2.5-coder")
        assertTrue(local.isReady)
        assertEquals("http://192.168.1.5:8080/v1/chat/completions", local.endpoint)

        // Zen without a key is "ready" — the server's 403 is the real answer
        val zenKeyless = AgentSettings.Config(AgentSettings.ZEN_BASE_URL, "", "big-pickle")
        assertTrue(zenKeyless.isReady)
        assertTrue(zenKeyless.isZen)

        // nothing configured: not ready
        assertFalse(AgentSettings.Config("", "", "").isReady)
        assertFalse(AgentSettings.Config("https://api.openai.com/v1", "sk-x", "").isReady)
    }

    @Test
    fun zenPresetPointsAtTheRealGateway() {
        val zen = AgentSettings.PRESETS.first { it.label == "OpenCode Zen" }
        assertEquals("https://opencode.ai/zen/v1", zen.baseUrl)
    }

    @Test
    fun behaviourKnobsRoundTrip() {
        // confirm modes and round limits with sane defaults and clamping
        val auto = AgentSettings.Config()
        assertEquals(AgentSettings.MODE_AUTO, auto.confirmMode)
        assertEquals(AgentSettings.DEFAULT_MAX_STEPS, auto.safeMaxSteps)
        assertFalse(auto.hasGitHub)

        val tuned = AgentSettings.Config(
            baseUrl = AgentSettings.ZEN_BASE_URL,
            model = "big-pickle",
            githubToken = "ghp_test",
            confirmMode = AgentSettings.MODE_MANUAL,
            maxSteps = 999,
        )
        assertTrue(tuned.hasGitHub)
        assertEquals(AgentSettings.MODE_MANUAL, tuned.confirmMode)
        assertEquals(AgentSettings.MAX_STEPS_LIMIT, tuned.safeMaxSteps)

        val asker = tuned.copy(confirmMode = AgentSettings.MODE_ASK, maxSteps = 0)
        assertEquals(AgentSettings.MODE_ASK, asker.confirmMode)
        assertEquals(1, asker.safeMaxSteps)
    }

    @Test
    fun roundBudgetIsBigEnoughForRealWork() {
        // A task that edits files, pushes them and waits for a CI run needs
        // dozens of rounds; 12 stopped the agent halfway every time.
        assertTrue(AgentSettings.DEFAULT_MAX_STEPS >= 20)
        assertTrue(AgentSettings.MAX_STEPS_LIMIT >= 100)
        assertTrue(AgentSettings.MAX_STEPS_LIMIT >= AgentSettings.DEFAULT_MAX_STEPS)
    }

    @Test
    fun modelsListParsingShape() {
        // GET /models of Zen returns OpenAI-style {"data":[{"id":...}]}
        val payload = JSONObject().put(
            "data",
            org.json.JSONArray()
                .put(JSONObject().put("id", "big-pickle"))
                .put(JSONObject().put("id", "claude-sonnet-4-5")),
        )
        val ids = (0 until payload.getJSONArray("data").length()).map {
            payload.getJSONArray("data").getJSONObject(it).getString("id")
        }
        assertEquals(listOf("big-pickle", "claude-sonnet-4-5"), ids)
    }
}
