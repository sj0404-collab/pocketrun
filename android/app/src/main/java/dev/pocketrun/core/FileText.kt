package dev.pocketrun.core

import java.io.File
import java.io.IOException

/**
 * Reading a file as text. Everything the editor, the content search and the
 * text viewer show goes through here, so the same rules apply everywhere: never
 * dump a binary into a text field, never read a gigabyte into memory, and never
 * let an invalid encoding crash the screen.
 */
object FileText {
    /** Above this the editor opens read-only instead of pretending it is a source file. */
    const val MAX_EDIT_BYTES = 2_000_000L

    /** A NUL byte in the head is the classic "this is not text" signal. */
    fun looksBinary(file: File, probe: Int = 8_192): Boolean {
        if (!file.isFile) return false
        return try {
            file.inputStream().use { stream ->
                val head = ByteArray(minOf(probe, file.length().toInt().coerceAtLeast(0)))
                if (head.isEmpty()) return false
                var read = 0
                while (read < head.size) {
                    val n = stream.read(head, read, head.size - read)
                    if (n <= 0) break
                    read += n
                }
                head.take(read).any { it == 0.toByte() }
            }
        } catch (_: IOException) {
            false
        }
    }

    /** The file as text, or null when it is binary, missing or too big. */
    fun read(file: File, maxBytes: Long = MAX_EDIT_BYTES): String? {
        if (!file.isFile) return null
        if (file.length() > maxBytes) return null
        if (looksBinary(file)) return null
        return try {
            val bytes = file.readBytes()
            String(bytes, Charsets.UTF_8)
        } catch (_: IOException) {
            null
        }
    }

    /** Total size of a folder, for the "N МБ" label. Bounded, so it stays cheap. */
    fun sizeOf(dir: File, maxEntries: Int = FileTree.MAX_NODES): Long {
        if (dir.isFile) return dir.length()
        var total = 0L
        var count = 0
        val stack = ArrayDeque<File>()
        stack.addLast(dir)
        while (stack.isNotEmpty() && count < maxEntries) {
            for (child in stack.removeLast().listFiles().orEmpty()) {
                count++
                if (child.isDirectory) stack.addLast(child) else total += child.length()
            }
        }
        return total
    }

    /** "1,4 МБ" the way a person would say it. */
    fun human(bytes: Long): String {
        if (bytes < 1024) return "$bytes Б"
        val units = listOf("КБ", "МБ", "ГБ", "ТБ")
        var value = bytes.toDouble() / 1024
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit++
        }
        val text = if (value >= 100) String.format("%.0f", value) else String.format("%.1f", value)
        return "$text ${units[unit]}"
    }
}
