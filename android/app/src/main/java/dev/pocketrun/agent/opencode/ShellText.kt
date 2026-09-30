package dev.pocketrun.agent.opencode

import java.io.File
import java.util.Locale

/**
 * Text filters for the sandbox shell: everything that turns bytes into other
 * bytes. Each reads stdin when no file is named, like the real tool, so they
 * compose in a pipeline.
 *
 * These are deliberate subsets. A half-implemented `sed` that quietly drops half
 * its syntax is worse than none, because the model will believe the edit
 * happened; where something is unsupported the command says so and exits
 * non-zero.
 */
internal object ShellText {

    /** Resolves a shell path to a sandboxed file, or null when it escapes. */
    fun interface Resolve {
        fun resolve(path: String, dir: File): File?
    }

    fun run(name: String, args: List<String>, dir: File, stdin: String, resolve: Resolve): MiniShell.Result? =
        when (name) {
            "sort" -> sort(args, dir, stdin, resolve)
            "uniq" -> uniq(args, dir, stdin, resolve)
            "cut" -> cut(args, dir, stdin, resolve)
            "tr" -> tr(args, dir, stdin, resolve)
            "sed" -> sed(args, dir, stdin, resolve)
            "awk" -> awk(args, dir, stdin, resolve)
            "nl" -> nl(args, dir, stdin, resolve)
            "tac" -> mapLines(args, dir, stdin, resolve) { it.reversed() }
            "rev" -> mapLines(args, dir, stdin, resolve) { it.reversed() }
            "expand" -> mapLines(args, dir, stdin, resolve, ::expandTabs)
            "fold" -> fold(args, dir, stdin, resolve)
            "tee" -> tee(args, dir, stdin, resolve)
            "seq" -> seq(args)
            "printf" -> printf(args)
            "basename" -> basename(args)
            "dirname" -> dirname(args)
            "diff" -> diff(args, dir, resolve)
            "jq" -> Jq.run(args, dir, stdin, resolve)
            else -> null
        }

    // ---------------------------------------------------------------- input

    /** Reads the named files, or stdin when there are none. */
    private fun input(args: List<String>, dir: File, stdin: String, resolve: Resolve): Pair<String, Int> {
        val files = args.filterNot { it.startsWith("-") }
        if (files.isEmpty()) return stdin to 0
        val out = StringBuilder()
        var exit = 0
        for (f in files) {
            val file = resolve.resolve(f, dir)
            if (file == null || !file.isFile) { exit = 1; out.append("нет такого файла: $f\n"); continue }
            if (file.length() > MiniShell.MAX_FILE) { exit = 1; out.append("файл слишком большой: $f\n"); continue }
            out.append(file.readText(Charsets.UTF_8))
        }
        return out.toString() to exit
    }

    /** Text without the trailing newline, split into lines. */
    private fun lines(text: String): List<String> {
        val parts = text.split("\n")
        return if (parts.isNotEmpty() && parts.last().isEmpty()) parts.dropLast(1) else parts
    }

    private fun join(list: List<String>): String =
        if (list.isEmpty()) "" else list.joinToString("\n") + "\n"

    /** Tab stops every 8 columns, so a tab advances to the next stop, not by 8. */
    private fun expandTabs(line: String): String {
        val out = StringBuilder()
        var column = 0
        for (c in line) {
            if (c == '\t') {
                val width = 8 - (column % 8)
                out.append(" ".repeat(width))
                column += width
            } else {
                out.append(c)
                column++
            }
        }
        return out.toString()
    }

    private fun mapLines(
        args: List<String>,
        dir: File,
        stdin: String,
        resolve: Resolve,
        block: (String) -> String,
    ): MiniShell.Result {
        val (text, exit) = input(args, dir, stdin, resolve)
        if (exit != 0) return MiniShell.Result(exit, text)
        return MiniShell.Result(0, join(lines(text).map(block)))
    }

    // ---------------------------------------------------------------- sort

    private fun sort(args: List<String>, dir: File, stdin: String, resolve: Resolve): MiniShell.Result {
        val numeric = args.any { it == "-n" || it == "-g" }
        val reverse = args.any { it == "-r" }
        val unique = args.any { it == "-u" }
        val ignoreCase = args.any { it == "-f" }
        val keyField = args.firstOrNull { it.startsWith("-k") }
            ?.removePrefix("-k")?.substringBefore(' ')?.trim()?.toIntOrNull()
        val sep = args.firstOrNull { it.startsWith("-t") }?.removePrefix("-t")?.takeIf { it.isNotEmpty() }

        val (text, exit) = input(args, dir, stdin, resolve)
        if (exit != 0) return MiniShell.Result(exit, text)

        fun keyOf(line: String): String {
            if (keyField == null) return line
            val parts = if (sep != null) line.split(sep) else line.split(Regex("\\s+"))
            return parts.getOrNull(keyField - 1) ?: ""
        }

        val comparator = Comparator<String> { a, b ->
            val ka = keyOf(a)
            val kb = keyOf(b)
            val primary = if (numeric) {
                (ka.trim().toDoubleOrNull() ?: Double.NEGATIVE_INFINITY)
                    .compareTo(kb.trim().toDoubleOrNull() ?: Double.NEGATIVE_INFINITY)
            } else {
                ka.compareTo(kb)
            }
            if (primary != 0) primary else a.compareTo(b)
        }
        val sorted = lines(text).sortedWith(comparator)
        val out = when {
            reverse && unique -> sorted.reversed().distinctBy { keyOf(it) }.reversed()
            reverse -> sorted.reversed()
            unique -> sorted.distinctBy { keyOf(it) }
            else -> sorted
        }
        val finalOut = if (ignoreCase) out.sortedWith(comparator) else out
        return MiniShell.Result(0, join(finalOut))
    }

    // ---------------------------------------------------------------- uniq

    private fun uniq(args: List<String>, dir: File, stdin: String, resolve: Resolve): MiniShell.Result {
        val count = args.any { it == "-c" }
        val onlyDuplicates = args.any { it == "-d" }
        val onlyUnique = args.any { it == "-u" }
        val ignoreCase = args.any { it == "-i" }
        val (text, exit) = input(args, dir, stdin, resolve)
        if (exit != 0) return MiniShell.Result(exit, text)

        val out = mutableListOf<String>()
        var runLine: String? = null
        var runCount = 0

        fun flush() {
            val line = runLine ?: return
            val keep = when {
                onlyDuplicates -> runCount > 1
                onlyUnique -> runCount == 1
                else -> true
            }
            if (keep) out += if (count) "${runCount.toString().padStart(7)} $line" else line
        }

        for (line in lines(text)) {
            val previous = runLine
            val same = previous != null && (if (ignoreCase) line.lowercase() else line) ==
                (if (ignoreCase) previous.lowercase() else previous)
            if (same) runCount++ else {
                flush()
                runLine = line
                runCount = 1
            }
        }
        flush()
        return MiniShell.Result(0, join(out))
    }

    // ---------------------------------------------------------------- cut

    private fun cut(args: List<String>, dir: File, stdin: String, resolve: Resolve): MiniShell.Result {
        val delim = args.firstOrNull { it.startsWith("--delimiter=") }?.removePrefix("--delimiter=")
            ?: args.firstOrNull { it.startsWith("-d") }?.removePrefix("-d")?.takeIf { it.isNotEmpty() }
        val fieldsSpec = args.firstOrNull { it.startsWith("-f") }?.removePrefix("-f")
        val charsSpec = args.firstOrNull { it.startsWith("-c") }?.removePrefix("-c")
        if (fieldsSpec == null && charsSpec == null) {
            return MiniShell.Result(1, "cut: нужен -f (поля) или -c (символы)\n")
        }
        val (text, exit) = input(args, dir, stdin, resolve)
        if (exit != 0) return MiniShell.Result(exit, text)

        val out = lines(text).map { line ->
            if (fieldsSpec != null) {
                val parts = if (delim != null) line.split(delim) else line.split("\t")
                positions(fieldsSpec, parts.size)
                    .map { r -> parts.subList(r.first - 1, r.last).joinToString(delim ?: "\t") }
                    .joinToString(delim ?: "\t")
            } else {
                buildString {
                    // positions() is 1-based, as the spec is; the string is not.
                    for (r in positions(charsSpec!!, line.length)) for (i in r) append(line[i - 1])
                }
            }
        }
        return MiniShell.Result(0, join(out))
    }

    /**
     * Expands `1,3-5,8-` into the selected ranges, clipped to [size]. An open
     * range runs to the end, and anything outside the line is dropped, which is
     * what cut does.
     */
    private fun positions(spec: String, size: Int): List<IntRange> {
        if (size <= 0) return emptyList()
        val out = mutableListOf<IntRange>()
        for (part in spec.split(',')) {
            val p = part.trim()
            if (p.isEmpty()) continue
            val sides = p.split('-')
            val range = when (sides.size) {
                1 -> {
                    val n = p.toIntOrNull() ?: continue
                    n..n
                }
                else -> {
                    val from = sides[0].trim().toIntOrNull() ?: continue
                    val toRaw = sides[1].trim()
                    if (toRaw.isEmpty()) from..Int.MAX_VALUE
                    else {
                        val to = toRaw.toIntOrNull() ?: continue
                        if (to < from) continue else from..to
                    }
                }
            }
            // Intersect in index space so a spec far past the end of the line
            // simply yields nothing rather than throwing.
            val from = maxOf(range.first, 1)
            val to = minOf(range.last, size)
            if (to >= from) out += from..to
        }
        return out
    }

    // ---------------------------------------------------------------- tr

    private fun tr(args: List<String>, dir: File, stdin: String, resolve: Resolve): MiniShell.Result {
        val sets = args.filterNot { it.startsWith("-") }
        val delete = args.any { it == "-d" || it == "--delete" }
        val squeeze = args.any { it == "-s" || it == "--squeeze-repeats" }
        val complement = args.any { it == "-c" || it == "-C" || it == "--complement" }
        // `tr -d SET` deletes, so one set is enough; otherwise both are required.
        if (sets.size < 2 && !(delete && sets.size == 1)) {
            return MiniShell.Result(1, "tr: нужны SET1 и SET2 (для -d достаточно одного)\n")
        }

        val from = expandSet(sets[0])
        val to = if (sets.size > 1) expandSet(sets[1]) else emptyList()
        val (text, exit) = input(args, dir, stdin, resolve)
        if (exit != 0) return MiniShell.Result(exit, text)

        val out = StringBuilder()
        var previous: Char? = null
        for (ch in text) {
            val hit = from.contains(ch)
            val selected = if (complement) !hit else hit
            if (delete && selected) continue
            val mapped = if (!selected) {
                ch
            } else if (complement && to.isEmpty()) {
                continue
            } else {
                to.getOrNull(if (complement) (ch.code - 'a'.code).mod(to.size.coerceAtLeast(1)) else from.indexOf(ch)) ?: ch
            }
            if (squeeze && mapped == previous) continue
            out.append(mapped)
            previous = mapped
        }
        return MiniShell.Result(0, out.toString())
    }

    /** Expands `a-z0-9_`, `[abc]` and `\n`-style escapes into the characters they name. */
    private fun expandSet(spec: String): List<Char> {
        val out = mutableListOf<Char>()
        var i = 0
        while (i < spec.length) {
            val c = spec[i]
            when {
                c == '\\' && i + 1 < spec.length -> {
                    out += when (val n = spec[i + 1]) {
                        'n' -> '\n'
                        't' -> '\t'
                        'r' -> '\r'
                        '0' -> '\u0000'
                        else -> n
                    }
                    i += 2
                }
                c == '[' -> {
                    val close = spec.indexOf(']', i)
                    if (close < 0) { out += c; i++ } else {
                        out += expandSet(spec.substring(i + 1, close))
                        i = close + 1
                    }
                }
                i + 2 < spec.length && spec[i + 1] == '-' && spec[i + 2] != ']' -> {
                    for (code in c.code..spec[i + 2].code) out += code.toChar()
                    i += 3
                }
                else -> { out += c; i++ }
            }
        }
        return out
    }

    // ---------------------------------------------------------------- sed

    /**
     * `sed` with what an agent actually uses: line addressing, `s///` with
     * `g`/`i`/`p`, plus `d`, `q` and `y///`.
     *
     * Hold space, `r`/`w` and branches are refused rather than ignored.
     */
    private fun sed(argsIn: List<String>, dir: File, stdin: String, resolve: Resolve): MiniShell.Result {
        val script = argsIn.firstOrNull { !it.startsWith("-") }
            ?: return MiniShell.Result(1, "sed: нужен скрипт, например sed 's/a/b/g' file\n")
        val quiet = argsIn.any { it == "-n" }
        val inPlace = argsIn.any { it == "-i" || it.startsWith("-i.") }

        val commands = parseSed(script)
            ?: return MiniShell.Result(
                1,
                "sed: неподдерживаемый скрипт «$script» (доступны s///, y///, d, p, q с адресами)\n",
            )

        val files = argsIn.filterNot { it.startsWith("-") && it != script }
        val (text, exit) = input(files, dir, stdin, resolve)
        if (exit != 0) return MiniShell.Result(exit, text)

        val out = mutableListOf<String>()
        var quit = false
        for ((index, original) in lines(text).withIndex()) {
            var current: String? = original
            for (c in commands) {
                if (current == null) break
                if (!c.addresses.any { it.matches(index + 1, original) }) continue
                val line = current
                when (c.op) {
                    's' -> current = line.replace(c.regex, c.replacement)
                    'y' -> current = translate(line, c.from, c.to)
                    'd' -> current = null
                    'p' -> out += line
                    'q' -> { out += line; quit = true; break }
                }
            }
            if (quit) break
            if (current != null) out += current
        }

        if (inPlace && files.isNotEmpty()) {
            for (f in files) {
                val file = resolve.resolve(f, dir) ?: continue
                runCatching { file.writeText(join(out), Charsets.UTF_8) }
            }
            return MiniShell.Result(0, "")
        }
        return MiniShell.Result(0, if (quiet) "" else join(out))
    }

    private class SedCommand(
        val addresses: List<SedAddress>,
        val op: Char,
        val regex: Regex = Regex(""),
        val replacement: String = "",
        val from: String = "",
        val to: String = "",
    )

    private sealed interface SedAddress {
        fun matches(line: Int, text: String): Boolean
        data class Num(val n: Int) : SedAddress {
            override fun matches(line: Int, text: String) = line == n
        }
        data class Pattern(val re: Regex) : SedAddress {
            override fun matches(line: Int, text: String) = re.containsMatchIn(text)
        }
    }

    private fun parseSed(script: String): List<SedCommand>? {
        val out = mutableListOf<SedCommand>()
        var i = 0
        while (i < script.length) {
            while (i < script.length && (script[i] == ' ' || script[i] == ';')) i++
            if (i >= script.length) break

            val addresses = mutableListOf<SedAddress>()
            while (i < script.length) {
                when {
                    script[i].isDigit() -> {
                        var n = 0
                        while (i < script.length && script[i].isDigit()) { n = n * 10 + (script[i] - '0'); i++ }
                        addresses += SedAddress.Num(n)
                    }
                    script[i] == '$' -> { addresses += SedAddress.Pattern(Regex(".*")); i++ }
                    script[i] == '/' -> {
                        val close = script.indexOf('/', i + 1)
                        if (close < 0) return null
                        addresses += SedAddress.Pattern(Regex(script.substring(i + 1, close)))
                        i = close + 1
                    }
                    else -> break
                }
                if (i < script.length && script[i] == ',') i++ else break
            }

            when (script.getOrNull(i)) {
                's' -> {
                    // `s|a|b|`, `s#a#b#` and `s/a/b/` are all the same command.
                    val delim = script.getOrNull(i + 1) ?: return null
                    if (!delim.isLetterOrDigit()) return null
                    val end = findDelimiter(script, i + 2, delim)
                    val parts = splitUnescaped(script.substring(i + 2, end), delim)
                    if (parts.size < 2) return null
                    val flags = parts.getOrNull(2)?.trim().orEmpty()
                    if (flags.any { it !in "gip" }) return null
                    out += SedCommand(
                        addresses = addresses,
                        op = 's',
                        regex = Regex(
                            parts[0],
                            if (flags.contains('i')) RegexOption.IGNORE_CASE else RegexOption.DOT_MATCHES_ALL,
                        ),
                        // `\1`..`\9` become Kotlin's group reference.
                        replacement = parts[1].replace(Regex("""\\(\d)"""), "\$1"),
                    )
                    i = end + 1
                }
                'y' -> {
                    val delim = script.getOrNull(i + 1) ?: return null
                    if (!delim.isLetterOrDigit()) return null
                    val end = findDelimiter(script, i + 2, delim)
                    val parts = splitUnescaped(script.substring(i + 2, end), delim)
                    if (parts.size < 2) return null
                    out += SedCommand(addresses, 'y', from = parts[0], to = parts[1])
                    i = end + 1
                }
                'd', 'p', 'q' -> { out += SedCommand(addresses, script[i]); i++ }
                else -> return null
            }
        }
        return out.ifEmpty { null }
    }

    private fun findDelimiter(s: String, from: Int, delim: Char): Int {
        var i = from
        while (i < s.length) {
            if (s[i] == '\\') { i += 2; continue }
            if (s[i] == delim) return i
            i++
        }
        return s.length
    }

    private fun splitUnescaped(s: String, delim: Char): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\\' && i + 1 < s.length -> { cur.append(c).append(s[i + 1]); i += 2 }
                c == delim -> { out += cur.toString(); cur.clear(); i++ }
                else -> { cur.append(c); i++ }
            }
        }
        out += cur.toString()
        return out
    }

    private fun translate(line: String, from: String, to: String): String {
        val f = expandSet(from)
        val t = expandSet(to)
        return buildString {
            for (ch in line) {
                val at = f.indexOf(ch)
                append(if (at >= 0) t.getOrNull(at) ?: ch else ch)
            }
        }
    }

    // ---------------------------------------------------------------- awk

    /**
     * A deliberately small awk: `[pattern] { print/printf ... }` rules with `-F`
     * and `-v`, plus field references, `NF` and `NR`.
     *
     * A real awk language is a project of its own, and a partial one that accepts
     * a script and quietly does something else is a trap.
     */
    private fun awk(argsIn: List<String>, dir: File, stdin: String, resolve: Resolve): MiniShell.Result {
        var fieldSep = " "
        val assigns = mutableListOf<String>()
        var rest = argsIn
        while (rest.isNotEmpty() && rest.first().startsWith("-")) {
            val a = rest.first()
            when {
                a == "-F" -> { fieldSep = rest.getOrNull(1) ?: " "; rest = rest.drop(2) }
                a.startsWith("-F") -> { fieldSep = a.drop(2); rest = rest.drop(1) }
                a == "-v" -> { rest.getOrNull(1)?.let { assigns += it }; rest = rest.drop(2) }
                else -> rest = rest.drop(1)
            }
        }
        if (rest.isEmpty()) return MiniShell.Result(1, "awk: нужен скрипт\n")
        val script = rest.first()

        val rules = parseAwk(script)
            ?: return MiniShell.Result(1, "awk: поддержаны только правила [шаблон]{print/printf}\n")
        val (text, exit) = input(rest.drop(1), dir, stdin, resolve)
        if (exit != 0) return MiniShell.Result(exit, text)

        val vars = HashMap<String, String>()
        for (a in assigns) {
            val m = Regex("""^\s*([A-Za-z_]\w*)\s*=\s*(.+?)\s*$""").find(a)
            if (m != null) vars[m.groupValues[1]] = m.groupValues[2].trim().trim('"', '\'')
        }
        vars["FS"] = fieldSep
        vars["OFS"] = " "

        val out = StringBuilder()
        var nr = 0
        for (line in lines(text)) {
            nr++
            val fields = if (fieldSep == " ") line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            else if (fieldSep == "\\t" || fieldSep == "\t") line.split("\t")
            else line.split(fieldSep)

            fun fieldOf(reference: String): String = when (reference) {
                "NF" -> fields.size.toString()
                "NR" -> nr.toString()
                "\$0" -> line
                else -> if (reference.startsWith("$")) {
                    val n = reference.drop(1).toIntOrNull() ?: 0
                    if (n == 0) line else fields.getOrNull(n - 1) ?: ""
                } else {
                    vars[reference] ?: ""
                }
            }

            for (rule in rules) {
                if (!awkMatches(rule.pattern, line, vars)) continue
                out.append(
                    when (rule.action) {
                        null -> line
                        else -> renderAwk(rule.action, ::fieldOf, vars["OFS"] ?: " ")
                    },
                ).append('\n')
            }
        }
        return MiniShell.Result(0, out.toString())
    }

    private class AwkRule(val pattern: String?, val action: String?)

    private fun parseAwk(script: String): List<AwkRule>? {
        val rules = mutableListOf<AwkRule>()
        var i = 0
        while (i < script.length) {
            while (i < script.length && script[i].isWhitespace()) i++
            if (i >= script.length) break
            if (script[i] == '{') {
                val end = script.indexOf('}', i)
                if (end < 0) return null
                val action = script.substring(i + 1, end).trim()
                if (!isPrintAction(action)) return null
                rules += AwkRule(null, action)
                i = end + 1
            } else {
                var pattern = StringBuilder()
                while (i < script.length && script[i] != '{') { pattern.append(script[i]); i++ }
                if (i >= script.length) return null
                val end = script.indexOf('}', i)
                if (end < 0) return null
                val action = script.substring(i + 1, end).trim()
                if (!isPrintAction(action)) return null
                rules += AwkRule(pattern.toString().trim(), action)
                i = end + 1
            }
        }
        return rules.ifEmpty { null }
    }

    private fun isPrintAction(action: String): Boolean =
        action.isEmpty() || action.startsWith("print") || action.startsWith("printf")

    private fun awkMatches(pattern: String?, line: String, vars: Map<String, String>): Boolean {
        val source = pattern ?: return true
        if (source.isEmpty()) return true
        var resolved = source
        for ((k, v) in vars) if (v.isNotEmpty()) resolved = resolved.replace("\$$k", v)
        if (resolved.length > 1 && resolved.startsWith("/") && resolved.endsWith("/")) {
            return runCatching { Regex(resolved.substring(1, resolved.length - 1)).containsMatchIn(line) }
                .getOrDefault(false)
        }
        val comparison = Regex("""^([A-Za-z_]\w*)\s*(==|=|!=|-eq|-ne|-lt|-le|-gt|-ge)\s*(.+)$""").find(resolved)
        if (comparison != null) {
            val actual = vars[comparison.groupValues[1]] ?: ""
            val want = comparison.groupValues[3].trim().trim('"', '\'')
            return when (comparison.groupValues[2]) {
                "=", "==" -> actual == want
                "!=", "-ne" -> actual != want
                "-lt" -> actual.compareTo(want) < 0
                "-le" -> actual.compareTo(want) <= 0
                "-gt" -> actual.compareTo(want) > 0
                "-ge" -> actual.compareTo(want) >= 0
                else -> false
            }
        }
        return runCatching { Regex(resolved).containsMatchIn(line) }.getOrDefault(false)
    }

    /**
     * Renders `print ...` / `printf ...`. The argument list is comma-separated
     * field references, quoted literals or bare words, joined by OFS.
     */
    private fun renderAwk(action: String, fieldOf: (String) -> String, ofs: String): String {
        val isPrintf = action.startsWith("printf")
        val body = action.drop(if (isPrintf) 6 else 5).trim()
        if (body.isEmpty()) return fieldOf("\$0")
        val parts = splitTopLevelCommas(body)
        return parts.mapIndexed { index, token ->
            val t = token.trim()
            val value = when {
                t.length >= 2 && t.startsWith('"') && t.endsWith('"') -> t.substring(1, t.length - 1)
                t.startsWith("$") -> fieldOf(t)
                t.isEmpty() -> ""
                else -> fieldOf("\$$t")
            }
            if (index < parts.size - 1 && !isPrintf) value + ofs else value
        }.joinToString("")
    }

    private fun splitTopLevelCommas(body: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var inString = false
        for (c in body) {
            when {
                c == '"' -> { inString = !inString; cur.append(c) }
                c == ',' && !inString -> { out += cur.toString(); cur.clear() }
                else -> cur.append(c)
            }
        }
        out += cur.toString()
        return out
    }

    // ---------------------------------------------------------------- nl / fold / tee

    /** POSIX nl numbers non-empty lines; -ba numbers blank ones too. */
    private fun nl(args: List<String>, dir: File, stdin: String, resolve: Resolve): MiniShell.Result {
        val numberBlanks = args.any { it == "-ba" }
        val (text, exit) = input(args, dir, stdin, resolve)
        if (exit != 0) return MiniShell.Result(exit, text)
        var n = 0
        return MiniShell.Result(
            0,
            join(
                lines(text).map { line ->
                    if (line.isEmpty() && !numberBlanks) line
                    else { n++; n.toString().padStart(6) + "\t" + line }
                },
            ),
        )
    }

    private fun fold(args: List<String>, dir: File, stdin: String, resolve: Resolve): MiniShell.Result {
        val width = args.firstOrNull { it.startsWith("-w") }?.removePrefix("-w")?.toIntOrNull() ?: 80
        val (text, exit) = input(args, dir, stdin, resolve)
        if (exit != 0) return MiniShell.Result(exit, text)
        val out = StringBuilder()
        for (line in lines(text)) {
            if (line.isEmpty()) { out.append('\n'); continue }
            var i = 0
            while (i < line.length) {
                out.append(line, i, minOf(i + width, line.length)).append('\n')
                i += width
            }
        }
        return MiniShell.Result(0, out.toString())
    }

    private fun tee(args: List<String>, dir: File, stdin: String, resolve: Resolve): MiniShell.Result {
        val append = args.any { it == "-a" }
        for (target in args.filterNot { it.startsWith("-") }) {
            val f = resolve.resolve(target, dir) ?: continue
            runCatching {
                f.parentFile?.mkdirs()
                if (append) f.appendText(stdin, Charsets.UTF_8) else f.writeText(stdin, Charsets.UTF_8)
            }
        }
        return MiniShell.Result(0, stdin)
    }

    // ---------------------------------------------------------------- seq / printf / paths

    private fun seq(args: List<String>): MiniShell.Result {
        val nums = args.filterNot { it.startsWith("-") }
        val separator = args.firstOrNull { it.startsWith("-s") }?.removePrefix("-s")?.takeIf { it.isNotEmpty() }
        val values = nums.map { it.toLongOrNull() ?: return MiniShell.Result(1, "seq: нужно число\n") }
        val (first, step, last) = when (values.size) {
            1 -> Triple(1L, 1L, values[0])
            2 -> Triple(values[0], 1L, values[1])
            3 -> Triple(values[0], values[1], values[2])
            else -> return MiniShell.Result(1, "seq: нужно от одного до трёх чисел\n")
        }
        if (step == 0L) return MiniShell.Result(1, "seq: шаг не может быть нулём\n")
        val out = StringBuilder()
        var i = first
        var guard = 0
        while ((step > 0 && i <= last) || (step < 0 && i >= last)) {
            if (guard++ > 1_000_000) break
            out.append(i).append(separator ?: "\n")
            i += step
        }
        return MiniShell.Result(0, out.toString())
    }

    private fun printf(args: List<String>): MiniShell.Result {
        if (args.isEmpty()) return MiniShell.Result(0, "")
        val format = args.first()
        val values = args.drop(1)
        var argIndex = 0
        val out = StringBuilder()
        var i = 0
        while (i < format.length) {
            val c = format[i]
            if (c == '\\') {
                out.append(
                    when (val n = format.getOrNull(i + 1)) {
                        'n' -> '\n'
                        't' -> '\t'
                        'r' -> '\r'
                        '\\' -> '\\'
                        null -> { out.append(c); i++; continue }
                        else -> n
                    },
                )
                i += 2
                continue
            }
            if (c == '%' && format.getOrNull(i + 1) == '%') { out.append('%'); i += 2; continue }
            if (c == '%') {
                var j = i + 1
                while (j < format.length && (format[j].isLetterOrDigit() || format[j] in ".-+ #0")) j++
                val spec = format.substring(i + 1, j)
                out.append(formatValue(spec, values.getOrNull(argIndex++)))
                i = j
                continue
            }
            out.append(c)
            i++
        }
        return MiniShell.Result(0, out.toString())
    }

    private fun formatValue(spec: String, value: String?): String {
        if (value == null) return ""
        val letter = spec.lastOrNull() ?: return value
        val body = spec.dropLast(1)
        val width = Regex("""(\d+)$""").find(body)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val leftAlign = body.contains('-')
        val zeroPad = body.contains('0')
        val precision = Regex("""\.(\d+)""").find(body)?.groupValues?.get(1)?.toIntOrNull()

        var text = when (letter) {
            'd', 'i' -> {
                val n = value.trim().toDoubleOrNull()?.toLong() ?: return value
                if (n < 0) "-${-n}" else n.toString()
            }
            'f', 'F' -> {
                val n = value.trim().toDoubleOrNull() ?: return value
                String.format(Locale.US, "%.${precision ?: 6}f", n)
            }
            'x' -> (value.trim().toLongOrNull() ?: return value).toString(16)
            'X' -> (value.trim().toLongOrNull() ?: return value).toString(16).uppercase()
            'o' -> (value.trim().toLongOrNull() ?: return value).toString(8)
            's' -> if (precision != null) value.take(precision) else value
            else -> value
        }
        if (width > text.length) {
            var sign = ""
            if (!leftAlign && zeroPad && text.startsWith("-")) { sign = "-"; text = text.drop(1) }
            val pad = if (leftAlign || letter == 's' && !zeroPad) " " else if (zeroPad) "0" else " "
            text = sign + pad.repeat(width - text.length) + text
        }
        return text
    }

    private fun basename(args: List<String>): MiniShell.Result {
        val path = args.firstOrNull() ?: return MiniShell.Result(1, "basename: нужен путь\n")
        val suffix = args.getOrNull(1)
        var name = path.trimEnd('/').substringAfterLast('/').ifEmpty { "/" }
        if (suffix != null && name.length > suffix.length && name.endsWith(suffix)) {
            name = name.dropLast(suffix.length)
        }
        return MiniShell.Result(0, "$name\n")
    }

    private fun dirname(args: List<String>): MiniShell.Result {
        val path = args.firstOrNull() ?: return MiniShell.Result(1, "dirname: нужен путь\n")
        val trimmed = path.trimEnd('/')
        if (!trimmed.contains('/')) return MiniShell.Result(0, ".\n")
        if (trimmed == "/") return MiniShell.Result(0, "/\n")
        val parent = trimmed.substringBeforeLast('/')
        return MiniShell.Result(0, (parent.ifEmpty { "/" }) + "\n")
    }

    // ---------------------------------------------------------------- diff

    private fun diff(args: List<String>, dir: File, resolve: Resolve): MiniShell.Result {
        val files = args.filterNot { it.startsWith("-") }
        if (files.size != 2) return MiniShell.Result(1, "diff: нужны два файла\n")
        val brief = args.any { it == "-q" }
        val a = resolve.resolve(files[0], dir)?.takeIf { it.isFile }
            ?: return MiniShell.Result(2, "diff: ${files[0]}: нет такого файла\n")
        val b = resolve.resolve(files[1], dir)?.takeIf { it.isFile }
            ?: return MiniShell.Result(2, "diff: ${files[1]}: нет такого файла\n")
        val left = a.readLines()
        val right = b.readLines()
        if (left == right) return MiniShell.Result(0, "")
        if (brief) return MiniShell.Result(1, "Файлы ${files[0]} и ${files[1]} различаются\n")
        val out = StringBuilder("--- ${files[0]}\n+++ ${files[1]}\n")
        var i = 0
        while (i < maxOf(left.size, right.size)) {
            val l = left.getOrNull(i)
            val r = right.getOrNull(i)
            when {
                l == null -> out.append("+${r}\n")
                r == null -> out.append("-${l}\n")
                l != r -> out.append("-${l}\n+${r}\n")
            }
            i++
        }
        return MiniShell.Result(1, out.toString())
    }
}
