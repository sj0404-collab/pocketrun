package dev.pocketrun.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileOpsTest {

    private fun dir(): File = File.createTempFile("pocketrun-ops", "").let {
        it.delete()
        it.mkdirs()
        it
    }

    @Test
    fun namesThatWouldEscapeTheProjectAreRejected() {
        assertNull(FileOps.safeName("../outside"))
        assertNull(FileOps.safeName("a/b"))
        assertNull(FileOps.safeName(".."))
        assertNull(FileOps.safeName("  "))
        assertNull(FileOps.safeName("x".repeat(200)))
        assertEquals("notes.txt", FileOps.safeName(" notes.txt "))
    }

    @Test
    fun aTakenNameGetsACounter() {
        val root = dir()
        File(root, "main.py").writeText("a")
        assertEquals("main (2).py", FileOps.uniqueName(root, "main.py").name)
        assertEquals("new.txt", FileOps.uniqueName(root, "new.txt").name)
    }

    @Test
    fun createRenameMoveDelete() {
        val root = dir()
        val a = FileOps.createFile(root, "a.txt", "hello")
        assertNotNull(a)
        assertEquals("hello", a!!.readText())

        val b = FileOps.rename(a, "b.txt")
        assertEquals("b.txt", b?.name)
        assertFalse(a.exists())

        val sub = FileOps.createDir(root, "sub")
        assertNotNull(sub)
        val moved = FileOps.move(b!!, sub!!)
        assertEquals(File(sub, "b.txt").absolutePath, moved?.absolutePath)
        assertTrue(moved!!.isFile)

        assertTrue(FileOps.delete(moved))
        assertFalse(moved.exists())
    }

    @Test
    fun renameDoesNotClobberAnExistingFile() {
        val root = dir()
        val keep = FileOps.createFile(root, "keep.txt", "keep")!!
        val other = FileOps.createFile(root, "other.txt", "other")!!
        val renamed = FileOps.rename(other, "keep.txt")
        assertEquals("keep (2).txt", renamed?.name)
        assertEquals("keep", keep.readText())
    }

    @Test
    fun aFolderCannotBeMovedIntoItself() {
        val root = dir()
        val sub = FileOps.createDir(root, "sub")!!
        val inner = FileOps.createDir(sub, "inner")!!
        assertNull(FileOps.move(sub, inner))
    }
}

class FileTextTest {

    private fun file(bytes: ByteArray): File = File.createTempFile("pocketrun-text", "").also {
        it.writeBytes(bytes)
    }

    @Test
    fun binaryFilesAreNotReadAsText() {
        val png = file(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0, 0, 0, 13))
        assertTrue(FileText.looksBinary(png))
        assertNull(FileText.read(png))
    }

    @Test
    fun cyrillicSurvivesTheRoundTrip() {
        val f = file("Привет, PocketRun!\n第二行".toByteArray())
        assertFalse(FileText.looksBinary(f))
        assertEquals("Привет, PocketRun!\n第二行", FileText.read(f))
    }

    @Test
    fun humanSizesReadLikeAPersonWouldSayThem() {
        assertEquals("512 Б", FileText.human(512))
        assertEquals("1,0 КБ", FileText.human(1024).replace('.', ','))
        assertEquals("1,4 МБ", FileText.human(1_500_000).replace('.', ','))
    }
}

class ContentSearchTest {

    private fun project(): File {
        val root = File.createTempFile("pocketrun-search", "").let {
            it.delete()
            it.mkdirs()
            it
        }
        File(root, "a.py").writeText("def main():\n    return 42\n")
        File(root, "sub").mkdirs()
        File(root, "sub/b.txt").writeText("nothing here\n")
        File(root, "sub/c.py").writeText("print('main')\n")
        File(root, "bin.dat").writeBytes(byteArrayOf(0, 1, 2))
        File(root, "node_modules").mkdirs()
        File(root, "node_modules/d.py").writeText("main\n")
        return root
    }

    @Test
    fun findsHitsWithLineNumbersInNestedFiles() {
        val hits = ContentSearch.search(project(), "main")
        assertEquals(2, hits.size)
        val inA = hits.first { it.file.name == "a.py" }
        assertEquals(1, inA.line)
        val inC = hits.first { it.file.name == "c.py" }
        assertEquals(1, inC.line)
    }

    @Test
    fun caseSensitiveAndRegexModes() {
        assertTrue(ContentSearch.search(project(), "NOTHING", caseSensitive = true).isEmpty())
        val regex = ContentSearch.search(project(), "def \\w+\\(\\):", regex = true)
        assertEquals(1, regex.size)
    }

    @Test
    fun anImpossibleRegexIsAnEmptyResultNotACrash() {
        assertTrue(ContentSearch.search(project(), "[unclosed", regex = true).isEmpty())
    }

    @Test
    fun anEmptyQueryFindsNothing() {
        assertTrue(ContentSearch.search(project(), "   ").isEmpty())
    }
}

class FileHistoryTest {

    @Test
    fun snapshotRestoreRoundTrip() {
        val project = File.createTempFile("pocketrun-hist", "").let {
            it.delete()
            it.mkdirs()
            it
        }
        val history = FileHistory(File(project, ".history"))
        val file = File(project, "notes.txt")
        file.writeText("original")

        val saved = history.snapshot(file, project)
        assertNotNull(saved)
        file.writeText("what the agent wrote")
        assertEquals("what the agent wrote", file.readText())

        assertTrue(history.restore(saved!!, file, project))
        assertEquals("original", file.readText())
        // Restoring is itself undoable: the overwritten text is kept too.
        assertTrue(history.versionsOf(file, project).any {
            FileText.read(it.file)?.contains("what the agent wrote") == true
        })
    }

    @Test
    fun versionsOfAnUntouchedFileAreEmpty() {
        val project = File.createTempFile("pocketrun-hist2", "").let {
            it.delete()
            it.mkdirs()
            it
        }
        val history = FileHistory(File(project, ".history"))
        assertTrue(history.versionsOf(File(project, "nope.txt"), project).isEmpty())
    }

    @Test
    fun filesInSubfoldersDoNotShareASlot() {
        val project = File.createTempFile("pocketrun-hist3", "").let {
            it.delete()
            it.mkdirs()
            it
        }
        val history = FileHistory(File(project, ".history"))
        val a = File(project, "a/b.txt").apply { parentFile.mkdirs(); writeText("1") }
        val b = File(project, "b.txt").apply { writeText("2") }
        history.snapshot(a, project)
        history.snapshot(b, project)
        assertEquals(1, history.versionsOf(a, project).size)
        assertEquals(1, history.versionsOf(b, project).size)
        assertEquals("1", FileText.read(history.versionsOf(a, project).first().file))
    }
}
