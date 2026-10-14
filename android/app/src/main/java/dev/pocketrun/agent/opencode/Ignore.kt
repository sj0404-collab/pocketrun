package dev.pocketrun.agent.opencode

import java.io.File

/**
 * `.gitignore` / `.ignore` support for the search tools. This is the practical
 * subset of gitignore syntax: comments and blanks, `!` negation, a trailing `/`
 * for directories, a leading `/` to anchor at the root, and `*` `?` `**`
 * wildcards. Rules are evaluated in order, the last match wins.
 */
class Ignore private constructor(private val rules: List<Rule>) {

    private data class Rule(
        val regex: Regex,
        val negated: Boolean,
        val dirOnly: Boolean,
        /** Patterns without a slash also match any path segment (gitignore rule). */
        val anySegment: Boolean,
    )

    /** True when [relativePath] (using `/`) is ignored. */
    fun isIgnored(relativePath: String, isDir: Boolean = false): Boolean {
        val path = relativePath.trimStart('/')
        var ignored = false
        for (rule in rules) {
            if (rule.dirOnly && !isDir && !hasParentMatch(rule, path)) continue
            if (rule.regex.matches(path)) ignored = !rule.negated
        }
        return ignored
    }

    /** A dir-only pattern also ignores everything under a matching directory. */
    private fun hasParentMatch(rule: Rule, path: String): Boolean {
        val segments = path.split('/')
        for (i in 1 until segments.size) {
            if (rule.regex.matches(segments.subList(0, i).joinToString("/"))) return true
        }
        return false
    }

    companion object {
        /** Reads `.gitignore` and `.ignore` from [dir]; [globalDir] adds global rules. */
        fun load(dir: File?, globalDir: File? = null): Ignore {
            val rules = mutableListOf<Rule>()
            globalDir?.let { g -> listOf(".ignore", ".gitignore").forEach { rules += parse(it, File(g, it)) } }
            dir?.let { d -> listOf(".gitignore", ".ignore").forEach { rules += parse(it, File(d, it)) } }
            return Ignore(rules)
        }

        private fun parse(name: String, file: File): List<Rule> {
            if (!file.isFile) return emptyList()
            return file.readLines(Charsets.UTF_8).mapNotNull { toRule(it) }
        }

        /** Builds rules from raw lines (used by tests and by [load]). */
        fun fromLines(vararg lines: String): Ignore = Ignore(lines.mapNotNull { toRule(it) })

        private fun toRule(rawLine: String): Rule? {
            val line = rawLine.trimEnd()
            if (line.isBlank() || line.startsWith("#")) return null
            var text = line
            val negated = text.startsWith("!")
            if (negated) text = text.substring(1)
            val dirOnly = text.endsWith("/")
            if (dirOnly) text = text.dropLast(1)
            val anchored = text.startsWith("/")
            if (anchored) text = text.substring(1)
            if (text.isBlank()) return null
            val anySegment = !anchored && !text.contains('/')
            return Rule(toRegex(text, anySegment), negated, dirOnly, anySegment)
        }

        private fun toRegex(pattern: String, anySegment: Boolean): Regex {
            val body = buildString {
                var i = 0
                while (i < pattern.length) {
                    when (val c = pattern[i]) {
                        '*' -> if (i + 1 < pattern.length && pattern[i + 1] == '*') {
                            append(".*")
                            i++
                            if (i + 1 < pattern.length && pattern[i + 1] == '/') i++
                        } else append("[^/]*")
                        '?' -> append("[^/]")
                        '/' -> append('/')
                        else -> if (c in "\\^$.|+()[]{}") { append('\\'); append(c) } else append(c)
                    }
                    i++
                }
            }
            val regex = if (anySegment) "(^|.*/)$body(/.*)?$" else "$body(/.*)?$"
            return Regex(regex)
        }
    }
}
