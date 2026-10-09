package dev.pocketrun.core

import android.os.Build
import android.os.Debug
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Real device facts for the runtime bridges.
 *
 * Everything here is read at call time from the Android build, the running
 * process or the Linux kernel's `/proc` pseudo-files. There are no canned
 * constants: a script asking `os.totalmem()` sees the phone it actually runs on,
 * and one asking `process.memoryUsage()` sees this process. The few JVM
 * fallbacks exist so the unit tests (which run off-device) still get an answer.
 */
object SystemInfo {

    data class CpuTimes(val user: Long, val nice: Long, val sys: Long, val idle: Long, val irq: Long)

    data class Cpu(val model: String, val speedMhz: Int, val times: CpuTimes)

    data class MemoryUsage(
        val rss: Long,
        val heapTotal: Long,
        val heapUsed: Long,
        val external: Long,
        val arrayBuffers: Long,
    )

    data class NetworkAddress(val address: String, val family: String, val mac: String, val internal: Boolean)

    private const val PROC_CPUINFO = "/proc/cpuinfo"
    private const val PROC_STAT = "/proc/stat"
    private const val PROC_MEMINFO = "/proc/meminfo"
    private const val PROC_UPTIME = "/proc/uptime"
    private const val PROC_LOADAVG = "/proc/loadavg"
    private const val PROC_HOSTNAME = "/proc/sys/kernel/hostname"

    private val CPU_MODEL_KEYS = listOf("model name", "Processor", "Hardware", "cpu model", "CPU part")
    private val CPU_SPEED_KEYS = listOf("cpu MHz", "BogoMIPS", "clock")

    /** The primary ABI of this device, as the OS reports it. */
    val abi: String
        get() = Build.SUPPORTED_ABIS?.firstOrNull()?.takeIf { it.isNotBlank() }
            ?: System.getProperty("os.arch")?.takeIf { it.isNotBlank() }
            ?: "unknown"

    /** Node's `os.arch()` spelling of [abi]: `arm64`, `x64`, … */
    val arch: String get() = nodeArch(abi)

    /** Node's `os.release()`: the kernel version. */
    val release: String
        get() = System.getProperty("os.version")?.takeIf { it.isNotBlank() } ?: "unknown"

    val osType: String get() = "Linux"

    val hostname: String
        get() = readProc(PROC_HOSTNAME)?.lineSequence()?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
            ?: runCatching { InetAddress.getLocalHost().hostName }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: "localhost"

    val pid: Int get() = android.os.Process.myPid().takeIf { it > 0 } ?: 1

    /** Seconds since boot, from `/proc/uptime`. */
    val uptimeSeconds: Double
        get() = readProc(PROC_UPTIME)?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.toDoubleOrNull() ?: 0.0

    /** 1/5/15-minute load average from `/proc/loadavg`. */
    val loadAverage: DoubleArray
        get() {
            val values = readProc(PROC_LOADAVG)?.trim()?.split(Regex("\\s+"))
                ?.take(3)?.mapNotNull { it.toDoubleOrNull() } ?: emptyList()
            return if (values.size == 3) values.toDoubleArray() else doubleArrayOf(0.0, 0.0, 0.0)
        }

    /** Physical RAM, in bytes, from `/proc/meminfo`. */
    val totalMemory: Long get() = meminfo("MemTotal")

    /** Available RAM, in bytes; `MemAvailable` with a `MemFree` fallback. */
    val freeMemory: Long get() = meminfo("MemAvailable").takeIf { it > 0 } ?: meminfo("MemFree")

    /** Per-logical-CPU totals, from `/proc/stat`. */
    fun cpus(): List<Cpu> {
        val times = parseCpuTimes()
        val models = parseCpuInfo(CPU_MODEL_KEYS)
        val speeds = parseCpuInfo(CPU_SPEED_KEYS).mapNotNull { it.trim().toDoubleOrNull()?.toInt() }
        val count = if (times.isNotEmpty()) times.size else Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        return (0 until count).map { i ->
            Cpu(
                model = models.getOrNull(i) ?: models.firstOrNull() ?: defaultModel(),
                speedMhz = speeds.getOrNull(i) ?: speeds.firstOrNull() ?: 0,
                times = times.getOrNull(i) ?: CpuTimes(0, 0, 0, 0, 0),
            )
        }
    }

    /** All network interfaces, keyed by name, with one entry per address. */
    fun networkInterfaces(): Map<String, List<NetworkAddress>> {
        val out = LinkedHashMap<String, MutableList<NetworkAddress>>()
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull() ?: return out
        for (ni in interfaces) {
            val entries = out.getOrPut(ni.name) { mutableListOf() }
            val mac = runCatching {
                ni.hardwareAddress?.joinToString(":") { "%02x".format(it) }
            }.getOrNull()
            val addresses = runCatching { ni.inetAddresses }.getOrNull() ?: continue
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement() ?: continue
                val host = address.hostAddress ?: continue
                entries += NetworkAddress(
                    address = host.substringBefore('%'),
                    family = if (address is java.net.Inet6Address) "IPv6" else "IPv4",
                    mac = mac ?: "00:00:00:00:00:00",
                    internal = address.isLoopbackAddress || address.isAnyLocalAddress,
                )
            }
        }
        return out
    }

    /** Heap and native-memory numbers for Node's `process.memoryUsage()`. */
    fun memoryUsage(): MemoryUsage {
        val runtime = Runtime.getRuntime()
        val heapTotal = runtime.totalMemory()
        val heapUsed = heapTotal - runtime.freeMemory()
        val nativeHeap = runCatching { Debug.getNativeHeapAllocatedSize() }.getOrDefault(0L)
        val pss = runCatching { Debug.getPss() * 1024L }.getOrDefault(0L)
        return MemoryUsage(
            rss = if (pss > 0) pss else heapTotal + nativeHeap,
            heapTotal = heapTotal,
            heapUsed = heapUsed,
            external = nativeHeap,
            arrayBuffers = 0L,
        )
    }

    /** Monotonic nanoseconds, for Node's `process.hrtime()`. */
    fun hrtimeNanos(): Long = System.nanoTime()

    private fun defaultModel(): String {
        val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else ""
        return soc.takeIf { it.isNotBlank() }
            ?: Build.HARDWARE.takeIf { it.isNotBlank() }
            ?: Build.BOARD.takeIf { it.isNotBlank() }
            ?: abi
    }

    private fun nodeArch(abi: String): String = when (abi.lowercase()) {
        "arm64-v8a", "aarch64" -> "arm64"
        "armeabi-v7a", "armeabi", "armv7l" -> "arm"
        "x86_64", "amd64" -> "x64"
        "x86", "i386", "i686" -> "ia32"
        else -> abi
    }

    private fun parseCpuTimes(): List<CpuTimes> {
        val text = readProc(PROC_STAT) ?: return emptyList()
        val times = mutableListOf<CpuTimes>()
        for (line in text.lineSequence()) {
            if (!line.startsWith("cpu") || line.getOrNull(3)?.isDigit() != true) continue
            val fields = line.substringAfter(' ').trim().split(Regex("\\s+")).mapNotNull { it.toLongOrNull() }
            val user = fields.getOrElse(0) { 0L }
            val nice = fields.getOrElse(1) { 0L }
            val sys = fields.getOrElse(2) { 0L }
            val idle = fields.getOrElse(3) { 0L }
            val irq = fields.getOrElse(5) { 0L } + fields.getOrElse(6) { 0L }
            times += CpuTimes(user, nice, sys, idle, irq)
        }
        return times
    }

    private fun parseCpuInfo(keys: List<String>): List<String> {
        val text = readProc(PROC_CPUINFO) ?: return emptyList()
        val wanted = keys.map { it.lowercase() }.toSet()
        return text.lineSequence().mapNotNull { line ->
            val idx = line.indexOf(':')
            if (idx <= 0) return@mapNotNull null
            val key = line.substring(0, idx).trim().lowercase()
            if (key !in wanted) return@mapNotNull null
            line.substring(idx + 1).trim().takeIf { it.isNotEmpty() }
        }.toList()
    }

    private fun meminfo(key: String): Long {
        val text = readProc(PROC_MEMINFO) ?: return 0L
        for (line in text.lineSequence()) {
            if (!line.startsWith("$key:")) continue
            val kb = Regex("(\\d+)").find(line.substringAfter(':'))?.groupValues?.get(1)?.toLongOrNull()
            return (kb ?: 0L) * 1024L
        }
        return 0L
    }

    private fun readProc(path: String): String? = runCatching {
        val file = java.io.File(path)
        if (!file.isFile) null else file.readText(Charsets.UTF_8)
    }.getOrNull()
}
