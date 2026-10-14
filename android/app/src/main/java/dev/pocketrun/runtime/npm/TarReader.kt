package dev.pocketrun.runtime.npm

import java.io.File
import java.io.FileInputStream
import java.util.zip.GZIPInputStream

/**
 * Minimal reader for the `.tgz` tarballs npm serves (ustar format, entries
 * rooted at `package/`). Enough for registry packages: regular files and
 * directories; symlinks and special entries are skipped, every extracted path
 * is forced to stay inside [destDir].
 */
object TarReader {

    private const val BLOCK = 512

    class TarFormatException(message: String) : Exception(message)

    /** Extracts [tgz] into [destDir], stripping [stripPrefix] from entries. */
    fun extract(tgz: File, destDir: File, stripPrefix: String = "package/") {
        destDir.mkdirs()
        GZIPInputStream(FileInputStream(tgz)).use { gz ->
            val header = ByteArray(BLOCK)
            while (true) {
                if (!readFully(gz, header)) return
                if (header.all { it == 0.toByte() }) return // end-of-archive block
                var name = cstr(header, 0, 100)
                val size = octal(cstr(header, 124, 12))
                val typeFlag = if (header[156] == 0.toByte()) '0' else header[156].toInt().toChar()
                val prefix = cstr(header, 345, 155)
                if (prefix.isNotEmpty()) name = "$prefix/$name"
                if (name.isEmpty()) skipData(gz, size)

                val data = if (size > 0) readExact(gz, size) else ByteArray(0)

                val rel = strip(name, stripPrefix)
                when (typeFlag) {
                    '0' -> {
                        if (rel != null) {
                            val out = safeFile(destDir, rel)
                            if (out != null) {
                                out.parentFile?.mkdirs()
                                out.writeBytes(data)
                            }
                        }
                    }
                    '5' -> {
                        if (rel != null) safeFile(destDir, rel)?.mkdirs()
                    }
                    else -> Unit // 'x'/'g' pax headers, '2' symlinks, 'L' long names: consumed, ignored
                }
            }
        }
    }

    // ---------------------------------------------------------------- format

    private fun cstr(buf: ByteArray, offset: Int, len: Int): String {
        var end = offset
        val limit = minOf(offset + len, buf.size)
        while (end < limit && buf[end] != 0.toByte()) end++
        return String(buf, offset, end - offset, Charsets.UTF_8)
    }

    private fun octal(s: String): Long {
        val t = s.trim(' ', '\u0000')
        if (t.isEmpty()) return 0L
        return t.toLongOrNull(radix = 8) ?: throw TarFormatException("bad tar size field: '$s'")
    }

    private fun readFully(gz: GZIPInputStream, buf: ByteArray): Boolean {
        var done = 0
        while (done < buf.size) {
            val n = gz.read(buf, done, buf.size - done)
            if (n < 0) return false
            done += n
        }
        return true
    }

    private fun readExact(gz: GZIPInputStream, size: Long): ByteArray {
        val out = ByteArray(size.toInt())
        var done = 0
        while (done < out.size) {
            val n = gz.read(out, done, out.size - done)
            if (n < 0) throw TarFormatException("archive truncated inside entry data")
            done += n
        }
        // trailing padding to the next 512 boundary
        val pad = ((BLOCK - (size % BLOCK)) % BLOCK).toInt()
        if (pad > 0) skipData(gz, pad.toLong())
        return out
    }

    private fun skipData(gz: GZIPInputStream, size: Long) {
        var left = size
        val buf = ByteArray(BLOCK)
        while (left > 0) {
            val n = gz.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) return
            left -= n
        }
    }

    // ---------------------------------------------------------------- paths

    private fun strip(name: String, prefix: String): String? {
        var n = name
        while (n.startsWith("./")) n = n.substring(2)
        if (n.isEmpty()) return null
        if (!prefix.isEmpty()) {
            if (!n.startsWith(prefix)) return null
            n = n.removePrefix(prefix)
        }
        while (n.startsWith("/")) return null // never absolute
        if (n.split('/').any { it == ".." }) return null
        if (n.isEmpty() || n == ".") return null
        return n
    }

    private fun safeFile(destDir: File, rel: String): File? {
        val target = File(destDir, rel).toPath().normalize()
        val root = destDir.toPath().normalize()
        if (target != root && !target.startsWith(root)) return null
        return target.toFile()
    }
}
