package dev.pocketrun.runtime

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.CharBuffer
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
    private val decodeBuffer = CharBuffer.allocate(1024)
    private val decoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)

    /**
     * Bytes at the end of the last read that could not be decoded yet. The
     * decoder never consumes a partial multi-byte character; it leaves it in
     * the input buffer, and we have to hand it back with the next chunk.
     */
    private var undecoded = ByteArray(0)

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
        } catch (_: IOException) {
            // The run directory was deleted mid-tail; stop following instead of
            // killing the thread with an uncaught exception.
        } catch (_: SecurityException) {
            // Same story with an unreadable file.
        } finally {
            finishDecoder()
            flushPartial()
            onDrained()
        }
    }

    private fun drain(): Boolean {
        if (!file.isFile) return false
        val length = file.length()
        if (length <= offset) return false
        var grew = false
        RandomAccessFile(file, "r").use { handle ->
            handle.seek(offset)
            val chunk = ByteArray((length - offset).coerceAtMost(64L * 1024L).toInt())
            val read = handle.read(chunk)
            if (read > 0) {
                offset += read
                decodeChunk(chunk, read)
                grew = true
            }
        }
        if (grew) emitCompleteLines()
        return grew
    }

    /**
     * Incremental UTF-8 decode. The one-shot `decode(ByteBuffer)` convenience
     * method resets the decoder, so a multi-byte character split across two
     * reads would come out as U+FFFD. The ByteBuffer/CharBuffer pair leaves
     * undecodable trailing bytes in the input buffer instead; we carry those
     * over to the next chunk, so Cyrillic or emoji never gets mangled at a
     * read boundary.
     */
    private fun decodeChunk(chunk: ByteArray, length: Int) {
        val input = if (undecoded.isEmpty()) {
            ByteBuffer.wrap(chunk, 0, length)
        } else {
            ByteBuffer.allocate(undecoded.size + length).apply {
                put(undecoded)
                put(chunk, 0, length)
                flip()
            }
        }
        while (input.hasRemaining()) {
            decodeBuffer.clear()
            val result = decoder.decode(input, decodeBuffer, false)
            decodeBuffer.flip()
            if (decodeBuffer.hasRemaining()) pending.append(decodeBuffer)
            if (result.isUnderflow()) break
            // OVERFLOW: the buffer filled with input still left; go around again.
        }
        undecoded = if (input.hasRemaining()) {
            val tail = ByteArray(input.remaining())
            input.get(tail)
            tail
        } else {
            ByteArray(0)
        }
    }

    /** Decodes whatever an incomplete trailing character turns into after REPLACE. */
    private fun finishDecoder() {
        if (undecoded.isEmpty()) return
        decodeBuffer.clear()
        decoder.decode(ByteBuffer.wrap(undecoded), decodeBuffer, true)
        decodeBuffer.flip()
        if (decodeBuffer.hasRemaining()) pending.append(decodeBuffer)
        undecoded = ByteArray(0)
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
