package dev.pocketrun.core

import android.content.Context
import java.io.File
import java.io.IOException
import java.nio.file.Path

/**
 * Every path the app touches lives under one of these roots. User code can reach
 * them through the `fs` module and the Python `os` module, but nothing outside
 * the sandbox is exposed, and [resolve] refuses to escape it.
 */
class Workspace private constructor(val root: File) {

    val projects: File get() = File(root, "projects").apply { mkdirs() }
    val packages: File get() = File(root, "packages").apply { mkdirs() }
    val cache: File get() = File(root, "cache").apply { mkdirs() }
    val logs: File get() = File(root, "logs").apply { mkdirs() }

    fun projectDir(name: String): File {
        // Unicode letters and digits survive, everything else becomes '_'.
        val safe = name.replace(Regex("[^\\p{L}\\p{N}._-]"), "_").trim('.', '_')
            .ifEmpty { "project" }
        return File(projects, safe).apply { mkdirs() }
    }

    /**
     * Resolves [candidate] against [base], returning null when it would leave the
     * sandbox. Callers treat null as "path rejected" rather than "file missing".
     *
     * Symlinks are followed before the containment check, so a link inside the
     * workspace that points at the app's private files (`shared_prefs/` with the
     * activation key, other projects, the keystore) is rejected exactly like an
     * absolute path would be. Paths that do not exist yet are resolved through
     * their deepest existing ancestor, so `write`, `mkdir` and friends keep
     * working for new files.
     */
    fun resolve(candidate: String, base: File = root): File? {
        val target = File(candidate).let { if (it.isAbsolute) it else File(base, candidate) }
        val sandbox = realPath(root) ?: return null
        val resolved = realPath(target) ?: return null
        return resolved.takeIf { contains(it, sandbox) }
    }

    /**
     * True when [file] really is inside the sandbox — symlinks resolved, not
     * just the text of the path. For gates that must not be tricked by a path
     * (`npx` bin resolution, script entry points).
     */
    fun isInside(file: File): Boolean {
        val sandbox = realPath(root) ?: return false
        val resolved = realPath(file) ?: return false
        return contains(resolved, sandbox)
    }

    fun relativeTo(file: File): String {
        val path = normalize(file.absolutePath)
        val prefix = normalize(root.absolutePath)
        return when {
            path == prefix -> ""
            path.startsWith("$prefix${File.separator}") -> path.removePrefix("$prefix${File.separator}")
            else -> path
        }
    }

    private fun contains(file: File, sandbox: File): Boolean {
        val path = trimSep(file.absolutePath)
        val prefix = trimSep(sandbox.absolutePath)
        return path == prefix || path.startsWith("$prefix${File.separator}")
    }

    /**
     * The path with every symlink resolved. The deepest existing ancestor is
     * canonicalized and the not-yet-created tail is appended, so callers can use
     * this for paths they are about to create. Returns null only when even the
     * root cannot be resolved, which cannot happen for a rooted path.
     */
    private fun realPath(file: File): File? {
        val missing = ArrayDeque<String>()
        var current: Path = file.absoluteFile.toPath().normalize()
        while (true) {
            val real = try {
                current.toRealPath()
            } catch (e: IOException) {
                // Nothing there yet (or unreadable): canonicalize the parent and
                // keep the missing name.
                val name = current.fileName?.toString() ?: return null
                missing.addLast(name)
                current = current.parent ?: return null
                continue
            }
            var result = real.toFile()
            for (name in missing.asReversed()) result = File(result, name)
            return result.absoluteFile
        }
    }

    private fun trimSep(path: String): String = path.trimEnd(File.separatorChar).ifEmpty { File.separator }

    private fun normalize(path: String): String {
        val canonical = File(path).toPath().normalize()
        return canonical.toString().trimEnd(File.separatorChar).ifEmpty { File.separator }
    }

    companion object {
        fun from(context: Context): Workspace = Workspace(
            File(context.filesDir, "workspace"),
        )

        /** JVM-friendly constructor for unit tests: the sandbox rooted at [dir]. */
        fun at(dir: File): Workspace = Workspace(dir.apply { mkdirs() })
    }
}
