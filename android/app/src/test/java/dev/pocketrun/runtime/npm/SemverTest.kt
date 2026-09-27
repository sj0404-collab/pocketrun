package dev.pocketrun.runtime.npm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SemverTest {

    @Test
    fun parseBasics() {
        assertEquals(Semver.V(1, 2, 3, emptyList()), Semver.parse("1.2.3"))
        assertEquals(Semver.V(1, 2, 3, emptyList()), Semver.parse("v1.2.3"))
        assertEquals(Semver.V(0, 10, 0, listOf("beta", "1")), Semver.parse("0.10.0-beta.1"))
        assertNull(Semver.parse("1.2"))
        assertNull(Semver.parse("banana"))
        assertNull(Semver.parse("1.2.3.4"))
    }

    @Test
    fun compareOrdering() {
        val v = listOf("1.0.0", "1.0.1", "1.1.0", "2.0.0", "1.0.0-alpha", "1.0.0-beta.2", "1.0.0-beta.10", "1.0.0-rc.1")
            .map { Semver.parse(it)!! }
        val sorted = v.sortedWith { a, b -> Semver.compare(a, b) }.map { "${it.major}.${it.minor}.${it.patch}${if (it.pre.isEmpty()) "" else "-" + it.pre.joinToString(".")}" }
        // prereleases of 1.0.0 sort below the stable 1.0.0, in identifier order (numeric aware)
        assertEquals(
            listOf("1.0.0-alpha", "1.0.0-beta.2", "1.0.0-beta.10", "1.0.0-rc.1", "1.0.0", "1.0.1", "1.1.0", "2.0.0"),
            sorted,
        )
    }

    @Test
    fun exactAndXRanges() {
        assertTrue(Semver.satisfiesString("1.2.3", "1.2.3"))
        assertFalse(Semver.satisfiesString("1.2.4", "1.2.3"))
        assertTrue(Semver.satisfiesString("1.2.9", "1.2.x"))
        assertTrue(Semver.satisfiesString("1.2.9", "1.2"))
        assertFalse(Semver.satisfiesString("1.3.0", "1.2.x"))
        assertTrue(Semver.satisfiesString("1.9.9", "1.x"))
        assertTrue(Semver.satisfiesString("1.9.9", "1"))
        assertFalse(Semver.satisfiesString("2.0.0", "1.x"))
        assertTrue(Semver.satisfiesString("5.0.0", "*"))
    }

    @Test
    fun caret() {
        assertTrue(Semver.satisfiesString("1.4.7", "^1.2.3"))
        assertTrue(Semver.satisfiesString("1.2.3", "^1.2.3"))
        assertFalse(Semver.satisfiesString("2.0.0", "^1.2.3"))
        assertFalse(Semver.satisfiesString("1.2.2", "^1.2.3"))
        assertTrue(Semver.satisfiesString("0.2.9", "^0.2.3"))
        assertFalse(Semver.satisfiesString("0.3.0", "^0.2.3"))
        assertTrue(Semver.satisfiesString("0.0.3", "^0.0.3"))
        assertFalse(Semver.satisfiesString("0.0.4", "^0.0.3"))
        assertTrue(Semver.satisfiesString("1.5.0", "^1.2.x"))
    }

    @Test
    fun tilde() {
        assertTrue(Semver.satisfiesString("1.2.9", "~1.2.3"))
        assertFalse(Semver.satisfiesString("1.3.0", "~1.2.3"))
        assertTrue(Semver.satisfiesString("1.2.0", "~1.2"))
        assertTrue(Semver.satisfiesString("1.9.0", "~1"))
        assertFalse(Semver.satisfiesString("2.0.0", "~1"))
    }

    @Test
    fun comparators() {
        assertTrue(Semver.satisfiesString("1.5.0", ">=1.0.0"))
        assertFalse(Semver.satisfiesString("0.9.0", ">=1.0.0"))
        assertTrue(Semver.satisfiesString("1.0.0", "<2.0.0"))
        assertFalse(Semver.satisfiesString("2.0.0", "<2.0.0"))
        assertTrue(Semver.satisfiesString("1.5.0", ">=1.0.0 <2.0.0"))
        assertFalse(Semver.satisfiesString("2.1.0", ">=1.0.0 <2.0.0"))
        assertTrue(Semver.satisfiesString("1.2.3", "=1.2.3"))
    }

    @Test
    fun orGroupsAndPrerelease() {
        assertTrue(Semver.satisfiesString("1.0.0", "^1.0.0 || ^2.0.0"))
        assertTrue(Semver.satisfiesString("2.3.0", "^1.0.0 || ^2.0.0"))
        assertFalse(Semver.satisfiesString("3.0.0", "^1.0.0 || ^2.0.0"))
        // prerelease versions only match ranges mentioning a prerelease of the same triple
        assertFalse(Semver.satisfiesString("2.0.0-rc.1", "^1.0.0 || ^2.0.0"))
        assertTrue(Semver.satisfiesString("2.0.0-rc.1", ">=2.0.0-rc.0"))
        assertTrue(Semver.satisfiesString("2.0.0-rc.1", "^2.0.0-rc.0"))
    }

    @Test
    fun unsupportedRanges() {
        assertFalse(Semver.isSupportedRange("github:user/repo"))
        assertFalse(Semver.isSupportedRange("git+https://x/y.git"))
        assertFalse(Semver.isSupportedRange("file:../local"))
        assertFalse(Semver.isSupportedRange("npm:foo@1.0.0"))
        assertTrue(Semver.isSupportedRange("^1.2.3"))
        assertTrue(Semver.isSupportedRange("*"))
    }
}
