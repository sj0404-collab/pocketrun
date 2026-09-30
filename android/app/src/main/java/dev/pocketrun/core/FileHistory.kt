package dev.pocketrun.core

import java.io.File
import java.io.IOException

/** One saved copy of a file, newest first when listed. */
data class Version(
    val file: File,
    val stamp: Long,
    val size: Long,
    val project: String,
) {
    val label: String get() = file.name.substringAfter("__", file.name)
}

/**
 * Versions of the files the user cares about, kept beside the projects.
 *
 * The agent edits files without asking first (it does ask, but the user can
 * approve a patch they have not read carefully), and there is no undo anywhere
 * else in the app. So before anything overwrites or deletes a file - a save in
 * the editor, a write from the agent, a delete in the file manager - the old
 * content is copied here, and the file manager offers to put it back.
 *
 * Layout: `.history/<project>/<mangled-relative-path>/<stamp>__<name>`.
 */
class FileHistory(private val root: File) {

    /** Copies the current content aside. Returns the copy, or null when there is
     *  nothing to keep (missing file) or the copy failed. */
    fun snapshot(file: File, projectDir: File): Version? {
        if (!file.isFile) return null
        val dir = dirFor(file, projectDir)
        val target = File(dir, "${stamp()}_${file.name.take(80)}")
        return try {
            file.copyTo(target, overwrite = false)
            version(target, projectDir.name)
        } catch (_: IOException) {
            null
        }
    }

    /** Every saved copy of this file, newest first. */
    fun versionsOf(file: File, projectDir: File): List<Version> {
        val dir = dirFor(file, projectDir)
        val entries = dir.listFiles()?.filter { it.isFile } ?: return emptyList()
        return entries
            .map { version(it, projectDir.name) }
            .sortedByDescending { it.stamp }
    }

    /**
     * Puts a saved copy back. The current content is snapshotted first, so
     * "restore" is itself undoable.
     */
    fun restore(version: Version, target: File, projectDir: File): Boolean {
        if (!version.file.isFile) return false
        if (target.isFile) snapshot(target, projectDir)
        return try {
            target.parentFile?.mkdirs()
            version.file.copyTo(target, overwrite = true)
            true
        } catch (_: IOException) {
            false
        }
    }

    fun deleteVersion(version: Version): Boolean = version.file.delete()

    /** Keeps the newest [keep] copies so the history folder cannot grow forever. */
    fun prune(file: File, projectDir: File, keep: Int = 20) {
        versionsOf(file, projectDir).drop(keep).forEach { it.file.delete() }
    }

    private fun dirFor(file: File, projectDir: File): File =
        File(File(root, FileOps.slug(projectDir.name)), key(file, projectDir)).apply { mkdirs() }

    /** The relative path with separators encoded, so a folder and a file with the
     *  same name can never land in the same history slot. */
    private fun key(file: File, projectDir: File): String {
        val relative = try {
            file.absolutePath.removePrefix(projectDir.absolutePath).trimStart(File.separatorChar)
        } catch (_: IllegalArgumentException) {
            file.name
        }
        val encoded = relative.replace("%", "%25").replace(File.separatorChar, '~')
        return encoded.ifEmpty { file.name }
    }

    private fun version(file: File, project: String): Version = Version(
        file = file,
        stamp = file.name.substringBefore("__").toLongOrNull() ?: 0L,
        size = file.length(),
        project = project,
    )

    private fun stamp(): String = String.format("%013d", System.currentTimeMillis())

    companion object {
        fun under(workspace: Workspace): FileHistory =
            FileHistory(File(workspace.root, ".history"))
    }
}
