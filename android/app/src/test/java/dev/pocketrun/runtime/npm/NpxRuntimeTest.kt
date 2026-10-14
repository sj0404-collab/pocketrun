package dev.pocketrun.runtime.npm

import dev.pocketrun.core.Workspace
import dev.pocketrun.runtime.ExecRequest
import dev.pocketrun.runtime.RuntimeKind
import dev.pocketrun.runtime.js.JsRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * npm/npx pieces that need no network: spec parsing, bin resolution and the
 * run path over an already-installed package (the same path a cached install
 * takes after the first download).
 */
class NpxRuntimeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun bootSource(): String {
        val candidates = listOf(
            File("src/main/assets/node/boot.js"),
            File("app/src/main/assets/node/boot.js"),
        )
        return candidates.firstOrNull { it.isFile }?.readText(Charsets.UTF_8)
            ?: error("boot.js not found")
    }

    private fun newNpx(workspace: Workspace): Pair<JsRuntime, NpxRuntime> {
        val js = JsRuntime(bootSource(), workspace, maxRunMs = 30_000)
        return js to NpxRuntime(js, workspace)
    }

    @Test
    fun parseSpecVariants() {
        assertEquals(NpxRuntime.PackageSpec("cowsay", null), NpxRuntime.parseSpec("cowsay"))
        assertEquals(NpxRuntime.PackageSpec("cowsay", "1.6.0"), NpxRuntime.parseSpec("cowsay@1.6.0"))
        assertEquals(NpxRuntime.PackageSpec("cowsay", "^1.6"), NpxRuntime.parseSpec("cowsay@^1.6"))
        assertEquals(NpxRuntime.PackageSpec("@scope/pkg", null), NpxRuntime.parseSpec("@scope/pkg"))
        assertEquals(NpxRuntime.PackageSpec("@scope/pkg", "2.1.0"), NpxRuntime.parseSpec("@scope/pkg@2.1.0"))
        assertNull(NpxRuntime.parseSpec("not a package!"))
        assertNull(NpxRuntime.parseSpec(""))
        assertNull(NpxRuntime.parseSpec("@broken"))
    }

    @Test
    fun parseCommandLineWithArgs() {
        val (spec, args) = NpxRuntime.parseCommandLine("""cowsay -f tux 'hello мир'""")!!
        assertEquals("cowsay", spec.name)
        assertNull(spec.version)
        assertEquals(listOf("-f", "tux", "hello мир"), args)

        val (spec2, args2) = NpxRuntime.parseCommandLine("semver@7.6.0 -h")!!
        assertEquals("semver", spec2.name)
        assertEquals("7.6.0", spec2.version)
        assertEquals(listOf("-h"), args2)
    }

    @Test
    fun resolveBinPrefersNameMatchThenFirst() {
        val workspace = Workspace.at(tmp.newFolder())
        val (_, npx) = newNpx(workspace)
        val pkg = File(workspace.root, "packages/tool").apply { mkdirs() }
        File(pkg, "package.json").writeText(
            """{"name":"@scope/tool","bin":{"other":"o.js","tool":"t.js"}}""",
        )
        File(pkg, "t.js").writeText("// t")
        File(pkg, "o.js").writeText("// o")

        val bin = npx.resolveBin(pkg, "@scope/tool")
        assertNotNull(bin)
        assertEquals("t.js", bin!!.name)
    }

    @Test
    fun runsInstalledPackageBinWithArgs() {
        val workspace = Workspace.at(tmp.newFolder())
        val (js, npx) = newNpx(workspace)

        // A fake installed package: main + bin + a nested dep to prove the walk.
        val pkg = File(workspace.root, "packages/greeter").apply { mkdirs() }
        File(pkg, "package.json").writeText(
            """{"name":"greeter","version":"1.0.0","bin":"bin/greet.js"}""",
        )
        File(pkg, "bin").mkdirs()
        File(pkg, "bin/greet.js").writeText(
            """
            var greet = require('../lib/greet.js');
            console.log(greet(process.argv.slice(2).join(' ')));
            """.trimIndent(),
        )
        File(pkg, "lib").mkdirs()
        File(pkg, "lib/greet.js").writeText(
            "module.exports = function (name) { return 'Привет, ' + (name || 'мир') + '!'; };",
        )

        val result = npx.executeSync(
            ExecRequest(
                RuntimeKind.NPX,
                "greeter",
                args = listOf("PocketRun"),
                cwd = workspace.root,
            ),
            20_000,
        )
        assertEquals("stdout: ${result.stdout}\nstderr: ${result.stderr}", 0, result.exitCode)
        assertTrue("stdout: ${result.stdout}", result.stdout.contains("Привет, PocketRun!"))
    }

    @Test
    fun installedPackagesList() {
        val workspace = Workspace.at(tmp.newFolder())
        val (_, npx) = newNpx(workspace)
        val pkg = File(workspace.root, "packages/alpha").apply { mkdirs() }
        File(pkg, "package.json").writeText("""{"name":"alpha","version":"0.1.2"}""")

        val list = npx.installedPackages()
        assertEquals(1, list.size)
        assertEquals("alpha" to "0.1.2", list[0])
    }

    @Test
    fun chooseVersionPicksDistTagExactRangeMax() {
        val meta = org.json.JSONObject(
            """
            {
              "dist-tags": {"latest": "2.0.0", "beta": "3.0.0-beta.1"},
              "versions": {
                "1.0.0": {}, "1.2.3": {}, "1.10.0": {}, "2.0.0": {},
                "3.0.0-beta.1": {}, "9.9.9-pre": {}
              }
            }
            """.trimIndent(),
        )
        val workspace = Workspace.at(tmp.newFolder())
        val (_, npx) = newNpx(workspace)
        assertEquals("2.0.0", npx.chooseVersion(meta, null))
        assertEquals("2.0.0", npx.chooseVersion(meta, "latest"))
        assertEquals("1.10.0", npx.chooseVersion(meta, "^1.0.0")) // numeric ordering, not lexicographic
        assertEquals("1.2.3", npx.chooseVersion(meta, "1.2.3"))
        assertEquals("3.0.0-beta.1", npx.chooseVersion(meta, "beta"))
        assertEquals("2.0.0", npx.chooseVersion(meta, ">=1.2.3 <3.0.0"))
        assertNull(npx.chooseVersion(meta, "^5.0.0"))
    }
}
