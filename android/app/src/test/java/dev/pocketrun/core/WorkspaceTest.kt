package dev.pocketrun.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/**
 * The sandbox gate every runtime goes through. The interesting cases are the
 * symlinks: a textual `..` check can be walked around with one link, and the
 * app's private data (`shared_prefs` with the activation key) is exactly what
 * must stay unreachable.
 */
class WorkspaceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newWorkspace(): Workspace = Workspace.at(tmp.newFolder("workspace"))

    private fun symlink(link: File, target: String): Boolean = try {
        link.parentFile?.mkdirs()
        Files.createSymbolicLink(link.toPath(), File(target).toPath())
        true
    } catch (t: Throwable) {
        false
    }

    @Test
    fun aPathInsideTheWorkspaceResolves() {
        val ws = newWorkspace()
        val resolved = ws.resolve("projects/demo/main.py", ws.root)
        assertNotNull(resolved)
        assertTrue(resolved!!.absolutePath.endsWith("projects/demo/main.py"))
    }

    @Test
    fun aRelativePathResolvesAgainstTheBase() {
        val ws = newWorkspace()
        val base = File(ws.root, "projects/demo").apply { mkdirs() }
        val resolved = ws.resolve("notes.txt", base)
        assertNotNull(resolved)
        assertTrue(resolved!!.absolutePath.endsWith("projects/demo/notes.txt"))
    }

    @Test
    fun aNotYetCreatedFileResolvesThroughItsExistingParent() {
        val ws = newWorkspace()
        val deep = ws.resolve("projects/new/dir/file.txt")
        assertNotNull(deep)
        assertTrue(deep!!.absolutePath.endsWith("projects/new/dir/file.txt"))
    }

    @Test
    fun escapingWithDotDotIsRefused() {
        val ws = newWorkspace()
        assertNull(ws.resolve("../../etc/passwd", ws.root))
        assertNull(ws.resolve("/etc/passwd"))
    }

    @Test
    fun theRootItselfIsInside() {
        val ws = newWorkspace()
        assertNotNull(ws.resolve("."))
        assertTrue(ws.isInside(ws.root))
    }

    @Test
    fun aSiblingWithACommonPrefixIsOutside() {
        val ws = newWorkspace()
        val sibling = File(ws.root.parentFile, "workspace-secret").apply { mkdirs() }
        assertNull(ws.resolve(sibling.absolutePath))
        assertFalse(ws.isInside(File(sibling, "key.xml")))
    }

    @Test
    fun aSymlinkOutOfTheWorkspaceIsRefused() {
        val ws = newWorkspace()
        val secret = tmp.newFolder("shared_prefs")
        val key = File(secret, "license.xml").apply { writeText("<string>PRK1</string>") }
        if (!symlink(File(ws.root, "escape"), secret.absolutePath)) {
            return // filesystem without symlink support
        }
        assertNull(ws.resolve("escape/license.xml", ws.root))
        assertFalse(ws.isInside(key))
    }

    @Test
    fun aSymlinkChainOutOfTheWorkspaceIsRefused() {
        val ws = newWorkspace()
        val secret = tmp.newFolder("private")
        File(secret, "id_rsa").writeText("key")
        if (!symlink(File(ws.root, "one"), "two")) return
        if (!symlink(File(ws.root, "two"), "three")) return
        if (!symlink(File(ws.root, "three"), secret.absolutePath)) return
        assertNull(ws.resolve("one/id_rsa", ws.root))
    }

    @Test
    fun aSymlinkInsideTheWorkspaceIsFollowed() {
        val ws = newWorkspace()
        val real = File(ws.root, "projects/data.txt").apply {
            parentFile?.mkdirs()
            writeText("данные")
        }
        if (!symlink(File(ws.root, "link.txt"), real.absolutePath)) return
        val resolved = ws.resolve("link.txt", ws.root)
        assertNotNull(resolved)
        assertEquals(real.canonicalPath, resolved!!.canonicalPath)
        assertTrue(ws.isInside(resolved))
    }

    @Test
    fun relativeToStripsTheSandboxPrefix() {
        val ws = newWorkspace()
        assertEquals("", ws.relativeTo(ws.root))
        assertEquals("projects/demo", ws.relativeTo(File(ws.root, "projects/demo")))
    }

    @Test
    fun projectDirKeepsLettersAndDropsTheRest() {
        val ws = newWorkspace()
        // Spaces and punctuation become '_', and the edges are trimmed.
        assertEquals("Мой_проект_1", ws.projectDir("Мой проект 1!").name)
        assertEquals("project", ws.projectDir("///").name)
        assertEquals("a.b-c_1", ws.projectDir("a.b-c 1").name)
    }
}
