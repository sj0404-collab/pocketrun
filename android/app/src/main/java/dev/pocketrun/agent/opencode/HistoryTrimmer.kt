package dev.pocketrun.agent.opencode

import org.json.JSONArray
import org.json.JSONObject

/**
 * Keeps the request small enough for the model to answer.
 *
 * A long turn produces a huge history: every tool result is up to 16 KB, and a
 * few dozen rounds later the prompt is bigger than the model's context — the
 * gateway then either rejects the request (400/413) or takes minutes to answer
 * and the turn dies on a timeout. This trims the history in two steps:
 *
 * 1. tool results of older rounds shrink to a head+tail excerpt;
 * 2. if that is not enough, whole old rounds are dropped.
 *
 * Both steps keep the OpenAI invariant that every `tool` message belongs to the
 * `tool_calls` of the assistant message right before it — rounds are dropped
 * whole, never in half. The opening user message (the original request) is
 * never dropped, and nothing is modified in place: the session history stays
 * exactly as it was.
 */
object HistoryTrimmer {

    /** Roughly 30k tokens of mixed Russian/English text — comfortable for the small free models too. */
    const val DEFAULT_MAX_CHARS = 96_000

    /** How much of a single tool result survives the first pass. */
    const val TOOL_KEEP = 3_000

    private const val MARK = "\n… [сокращено: %d из %d символов]\n"

    /**
     * Returns a request-sized copy of [messages] (index 0 is the system
     * message) with the oldest, bulkiest parts removed. Returns the very same
     * array when nothing needs trimming.
     */
    fun trim(messages: JSONArray, maxChars: Int = DEFAULT_MAX_CHARS, toolKeep: Int = TOOL_KEEP): JSONArray {
        if (messages.length() <= 2) return messages
        val blocks = blocks(messages)
        // A history with an orphan `tool` message has to be rebuilt even when it
        // is small: the API rejects it whole, and the next round is the answer.
        val repaired = blocks.sumOf { it.messages.size } < messages.length() - 1
        if (!repaired && sizeOf(messages) <= maxChars) return messages
        // The system message rides along in every request, so it is part of the
        // budget even though it is never a candidate for dropping.
        val budget = (maxChars - (messages.opt(0)?.toString()?.length ?: 0)).coerceAtLeast(toolKeep)

        // Step 1: shrink the tool outputs, oldest round first.
        val shrunk = blocks.map { Block(it.messages.map { m -> shrinkTool(m, toolKeep) }, it.droppable) }
        if (sizeOf(shrunk) <= budget) return of(messages, shrunk)

        // Step 2: drop whole rounds from the oldest end, keeping the opening
        // request that carries the original task.
        val kept = ArrayList(shrunk)
        while (kept.size > 1 && sizeOf(kept) > budget) {
            val victim = kept.indexOfFirst { it.droppable }
            if (victim < 0) break
            kept.removeAt(victim)
        }
        return of(messages, kept)
    }

    // ---------------------------------------------------------------- blocks

    /** One round: a user/assistant message plus the tool results that answer it. */
    private class Block(
        val messages: List<JSONObject>,
        /** False for the opening request, which is never dropped. */
        val droppable: Boolean,
    )

    /** Groups messages into rounds; a `tool` message always joins its call. */
    private fun blocks(messages: JSONArray): List<Block> {
        val out = mutableListOf<Block>()
        val pending = LinkedHashSet<String>()
        var current = mutableListOf<JSONObject>()
        for (i in 1 until messages.length()) {
            val m = messages.optJSONObject(i) ?: continue
            when (m.optString("role")) {
                "tool" -> {
                    // Answerable only by the call that is still waiting for it.
                    // Anything else is an orphan and would be rejected by the API.
                    if (!pending.remove(m.optString("tool_call_id"))) continue
                }
                else -> {
                    m.optJSONArray("tool_calls")?.let { calls ->
                        for (c in 0 until calls.length()) {
                            calls.optJSONObject(c)?.optString("id")?.let { pending += it }
                        }
                    }
                    if (current.isNotEmpty()) {
                        out += Block(current, droppable = out.isNotEmpty())
                        current = mutableListOf()
                    }
                }
            }
            current.add(m)
        }
        if (current.isNotEmpty()) out += Block(current, droppable = out.isNotEmpty())
        return out
    }

    /** Rebuilds the request array: the system message plus the kept rounds. */
    private fun of(messages: JSONArray, blocks: List<Block>): JSONArray {
        val out = JSONArray()
        messages.opt(0)?.let { out.put(it) }
        blocks.forEach { block -> block.messages.forEach { out.put(it) } }
        return out
    }

    private fun shrinkTool(message: JSONObject, keep: Int): JSONObject {
        if (message.optString("role") != "tool") return message
        val content = message.optString("content")
        if (content.length <= keep * 2) return message
        val head = content.take(keep)
        val tail = content.takeLast(keep)
        val copy = JSONObject(message.toString())
        copy.put("content", head + String.format(MARK, head.length + tail.length, content.length) + tail)
        return copy
    }

    private fun sizeOf(messages: JSONArray): Int {
        var total = 0
        for (i in 0 until messages.length()) total += messages.opt(i)?.toString()?.length ?: 0
        return total
    }

    private fun sizeOf(blocks: List<Block>): Int =
        blocks.sumOf { block -> block.messages.sumOf { it.toString().length } }
}
