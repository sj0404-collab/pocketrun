package dev.pocketrun.runtime.js

import com.sun.net.httpserver.HttpServer
import dev.pocketrun.core.Workspace
import dev.pocketrun.runtime.ExecRequest
import dev.pocketrun.runtime.RuntimeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetSocketAddress

/**
 * Integration tests for the Node layer: boot.js + JsRuntime + the workspace
 * sandbox, the same combination that ships in the APK. These mirror the
 * desktop harness that validated boot.js during development.
 */
class JsRuntimeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun bootSource(): String {
        val candidates = listOf(
            File("src/main/assets/node/boot.js"), // gradle test working dir: android/app
            File("app/src/main/assets/node/boot.js"), // repo root
        )
        return candidates.firstOrNull { it.isFile }?.readText(Charsets.UTF_8)
            ?: error("boot.js not found relative to ${File(".").absolutePath}")
    }

    private fun newRuntime(workspace: Workspace): JsRuntime =
        JsRuntime(bootSource(), workspace, maxRunMs = 30_000)

    private fun run(workspace: Workspace, script: String, cwd: File? = null): dev.pocketrun.runtime.ExecResult {
        val main = File(cwd ?: workspace.root, "main.js")
        main.writeText(script, Charsets.UTF_8)
        return newRuntime(workspace).executeSync(
            ExecRequest(RuntimeKind.NODE, main.absolutePath, emptyList(), cwd ?: workspace.root),
            20_000,
        )
    }

    // ---------------------------------------------------------------- modules

    @Test
    fun requireRelativeJsonAndPackages() {
        val workspace = Workspace.at(tmp.newFolder())
        File(workspace.root, "proj/lib/util.js").apply { parentFile.mkdirs() }
            .writeText("module.exports = function (x) { return x * 2; };")
        File(workspace.root, "proj/data.json").writeText("""{"answer": 42, "lang": "русский"}""")
        File(workspace.root, "packages/leftpad/package.json").apply { parentFile.mkdirs() }
            .writeText("""{"name":"leftpad","version":"1.0.0","main":"index.js"}""")
        File(workspace.root, "packages/leftpad/index.js")
            .writeText("module.exports.pad = function (s, n) { while (s.length < n) s = ' ' + s; return s; };")

        val result = run(
            workspace,
            """
            var util = require('./proj/lib/util.js');
            var data = require('./proj/data.json');
            var leftpad = require('leftpad');
            console.log(util(21), data.answer, data.lang, leftpad.pad('x', 3).length);
            """.trimIndent(),
        )
        assertEquals(0, result.exitCode)
        assertTrue("stdout: ${result.stdout}", result.stdout.contains("42 42 русский 3"))
    }

    @Test
    fun mainScriptRequiresFromOwnDirectoryNotCwd() {
        // The npx contract: a bin script run with a foreign cwd still finds its siblings.
        val workspace = Workspace.at(tmp.newFolder())
        File(workspace.root, "packages/tool/package.json").apply { parentFile.mkdirs() }
            .writeText("""{"name":"tool","version":"1.0.0","bin":"cli.js"}""")
        File(workspace.root, "packages/tool/cli.js")
            .writeText("console.log('argv=' + JSON.stringify(process.argv.slice(2))); console.log(require('./index.js')());")
        File(workspace.root, "packages/tool/index.js")
            .writeText("module.exports = function () { return 'from-index'; };")

        val result = newRuntime(workspace).executeSync(
            ExecRequest(
                RuntimeKind.NODE,
                File(workspace.root, "packages/tool/cli.js").absolutePath,
                args = listOf("hello", "мир"),
                cwd = workspace.root,
            ),
            20_000,
        )
        assertEquals(0, result.exitCode)
        assertTrue("stdout: ${result.stdout}", result.stdout.contains("""["hello","мир"]"""))
        assertTrue("stdout: ${result.stdout}", result.stdout.contains("from-index"))
    }

    @Test
    fun unknownModuleFailsWithExitCode() {
        val workspace = Workspace.at(tmp.newFolder())
        val result = run(workspace, "require('no-such-package');")
        assertEquals(1, result.exitCode)
        assertTrue("stderr: ${result.stderr}", result.stderr.contains("Cannot find module"))
    }

    // ---------------------------------------------------------------- fs sandbox

    @Test
    fun fsSandboxRejectsEscape() {
        val workspace = Workspace.at(tmp.newFolder())
        val outside = File(tmp.root, "outside.txt")
        val result = run(
            workspace,
            """
            var fs = require('fs');
            var threwRead = false, threwWrite = false;
            try { fs.readFileSync('${workspace.root.absolutePath.replace("\\", "\\\\")}/../../etc/passwd'); }
            catch (e) { threwRead = true; }
            try { fs.writeFileSync('${outside.absolutePath.replace("\\", "\\\\")}', 'x'); }
            catch (e) { threwWrite = true; }
            console.log('read-blocked=' + threwRead, 'write-blocked=' + threwWrite);
            """.trimIndent(),
        )
        assertEquals(0, result.exitCode)
        assertTrue("stdout: ${result.stdout}", result.stdout.contains("read-blocked=true write-blocked=true"))
        assertTrue(outside.isFile.not())
    }

    @Test
    fun fsWriteReadWithCyrillic() {
        val workspace = Workspace.at(tmp.newFolder())
        val result = run(
            workspace,
            """
            var fs = require('fs');
            fs.writeFileSync('tmp/привет.txt', 'содержимое — кириллица');
            console.log(fs.readFileSync('tmp/привет.txt', 'utf8'));
            var st = fs.statSync('tmp/привет.txt');
            console.log('size=' + st.size);
            """.trimIndent(),
        )
        assertEquals(0, result.exitCode)
        assertTrue("stdout: ${result.stdout}", result.stdout.contains("содержимое — кириллица"))
        // 23 chars: 20 cyrillic (2 bytes each) + ' — ' … counted by bytes, just check it is there
        assertTrue("stdout: ${result.stdout}", result.stdout.contains("size="))
    }

    // ---------------------------------------------------------------- timers / exit

    @Test
    fun timersRunInDueOrderAfterMainScript() {
        val workspace = Workspace.at(tmp.newFolder())
        val result = run(
            workspace,
            """
            var order = [];
            process.nextTick(function () { order.push('tick'); });
            setImmediate(function () { order.push('immediate'); });
            setTimeout(function () { order.push('t10'); }, 10);
            setTimeout(function () { order.push('t5'); }, 5);
            setInterval(function () { order.push('boom'); }, 50);
            setTimeout(function () { console.log('RESULT ' + order.join(',')); process.exit(7); }, 40);
            """.trimIndent(),
        )
        assertEquals("stderr: ${result.stderr}", 7, result.exitCode)
        val out = result.stdout.trim()
        assertTrue("stdout: $out", out.contains("RESULT tick,immediate,t5,t10"))
        // the 50ms interval must not fire before the 40ms exit timer
        assertTrue("interval fired too early: $out", !out.contains("boom"))
    }

    @Test
    fun exitHandlersRunOnExit() {
        val workspace = Workspace.at(tmp.newFolder())
        val result = run(
            workspace,
            """
            process.on('exit', function (code) { console.log('bye code=' + code); });
            console.log('main done');
            """.trimIndent(),
        )
        assertEquals(0, result.exitCode)
        assertTrue("stdout: ${result.stdout}", result.stdout.contains("main done"))
        assertTrue("stdout: ${result.stdout}", result.stdout.contains("bye code=0"))
    }

    // ---------------------------------------------------------------- http

    @Test
    fun httpGetAndPostAgainstLocalServer() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/hello") { exchange ->
            val body = "hi ${exchange.requestMethod} ${exchange.requestURI}"
            exchange.sendResponseHeaders(200, body.toByteArray(Charsets.UTF_8).size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        server.createContext("/echo") { exchange ->
            val text = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            val body = "echo:$text"
            exchange.sendResponseHeaders(201, body.toByteArray(Charsets.UTF_8).size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        server.start()
        try {
            val port = server.address.port
            val workspace = Workspace.at(tmp.newFolder())
            val result = run(
                workspace,
                """
                var http = require('http');
                http.get('http://127.0.0.1:$port/hello', function (res) {
                    var body = '';
                    res.on('data', function (c) { body += c.toString(); });
                    res.on('end', function () {
                        console.log('status=' + res.statusCode + ' body=' + body);
                        var req = http.request(
                            { host: '127.0.0.1', port: $port, path: '/echo', method: 'POST' },
                            function (res2) {
                                var b2 = '';
                                res2.on('data', function (c) { b2 += c.toString(); });
                                res2.on('end', function () {
                                    console.log('post=' + res2.statusCode + ' ' + b2);
                                });
                            },
                        );
                        req.end('привет-POST');
                    });
                });
                """.trimIndent(),
            )
            assertEquals(0, result.exitCode)
            assertTrue("stdout: ${result.stdout}", result.stdout.contains("status=200 body=hi GET /hello"))
            assertTrue("stdout: ${result.stdout}", result.stdout.contains("post=201 echo:привет-POST"))
        } finally {
            server.stop(0)
        }
    }

    // ---------------------------------------------------------------- buffer / api surface

    @Test
    fun bufferEncodings() {
        val workspace = Workspace.at(tmp.newFolder())
        val result = run(
            workspace,
            """
            var b = Buffer.from('привет hi!', 'utf8');
            console.log(b.toString('base64'));
            console.log(Buffer.from(b.toString('base64'), 'base64').toString('utf8'));
            console.log(Buffer.from('6869', 'hex').toString('utf8'));
            console.log('len=' + Buffer.byteLength('привет'));
            """.trimIndent(),
        )
        assertEquals(0, result.exitCode)
        val lines = result.stdout.trim().lines()
        assertEquals(4, lines.size)
        assertEquals("привет hi!", java.util.Base64.getDecoder().decode(lines[0]).toString(Charsets.UTF_8))
        assertEquals("привет hi!", lines[1])
        assertEquals("hi", lines[2])
        assertEquals("len=12", lines[3])
    }

    @Test
    fun runtimeErrorReportsLineAndExitCode() {
        val workspace = Workspace.at(tmp.newFolder())
        val result = run(
            workspace,
            """
            console.log('before');
            nope();
            """.trimIndent(),
        )
        assertEquals(1, result.exitCode)
        assertTrue("stderr: ${result.stderr}", result.stderr.contains("ERROR at line"))
        assertTrue("stdout: ${result.stdout}", result.stdout.contains("before"))
    }
}
