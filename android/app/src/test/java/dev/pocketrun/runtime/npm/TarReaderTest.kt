package dev.pocketrun.runtime.npm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream

class TarReaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Tiny ustar writer so the test does not depend on a system `tar`. */
    private class TarWriter {
        private val out = ByteArrayOutputStream()

        fun file(name: String, content: ByteArray) {
            header(name, content.size.toLong(), '0')
            out.write(content)
            pad(content.size.toLong())
        }

        fun dir(name: String) = header(name, 0, '5')

        private fun header(name: String, size: Long, type: Char) {
            val h = ByteArray(512)
            fun put(offset: Int, s: String, len: Int) {
                val b = s.toByteArray(Charsets.UTF_8)
                System.arraycopy(b, 0, h, offset, minOf(b.size, len))
            }
            put(0, name, 100)
            put(100, "000644", 8)   // mode
            put(108, "000000", 8)   // uid
            put(116, "000000", 8)   // gid
            put(124, java.lang.Long.toOctalString(size).padStart(11, '0') + "\u0000", 12)
            put(136, "00000000000", 12) // mtime
            put(148, "        ", 8)     // checksum placeholder
            h[156] = type.code.toByte()
            put(257, "ustar\u000000", 8)
            // checksum: spaces treated as NUL
            var sum = 0
            for (i in 0 until 512) sum += (h[i].toInt() and 0xFF)
            put(148, "%06o\u0000 ".format(sum), 8)
            out.write(h)
        }

        private fun pad(size: Long) {
            val pad = ((512 - (size % 512)) % 512).toInt()
            repeat(pad) { out.write(0) }
        }

        fun toTgz(file: File) {
            GZIPOutputStream(file.outputStream()).use { gz -> gz.write(out.toByteArray()) }
        }
    }

    @Test
    fun extractsPackagePrefix() {
        val writer = TarWriter().apply {
            dir("package/")
            dir("package/lib/")
            file("package/package.json", """{"name":"fake","version":"1.0.0","bin":"cli.js"}""".toByteArray())
            file("package/lib/util.js", "module.exports = function(){ return 42; };".toByteArray())
            file("package/cli.js", "console.log('hi');".toByteArray())
        }
        val tgz = tmp.newFile("fake-1.0.0.tgz")
        writer.toTgz(tgz)

        val dest = tmp.newFolder("out")
        TarReader.extract(tgz, dest, "package/")

        assertEquals("fake", File(dest, "package.json").readText().let { org.json.JSONObject(it).getString("name") })
        assertEquals("console.log('hi');", File(dest, "cli.js").readText())
        assertEquals("module.exports = function(){ return 42; };", File(dest, "lib/util.js").readText())
    }

    @Test
    fun skipsEntriesOutsidePrefix() {
        val writer = TarWriter().apply {
            file("package/ok.txt", "ok".toByteArray())
            file("/etc/evil.txt", "evil".toByteArray()) // absolute name — must be refused
            file("package/../evil.txt", "evil".toByteArray()) // traversal — must be refused
        }
        val tgz = tmp.newFile("evil.tgz")
        writer.toTgz(tgz)

        val dest = tmp.newFolder("out")
        TarReader.extract(tgz, dest, "package/")

        assertTrue(File(dest, "ok.txt").isFile)
        assertFalse(File(dest, "../evil.txt").exists())
        // nothing escaped the destination dir
        assertFalse(File(tmp.root, "evil.txt").exists())
    }

    @Test
    fun longNamesWithUstarPrefix() {
        // ustar splits long paths at a '/' boundary: prefix (≤155 bytes) in the
        // prefix field, the rest (≤100 bytes) in the name field; the reader
        // rejoins them with '/'.
        val prefix = "package/" + "a".repeat(100) // 108 bytes
        val name = "deep/" + "b".repeat(90) + ".txt" // 99 bytes
        val fullName = "$prefix/$name"
        assertTrue(prefix.toByteArray(Charsets.UTF_8).size <= 155)
        assertTrue(name.toByteArray(Charsets.UTF_8).size <= 100)

        val out = ByteArrayOutputStream()
        val h = ByteArray(512)
        fun put(offset: Int, s: String, len: Int) {
            val b = s.toByteArray(Charsets.UTF_8)
            System.arraycopy(b, 0, h, offset, minOf(b.size, len))
        }
        put(0, name, 100)
        put(124, "%011o\u0000".format(5L), 12)
        h[156] = '0'.code.toByte()
        put(257, "ustar\u000000", 8)
        put(345, prefix, 155)
        var sum = 0
        for (i in 0 until 512) sum += (h[i].toInt() and 0xFF)
        put(148, "%06o\u0000 ".format(sum), 8)
        out.write(h)
        out.write("hello".toByteArray())
        out.write(ByteArray((512 - 5 % 512) % 512))
        out.write(ByteArray(1024)) // end-of-archive
        val tgz = tmp.newFile("long.tgz")
        GZIPOutputStream(tgz.outputStream()).use { it.write(out.toByteArray()) }

        val dest = tmp.newFolder("out")
        TarReader.extract(tgz, dest, "package/")
        val extracted = File(dest, fullName.removePrefix("package/"))
        assertTrue("expected ${extracted.absolutePath}", extracted.isFile)
        assertEquals("hello", extracted.readText())
    }
}
