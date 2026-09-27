package dev.pocketrun.agent.opencode

import dev.pocketrun.core.Workspace
import dev.pocketrun.runtime.js.JsRuntime
import dev.pocketrun.runtime.npm.NpxRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The bash tool's mini-shell: built-in commands, quoting, &&, pipes,
 * redirection and the `node` route into the real runtime.
 */
class MiniShellTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun bootSource(): String {
        val candidates = listOf(
            File("src/main/assets/node/boot.js"),
            File("app/src/main/assets/node/boot.js"),
        )
        return candidates.firstOrNull { it.isFile }?.readText(Charsets.UTF_8) ?: error("boot.js not found")
    }

    private fun newShell(workspace: Workspace): MiniShell {
        val js = JsRuntime(bootSource(), workspace, maxRunMs = 15_000)
        return MiniShell(workspace, js, NpxRuntime(js, workspace))
    }

    @Test
    fun echoQuoting() {
        val ws = Workspace.at(tmp.newFolder())
        val sh = newShell(ws)
        val r = sh.execute("echo \"hello  world\"", ws.root)
        assertEquals(0, r.exitCode)
        assertEquals("hello  world", r.output.trim())
    }

    @Test
    fun cdPwdAndLs() {
        val ws = Workspace.at(tmp.newFolder())
        val sh = newShell(ws)
        File(ws.root, "proj/sub").mkdirs()
        File(ws.root, "proj/a.txt").writeText("hi")
        val r = sh.execute("cd proj && ls", ws.root)
        assertEquals(0, r.exitCode)
        assertTrue(r.output.contains("sub"))
        assertTrue(r.output.contains("a.txt"))
    }

    @Test
    fun andChainingStopsOnError() {
        val ws = Workspace.at(tmp.newFolder())
        val sh = newShell(ws)
        val r = sh.execute("true && false && echo never", ws.root)
        assertTrue(r.exitCode != 0)
        assertTrue(!r.output.contains("never"))
    }

    @Test
    fun pipesAndRedirect() {
        val ws = Workspace.at(tmp.newFolder())
        val sh = newShell(ws)
        sh.execute("echo one > f.txt", ws.root)
        sh.execute("echo two >> f.txt", ws.root)
        val cat = sh.execute("cat f.txt", ws.root)
        assertEquals("one\ntwo", cat.output.trim())
        val piped = sh.execute("cat f.txt | grep two | wc -l", ws.root)
        // wc prints "lines words chars"; the piped text is one line "two".
        assertTrue(piped.output.trim().startsWith("1 "))
    }

    @Test
    fun grepCaseInsensitive() {
        val ws = Workspace.at(tmp.newFolder())
        val sh = newShell(ws)
        File(ws.root, "note.txt").writeText("Alpha\nbeta\nGAMMA\n")
        val r = sh.execute("grep -i alpha note.txt", ws.root)
        assertEquals(0, r.exitCode)
        assertTrue(r.output.contains("Alpha"))
        val miss = sh.execute("grep zeta note.txt", ws.root)
        assertTrue(miss.exitCode != 0)
    }

    @Test
    fun findByName() {
        val ws = Workspace.at(tmp.newFolder())
        val sh = newShell(ws)
        File(ws.root, "src/deep").mkdirs()
        File(ws.root, "src/deep/main.py").writeText("print(1)")
        val r = sh.execute("find . -name main.py", ws.root)
        assertEquals(0, r.exitCode)
        assertTrue(r.output.contains("main.py"))
    }

    @Test
    fun mkdirTouchRmRecursive() {
        val ws = Workspace.at(tmp.newFolder())
        val sh = newShell(ws)
        val r1 = sh.execute("mkdir -p a/b/c && touch a/b/c/x.txt && ls a/b/c", ws.root)
        assertEquals(0, r1.exitCode)
        assertTrue(r1.output.contains("x.txt"))
        val r2 = sh.execute("rm -r a && ls", ws.root)
        assertEquals(0, r2.exitCode)
        assertTrue(!r2.output.contains("a"))
    }

    @Test
    fun headTailWc() {
        val ws = Workspace.at(tmp.newFolder())
        val sh = newShell(ws)
        File(ws.root, "n.txt").writeText((1..10).joinToString("\n"))
        assertEquals("1", sh.execute("head -n 1 n.txt", ws.root).output.trim())
        assertEquals("10", sh.execute("tail -n 1 n.txt", ws.root).output.trim())
        assertEquals("2", sh.execute("head -2 n.txt", ws.root).output.trim().lines()[1])
        // wc reports "lines words chars" — a 10-line file has 10 lines.
        assertTrue(sh.execute("wc -l n.txt", ws.root).output.trim().startsWith("10 "))
    }

    @Test
    fun nodeEvaluatesCode() {
        val ws = Workspace.at(tmp.newFolder())
        val sh = newShell(ws)
        val r = sh.execute("node -e 'console.log(6 * 7)'", ws.root)
        assertEquals(0, r.exitCode)
        assertTrue(r.output.contains("42"))
    }

    @Test
    fun unknownCommandFails() {
        val ws = Workspace.at(tmp.newFolder())
        val sh = newShell(ws)
        val r = sh.execute("git status", ws.root)
        assertTrue(r.exitCode != 0)
        assertTrue(r.output.contains("git"))
    }

    @Test
    fun pathEscapeRefused() {
        val ws = Workspace.at(tmp.newFolder())
        val sh = newShell(ws)
        val outside = tmp.newFolder("outside")
        File(outside, "secret.txt").writeText("no")
        val r = sh.execute("cat ../../outside/secret.txt", ws.root)
        // The command must not print the outside file's contents.
        assertTrue(!r.output.contains("no"))
    }
}
