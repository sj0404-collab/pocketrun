package dev.pocketrun.core

import android.content.Context
import java.io.File

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
     */
    fun resolve(candidate: String, base: File = root): File? {
        val target = File(candidate).let { if (it.isAbsolute) it else File(base, candidate) }
        val normalized = normalize(target.absolutePath)
        val normalizedRoot = normalize(root.absolutePath)
        if (normalized != normalizedRoot && !normalized.startsWith("$normalizedRoot${File.separator}")) {
            return null
        }
        return File(normalized)
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
