package dev.pocketrun.agent.opencode

import dev.pocketrun.core.Workspace
import dev.pocketrun.runtime.js.JsRuntime
import dev.pocketrun.runtime.npm.NpxRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Reading a file longer than one tool result.
 *
 * The 60 KB test file used to come back cut at 32 KB with "обрезано" and nothing
 * else, so the model believed it had reached the end of the file and never asked
 * for more. Every page has to say where it is, how much is left and what offset
 * to pass next - otherwise paging is guesswork.
 */
class OpenCodeToolsReadTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun bootSource(): String {
        val candidates = listOf(
            File("src/main/assets/node/boot.js"),
            File("app/src/main/assets/node/boot.js"),
        )
        return candidates.firstOrNull { it.isFile }?.readText(Charsets.UTF_8) ?: error("boot.js not found")
    }

    private class Harness(val tools: OpenCodeTools, val projectDir: File)

    private fun newTools(): Harness {
        val ws = Workspace.at(tmp.newFolder())
        val project = File(ws.root, "proj").apply { mkdirs() }
        val js = JsRuntime(bootSource(), ws, maxRunMs = 10_000)
        return Harness(OpenCodeTools(ws, js, NpxRuntime(js, ws), project), project)
    }

    @Test
    fun shortFileIsReturnedWholeAndSaysItIsTheEnd() {
        val h = newTools()
        File(h.projectDir, "small.txt").writeText("alpha\nbeta\n")

        val out = h.tools.execute("read", """{"filePath":"small.txt"}""")

        assertTrue(out, out.contains("alpha"))
        assertTrue(out, out.contains("beta"))
        assertTrue(out, out.contains("это конец файла"))
    }

    @Test
    fun longFileIsPagedAndTheNextOffsetIsGiven() {
        val h = newTools()
        // Comfortably past the 32 KB a single result may carry.
        val lines = (1..3000).map { "line $it ${"x".repeat(20)}" }
        File(h.projectDir, "long.txt").writeText(lines.joinToString("\n"))

        val first = h.tools.execute("read", """{"filePath":"long.txt"}""")

        assertTrue("the file size must be stated", first.contains("3000 строк"))
        assertFalse("a cut page must not claim to be the end", first.contains("это конец файла"))
        assertTrue("the continuation must be spelled out", first.contains("offset="))
        assertTrue(first, first.contains("line 1 "))

        val next = Regex("""offset=(\d+)""").find(first)!!.groupValues[1].toInt()
        assertTrue("offset must point past what was returned", next > 1)

        val second = h.tools.execute("read", """{"filePath":"long.txt","offset":$next}""")
        assertTrue(second, second.contains("line $next "))
    }

    @Test
    fun pagingThroughTheWholeFileReachesEveryLine() {
        val h = newTools()
        val lines = (1..2000).map { "row $it ${"y".repeat(30)}" }
        File(h.projectDir, "all.txt").writeText(lines.joinToString("\n"))

        var offset = 1
        var guard = 0
        val seen = StringBuilder()
        while (guard++ < 50) {
            val page = h.tools.execute("read", """{"filePath":"all.txt","offset":$offset}""")
            seen.append(page).append('\n')
            if (page.contains("это конец файла")) break
            val m = Regex("""offset=(\d+)""").find(page) ?: break
            offset = m.groupValues[1].toInt()
        }

        // Every line of the file must have been handed over, not just the head.
        assertTrue("row 1 missing", seen.contains("row 1 "))
        assertTrue("last row missing", seen.contains("row 2000 "))
        assertTrue("paging gave up early", seen.contains("это конец файла"))
    }

    @Test
    fun explicitLimitIsHonouredAndStillReportsWhatFollows() {
        val h = newTools()
        File(h.projectDir, "many.txt").writeText((1..500).joinToString("\n") { "n $it" })

        val out = h.tools.execute("read", """{"filePath":"many.txt","limit":10}""")

        assertTrue(out, out.contains("n 1"))
        assertTrue(out, out.contains("n 10"))
        assertFalse("the limit was not applied", out.contains("n 11 "))
        assertFalse(out, out.contains("это конец файла"))
        assertTrue(out, out.contains("490"))
    }

    @Test
    fun aSingleLineLongerThanTheBudgetIsCutAndSaysSo() {
        val h = newTools()
        File(h.projectDir, "long-line.txt").writeText("z".repeat(80_000) + "\ntail\n")

        val out = h.tools.execute("read", """{"filePath":"long-line.txt"}""")

        assertTrue("the cut must be announced", out.contains("обрезана"))
        assertTrue("the next page must be offered", out.contains("offset=2"))
    }

    @Test
    fun binaryIsRefusedInsteadOfMangled() {
        val h = newTools()
        File(h.projectDir, "blob.bin").writeBytes(byteArrayOf(1, 2, 0, 3, 4, 5))

        val out = h.tools.execute("read", """{"filePath":"blob.bin"}""")

        assertTrue(out, out.contains("двоичный"))
    }

    @Test
    fun emptyFileIsNotAnError() {
        val h = newTools()
        File(h.projectDir, "empty.txt").writeText("")

        assertEquals("(пусто)", h.tools.execute("read", """{"filePath":"empty.txt"}""").substringAfter("---\n"))
    }
}
