package dev.pocketrun.agent.opencode

import java.io.File

/**
 * The `apply_patch` tool format used by opencode (inherited from Codex):
 *
 * ```
 * *** Begin Patch
 * *** Add File: path/to/new.js
 * +line
 * +line
 * *** Update File: path/to/existing.js
 * context line (optional, for readability)
 * -old line
 * +new line
 * *** Move to: path/to/renamed.js
 * *** Delete File: path/to/old.js
 * *** End Patch
 * ```
 *
 * For updates, consecutive `-`/`+` blocks are replaced as a unit; the removed
 * lines must appear in the file (first match wins, error when absent).
 */
object ApplyPatch {

    class PatchException(message: String) : Exception(message)

    data class PatchResult(val applied: List<String>, val skipped: List<String>)

    fun apply(patchText: String, resolve: (String) -> File?): PatchResult {
        val rawLines = patchText.lines()
        if (rawLines.firstOrNull()?.trim() != "*** Begin Patch") {
            throw PatchException("патч должен начинаться с '*** Begin Patch'")
        }
        // The whole patch may be indented (e.g. inside a markdown block):
        // detect the first marker's indent and strip it from every line.
        val first = rawLines.first()
        val indent = first.takeWhile { it == ' ' || it == '\t' }
        val lines = rawLines.map { l -> if (indent.isNotEmpty() && l.startsWith(indent)) l.substring(indent.length) else l }

        val applied = mutableListOf<String>()
        val skipped = mutableListOf<String>()

        fun isSectionMarker(l: String): Boolean =
            l.startsWith("*** ") && !l.startsWith("*** Move to:")

        var i = 1
        while (i < lines.size) {
            val line = lines[i]
            when {
                line.trim() == "*** End Patch" -> return PatchResult(applied, skipped)
                line.trimStart().startsWith("*** Add File:") -> {
                    val path = line.trimStart().removePrefix("*** Add File:").trim()
                    val content = StringBuilder()
                    i++
                    while (i < lines.size && !isSectionMarker(lines[i])) {
                        val l = lines[i]
                        when {
                            l.startsWith("+") -> content.append(l.substring(1)).append('\n')
                            l.isEmpty() -> content.append('\n')
                            else -> throw PatchException("Add File: строка вне '+': '$l'")
                        }
                        i++
                    }
                    val f = resolve(path) ?: throw PatchException("путь вне песочницы: $path")
                    f.parentFile?.mkdirs()
                    f.writeText(content.toString(), Charsets.UTF_8)
                    applied += "add $path"
                }
                line.trimStart().startsWith("*** Update File:") -> {
                    val path = line.trimStart().removePrefix("*** Update File:").trim()
                    i++
                    var moveTo: String? = null
                    val hunks = mutableListOf<Hunk>()
                    // a hunk accumulates context, then '-' lines, then '+' lines
                    var context = mutableListOf<String>()
                    var removed = mutableListOf<String>()
                    var added = mutableListOf<String>()
                    fun flush() {
                        if (removed.isEmpty() && added.isEmpty()) {
                            context = mutableListOf()
                            return
                        }
                        hunks += Hunk(context.toList(), removed.toList(), added.toList())
                        context = mutableListOf()
                        removed = mutableListOf()
                        added = mutableListOf()
                    }
                    while (i < lines.size && !isSectionMarker(lines[i])) {
                        val l = lines[i]
                        when {
                            l.startsWith("*** Move to:") -> moveTo = l.removePrefix("*** Move to:").trim()
                            l == "@@" -> flush()
                            l.startsWith("+") -> added += l.substring(1)
                            l.startsWith("-") -> removed += l.substring(1)
                            l.startsWith("\\ No newline at end of file") -> Unit
                            else -> {
                                if (removed.isNotEmpty() || added.isNotEmpty()) flush()
                                context += l
                            }
                        }
                        i++
                    }
                    flush()
                    val f = resolve(path) ?: throw PatchException("путь вне песочницы: $path")
                    if (!f.isFile) throw PatchException("Update File: $path не найден")
                    val old = f.readText(Charsets.UTF_8)
                    val updated = applyHunks(old, hunks, path)
                    val target = moveTo?.let { resolve(it) ?: throw PatchException("путь вне песочницы: $it") } ?: f
                    if (target != f) {
                        f.delete()
                        target.parentFile?.mkdirs()
                    }
                    target.writeText(updated, Charsets.UTF_8)
                    applied += if (moveTo != null) "update $path → $moveTo" else "update $path"
                }
                line.trimStart().startsWith("*** Delete File:") -> {
                    val path = line.trimStart().removePrefix("*** Delete File:").trim()
                    val f = resolve(path)
                    if (f != null && f.isFile && f.delete()) applied += "delete $path" else skipped += "delete $path (не найден)"
                    i++
                }
                line.isBlank() -> i++
                else -> throw PatchException("неизвестная строка патча: '$line'")
            }
        }
        return PatchResult(applied, skipped)
    }

    private class Hunk(
        val contextBefore: List<String>,
        val removed: List<String>,
        val added: List<String>,
    ) {
        val hasChanges: Boolean get() = removed.isNotEmpty() || added.isNotEmpty()
    }

    private fun applyHunks(text: String, hunks: List<Hunk>, path: String): String {
        // A hunk with no '-' and only '+' inserts after its context (or at start).
        // A hunk with '-' replaces the removed block with its added line(s).
        var lines = text.split('\n').toMutableList()
        // drop a single trailing empty element produced by a trailing \n
        val hadTrailingNewline = text.endsWith("\n")
        if (hadTrailingNewline && lines.isNotEmpty() && lines.last().isEmpty()) lines.removeAt(lines.lastIndex)

        for (hunk in hunks.filter { it.hasChanges }) {
            if (hunk.removed.isEmpty()) {
                // pure insertion: after contextBefore (or at file start when no context)
                val anchor = hunk.contextBefore.lastOrNull()
                val at = if (anchor == null) 0 else indexOfLine(lines, anchor, 0, path) + 1
                lines.addAll(at, hunk.added)
            } else {
                val at = indexOfBlock(lines, hunk.removed, path)
                lines.subList(at, at + hunk.removed.size).clear()
                lines.addAll(at, hunk.added)
            }
        }
        return lines.joinToString("\n") + if (hadTrailingNewline) "\n" else ""
    }

    private fun indexOfLine(lines: List<String>, needle: String, from: Int, path: String): Int {
        for (i in from until lines.size) if (lineMatches(lines[i], needle)) return i
        throw PatchException("Update File $path: контекст '$needle' не найден")
    }

    private fun indexOfBlock(lines: List<String>, block: List<String>, path: String): Int {
        outer@ for (i in 0..lines.size - block.size) {
            for (j in block.indices) if (!lineMatches(lines[i + j], block[j])) continue@outer
            return i
        }
        throw PatchException("Update File $path: блок не найден: ${block.firstOrNull() ?: ""}")
    }

    /**
     * Context/removed lines match the file exactly, or modulo one leading
     * space on the patch side (models often send diff-style context lines).
     */
    private fun lineMatches(fileLine: String, patchLine: String): Boolean =
        fileLine == patchLine ||
            (patchLine.startsWith(" ") && fileLine == patchLine.substring(1))
}
