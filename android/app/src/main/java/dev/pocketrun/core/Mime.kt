package dev.pocketrun.core

import android.webkit.MimeTypeMap
import java.io.File
import java.util.Locale

/**
 * The content type to hand the system with a file. Android's own lookup table is
 * used first, then a per-kind fallback: a .md or an .log has no registered type
 * and would otherwise open as "nothing", while every viewer out there will take
 * text/plain.
 */
object Mime {

    fun of(file: File): String {
        val ext = FileTree.ext(file)
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        return when (FileTree.kindOf(file)) {
            FileKind.IMAGE -> "image/*"
            FileKind.AUDIO -> "audio/*"
            FileKind.VIDEO -> "video/*"
            FileKind.CODE, FileKind.TEXT -> "text/plain"
            FileKind.ARCHIVE -> "application/octet-stream"
            else -> "application/octet-stream"
        }
    }

    fun extensionOf(type: String): String = type.substringAfterLast('/', "bin").lowercase(Locale.ROOT)
}
