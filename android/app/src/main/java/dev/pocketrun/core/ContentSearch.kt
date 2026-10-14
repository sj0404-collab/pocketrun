package dev.pocketrun.core

import java.io.File
import java.util.Locale

/** One line that matched, with enough context to jump straight to it. */
data class SearchHit(val file: File, val line: Int, val text: String) {
    val path: String get() = file.path
}

/**
 * grep over a project. The agent edits files behind the app's back all the time,
 * so "where did that change land?" needs an answer that does not require the
 * user to open every folder by hand.
 *
 * Deliberately bounded: binaries are skipped, huge files are skipped, and the
 * number of hits is capped. A search on a phone has to end, and saying "first
 * 500 matches" is better than hanging.
 */
object ContentSearch {
    const val MAX_HITS = 500
    const val MAX_FILES = 3_000
    const val MAX_FILE_BYTES = 1_000_000L

    fun search(
        root: File,
        query: String,
        showHidden: Boolean = false,
        regex: Boolean = false,
        caseSensitive: Boolean = false,
    ): List<SearchHit> {
        val needle = query.trim()
        if (needle.isEmpty() || !root.isDirectory) return emptyList()
        val pattern = if (regex) {
            runCatching {
                Regex(
                    needle,
                    if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE),
                )
            }.getOrNull() ?: return emptyList()
        } else {
            null
        }
        val hits = ArrayList<SearchHit>()
        var seen = 0
        val stack = ArrayDeque<File>()
        stack.addLast(root)
        while (stack.isNotEmpty() && seen < MAX_FILES && hits.size < MAX_HITS) {
            val dir = stack.removeLast()
            for (child in dir.listFiles().orEmpty()) {
                if (seen >= MAX_FILES || hits.size >= MAX_HITS) break
                if (child.isDirectory) {
                    val skip = child.name in FileTree.SKIP_DIRS
                    val hidden = FileTree.isHidden(child)
                    if (showHidden || !skip && !hidden) stack.addLast(child)
                    continue
                }
                if (!showHidden && FileTree.isHidden(child)) continue
                seen++
                if (child.length() > MAX_FILE_BYTES) continue
                val text = FileText.read(child, MAX_FILE_BYTES) ?: continue
                addHits(child, text, needle, pattern, caseSensitive, hits)
            }
        }
        return hits
    }

    private fun addHits(
        file: File,
        text: String,
        needle: String,
        pattern: Regex?,
        caseSensitive: Boolean,
        out: MutableList<SearchHit>,
    ) {
        val haystack = if (caseSensitive) needle else needle.lowercase(Locale.ROOT)
        var lineNo = 0
        for (line in text.lineSequence()) {
            lineNo++
            val found = pattern?.containsMatchIn(line)
                ?: if (caseSensitive) line.contains(needle) else line.lowercase(Locale.ROOT).contains(haystack)
            if (!found) continue
            out += SearchHit(file, lineNo, line.trim().take(240))
            if (out.size >= MAX_HITS) return
        }
    }
}
