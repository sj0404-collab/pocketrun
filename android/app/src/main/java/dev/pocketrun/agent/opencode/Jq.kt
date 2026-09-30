package dev.pocketrun.agent.opencode

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * A small `jq` for the sandbox shell: enough to pull a value out of a JSON
 * document and shape the output, which is what an agent actually reaches for
 * when it has just written or fetched JSON.
 *
 * Supported: `.`, `.key`, `.key.key`, `."odd key"`, `.a[0]`, `.a[]`, `.[2:5]`,
 * `|`, `-r` (raw strings), `-c` (compact), and the outputs `keys`, `length`.
 *
 * Unsupported expressions are an error with a message, never a silent null: a
 * filter that quietly returned nothing is how an agent ends up believing an API
 * said something it did not.
 */
internal object Jq {

    fun run(argsIn: List<String>, dir: File, stdin: String, resolve: ShellText.Resolve): MiniShell.Result {
        var raw = false
        var compact = false
        val rest = mutableListOf<String>()
        for (a in argsIn) {
            when {
                a == "-r" || a == "--raw-output" -> raw = true
                a == "-c" || a == "--compact-output" -> compact = true
                a == "-e" -> Unit
                else -> rest += a
            }
        }
        val filter = rest.firstOrNull()
            ?: return MiniShell.Result(2, "jq: нужен фильтр, например jq -r '.name' data.json\n")
        val files = rest.drop(1)

        val text = readInput(files, dir, stdin, resolve)
            ?: return MiniShell.Result(2, "jq: ${files.firstOrNull() ?: "-"}: нет такого файла\n")

        val document = try {
            parseMulti(text)
        } catch (t: Throwable) {
            return MiniShell.Result(4, "jq: не разобрался JSON: ${t.message}\n")
        }

        val steps = parseFilter(filter)
            ?: return MiniShell.Result(3, "jq: неподдерживаемый фильтр «$filter»\n")

        var values = document
        for (step in steps) {
            values = when (step) {
                is Step.Pipe -> values
                is Step.Identity -> values
                is Step.Length -> values.map { v ->
                    when (v) {
                        is JSONArray -> JSONArray().put(v.length())
                        is JSONObject -> JSONArray().put(v.length())
                        is String -> JSONArray().put(v.length)
                        else -> JSONArray().put(0)
                    }
                }
                is Step.Keys -> values.map { v ->
                    val o = JSONObject()
                    when (v) {
                        is JSONObject -> for (k in v.keys()) o.put(k, true)
                        is JSONArray -> for (i in 0 until v.length()) o.put(v.get(i).toString(), true)
                        else -> Unit
                    }
                    o
                }
                is Step.Field -> values.map { readField(it, step.name) }
                is Step.Index -> values.map { readIndex(it, step.at) }
                is Step.Iterate -> values.flatMap { v ->
                    when (v) {
                        is JSONArray -> (0 until v.length()).map { v.get(it) }
                        is JSONObject -> v.keys().asSequence().map { v.opt(it) }.toList()
                        else -> emptyList()
                    }
                }
                is Step.Slice -> values.map { readSlice(it, step.from, step.to) }
            }.filterNotNull()
        }

        val out = StringBuilder()
        for (v in values) out.append(render(v, raw, compact)).append('\n')
        return MiniShell.Result(0, out.toString())
    }

    // ---------------------------------------------------------------- filter

    private sealed interface Step {
        data object Identity : Step
        data object Pipe : Step
        data object Iterate : Step
        data object Length : Step
        data object Keys : Step
        data class Field(val name: String) : Step
        data class Index(val at: String) : Step
        data class Slice(val from: Int?, val to: Int?) : Step
    }

    private fun parseFilter(filter: String): List<Step>? {
        val steps = mutableListOf<Step>()
        for (raw in filter.split('|')) {
            val part = raw.trim()
            when {
                part.isEmpty() -> steps += Step.Pipe
                part == "." -> steps += Step.Identity
                part == "length" -> steps += Step.Length
                part == "keys" -> steps += Step.Keys
                part == ".[]" -> steps += Step.Iterate
                part.startsWith(".") && part.endsWith("[]") && part.length > 3 -> {
                    steps += Step.Field(part.substring(1, part.length - 2))
                    steps += Step.Iterate
                }
                part.startsWith(".") -> {
                    val rest = part.substring(1)
                    var i = 0
                    while (i < rest.length) {
                        when (rest[i]) {
                            '"' -> {
                                val close = rest.indexOf('"', i + 1)
                                if (close < 0) return null
                                steps += Step.Field(rest.substring(i + 1, close))
                                i = close + 1
                            }
                            '[', '.' -> {}
                            else -> {
                                var j = i
                                while (j < rest.length && rest[j] != '.' && rest[j] != '[') j++
                                steps += Step.Field(rest.substring(i, j))
                                i = j
                            }
                        }
                        if (i < rest.length && rest[i] == '[') {
                            val close = rest.indexOf(']', i)
                            if (close < 0) return null
                            val inside = rest.substring(i + 1, close)
                            if (inside.contains(':')) {
                                val parts = inside.split(':')
                                steps += Step.Slice(
                                    parts.getOrNull(0)?.trim()?.toIntOrNull(),
                                    parts.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }?.toIntOrNull(),
                                )
                            } else {
                                steps += Step.Index(inside)
                            }
                            i = close + 1
                        }
                    }
                }
                else -> return null
            }
        }
        return steps.ifEmpty { null }
    }

    // ---------------------------------------------------------------- access

    private fun readField(value: Any?, name: String): Any? = when (value) {
        is JSONObject -> if (value.has(name)) value.get(name) else null
        is JSONArray -> {
            val at = name.toIntOrNull()
            if (at != null && at in 0 until value.length()) value.get(at) else null
        }
        is String -> null
        else -> null
    }

    private fun readIndex(value: Any?, at: String): Any? = when (value) {
        is JSONArray -> {
            if (at == "-1") {
                if (value.length() == 0) null else value.get(value.length() - 1)
            } else {
                at.toIntOrNull()?.let { if (it in 0 until value.length()) value.get(it) else null }
            }
        }
        is JSONObject -> if (value.has(at)) value.get(at) else null
        else -> null
    }

    private fun readSlice(value: Any?, from: Int?, to: Int?): Any? {
        if (value !is JSONArray) return null
        val start = (from ?: 0).coerceIn(0, value.length())
        val end = (to ?: value.length()).coerceIn(start, value.length())
        val out = JSONArray()
        for (i in start until end) out.put(value.get(i))
        return out
    }

    // ---------------------------------------------------------------- output

    private fun render(value: Any?, raw: Boolean, compact: Boolean): String = when {
        value == null || value === JSONObject.NULL -> "null"
        value is String && raw -> value
        value is String -> JSONObject.quote(value)
        compact -> value.toString()
        else -> value.toString()
    }

    private fun parseMulti(text: String): List<Any> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return listOf(JSONObject())
        // A stream of documents, as `jq` accepts: keep them all.
        if (trimmed.startsWith("{") && trimmed.endsWith("}") && countTopLevel(trimmed) > 1) {
            return splitTopLevel(trimmed).mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
        }
        return listOf(JSONObject(trimmed))
    }

    private fun countTopLevel(text: String): Int {
        var depth = 0
        var inString = false
        var escaped = false
        var count = 0
        var sawValue = false
        for (c in text) {
            if (inString) {
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == '"') inString = false
                continue
            }
            when (c) {
                '"' -> { inString = true; sawValue = true }
                '{', '[' -> depth++
                '}', ']' -> depth--
                '\n', ',', ' ' -> if (depth == 0) sawValue = false
            }
        }
        if (sawValue) count++
        return count
    }

    private fun splitTopLevel(text: String): List<String> {
        val parts = mutableListOf<String>()
        var depth = 0
        var inString = false
        var escaped = false
        val cur = StringBuilder()
        for (c in text) {
            if (inString) {
                cur.append(c)
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == '"') inString = false
                continue
            }
            when (c) {
                '"' -> { inString = true; cur.append(c) }
                '{', '[' -> { depth++; cur.append(c) }
                '}', ']' -> { depth--; cur.append(c) }
                '\n', ',' -> if (depth == 0) {
                    if (cur.isNotBlank()) parts += cur.toString().trim()
                    cur.clear()
                } else cur.append(c)
                else -> cur.append(c)
            }
        }
        if (cur.isNotBlank()) parts += cur.toString().trim()
        return parts
    }

    private fun readInput(files: List<String>, dir: File, stdin: String, resolve: ShellText.Resolve): String? {
        if (files.isEmpty()) return stdin.ifBlank { null }
        val out = StringBuilder()
        for (f in files) {
            val file = resolve.resolve(f, dir) ?: return null
            if (!file.isFile) return null
            if (file.length() > MiniShell.MAX_FILE) return null
            out.append(file.readText(Charsets.UTF_8))
        }
        return out.toString()
    }
}
