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
 * The commands the sandbox shell added.
 *
 * Every case here is a filter an agent reaches for constantly. Where a command is
 * only a subset of the real one it says so and exits non-zero, because a filter
 * that quietly does something else is worse than a missing one - the model moves
 * on believing the edit landed.
 */
class ShellToolsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun bootSource(): String = listOf(
        File("src/main/assets/node/boot.js"),
        File("app/src/main/assets/node/boot.js"),
    ).firstOrNull { it.isFile }?.readText(Charsets.UTF_8) ?: error("boot.js not found")

    private class Harness(val shell: MiniShell, val dir: File)

    private fun newHarness(): Harness {
        val ws = Workspace.at(tmp.newFolder())
        val dir = File(ws.root, "proj").apply { mkdirs() }
        val js = JsRuntime(bootSource(), ws, maxRunMs = 10_000)
        return Harness(MiniShell(ws, js, NpxRuntime(js, ws)), dir)
    }

    private fun sh(command: String, setup: (File) -> Unit = {}): MiniShell.Result {
        val h = newHarness()
        setup(h.dir)
        return h.shell.execute(command, h.dir, 20_000)
    }

    /** Creates [name] inside the project folder; the setup lambda gets that folder. */
    private fun write(dir: File, name: String, content: String): File =
        File(dir, name).apply { parentFile.mkdirs(); writeText(content) }

    private fun ok(command: String, setup: (File) -> Unit = {}): String {
        val r = sh(command, setup)
        assertEquals("'$command' -> ${r.output}", 0, r.exitCode)
        return r.output
    }

    // ---------------------------------------------------------------- sort / uniq / cut

    @Test
    fun sortNumericReverseAndUnique() {
        assertEquals("1\n2\n10\n", ok("printf '10\\n2\\n1\\n' | sort -n"))
        assertEquals("10\n2\n1\n", ok("printf '1\\n2\\n10\\n' | sort -nr"))
        assertEquals("a\nb\nc\n", ok("printf 'b\\na\\nb\\nc\\n' | sort -u"))
        // -k1,1 sorts on the first word, so "b b" files stay together.
        assertEquals("a x\nb y\nb z\n", ok("printf 'b z\\na x\\nb y\\n' | sort -u -k1,1"))
        assertEquals("a\nB\nc\n", ok("printf 'a\\nB\\nc\\n' | sort -f"))
    }

    @Test
    fun uniqCountsAndFilters() {
        assertEquals("      3 a\n      1 b\n", ok("printf 'a\\na\\na\\nb\\n' | uniq -c"))
        assertEquals("a\n", ok("printf 'a\\na\\nb\\n' | uniq -d"))
        assertEquals("b\n", ok("printf 'a\\na\\nb\\n' | uniq -u"))
        // -i folds case for comparison but keeps the first line of the run.
        assertEquals("a\n", ok("printf 'a\\nA\\n' | uniq -i"))
    }

    @Test
    fun cutSelectsFieldsAndCharacters() {
        assertEquals("a\nb\n", ok("printf 'a:x:1\\nb:y:2\\n' | cut -d: -f1"))
        assertEquals("x\na\n", ok("printf 'abc\\ndef\\n' | cut -c2"))
        assertEquals("a:1\nb:2\n", ok("printf 'a:x:1\\nb:y:2\\n' | cut -d: -f1,3"))
        assertEquals("x:1\n", ok("printf 'a:x:1:9\\n' | cut -d: -f2-3"))
        // An open range runs to the end of the line.
        assertEquals("x:1:9\n", ok("printf 'a:x:1:9\\n' | cut -d: -f2-"))
    }

    // ---------------------------------------------------------------- tr / seq / printf

    @Test
    fun trTranslatesDeletesAndSqueezes() {
        assertEquals("bcd\n", ok("echo abcccd | tr -d a"))
        assertEquals("bcccd\n", ok("echo abcccd | tr a b"))
        assertEquals("abc\n", ok("echo aaa bbb ccc | tr -s ' '"))
        assertEquals("b\n", ok("echo ab | tr a-z b"))
    }

    @Test
    fun seqAndPrintf() {
        assertEquals("1\n2\n3\n", ok("seq 3"))
        assertEquals("2\n4\n6\n", ok("seq 2 2 6"))
        assertEquals("0\n1\n2\n", ok("seq 0 2"))
        assertEquals("a-b-c\n", ok("printf '%s-%s-%s\\n' a b c"))
        assertEquals("   7|\n", ok("printf '%4d|\\n' 7"))
        assertEquals("line\n", ok("printf 'line\\n'"))
        assertEquals("3.14\n", ok("printf '%.2f\\n' 3.14159"))
    }

    // ---------------------------------------------------------------- sed

    @Test
    fun sedSubstitutesDeletesAndTransliterates() {
        assertEquals("a-b-c\n", ok("echo a_b_c | sed 's/_/-/g'"))
        assertEquals("only\n", ok("printf 'one\\ntwo\\n' | sed '/two/d'"))
        assertEquals("one\ntwo\n", ok("echo one two | sed 's/[0-9]//g'"))
        assertEquals("Abc\n", ok("echo abc | sed 'y/abc/ABC/'"))
        assertEquals("1\n", ok("printf 'a\\nb\\n' | sed -n '1p'"))
    }

    @Test
    fun sedInPlaceEditsTheFile() {
        val h = newHarness()
        val f = write(h.dir, "f.txt", "old old\n")
        val r = h.shell.execute("sed -i 's/old/new/g' f.txt", h.dir, 20_000)
        assertEquals(0, r.exitCode)
        assertEquals("new new\n", f.readText())
    }

    @Test
    fun sedRefusesSyntaxItCannotHonour() {
        // A hold-space script that was accepted but silently ignored would be the
        // worst outcome, so it is an error.
        val r = sh("echo x | sed 'H'")
        assertEquals(1, r.exitCode)
        assertTrue(r.output, r.output.contains("неподдерживаемый"))
    }

    // ---------------------------------------------------------------- awk

    @Test
    fun awkPrintsFieldsAndMatchesPatterns() {
        assertEquals("a b\n", ok("printf 'a b\\nc d\\n' | awk '{print $1, $2}'"))
        assertEquals("2\n", ok("printf 'a b\\nc d\\n' | awk '{print NF}'"))
        assertEquals("c\n", ok("printf 'a b\\nc d\\n' | awk '$1 == \"c\" {print $1}'"))
        assertEquals("a b\nc d\n", ok("printf 'a b\\nc d\\n' | awk '{print}'"))
        assertEquals("b\n", ok("printf 'a b\\nc d\\n' | awk '$2 == \"b\" {print $2}'"))
        assertEquals("2\n", ok("printf 'x\\ny\\n' | awk 'END-like'") .let { "" })
    }

    @Test
    fun awkUsesTheFieldSeparatorFlag() {
        assertEquals("1|2\n", ok("printf 'a:1\\nb:2\\n' | awk -F: '{print $2 \"|\" $1}'"))
    }

    // ---------------------------------------------------------------- grep-adjacent helpers

    @Test
    fun nlFoldsAndExpands() {
        assertTrue(ok("printf 'a\\nb\\n' | nl").contains("1\ta"))
        assertEquals("ab\ncd\n", ok("echo abcd | fold -w2"))
        assertEquals("a b\n", ok("printf 'a\\tb\\n' | expand"))
    }

    @Test
    fun teeWritesAndPassesThrough() {
        val h = newHarness()
        val r = h.shell.execute("echo hi | tee copy.txt", h.dir, 20_000)
        assertEquals(0, r.exitCode)
        assertEquals("hi\n", r.output)
        assertEquals("hi\n", File(h.dir, "copy.txt").readText())
    }

    // ---------------------------------------------------------------- diff

    @Test
    fun diffReportsDifferences() {
        val setup: (File) -> Unit = { dir ->
            write(dir, "a.txt", "1\n2\n3\n")
            write(dir, "b.txt", "1\n9\n3\n")
        }
        val r = sh("diff a.txt b.txt", setup)
        assertEquals(1, r.exitCode)
        assertTrue(r.output, r.output.contains("-2"))
        assertTrue(r.output, r.output.contains("+9"))
        assertEquals(0, sh("diff a.txt a.txt", setup).exitCode)
    }

    // ---------------------------------------------------------------- jq

    @Test
    fun jqExtractsValues() {
        val json = """{"name":"папка","items":[{"id":1},{"id":2}],"n":7}"""
        val setup: (File) -> Unit = { write(it, "d.json", json) }
        assertEquals("папка", ok("jq -r '.name' d.json", setup).trim())
        assertEquals("7", ok("jq -r '.n' d.json", setup).trim())
        assertEquals("1", ok("jq -r '.items[0].id' d.json", setup).trim())
        assertEquals("name items n", ok("jq -r 'keys | join(\" \")' d.json", setup).trim())
        assertEquals("3", ok("jq -r '. | length' d.json", setup).trim())
        assertEquals("2", ok("jq -r '.items | length' d.json", setup).trim())
    }

    @Test
    fun jqReportsBadInputInsteadOfReturningNothing() {
        val broken = sh("jq -r '.a' d.json") { write(it, "d.json", "{not json") }
        assertEquals(4, broken.exitCode)
        assertTrue(broken.output, broken.output.contains("JSON"))

        val unsupported = sh("jq '.a | select(.b > 1)' d.json") { write(it, "d.json", """{"a":1}""") }
        assertEquals(3, unsupported.exitCode)
    }

    // ---------------------------------------------------------------- system tools

    @Test
    fun statReportsSizeAndType() {
        val setup: (File) -> Unit = { write(it, "f.txt", "hello") }
        val r = ok("stat f.txt", setup)
        assertTrue(r, r.contains("regular file"))
        assertTrue(r, r.contains("5"))
        assertEquals("5", ok("stat --format=%s f.txt", setup).trim())
        assertTrue(ok("stat --format=%F d") { File(it, "d").mkdirs() }.contains("directory"))
    }

    @Test
    fun duAndDfAndTree() {
        assertTrue(ok("du -s d") { write(it, "d/x", "12345") }.contains("5"))
        assertTrue(ok("df").contains("Доступно"))
        val tree = ok("tree d") {
            write(it, "d/a.txt", "a")
            File(it, "d/sub").mkdirs()
        }
        assertTrue(tree, tree.contains("a.txt"))
        assertTrue(tree, tree.contains("sub"))
        assertTrue(tree, tree.contains("1 directories"))
    }

    @Test
    fun fileRecognisesCommonTypes() {
        assertTrue(ok("file a.js") { write(it, "a.js", "var a = 1") }.contains("source text"))
        assertTrue(ok("file a.png") { write(it, "a.png", "x") }.contains("image data"))
        assertTrue(ok("file a.zip") { write(it, "a.zip", "x") }.contains("archive data"))
        assertTrue(ok("file a.txt") { write(it, "a.txt", "") }.contains("empty"))
        assertTrue(ok("file d") { File(it, "d").mkdirs() }.contains("directory"))
    }

    @Test
    fun checksumsMatchKnownValues() {
        // echo -n "abc" | sha256sum
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            ok("echo -n abc | sha256sum").trim().substringBefore(" "),
        )
        assertEquals("900150983cd24fb0d6963f7d28e17f72", ok("echo -n abc | md5sum").trim().substringBefore(" "))
        assertTrue(ok("sha256sum f.txt") { write(it, "f.txt", "abc") }.startsWith("ba7816bf"))
    }

    @Test
    fun base64RoundTrips() {
        assertEquals("YWJj\n", ok("echo -n abc | base64"))
        assertEquals("abc", ok("echo YWJj | base64 -d").trim())
    }

    @Test
    fun xxdShowsBytes() {
        val r = ok("echo -n abc | xxd")
        assertTrue(r, r.contains("61 62 63"))
        assertTrue(r, r.contains("abc"))
    }

    @Test
    fun testPredicateWorks() {
        assertEquals(0, sh("test -f f.txt") { write(it, "f.txt", "x") }.exitCode)
        assertEquals(1, sh("test -f missing.txt").exitCode)
        assertEquals(0, sh("test -d d") { File(it, "d").mkdirs() }.exitCode)
        assertEquals(0, sh("test 5 -gt 3").exitCode)
        assertEquals(1, sh("test 3 -gt 5").exitCode)
        assertEquals(0, sh("test -z ''").exitCode)
        assertEquals(0, sh("[ -f f.txt ]") { write(it, "f.txt", "x") }.exitCode)
    }

    @Test
    fun realpathAndBasenameAndDirname() {
        assertTrue(ok("realpath f.txt") { write(it, "f.txt", "x") }.contains("f.txt"))
        assertEquals("a.txt\n", ok("basename /x/y/a.txt"))
        assertEquals("a.txt\n", ok("basename /x/y/a.txt .txt"))
        assertEquals("/x/y\n", ok("dirname /x/y/a.txt"))
        assertEquals(".\n", ok("dirname a.txt"))
    }

    // ---------------------------------------------------------------- git

    @Test
    fun gitRecordsAndReportsHistory() {
        val h = newHarness()
        val dir = h.dir
        fun run(c: String) = h.shell.execute(c, dir, 20_000)

        assertEquals(0, run("git init").exitCode)
        File(dir, "f.txt").writeText("one\n")
        assertEquals(0, run("git add f.txt").exitCode)
        assertTrue(run("git status").output.contains("Новые файлы"))
        val c1 = run("git commit -m 'первый'")
        assertEquals(0, c1.exitCode)
        assertTrue(c1.output, c1.output.contains("первый"))

        File(dir, "f.txt").writeText("two\n")
        assertTrue(run("git diff").output.contains("-one"))
        assertTrue(run("git diff").output.contains("+two"))

        assertEquals(0, run("git commit -am 'второй'").exitCode)
        val log = run("git log").output
        assertTrue(log, log.contains("второй"))
        assertTrue(log, log.contains("первый"))

        assertEquals(0, run("git checkout -b ветка").exitCode)
        assertTrue(run("git branch").output.contains("ветка"))
    }

    @Test
    fun gitRestoreBringsBackTheCommittedVersion() {
        val h = newHarness()
        val dir = h.dir
        fun run(c: String) = h.shell.execute(c, dir, 20_000)

        run("git init")
        File(dir, "f.txt").writeText("v1\n")
        run("git add f.txt")
        run("git commit -m one")

        // Committed is v1; the working tree drifted to v2 and restore must undo
        // exactly that, which means the committed content has to have been stored
        // rather than merely digested.
        File(dir, "f.txt").writeText("v2\n")
        assertTrue(run("git status").output.contains("Изменённые"))
        assertEquals(0, run("git restore f.txt").exitCode)
        assertEquals("v1\n", File(dir, "f.txt").readText())
    }

    @Test
    fun gitRefusesCommandsItCannotHonour() {
        val r = sh("git push")
        assertEquals(129, r.exitCode)
        assertTrue(r.output, r.output.contains("github"))
    }

    @Test
    fun gitOutsideARepositorySaysSo() {
        val r = sh("git status")
        assertEquals(128, r.exitCode)
        assertTrue(r.output, r.output.contains("не git-репозиторий"))
    }

    // ---------------------------------------------------------------- sandbox

    @Test
    fun newToolsCannotEscapeTheWorkspace() {
        val h = newHarness()
        val escape = File(h.dir, "../../escape.txt").canonicalFile

        // Reading outside the workspace is refused.
        assertEquals(1, h.shell.execute("cat ../../../../etc/passwd", h.dir, 20_000).exitCode)

        // So is writing: the redirect target is sandboxed, and a resolved path
        // that escapes is refused by the resolver rather than created.
        h.shell.execute("echo hi | tee ../../escape.txt", h.dir, 20_000)
        assertFalse("nothing may be written outside the workspace", escape.exists())

        // Writing inside it still works, so the refusal is not just everything failing.
        assertEquals(0, h.shell.execute("echo hi | tee inside.txt", h.dir, 20_000).exitCode)
        assertTrue(File(h.dir, "inside.txt").isFile)

        // git is sandboxed too: a pathspec that leaves the project is refused.
        run2(h, "git init")
        assertEquals(1, run2(h, "git add ../../outside.txt").exitCode)
    }

    private fun run2(h: Harness, command: String): MiniShell.Result =
        h.shell.execute(command, h.dir, 20_000)

    @Test
    fun helpListsTheNewCommands() {
        val help = ok("help")
        for (cmd in listOf("git", "curl", "jq", "sed", "awk", "sort", "stat", "tree", "du", "sha256sum")) {
            assertTrue("help should mention $cmd", help.contains(cmd))
        }
    }

    @Test
    fun unknownCommandStillReportsTheList() {
        val r = sh("definitely-not-a-command")
        assertEquals(127, r.exitCode)
        assertTrue(r.output, r.output.contains("git"))
    }
}
