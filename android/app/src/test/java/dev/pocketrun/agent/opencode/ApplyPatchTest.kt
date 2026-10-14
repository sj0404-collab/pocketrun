package dev.pocketrun.agent.opencode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The apply_patch tool: *** Begin Patch blocks with Add / Update (+,- lines) /
 * Move to / Delete sections — including indented patches (markdown blocks)
 * and diff-style context lines.
 */
class ApplyPatchTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun resolver(): (String) -> File? = { path ->
        val f = File(tmp.root, path)
        f.parentFile?.mkdirs()
        f
    }

    @Test
    fun addFile() {
        val patch = listOf(
            "*** Begin Patch",
            "*** Add File: hello.txt",
            "+Hello, patch!",
            "+Second line",
            "*** End Patch",
        ).joinToString("\n")
        val result = ApplyPatch.apply(patch, resolver())
        assertTrue(result.applied.isNotEmpty())
        assertEquals("Hello, patch!\nSecond line", File(tmp.root, "hello.txt").readText().trim())
    }

    @Test
    fun addIndentedPatch() {
        // e.g. the model wrapped the patch in an indented markdown block
        val patch = listOf(
            "        *** Begin Patch",
            "        *** Add File: indented.txt",
            "        +line one",
            "        +  line two (kept)",
            "        *** End Patch",
        ).joinToString("\n")
        val result = ApplyPatch.apply(patch, resolver())
        assertTrue(result.applied.isNotEmpty())
        assertEquals("line one\n  line two (kept)", File(tmp.root, "indented.txt").readText().trim())
    }

    @Test
    fun updateFile() {
        File(tmp.root, "app.py").writeText("def main():\n    print('old')\n    return 0\n")
        val patch = listOf(
            "*** Begin Patch",
            "*** Update File: app.py",
            "@@",
            " def main():",
            "-    print('old')",
            "+    print('new')",
            "     return 0",
            "*** End Patch",
        ).joinToString("\n")
        val result = ApplyPatch.apply(patch, resolver())
        assertTrue(result.applied.isNotEmpty())
        val text = File(tmp.root, "app.py").readText()
        assertTrue(text.contains("print('new')"))
        assertFalse(text.contains("print('old')"))
        assertTrue(text.contains("def main():"))
        assertTrue(text.contains("return 0"))
    }

    @Test
    fun updatePureInsertionAfterContext() {
        File(tmp.root, "notes.md").writeText("# Title\n\nBody.\n")
        val patch = listOf(
            "*** Begin Patch",
            "*** Update File: notes.md",
            "@@",
            " # Title",
            "+",
            "+Added under the title.",
            "*** End Patch",
        ).joinToString("\n")
        ApplyPatch.apply(patch, resolver())
        val text = File(tmp.root, "notes.md").readText()
        assertTrue(text.contains("Added under the title."))
        assertTrue(text.contains("Body."))
    }

    @Test
    fun deleteFile() {
        File(tmp.root, "gone.txt").writeText("bye")
        val patch = listOf(
            "*** Begin Patch",
            "*** Delete File: gone.txt",
            "*** End Patch",
        ).joinToString("\n")
        val result = ApplyPatch.apply(patch, resolver())
        assertTrue(result.applied.isNotEmpty())
        assertFalse(File(tmp.root, "gone.txt").exists())
    }

    @Test
    fun moveFile() {
        File(tmp.root, "a.txt").writeText("content")
        val patch = listOf(
            "*** Begin Patch",
            "*** Update File: a.txt",
            "*** Move to: b/nested.txt",
            "*** End Patch",
        ).joinToString("\n")
        val result = ApplyPatch.apply(patch, resolver())
        assertTrue(result.applied.isNotEmpty())
        assertFalse(File(tmp.root, "a.txt").exists())
        assertEquals("content", File(tmp.root, "b/nested.txt").readText())
    }

    @Test
    fun missingMarkerThrows() {
        try {
            ApplyPatch.apply("this is not a patch", resolver())
            fail("expected PatchException")
        } catch (e: ApplyPatch.PatchException) {
            assertTrue(e.message!!.contains("Begin Patch"))
        }
    }

    @Test
    fun contextMismatchThrows() {
        File(tmp.root, "c.txt").writeText("one\ntwo\nthree\n")
        val patch = listOf(
            "*** Begin Patch",
            "*** Update File: c.txt",
            "@@",
            "-no-such-line",
            "+replacement",
            "*** End Patch",
        ).joinToString("\n")
        try {
            ApplyPatch.apply(patch, resolver())
            fail("expected PatchException")
        } catch (e: ApplyPatch.PatchException) {
            assertTrue(e.message!!.contains("не найден"))
        }
        assertEquals("one\ntwo\nthree\n", File(tmp.root, "c.txt").readText())
    }
}
