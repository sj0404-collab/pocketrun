package dev.pocketrun.runtime.npm

import dev.pocketrun.core.Workspace
import dev.pocketrun.runtime.ExecRequest
import dev.pocketrun.runtime.ExecResult
import dev.pocketrun.runtime.ExecutionHandle
import dev.pocketrun.runtime.OutputListener
import dev.pocketrun.runtime.OutputStream
import dev.pocketrun.runtime.RuntimeKind
import dev.pocketrun.runtime.js.JsRuntime
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * npm/npx for pure-JS packages: `install` downloads a registry tarball into
 * `workspace/packages/<name>` (dependencies hoisted there too; conflicting
 * versions nest under `packages/<parent>/node_modules/<dep>`, which is exactly
 * where boot.js looks while walking up), and `run` executes the package `bin`
 * through [JsRuntime] with the caller's working directory.
 *
 * Real `npx <pkg>` semantics, minus native addons and postinstall scripts —
 * those cannot work inside the sandbox and are skipped.
 */
class NpxRuntime(
    private val js: JsRuntime,
    private val workspace: Workspace,
    private val registry: NpmRegistry = NpmRegistry(),
) {

    companion object {
        private const val MAX_PACKAGES = 40
        private const val MAX_DEPTH = 4
        private const val DEFAULT_TIMEOUT_MS = 180_000L

        /** `name`, `name@version`, `@scope/name`, `@scope/name@version`. */
        fun parseSpec(raw: String): PackageSpec? {
            val s = raw.trim()
            if (s.isEmpty()) return null
            val at = if (s.startsWith("@")) s.indexOf('@', 1) else s.indexOf('@')
            val name = if (at < 0) s else s.substring(0, at)
            val version = if (at < 0) null else s.substring(at + 1).trim().takeIf { it.isNotEmpty() }
            if (!Regex("^[a-zA-Z0-9@][a-zA-Z0-9@/._-]*$").matches(name)) return null
            if (name.count { it == '/' } > 1) return null
            if (name.startsWith("@") && name.indexOf('/', 1) < 0) return null
            return PackageSpec(name, version)
        }

        /** `pkg arg1 arg2` → spec + args (whitespace-separated, quotes respected). */
        fun parseCommandLine(line: String): Pair<PackageSpec, List<String>>? {
            val tokens = tokenize(line)
            val spec = tokens.firstOrNull()?.let { parseSpec(it) } ?: return null
            return spec to tokens.drop(1)
        }

        private fun tokenize(line: String): List<String> {
            val out = mutableListOf<String>()
            val cur = StringBuilder()
            var quote: Char? = null
            for (ch in line.trim()) {
                when {
                    quote != null && ch == quote -> quote = null
                    quote != null -> cur.append(ch)
                    ch == '"' || ch == '\'' -> quote = ch
                    ch.isWhitespace() -> { if (cur.isNotEmpty()) { out += cur.toString(); cur.clear() } }
                    else -> cur.append(ch)
                }
            }
            if (cur.isNotEmpty()) out += cur.toString()
            return out
        }
    }

    data class PackageSpec(val name: String, val version: String?)

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "pocketrun-npx").apply { isDaemon = true }
    }

    /** Top-level install location for a package name. */
    fun packageDir(name: String): File = File(workspace.packages, name)

    fun installedPackages(): List<Pair<String, String>> =
        workspace.packages.listFiles { f -> f.isDirectory && File(f, "package.json").isFile }
            .orEmpty()
            .mapNotNull { dir ->
                val name = readName(File(dir, "package.json")) ?: return@mapNotNull null
                name to readVersion(File(dir, "package.json")).orEmpty()
            }
            .sortedBy { it.first.lowercase() }

    // ---------------------------------------------------------------- execute

    fun execute(request: ExecRequest, listener: OutputListener): ExecutionHandle {
        check(request.kind == RuntimeKind.NPX) { "NpxRuntime expects NPX requests" }
        val running = AtomicBoolean(true)
        val cancelled = AtomicBoolean(false)
        val inner = AtomicReference<ExecutionHandle?>(null)
        val installLog = StringBuilder()

        fun log(line: String) {
            synchronized(installLog) { installLog.append(line).append('\n') }
            listener.onOutput(OutputStream.STDOUT, line + "\n")
        }

        executor.execute {
            try {
                val spec = parseSpec(request.target)
                    ?: throw IllegalArgumentException(
                        "не удалось разобрать пакет '${request.target}' — ожидается имя[@версия]",
                    )
                val pkgDir = install(spec, cancelled, ::log)
                val binPath = resolveBin(pkgDir, spec.name)
                    ?: throw IllegalStateException("у пакета ${spec.name} нет исполняемого файла (bin) — возможно, он не поддерживается в песочнице")
                log("▶ запускаю ${spec.name} (${binPath.relativeToOrNull(workspace.root)?.path ?: binPath.path})")

                val jsRequest = ExecRequest(
                    kind = RuntimeKind.NODE,
                    target = binPath.absolutePath,
                    args = request.args,
                    cwd = request.cwd ?: workspace.root,
                    stdin = request.stdin,
                )
                inner.set(
                    js.execute(jsRequest, object : OutputListener {
                        override fun onOutput(stream: OutputStream, text: String) {
                            listener.onOutput(stream, text)
                        }

                        override fun onFinished(result: ExecResult) {
                            val prefix = synchronized(installLog) { installLog.toString() }
                            running.set(false)
                            listener.onFinished(
                                result.copy(
                                    stdout = prefix + result.stdout,
                                    stderr = withSyntaxHint(result.stderr),
                                ),
                            )
                        }

                        override fun onFailed(error: Throwable) {
                            running.set(false)
                            listener.onFailed(error)
                        }
                    }),
                )
            } catch (t: Throwable) {
                running.set(false)
                listener.onFailed(t)
            }
        }

        return object : ExecutionHandle {
            override val isRunning: Boolean get() = running.get()
            override fun cancel() {
                cancelled.set(true)
                inner.get()?.cancel()
            }
        }
    }

    /** Blocking variant used by the agent's run_npx tool. */
    fun executeSync(request: ExecRequest, timeoutMs: Long = DEFAULT_TIMEOUT_MS): ExecResult {
        val latch = CountDownLatch(1)
        var result: ExecResult? = null
        var error: Throwable? = null
        val handle = execute(request, object : OutputListener {
            override fun onFinished(r: ExecResult) { result = r; latch.countDown() }
            override fun onFailed(e: Throwable) { error = e; latch.countDown() }
        })
        return if (latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            result ?: ExecResult(1, "", error?.message ?: "ошибка запуска npx", 0)
        } else {
            handle.cancel()
            ExecResult(124, "", "превышено время выполнения npx (${timeoutMs / 1000} с)", timeoutMs)
        }
    }

    /**
     * Rhino understands a large ES6 subset but no class/async/import/for-of-const.
     * When a package fails on that, say so plainly instead of a bare parse error.
     */
    private fun withSyntaxHint(stderr: String): String {
        if (!stderr.contains("Cannot parse module")) return stderr
        return stderr + "\n" +
            "⚠ Пакет использует синтаксис, который встроенный JS-движок не понимает\n" +
            "  (class / async-await / import-export / spread). Часто помогает более старая\n" +
            "  версия пакета — например cowsay@1.4.0 вместо cowsay@latest.\n"
    }

    // ---------------------------------------------------------------- install

    /**
     * Makes sure [spec] is installed under packages/ and returns its directory.
     * Reuses an existing install when the version satisfies the spec.
     */
    @Synchronized
    fun install(
        spec: PackageSpec,
        cancelled: AtomicBoolean = AtomicBoolean(false),
        log: (String) -> Unit = {},
        budget: IntArray = intArrayOf(MAX_PACKAGES),
    ): File {
        if (cancelled.get()) throw IllegalStateException("установка отменена")
        val pkgDir = packageDir(spec.name)
        val existing = readVersion(manifestOf(pkgDir))
        if (existing != null && spec.version?.let { Semver.isSupportedRange(it) } != false &&
            (spec.version == null || Semver.satisfiesString(existing, spec.version))
        ) {
            log("• ${spec.name}@$existing уже установлен")
            return pkgDir
        }
        if (existing != null) pkgDir.deleteRecursively()
        if (--budget[0] < 0) throw IllegalStateException("слишком много пакетов (лимит $MAX_PACKAGES)")

        log("⬇ ${spec.name}${spec.version?.let { "@$it" } ?: ""}")
        val meta = registry.metadata(spec.name)
        val version = chooseVersion(meta, spec.version)
            ?: throw IllegalStateException("версия не найдена: ${spec.name}@${spec.version ?: "latest"}")
        val versionMeta = meta.optJSONObject("versions")?.optJSONObject(version)
            ?: throw IllegalStateException("в метаданных нет версии $version для ${spec.name}")
        log("  → ${spec.name}@$version")

        val tarball = versionMeta.optJSONObject("dist")?.optString("tarball")
        if (tarball.isNullOrEmpty()) throw IllegalStateException("у ${spec.name}@$version нет tarball")
        val tgz = File(workspace.cache, "tmp-${UUID.randomUUID()}.tgz")
        try {
            registry.downloadTarball(tarball, tgz)
            pkgDir.mkdirs()
            TarReader.extract(tgz, pkgDir, "package/")
        } finally {
            tgz.delete()
        }

        installDependencies(pkgDir, versionMeta, cancelled, log, budget)
        return pkgDir
    }

    private fun installDependencies(
        pkgDir: File,
        versionMeta: JSONObject,
        cancelled: AtomicBoolean,
        log: (String) -> Unit,
        budget: IntArray,
        depth: Int = 0,
    ) {
        if (depth >= MAX_DEPTH) return
        val deps = versionMeta.optJSONObject("dependencies") ?: return
        val names = deps.keys().asSequence().toList()
        for (dep in names) {
            if (cancelled.get()) throw IllegalStateException("установка отменена")
            val range = deps.optString(dep, "")
            if (!Semver.isSupportedRange(range)) {
                log("  ⚠ $dep пропущен (неподдерживаемый спецификатор: ${range.take(40)})")
                continue
            }
            val nested = File(pkgDir, "node_modules/$dep")
            val top = packageDir(dep)
            val nestedVer = readVersion(manifestOf(nested))
            if (nestedVer != null && Semver.satisfiesString(nestedVer, range)) continue
            val topVer = readVersion(manifestOf(top))
            if (topVer != null && Semver.satisfiesString(topVer, range)) {
                log("  • $dep@$topVer (уже установлен)")
                continue
            }
            // Hoist fresh deps to packages/; conflicting versions nest locally.
            val target = if (topVer != null) nested else top
            if (topVer != null) log("  ⚠ $dep: конфликт версий ($topVer не подходит под $range) — ставлю рядом")
            installInto(dep, range, target, cancelled, log, budget, depth)
        }
    }

    /** Installs [dep] exactly into [targetDir] (no reuse check for that dir). */
    private fun installInto(
        dep: String,
        range: String,
        targetDir: File,
        cancelled: AtomicBoolean,
        log: (String) -> Unit,
        budget: IntArray,
        depth: Int,
    ) {
        if (cancelled.get()) throw IllegalStateException("установка отменена")
        if (--budget[0] < 0) throw IllegalStateException("слишком много пакетов (лимит $MAX_PACKAGES)")
        log("  ⬇ $dep@$range")
        val meta = registry.metadata(dep)
        val version = chooseVersion(meta, range)
            ?: run {
                log("  ⚠ $dep: не найдена версия под $range — пропущен")
                return
            }
        val versionMeta = meta.optJSONObject("versions")?.optJSONObject(version) ?: return
        val tarball = versionMeta.optJSONObject("dist")?.optString("tarball") ?: return
        if (targetDir.exists()) targetDir.deleteRecursively()
        val tgz = File(workspace.cache, "tmp-${UUID.randomUUID()}.tgz")
        try {
            registry.downloadTarball(tarball, tgz)
            targetDir.mkdirs()
            TarReader.extract(tgz, targetDir, "package/")
        } finally {
            tgz.delete()
        }
        log("  ✓ $dep@$version")
        installDependencies(targetDir, versionMeta, cancelled, log, budget, depth + 1)
    }

    fun uninstall(name: String): Boolean {
        val spec = parseSpec(name) ?: return false
        val dir = packageDir(spec.name)
        return dir.exists() && dir.deleteRecursively()
    }

    // ---------------------------------------------------------------- metadata

    private fun manifestOf(pkgDir: File): File = File(pkgDir, "package.json")

    private fun readManifest(f: File): JSONObject? =
        if (!f.isFile) null else runCatching { JSONObject(f.readText(Charsets.UTF_8)) }.getOrNull()

    private fun readVersion(manifest: File): String? = readManifest(manifest)?.optString("version")?.takeIf { it.isNotEmpty() }

    private fun readName(manifest: File): String? = readManifest(manifest)?.optString("name")?.takeIf { it.isNotEmpty() }

    /** Resolves the requested version (exact / range / dist-tag) from metadata. */
    internal fun chooseVersion(meta: JSONObject, requested: String?): String? {
        val versions = meta.optJSONObject("versions") ?: return null
        if (requested == null || requested == "latest" || requested == "*") {
            val tag = meta.optJSONObject("dist-tags")?.optString("latest")?.takeIf { it.isNotEmpty() }
            if (tag != null && versions.has(tag)) return tag
            return versions.keys().asSequence()
                .filter { Semver.parse(it) != null }
                .maxWithOrNull { a, b -> Semver.compare(Semver.parse(a)!!, Semver.parse(b)!!) }
        }
        if (Semver.parse(requested) != null && versions.has(requested)) return requested
        val tag = meta.optJSONObject("dist-tags")?.optString(requested)?.takeIf { it.isNotEmpty() }
        if (tag != null && versions.has(tag)) return tag
        var best: String? = null
        for (key in versions.keys()) {
            val v = Semver.parse(key) ?: continue
            if (!Semver.satisfies(v, requested)) continue
            if (best == null || Semver.compare(v, Semver.parse(best)!!) > 0) best = key
        }
        return best
    }

    /** Finds the executable of an installed package: its `bin` entry. */
    internal fun resolveBin(pkgDir: File, name: String): File? {
        val manifest = readManifest(manifestOf(pkgDir)) ?: return null
        val bin = manifest.opt("bin") ?: return null
        val rel = when (bin) {
            is String -> bin
            is JSONObject -> {
                val short = name.substringAfterLast('/')
                bin.optString(short).takeIf { it.isNotEmpty() }
                    ?: bin.keys().asSequence().firstOrNull()?.let { bin.getString(it) }
            }
            else -> return null
        } ?: return null
        val f = File(pkgDir, rel).toPath().normalize().toFile()
        return f.takeIf { it.isFile }
    }
}
