package dev.pocketrun.runtime.npm

/**
 * The slice of semver npm needs: parse, compare, and range matching
 * (`^1.2.3`, `~1.2`, `>=1.0 <2.0`, `1.2.x`, `*`, `||`, exact, dist-tags are
 * handled by the caller). Prerelease versions only satisfy ranges that ask
 * for a prerelease of the same major.minor.patch, like node-semver does.
 */
object Semver {

    data class V(val major: Int, val minor: Int, val patch: Int, val pre: List<String>) {
        val isStable: Boolean get() = pre.isEmpty()
    }

    private val FULL = Regex("""^v?(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.\-]+))?${'$'}""")

    fun parse(s: String?): V? {
        if (s == null) return null
        val m = FULL.matchEntire(s.trim()) ?: return null
        return V(
            m.groupValues[1].toInt(),
            m.groupValues[2].toInt(),
            m.groupValues[3].toInt(),
            m.groupValues[4].takeIf { it.isNotEmpty() }?.split('.') ?: emptyList(),
        )
    }

    fun compare(a: V, b: V): Int {
        (a.major.compareTo(b.major)).let { if (it != 0) return it }
        (a.minor.compareTo(b.minor)).let { if (it != 0) return it }
        (a.patch.compareTo(b.patch)).let { if (it != 0) return it }
        // A stable version sorts above any prerelease of the same numbers.
        if (a.pre.isEmpty() && b.pre.isEmpty()) return 0
        if (a.pre.isEmpty()) return 1
        if (b.pre.isEmpty()) return -1
        val n = minOf(a.pre.size, b.pre.size)
        for (i in 0 until n) {
            val x = a.pre[i]; val y = b.pre[i]
            val xn = x.toIntOrNull(); val yn = y.toIntOrNull()
            val c = when {
                xn != null && yn != null -> xn.compareTo(yn)
                xn != null -> -1 // numeric < alphanumeric
                yn != null -> 1
                else -> x.compareTo(y)
            }
            if (c != 0) return c
        }
        return a.pre.size.compareTo(b.pre.size)
    }

    /** True when [range] is something we can evaluate (not git:/file:/workspace: specs). */
    fun isSupportedRange(range: String): Boolean {
        val r = range.trim()
        if (r.isEmpty()) return true
        if (r.contains("://")) return false
        if (listOf("github:", "git+", "file:", "link:", "npm:", "workspace:", "http:", "https:").any { r.startsWith(it) }) return false
        return true
    }

    fun satisfies(version: V, range: String): Boolean {
        return range.split("||").any { group -> satisfiesGroup(version, group.trim()) }
    }

    fun satisfiesString(version: String, range: String): Boolean {
        val v = parse(version) ?: return false
        return satisfies(v, range)
    }

    // ---------------------------------------------------------------- group

    private fun satisfiesGroup(v: V, group: String): Boolean {
        if (group.isEmpty() || group == "*" || group == "latest") return true
        val parts = group.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val ok = parts.all { satisfiesSingle(v, it) }
        if (!ok) return false
        // Prerelease versions need a comparator on the same x.y.z with a prerelease.
        if (!v.isStable) {
            return parts.any { hasPrereleaseOn(it, v) }
        }
        return true
    }

    private fun hasPrereleaseOn(comparator: String, v: V): Boolean {
        val spec = comparator.trimStart('^', '~', '>', '<', '=', ' ')
        return parse(spec)?.let { it.major == v.major && it.minor == v.minor && it.patch == v.patch && !it.isStable } == true
    }

    private fun satisfiesSingle(v: V, c: String): Boolean {
        if (c.isEmpty() || c == "*" || c == "x" || c == "X") return true
        return when {
            c.startsWith("^") -> caret(v, c.substring(1))
            c.startsWith("~>") -> tilde(v, c.substring(2)) // npm treats ~> like ~
            c.startsWith("~") -> tilde(v, c.substring(1))
            c.startsWith(">=") -> cmp(v, c.substring(2)) >= 0
            c.startsWith("<=") -> cmp(v, c.substring(2)) <= 0
            c.startsWith(">") -> cmp(v, c.substring(1)) > 0
            c.startsWith("<") -> cmp(v, c.substring(1)) < 0
            c.startsWith("=") -> cmp(v, c.substring(1)) == 0
            else -> xRange(v, c)
        }
    }

    // ---------------------------------------------------------------- helpers

    /** compare v against a plain version spec; Int.MIN_VALUE when unparsable. */
    private fun cmp(v: V, spec: String): Int {
        val o = parse(spec) ?: return Int.MIN_VALUE
        return compare(v, o)
    }

    private fun lower(v: V, spec: String, orEqual: Boolean): Boolean {
        val c = cmp(v, spec)
        if (c == Int.MIN_VALUE) return false
        return if (orEqual) c >= 0 else c > 0
    }

    private fun upper(v: V, spec: String, orEqual: Boolean): Boolean {
        val c = cmp(v, spec)
        if (c == Int.MIN_VALUE) return false
        return if (orEqual) c <= 0 else c < 0
    }

    private fun exactX(v: V, spec: String): Boolean {
        // "1.2.3" exact, "1.2.x"/"1.2" → within 1.2.*, "1.x"/"1" → within 1.*
        val t = spec.trim().removePrefix("v").split('.')
        val nums = mutableListOf<Int>()
        var sawX = false
        for (part in t) {
            if (part == "x" || part == "X" || part == "*") { sawX = true; break }
            val n = part.toIntOrNull() ?: return false
            nums += n
        }
        if (nums.size == 3 && !sawX) return cmp(v, spec) == 0
        return when (nums.size) {
            0 -> true
            1 -> v.major == nums[0]
            2 -> v.major == nums[0] && v.minor == nums[1]
            else -> false
        }
    }

    private fun xRange(v: V, c: String) = exactX(v, c)

    private fun caret(v: V, spec: String): Boolean {
        // ^1.2.3 → >=1.2.3 <2.0.0; ^0.2.3 → <0.3.0; ^0.0.3 → <0.0.4;
        // ^2.0.0-rc.0 keeps the prerelease lower bound: >=2.0.0-rc.0 <3.0.0
        val full = parse(spec)
        if (full != null) {
            val upperSpec = when {
                full.major > 0 -> "${full.major + 1}.0.0"
                full.minor > 0 -> "0.${full.minor + 1}.0"
                full.patch > 0 -> "0.0.${full.patch + 1}"
                else -> return false
            }
            return lower(v, spec, true) && upper(v, upperSpec, false)
        }
        // x-parts: ^1.2.x → >=1.2.0 <2.0.0
        val t = spec.trim().removePrefix("v").split('.')
        val parts = mutableListOf<Int>()
        for (part in t) {
            if (part == "x" || part == "X" || part == "*") break
            val n = part.toIntOrNull() ?: break
            parts += n
        }
        while (parts.size < 3) parts += 0
        val base = "${parts[0]}.${parts[1]}.${parts[2]}"
        if (!lower(v, base, true)) return false
        val upperSpec = when {
            parts[0] > 0 -> "${parts[0] + 1}.0.0"
            parts[1] > 0 -> "0.${parts[1] + 1}.0"
            parts[2] > 0 -> "0.0.${parts[2] + 1}"
            else -> return false // ^0.0.0 — degenerate
        }
        return upper(v, upperSpec, false)
    }

    private fun tilde(v: V, spec: String): Boolean {
        // ~1.2.3 → >=1.2.3 <1.3.0; ~1.2 → <1.3.0; ~1 → <2.0.0
        val full = parse(spec)
        if (full != null) {
            val upperSpec = if (spec.trim().removePrefix("v").split('.').size >= 2) {
                "${full.major}.${full.minor + 1}.0"
            } else {
                "${full.major + 1}.0.0"
            }
            return lower(v, spec, true) && upper(v, upperSpec, false)
        }
        val t = spec.trim().removePrefix("v").split('.')
        val parts = mutableListOf<Int>()
        for (part in t) {
            if (part == "x" || part == "X" || part == "*") break
            val n = part.toIntOrNull() ?: break
            parts += n
        }
        while (parts.size < 3) parts += 0
        if (!lower(v, "${parts[0]}.${parts[1]}.${parts[2]}", true)) return false
        val upperSpec = if (t.size >= 2) "${parts[0]}.${parts[1] + 1}.0" else "${parts[0] + 1}.0.0"
        return upper(v, upperSpec, false)
    }
}
