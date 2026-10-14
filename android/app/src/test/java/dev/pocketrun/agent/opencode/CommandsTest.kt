package dev.pocketrun.agent.opencode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Custom-command parsing and template expansion (`$ARGUMENTS`, `$1`, `@file`). */
class CommandsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun command(template: String) =
        OpenCodeConfig.CommandDef("t", "", template, null, null, false)

    @Test
    fun parseSplitsNameAndArgs() {
        assertEquals("name" to "a b c", Commands.parse("/name a b c"))
        assertEquals("name" to "", Commands.parse("/name"))
        assertNull(Commands.parse("just text"))
        assertNull(Commands.parse("/"))
    }

    @Test
    fun argumentsAndPositional() {
        assertEquals("a b c", Commands.expand(command("\$1 \$2 \$3"), "a b c", null))
        assertEquals("say: hello world", Commands.expand(command("say: \$ARGUMENTS"), "hello world", null))
        assertEquals("x", Commands.expand(command("\$1 \$2 \$3"), "x", null))
    }

    @Test
    fun fileMentionIsInlined() {
        val dir = tmp.newFolder()
        File(dir, "a.txt").writeText("FILE-BODY")
        val out = Commands.expand(command("See @a.txt now"), "", dir)
        assertTrue(out.contains("FILE-BODY"))
        assertTrue(out.contains("```a.txt"))
    }

    @Test
    fun missingFileMentionIsLeftAlone() {
        val dir = tmp.newFolder()
        val out = Commands.expand(command("See @missing.txt"), "", dir)
        assertTrue(out.contains("@missing.txt"))
    }
}
