package dev.pocketrun.agent

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A tiny HTTP/1.1 server on a loopback port — enough to answer the LLM client's
 * requests in a unit test. Android unit tests compile against a stub
 * android.jar with only `java.base` on the classpath, so `com.sun.net.httpserver`
 * is not available; this is the whole "server" in ~60 lines.
 *
 * Responses are served in order; the last one repeats when the queue runs dry,
 * which is what a model that keeps asking for the same tool call looks like.
 */
class FakeHttpServer(responses: List<Resp>) {

    data class Resp(
        val contentType: String,
        val body: String,
        val status: Int = 200,
        /** Split the body and pause in between: a stream that goes quiet. */
        val pauseMs: Int = 0,
    )

    private val queue = ArrayDeque(responses)
    private val running = AtomicBoolean(true)
    private val server = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))

    val port: Int get() = server.localPort

    /** The bodies of the requests received so far, in order. */
    val requests: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

    init {
        val acceptor = Thread({
            while (running.get()) {
                val socket = try {
                    server.accept()
                } catch (t: Throwable) {
                    if (running.get()) continue else return@Thread
                }
                try {
                    handle(socket)
                } catch (t: Throwable) {
                    // a client that walked away mid-answer is normal here
                }
            }
        }, "fake-http")
        acceptor.isDaemon = true
        acceptor.start()
    }

    private fun handle(socket: Socket) {
        socket.use { s ->
            val input = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.ISO_8859_1))
            input.readLine() ?: return // request line
            var length = 0
            while (true) {
                val line = input.readLine() ?: break
                if (line.isEmpty()) break
                if (line.substringBefore(':').lowercase() == "content-length") {
                    length = line.substringAfter(':').trim().toIntOrNull() ?: 0
                }
            }
            val body = CharArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(body, read, length - read)
                if (n < 0) break
                read += n
            }
            // Headers are ASCII, but the body is JSON: it arrives as UTF-8
            // (application/json defaults to it). The reader decodes bytes as
            // latin-1 so that header lines survive, so the body is turned back
            // into bytes before it is read as text — otherwise a non-ASCII
            // prompt reaches the assertion as mojibake.
            val raw = String(body, 0, read).toByteArray(Charsets.ISO_8859_1)
            requests.add(String(raw, Charsets.UTF_8))

            val resp = synchronized(queue) { if (queue.size > 1) queue.removeFirst() else queue.first() }
            val bytes = resp.body.toByteArray(Charsets.UTF_8)
            val head = buildString {
                append("HTTP/1.1 ${resp.status} ${if (resp.status == 200) "OK" else "Error"}\r\n")
                append("Content-Type: ${resp.contentType}\r\n")
                append("Content-Length: ${bytes.size}\r\n")
                append("Connection: close\r\n\r\n")
            }
            val out = s.getOutputStream()
            out.write(head.toByteArray(Charsets.ISO_8859_1))
            if (resp.pauseMs > 0 && bytes.size > 128) {
                out.write(bytes, 0, 128)
                out.flush()
                Thread.sleep(resp.pauseMs.toLong())
                out.write(bytes, 128, bytes.size - 128)
            } else {
                out.write(bytes)
            }
            out.flush()
        }
    }

    fun close() {
        running.set(false)
        runCatching { server.close() }
    }
}
