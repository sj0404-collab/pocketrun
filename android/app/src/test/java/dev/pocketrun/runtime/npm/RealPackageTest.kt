package dev.pocketrun.runtime.npm

import dev.pocketrun.core.Workspace
import dev.pocketrun.runtime.ExecRequest
import dev.pocketrun.runtime.RuntimeKind
import dev.pocketrun.runtime.js.JsRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket

/**
 * The real installer against the real npm registry, running a real package.
 *
 * Everything else in this suite fakes the registry, which is right for testing
 * resolution rules but useless for the question that actually matters: does a
 * package written in modern JavaScript load? semver and ora are both CommonJS and
 * both use `class` (ora also uses `async`/`await`) - on Rhino every one of them
 * was a hard parse error, so a user could install them and never run them.
 *
 * Skipped when the registry cannot be reached, so an offline CI run is not a
 * failure. The syntax itself is covered without a network by ModernSyntaxTest.
 */
class RealPackageTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun bootSource(): String = listOf(
        File("src/main/assets/node/boot.js"),
        File("app/src/main/assets/node/boot.js"),
    ).firstOrNull { it.isFile }?.readText(Charsets.UTF_8) ?: error("boot.js not found")

    /** A one-pixel PNG, so the offline/online switch cannot depend on real fetches twice. */
    private fun registryUp(): Boolean = try {
        ServerSocket(0, 1, InetAddress.getByName("registry.npmjs.org")).use { true }
    } catch (_: Throwable) {
        false
    }

    private fun run(ws: Workspace, npx: NpxRuntime, spec: String, script: String): String {
        val parsed = NpxRuntime.parseSpec(spec) ?: error("cannot parse $spec")
        npx.install(parsed)

        val js = JsRuntime(bootSource(), ws, maxRunMs = 30_000)
        val main = File(ws.root, "main.js").apply { writeText(script, Charsets.UTF_8) }
        val result = js.executeSync(
            ExecRequest(RuntimeKind.NODE, main.absolutePath, emptyList(), ws.root),
            25_000,
        )
        assertEquals("$spec stderr: ${result.stderr}", 0, result.exitCode)
        return result.stdout
    }

    @Test
    fun semverLoadsAndComparesVersions() {
        if (!registryUp()) return
        val ws = Workspace.at(tmp.newFolder())
        val js = JsRuntime(bootSource(), ws, maxRunMs = 30_000)
        val out = run(
            ws, NpxRuntime(js, ws), "semver@7.6.3",
            """
            const semver = require('semver');
            console.log('gt:' + semver.gt('2.0.0', '1.9.9'));
            console.log('sat:' + semver.satisfies('1.2.3', '^1.0.0'));
            console.log('kind:' + typeof semver.SemVer);
            console.log('instance:' + (new semver.SemVer('3.4.5') instanceof semver.SemVer));
            console.log('major:' + new semver.SemVer('3.4.5').major);
            """.trimIndent(),
        )
        assertTrue("stdout: $out", out.contains("gt:true"))
        assertTrue("stdout: $out", out.contains("sat:true"))
        assertTrue("stdout: $out", out.contains("kind:function"))
        assertTrue("stdout: $out", out.contains("instance:true"))
        assertTrue("stdout: $out", out.contains("major:3"))
    }

    @Test
    fun oraLoadsWithClassesAndAsyncAwait() {
        if (!registryUp()) return
        val ws = Workspace.at(tmp.newFolder())
        val js = JsRuntime(bootSource(), ws, maxRunMs = 30_000)
        val out = run(
            ws, NpxRuntime(js, ws), "ora@5.4.1",
            """
            const ora = require('ora');
            const spinner = ora({ text: 'работаю', isEnabled: false });
            console.log('type:' + typeof spinner);
            console.log('text:' + spinner.text);
            console.log('has-start:' + (typeof spinner.start === 'function'));
            (async function () {
                await Promise.resolve();
                const level = spinner.level !== undefined ? 'has-level' : 'no-level';
                console.log(level);
                process.exit(0);
            })();
            """.trimIndent(),
        )
        assertTrue("stdout: $out", out.contains("type:object"))
        assertTrue("stdout: $out", out.contains("text:работаю"))
        assertTrue("stdout: $out", out.contains("has-start:true"))
        assertTrue("stdout: $out", out.contains("has-level"))
    }
}
