package dev.pocketrun.core

import java.io.File
import java.util.Locale

/**
 * What a file is to the UI. The tree icon, the viewer, the filter chip and the
 * "открыть" action all read this one classification, so a file can never open in
 * the wrong viewer because two lists disagreed about its type.
 */
enum class FileKind(val title: String) {
    FOLDER("Папка"),
    CODE("Код"),
    TEXT("Текст"),
    IMAGE("Фото"),
    AUDIO("Аудио"),
    VIDEO("Видео"),
    ARCHIVE("Архив"),
    OTHER("Файл"),
}

/** One row of the tree. A flat list with a depth keeps LazyColumn cheap. */
data class FileNode(
    val file: File,
    val depth: Int,
    val kind: FileKind,
    val size: Long,
    val modified: Long,
    val folders: Int = 0,
    val files: Int = 0,
) {
    val name: String get() = file.name
    val isFolder: Boolean get() = kind == FileKind.FOLDER
}

/** What the tree shows: a name substring, one kind, and whether dotfiles count. */
data class TreeFilter(
    val query: String = "",
    val kind: FileKind? = null,
    val showHidden: Boolean = false,
) {
    val isFiltering: Boolean get() = query.isNotBlank() || kind != null
}

/**
 * The project folder as a tree. A project really is a directory on disk - the
 * whole point of this file is that the app stopped pretending otherwise: every
 * file is visible, every file is openable, and a change made by the agent shows
 * up without the user pulling to refresh.
 */
object FileTree {
    /**
     * Directories that are noise in a tree but must still work on disk. Skipped
     * unless the user asks for everything.
     */
    val SKIP_DIRS = setOf(
        ".git", "node_modules", "__pycache__", ".venv", "venv", ".gradle", ".idea",
        ".cxx", ".pytest_cache", ".mypy_cache", ".ruff_cache", ".ipynb_checkpoints",
    )

    const val MAX_DEPTH = 12
    const val MAX_NODES = 2_000

    private val NAMED_CODE = setOf(
        "makefile", "dockerfile", "rakefile", "gemfile", "procfile", "justfile", "brewfile",
    )

    private val CODE_EXT = setOf(
        "py", "pyw", "js", "mjs", "cjs", "ts", "tsx", "jsx", "java", "kt", "kts", "c", "h",
        "cc", "cpp", "hpp", "cs", "go", "rs", "rb", "php", "sh", "bash", "zsh", "fish", "ps1",
        "sql", "html", "htm", "css", "scss", "sass", "less", "vue", "svelte", "lua", "pl", "r",
        "swift", "gradle", "cmake", "m", "proto", "ex", "exs", "erl", "clj", "groovy",
    )

    private val TEXT_EXT = setOf(
        "txt", "md", "markdown", "rst", "adoc", "log", "csv", "tsv", "json", "jsonl", "yaml",
        "yml", "toml", "ini", "cfg", "conf", "env", "properties", "xml", "srt", "vtt", "lock",
    )

    private val IMAGE_EXT = setOf(
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif", "svg", "ico",
    )

    private val AUDIO_EXT = setOf(
        "mp3", "wav", "ogg", "oga", "m4a", "aac", "flac", "opus", "mid", "midi", "amr", "3ga",
    )

    private val VIDEO_EXT = setOf(
        "mp4", "mkv", "webm", "avi", "mov", "mpg", "mpeg", "m4v", "3gp", "ts", "wmv", "flv",
    )

    private val ARCHIVE_EXT = setOf(
        "zip", "tar", "gz", "tgz", "bz2", "xz", "7z", "rar", "jar", "apk", "deb", "rpm",
    )

    fun ext(file: File): String = file.extension.lowercase(Locale.ROOT)

    fun kindOf(file: File): FileKind {
        if (file.isDirectory) return FileKind.FOLDER
        val name = file.name.lowercase(Locale.ROOT)
        val ext = ext(file)
        return when {
            ext in CODE_EXT || name in NAMED_CODE -> FileKind.CODE
            ext in TEXT_EXT -> FileKind.TEXT
            ext in IMAGE_EXT -> FileKind.IMAGE
            ext in AUDIO_EXT -> FileKind.AUDIO
            ext in VIDEO_EXT -> FileKind.VIDEO
            ext in ARCHIVE_EXT -> FileKind.ARCHIVE
            else -> FileKind.OTHER
        }
    }

    /** True for anything the editor or the content search may open as text. */
    fun isTextual(kind: FileKind): Boolean = kind == FileKind.CODE || kind == FileKind.TEXT

    fun isHidden(file: File): Boolean = file.name.startsWith(".")

    private fun skip(file: File): Boolean = file.isDirectory && file.name in SKIP_DIRS

    /**
     * The tree, flattened for a lazy list.
     *
     * Without a filter this walks only what the user has expanded, so a project
     * with thousands of files costs a directory listing per open folder. With a
     * filter the whole tree is walked once, and a folder is kept when the filter
     * matches it or anything inside it - that is what makes a search reveal a
     * file that sits in a collapsed folder.
     */
    fun build(root: File, expanded: Set<String>, filter: TreeFilter): List<FileNode> {
        if (!root.isDirectory) return emptyList()
        if (!filter.isFiltering) return walk(root, 0, expanded, filter.showHidden)
        val all = walk(root, 0, everythingExpanded = true, showHidden = filter.showHidden)
        val keep = keepSet(root, all, filter)
        return all.filter { it.file.absolutePath in keep }
    }

    private fun walk(
        dir: File,
        depth: Int,
        expanded: Set<String> = emptySet(),
        showHidden: Boolean = false,
        everythingExpanded: Boolean = false,
    ): List<FileNode> {
        if (depth > MAX_DEPTH) return emptyList()
        val out = ArrayList<FileNode>()
        for (child in children(dir, showHidden)) {
            if (out.size >= MAX_NODES) break
            val kind = kindOf(child)
            out += node(child, depth, kind)
            if (kind != FileKind.FOLDER) continue
            val open = everythingExpanded || child.absolutePath in expanded
            if (open) out += walk(child, depth + 1, expanded, showHidden, everythingExpanded)
        }
        return out
    }

    private fun node(file: File, depth: Int, kind: FileKind): FileNode {
        if (kind != FileKind.FOLDER) {
            return FileNode(file, depth, kind, file.length(), file.lastModified())
        }
        val entries = file.listFiles()
        return FileNode(
            file = file,
            depth = depth,
            kind = kind,
            size = 0,
            modified = file.lastModified(),
            folders = entries?.count { it.isDirectory } ?: 0,
            files = entries?.count { !it.isDirectory } ?: 0,
        )
    }

    private fun children(dir: File, showHidden: Boolean): List<File> {
        val entries = dir.listFiles() ?: return emptyList()
        return entries
            .filter { (showHidden || !isHidden(it)) && (showHidden || !skip(it)) }
            .sortedWith(compareBy({ if (it.isDirectory) 0 else 1 }, { it.name.lowercase(Locale.ROOT) }))
    }

    /** The matches themselves plus every folder on the way to the project root. */
    private fun keepSet(root: File, all: List<FileNode>, filter: TreeFilter): Set<String> {
        val query = filter.query.trim().lowercase(Locale.ROOT)
        val keep = HashSet<String>()
        for (node in all) {
            if (node.isFolder) continue
            val byName = query.isEmpty() || node.name.lowercase(Locale.ROOT).contains(query)
            val byKind = filter.kind == null || node.kind == filter.kind
            if (!byName || !byKind) continue
            keep += node.file.absolutePath
            var current = node.file.parentFile
            while (current != null && current.absolutePath.length >= root.absolutePath.length) {
                keep += current.absolutePath
                if (current.absolutePath == root.absolutePath) break
                current = current.parentFile
            }
        }
        return keep
    }

    /**
     * A cheap summary of the whole tree, used to notice that the agent, the
     * editor or a file imported from the phone changed something while the app was
     * looking at it. Names, sizes and mtimes only - no nodes are built, so it is
     * safe to run every couple of seconds.
     */
    fun fingerprint(root: File): String {
        if (!root.isDirectory) return "-"
        var hash = 1125899906842597L
        var count = 0
        val stack = ArrayDeque<File>()
        stack.addLast(root)
        while (stack.isNotEmpty() && count < MAX_NODES) {
            val dir = stack.removeLast()
            for (child in dir.listFiles().orEmpty()) {
                count++
                if (child.isDirectory && child.name !in SKIP_DIRS) stack.addLast(child)
                hash = hash * 31 + child.name.hashCode()
                hash = hash * 31 + child.length()
                hash = hash * 31 + child.lastModified()
            }
        }
        return "$count:${java.lang.Long.toHexString(hash)}"
    }
}
