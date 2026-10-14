package dev.pocketrun.agent.opencode

import java.io.File

/**
 * Custom slash commands from `opencode.json` (`command` block) and
 * `.opencode/command/<name>.md`. Expanding a template applies the same substitutions
 * opencode does: `$ARGUMENTS`, positional `$1`…`$9`, and `@path` file includes.
 */
object Commands {

    /** Splits "/name the rest" into (name, args). Null when not a slash line. */
    fun parse(input: String): Pair<String, String>? {
        val text = input.trim()
        if (!text.startsWith("/")) return null
        val body = text.substring(1)
        val name = body.substringBefore(' ').trim()
        if (name.isEmpty()) return null
        val args = body.substringAfter(' ', "").trim()
        return name to args
    }

    /**
     * The prompt text a command expands to. [baseDir] anchors `@file` lookups;
     * a missing file stays as a short note instead of failing the whole call.
     */
    fun expand(command: OpenCodeConfig.CommandDef, args: String, baseDir: File?): String {
        val positional = if (args.isBlank()) emptyList() else args.split(Regex("\\s+"))
        var text = command.template
        for (i in 9 downTo 1) {
            text = text.replace("\$$i", positional.getOrElse(i - 1) { "" })
        }
        text = text.replace("\$ARGUMENTS", args)
        text = expandFileMentions(text, baseDir)
        return text.trim()
    }

    private val mention = Regex("@([\\w./\\-]+)")

    private fun expandFileMentions(text: String, baseDir: File?): String {
        if (baseDir == null) return text
        return mention.replace(text) { m ->
            val path = m.groupValues[1]
            val file = File(baseDir, path)
            if (file.isFile && file.canonicalPath.startsWith(baseDir.canonicalPath)) {
                val body = runCatching { file.readText(Charsets.UTF_8) }.getOrDefault("")
                "```$path\n${body.take(16 * 1024)}\n```"
            } else {
                m.value
            }
        }
    }
}
