package dev.pocketrun.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileTreeTest {

    private fun tree(): File {
        val root = File.createTempFile("pocketrun-tree", "").let {
            it.delete()
            it.mkdirs()
            it
        }
        File(root, "src/main").mkdirs()
        File(root, "src/assets").mkdirs()
        File(root, "src/main.py").writeText("print(1)")
        File(root, "README.md").writeText("# hi")
        File(root, "photo.jpg").writeBytes(byteArrayOf(1, 2, 3))
        File(root, "clip.mp4").writeBytes(byteArrayOf(1, 2, 3))
        File(root, "notes.txt").writeText("hi")
        File(root, "app.apk").writeBytes(byteArrayOf(1))
        File(root, ".env").writeText("SECRET=1")
        File(root, "node_modules/left-pad").mkdirs()
        File(root, "node_modules/left-pad/index.js").writeText("x")
        return root
    }

    @Test
    fun classifiesByWhatTheViewerNeeds() {
        val root = tree()
        assertEquals(FileKind.CODE, FileTree.kindOf(File(root, "src/main.py")))
        assertEquals(FileKind.TEXT, FileTree.kindOf(File(root, "README.md")))
        assertEquals(FileKind.TEXT, FileTree.kindOf(File(root, "notes.txt")))
        assertEquals(FileKind.IMAGE, FileTree.kindOf(File(root, "photo.jpg")))
        assertEquals(FileKind.VIDEO, FileTree.kindOf(File(root, "clip.mp4")))
        assertEquals(FileKind.ARCHIVE, FileTree.kindOf(File(root, "app.apk")))
        assertEquals(FileKind.FOLDER, FileTree.kindOf(File(root, "src")))
        assertTrue(FileTree.isTextual(FileKind.CODE))
        assertFalse(FileTree.isTextual(FileKind.IMAGE))
    }

    @Test
    fun foldersComeFirstAndHiddenNoiseIsLeftOut() {
        val root = tree()
        val names = FileTree.build(root, emptySet(), TreeFilter()).map { it.name }
        // Folders first, then names case-insensitively: readme.md sorts after photo.jpg.
        assertEquals(listOf("src", "app.apk", "clip.mp4", "notes.txt", "photo.jpg", "README.md"), names)
    }

    @Test
    fun collapsedFoldersAreNotWalked() {
        val root = tree()
        val collapsed = FileTree.build(root, emptySet(), TreeFilter()).map { it.name }
        assertFalse(collapsed.contains("main.py"))

        val opened = FileTree.build(root, setOf(File(root, "src").absolutePath), TreeFilter())
        assertTrue(opened.any { it.name == "main.py" })
        assertEquals(0, opened.first { it.name == "src" }.depth)
        assertEquals(1, opened.first { it.name == "main.py" }.depth)
    }

    @Test
    fun aSearchRevealsFilesInsideCollapsedFolders() {
        val root = tree()
        val hits = FileTree.build(root, emptySet(), TreeFilter(query = "main.py"))
        // src/ was never expanded, and main.py still shows up - with src above it.
        assertEquals(listOf("src", "main.py"), hits.map { it.name })
    }

    @Test
    fun kindFilterKeepsOnlyThatKind() {
        val root = tree()
        val hits = FileTree.build(root, emptySet(), TreeFilter(kind = FileKind.IMAGE))
        assertEquals(listOf("photo.jpg"), hits.map { it.name })
    }

    @Test
    fun hiddenToggleShowsDotfiles() {
        val root = tree()
        val hidden = FileTree.build(root, emptySet(), TreeFilter(showHidden = true))
        assertTrue(hidden.any { it.name == ".env" })
        assertTrue(hidden.any { it.name == "node_modules" })
    }

    @Test
    fun fingerprintMovesWhenTheAgentWritesAFile() {
        val root = tree()
        val before = FileTree.fingerprint(root)
        assertEquals(before, FileTree.fingerprint(root))
        File(root, "src/new.py").writeText("x")
        assertTrue(FileTree.fingerprint(root) != before)
    }

    @Test
    fun foldersCarryTheirOwnCounts() {
        val root = tree()
        val src = FileTree.build(root, emptySet(), TreeFilter()).first { it.name == "src" }
        assertEquals(2, src.folders)
        assertEquals(1, src.files)
    }

    @Test
    fun stampMovesWhenAFolderIsOpenedEvenThoughTheDiskDidNotChange() {
        // The bug behind "Открыть папку" doing nothing: the guard that decides
        // whether to rebuild the tree only looked at the disk, so opening a
        // folder looked identical to doing nothing at all and the tree was
        // never rebuilt - the row showed an open-folder icon with no children.
        val root = tree()
        val src = File(root, "src").absolutePath
        val none = FileTree.stamp(root, emptySet(), TreeFilter())

        assertTrue(
            "opening a folder must invalidate the tree",
            FileTree.stamp(root, setOf(src), TreeFilter()) != none,
        )
        assertTrue(
            "closing it again must invalidate it too",
            FileTree.stamp(root, emptySet(), TreeFilter()) != FileTree.stamp(root, setOf(src), TreeFilter()),
        )
        assertEquals(none, FileTree.stamp(root, emptySet(), TreeFilter()))
    }

    @Test
    fun stampMovesForEveryFilterThatChangesWhatIsShown() {
        val root = tree()
        val plain = FileTree.stamp(root, emptySet(), TreeFilter())
        assertTrue(FileTree.stamp(root, emptySet(), TreeFilter(query = "py")) != plain)
        assertTrue(FileTree.stamp(root, emptySet(), TreeFilter(kind = FileKind.CODE)) != plain)
        assertTrue(FileTree.stamp(root, emptySet(), TreeFilter(showHidden = true)) != plain)
    }

    @Test
    fun stampDoesNotConfuseFieldsThatRunTogether() {
        val root = tree()
        val a = FileTree.stamp(root, emptySet(), TreeFilter(query = "a", kind = FileKind.CODE))
        val b = FileTree.stamp(root, emptySet(), TreeFilter(query = "aCODE", kind = null))
        assertTrue("query and kind must not smear into one another", a != b)
    }

    @Test
    fun openingAFolderActuallyShowsItsChildren() {
        val root = tree()
        val collapsed = FileTree.build(root, emptySet(), TreeFilter())
        assertFalse(collapsed.any { it.name == "main" })

        val opened = FileTree.build(root, setOf(File(root, "src").absolutePath), TreeFilter())
        assertTrue("the folder's children must appear once it is open", opened.any { it.name == "main" })
    }
}
