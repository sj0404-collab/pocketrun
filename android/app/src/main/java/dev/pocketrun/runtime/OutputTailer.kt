package dev.pocketrun.runtime

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Follows a file that another thread is appending to and reports the growth as
 * text. Python and the JS engine both write run output this way: a pipe would
 * let a slow reader block the interpreter, and a file also keeps the output if
 * the UI dies mid-run.
 */
class OutputTailer(
    private val file: File,
    private val stream: OutputStream,
    private val listener: OutputListener,
    private val stopped: AtomicBoolean,
    private val onDrained: () -> Unit,
) : Thread("pocketrun-tail-${stream.name.lowercase()}") {

    private var offset: Long = 0
    private val pending = StringBuilder()
    private val decoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)

    init {
        isDaemon = true
    }

    override fun run() {
        try {
            while (!stopped.get()) {
                if (drain()) onDrained()
                sleep(40)
            }
            drain()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            flushPartial()
            onDrained()
        }
    }

    private fun drain(): Boolean {
        if (!file.isFile) return false
        val length = file.length()
        if (length <= offset) return false
        val grew = RandomAccessFile(file, "r").use { handle ->
            handle.seek(offset)
            val chunk = ByteArray((length - offset).coerceAtMost(64L * 1024L).toInt())
            val read = handle.read(chunk)
            if (read <= 0) return false
            offset += read
            // The decoder holds on to a partial multi-byte character between chunks.
            val text = decoder.decode(ByteBuffer.wrap(chunk, 0, read)).toString()
            pending.append(text)
            true
        }
        if (grew) emitCompleteLines()
        return grew
    }

    private fun emitCompleteLines() {
        var newline = pending.indexOf("\n")
        while (newline >= 0) {
            val line = pending.substring(0, newline).removeSuffix("\r")
            pending.delete(0, newline + 1)
            listener.onOutput(stream, line + "\n")
            newline = pending.indexOf("\n")
        }
        // A single very long line is flushed early so the UI keeps updating.
        if (pending.length > 32 * 1024) flushPartial()
    }

    private fun flushPartial() {
        if (pending.isEmpty()) return
        listener.onOutput(stream, pending.toString())
        pending.setLength(0)
    }
}
