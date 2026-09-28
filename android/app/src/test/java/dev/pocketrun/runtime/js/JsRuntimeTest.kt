package dev.pocketrun.runtime.js

import dev.pocketrun.core.Workspace
import dev.pocketrun.runtime.ExecRequest
import dev.pocketrun.runtime.RuntimeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

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
            // The delays are far apart on purpose: a timer is due "now + delay",
            // so a slow machine that takes longer than the smaller delay to
            // execute this script legitimately runs the bigger one first, and
            // the assertion below would be testing the load, not the runtime.
            """
            var order = [];
            process.nextTick(function () { order.push('tick'); });
            setImmediate(function () { order.push('immediate'); });
            setTimeout(function () { order.push('t20'); }, 20);
            setTimeout(function () { order.push('t200'); }, 200);
            setInterval(function () { order.push('boom'); }, 400);
            setTimeout(function () { console.log('RESULT ' + order.join(',')); process.exit(7); }, 300);
            """.trimIndent(),
        )
        assertEquals("stderr: ${result.stderr}", 7, result.exitCode)
        val out = result.stdout.trim()
        assertTrue("stdout: $out", out.contains("RESULT tick,immediate,t20,t200"))
        // the 400ms interval must not fire before the 300ms exit timer
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

    /**
     * Two-endpoint HTTP server on a raw ServerSocket: com.sun.net.httpserver
     * is a JDK class and Android unit tests compile against android.jar.
     * GET /hello → "hi <method> <path>" (200); POST /echo → "echo:<body>" (201).
     */
    private class MiniHttp {
        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val threads = mutableListOf<Thread>()
        private val acceptor = Thread { acceptLoop() }

        fun start() { acceptor.isDaemon = true; acceptor.start() }
        fun stop() { runCatching { server.close() } }
        val port: Int get() = server.localPort

        private fun acceptLoop() {
            while (true) {
                val client = try { server.accept() } catch (e: Exception) { break }
                val t = Thread { handle(client) }
                t.isDaemon = true
                threads += t
                t.start()
            }
        }

        private fun handle(client: Socket) {
            client.use { socket ->
                socket.soTimeout = 10_000
                val input = BufferedInputStream(socket.getInputStream())
                val requestLine = readLine(input) ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0]
                val path = parts[1].substringBefore('?')
                var contentLength = 0
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    val idx = line.indexOf(':')
                    if (idx > 0 && line.substring(0, idx).equals("Content-Length", ignoreCase = true)) {
                        contentLength = line.substring(idx + 1).trim().toIntOrNull() ?: 0
                    }
                }
                val body = if (contentLength > 0) ByteArray(contentLength).also { readFully(input, it) }.toString(Charsets.UTF_8) else ""

                val (status, reason, respBody) = when (path) {
                    "/hello" -> Triple(200, "OK", "hi $method $path")
                    "/echo" -> Triple(201, "Created", "echo:$body")
                    else -> Triple(404, "Not Found", "nope")
                }
                val bytes = respBody.toByteArray(Charsets.UTF_8)
                val head = "HTTP/1.1 $status $reason\r\nContent-Type: text/plain; charset=utf-8\r\n" +
                    "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                socket.getOutputStream().apply {
                    write(head.toByteArray(Charsets.ISO_8859_1))
                    write(bytes)
                    flush()
                }
            }
        }

        private fun readLine(input: InputStream): String? {
            val sb = StringBuilder()
            while (true) {
                val c = input.read()
                if (c < 0) return if (sb.isEmpty()) null else sb.toString()
                if (c == '\n'.code) return sb.toString().trimEnd('\r')
                sb.append(c.toChar())
                if (sb.length > 16_384) return sb.toString()
            }
        }

        private fun readFully(input: InputStream, target: ByteArray) {
            var done = 0
            while (done < target.size) {
                val n = input.read(target, done, target.size - done)
                if (n < 0) return
                done += n
            }
        }
    }

    @Test
    fun httpGetAndPostAgainstLocalServer() {
        val server = MiniHttp()
        server.start()
        try {
            val port = server.port
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
            assertEquals("stderr: ${result.stderr}", 0, result.exitCode)
            assertTrue("stdout: ${result.stdout}", result.stdout.contains("status=200 body=hi GET /hello"))
            assertTrue("stdout: ${result.stdout}", result.stdout.contains("post=201 echo:привет-POST"))
        } finally {
            server.stop()
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
