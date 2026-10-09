package dev.pocketrun.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The native provider must answer on any host, on-device or in the JVM test
 * runner. These assertions hold everywhere; the /proc-backed numbers that are
 * only non-zero on a real Linux kernel are exercised by JsRuntimeTest.
 */
class SystemInfoTest {

    @Test
    fun archIsNormalisedToNodeSpelling() {
        // Off-device the ABI falls back to the JVM's os.arch, so the mapping is
        // observable: x86_64/amd64 -> x64, aarch64 -> arm64, etc.
        val raw = System.getProperty("os.arch") ?: ""
        val expected = when (raw.lowercase()) {
            "arm64-v8a", "aarch64" -> "arm64"
            "armeabi-v7a", "armeabi", "armv7l" -> "arm"
            "x86_64", "amd64" -> "x64"
            "x86", "i386", "i686" -> "ia32"
            else -> raw
        }
        assertEquals(expected, SystemInfo.arch)
        assertTrue("abi: ${SystemInfo.abi}", SystemInfo.abi.isNotBlank())
    }

    @Test
    fun cpusAndLoadAlwaysHaveAShape() {
        assertTrue(SystemInfo.cpus().isNotEmpty())
        assertEquals(3, SystemInfo.loadAverage.size)
        assertTrue("pid", SystemInfo.pid > 0)
    }

    @Test
    fun memoryUsageIsConsistent() {
        val usage = SystemInfo.memoryUsage()
        assertTrue("heapTotal=${usage.heapTotal}", usage.heapTotal > 0)
        assertTrue("heapUsed=${usage.heapUsed}", usage.heapUsed in 0..usage.heapTotal)
        assertTrue("rss=${usage.rss}", usage.rss > 0)
    }

    @Test
    fun hostnameIsNeverBlank() {
        assertTrue(SystemInfo.hostname.isNotBlank())
    }

    @Test
    fun hrtimeIsMonotonic() {
        val first = SystemInfo.hrtimeNanos()
        val second = SystemInfo.hrtimeNanos()
        assertTrue("$first -> $second", second >= first)
    }
}
