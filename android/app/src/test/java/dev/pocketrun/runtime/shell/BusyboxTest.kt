package dev.pocketrun.runtime.shell

import dev.pocketrun.core.Workspace
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * BusyBox degrades gracefully when the native binary is absent (JVM tests and
 * any build without the staged jniLibs): unavailable, no applets, 127 result.
 */
class BusyboxTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun missingBinaryIsUnavailable() {
        val ws = Workspace.at(tmp.newFolder())
        val bb = Busybox(File(tmp.root, "no-such-dir"), ws)
        assertEquals(false, bb.isAvailable())
        assertEquals(emptySet<String>(), bb.applets())
        assertEquals(false, bb.knowsApplet("ls"))
    }

    @Test
    fun runWithoutBinaryReturns127() {
        val ws = Workspace.at(tmp.newFolder())
        val bb = Busybox(File(tmp.root, "no-such-dir"), ws)
        val r = bb.run(listOf("echo", "hi"), ws.root, null)
        assertEquals(127, r.exitCode)
        assert(r.stderr.isNotBlank())
    }

    @Test
    fun runWithoutBinaryDominatesEmptyArgs() {
        val ws = Workspace.at(tmp.newFolder())
        val bb = Busybox(File(tmp.root, "no-such-dir"), ws)
        val r = bb.run(emptyList(), ws.root, null)
        assertEquals(127, r.exitCode)
        assert(r.stderr.isNotBlank())
    }
}