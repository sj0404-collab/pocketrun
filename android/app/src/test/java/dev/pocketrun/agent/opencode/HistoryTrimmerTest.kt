package dev.pocketrun.agent.opencode

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * History trimming: the prompt has to shrink with the size of the task, but
 * never below what makes the next request valid — a `tool` message must always
 * follow the assistant message whose `tool_calls` it answers, and the opening
 * request must survive.
 */
class HistoryTrimmerTest {

    private fun system() = JSONObject().put("role", "system").put("content", "system prompt")

    private fun user(text: String) = JSONObject().put("role", "user").put("content", text)

    private fun round(i: Int, outputChars: Int): List<JSONObject> = listOf(
        JSONObject()
            .put("role", "assistant")
            .put(
                "tool_calls",
                JSONArray().put(
                    JSONObject().put("id", "id$i").put("type", "function")
                        .put("function", JSONObject().put("name", "read").put("arguments", """{"filePath":"f$i"}""")),
                ),
            ),
        JSONObject().put("role", "tool").put("tool_call_id", "id$i").put("content", "x".repeat(outputChars)),
    )

    private fun history(rounds: Int, outputChars: Int): JSONArray {
        val arr = JSONArray().put(system()).put(user("исходная задача"))
        for (i in 0 until rounds) round(i, outputChars).forEach { arr.put(it) }
        return arr
    }

    /** A trimmed history must still be a legal request. */
    private fun assertValid(messages: JSONArray) {
        val pending = LinkedHashSet<String>()
        for (i in 0 until messages.length()) {
            val m = messages.getJSONObject(i)
            when (m.optString("role")) {
                "system" -> assertEquals("системное сообщение должно быть первым", 0, i)
                "assistant" -> m.optJSONArray("tool_calls")?.let { calls ->
                    for (c in 0 until calls.length()) pending += calls.getJSONObject(c).optString("id")
                }
                "tool" -> assertTrue(
                    "tool ${m.optString("tool_call_id")} без вызова",
                    pending.remove(m.optString("tool_call_id")),
                )
            }
        }
    }

    private fun size(messages: JSONArray): Int = (0 until messages.length()).sumOf { messages.getJSONObject(it).toString().length }

    @Test
    fun smallHistoryIsUntouched() {
        val h = history(rounds = 3, outputChars = 100)
        assertTrue(HistoryTrimmer.trim(h) === h)
    }

    @Test
    fun fatToolOutputIsExcerptedAndTheRequestStaysValid() {
        val h = history(rounds = 8, outputChars = 20_000)
        val trimmed = HistoryTrimmer.trim(h, maxChars = 60_000, toolKeep = 500)
        assertTrue(size(trimmed) < size(h))
        assertTrue(trimmed.toString().contains("сокращено"))
        assertValid(trimmed)
        // The opening request is the one thing that must always be there.
        assertEquals("исходная задача", trimmed.getJSONObject(1).optString("content"))
    }

    @Test
    fun oldRoundsAreDroppedWholeWhenExcerptsAreNotEnough() {
        val h = history(rounds = 20, outputChars = 8_000)
        val trimmed = HistoryTrimmer.trim(h, maxChars = 30_000, toolKeep = 2_000)
        assertTrue(size(trimmed) <= 30_000)
        assertValid(trimmed)
        // The newest rounds are the ones the model needs; the oldest go first.
        // Compared as ids, not as substrings: "id1" is a prefix of "id10".
        val kept = (0 until trimmed.length())
            .mapNotNull { trimmed.optJSONObject(it)?.optString("tool_call_id") }
            .filter { it.isNotEmpty() }
            .toSet()
        assertTrue(kept.contains("id19"))
        assertTrue(!kept.contains("id0"))
        assertTrue(!kept.contains("id1"))
    }

    @Test
    fun theSourceHistoryIsNeverModifiedInPlace() {
        val h = history(rounds = 8, outputChars = 20_000)
        val before = h.toString()
        HistoryTrimmer.trim(h, maxChars = 10_000, toolKeep = 500)
        assertEquals(before, h.toString())
    }

    @Test
    fun aSingleRoundIsExcerptedRatherThanDropped() {
        val h = history(rounds = 1, outputChars = 50_000)
        val trimmed = HistoryTrimmer.trim(h, maxChars = 4_000, toolKeep = 500)
        assertValid(trimmed)
        assertEquals(4, trimmed.length())
        assertTrue(trimmed.toString().contains("сокращено"))
    }

    @Test
    fun orphanToolMessagesAreDropped() {
        val h = JSONArray()
            .put(system())
            .put(user("задача"))
            .put(JSONObject().put("role", "tool").put("tool_call_id", "ghost").put("content", "осиротевший"))
        val trimmed = HistoryTrimmer.trim(h, maxChars = 1, toolKeep = 10)
        assertValid(trimmed)
        assertTrue(!trimmed.toString().contains("ghost"))
    }
}
