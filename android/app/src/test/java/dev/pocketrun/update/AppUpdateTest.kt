package dev.pocketrun.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The updater's arithmetic and its reading of a GitHub release, without a device. */
class AppUpdateTest {

    private val release = """
        {
          "tag_name": "v1.5.0",
          "name": "PocketRun v1.5.0",
          "body": "песочница и обновления",
          "assets": [
            { "name": "PocketRun-v1.5.0-debug.apk", "size": 35,
              "browser_download_url": "https://example.test/debug.apk" },
            { "name": "PocketRun-v1.5.0-release.apk", "size": 19,
              "browser_download_url": "https://example.test/release.apk" }
          ]
        }
    """.trimIndent()

    @Test
    fun `the release apk is the one to install`() {
        val info = AppUpdate.parse(release)
        assertEquals("v1.5.0", info.tag)
        assertEquals("1.5.0", info.version)
        assertEquals("PocketRun-v1.5.0-release.apk", info.asset.name)
        assertEquals("https://example.test/release.apk", info.asset.url)
        assertEquals(19L, info.asset.size)
    }

    @Test
    fun `a differently named apk is still installable`() {
        val odd = release.replace("PocketRun-v1.5.0-release.apk", "pocketrun.apk")
        assertEquals("pocketrun.apk", AppUpdate.parse(odd).asset.name)
    }

    @Test(expected = AppUpdate.ParseException::class)
    fun `a release with only a debug apk is refused`() {
        AppUpdate.parse("""
            {"tag_name":"v1.5.0","assets":[
              {"name":"PocketRun-v1.5.0-debug.apk","size":35,"browser_download_url":"https://x/d.apk"}]}
        """.trimIndent())
    }

    @Test(expected = AppUpdate.ParseException::class)
    fun `a release without an apk is refused`() {
        AppUpdate.parse("""{"tag_name":"v1.5.0","assets":[]}""")
    }

    @Test(expected = AppUpdate.ParseException::class)
    fun `a draft is not an update`() {
        AppUpdate.parse("""{"tag_name":"v1.5.0","draft":true,"assets":[]}""")
    }

    @Test
    fun `the version is read out of the tag and the build suffix`() {
        assertEquals("1.5.0", AppUpdate.version("v1.5.0"))
        assertEquals("1.5.0", AppUpdate.version("1.5.0-debug"))
        assertEquals("1.5.1", AppUpdate.version("V1.5.1+build7"))
    }

    @Test
    fun `only a strictly newer version is an update`() {
        assertTrue(AppUpdate.isNewer("1.5.0", "1.4.2"))
        assertTrue(AppUpdate.isNewer("1.10.0", "1.9.9"))
        assertTrue(AppUpdate.isNewer("2.0", "1.99.99"))
        assertFalse(AppUpdate.isNewer("1.4.2", "1.4.2"))
        assertFalse(AppUpdate.isNewer("1.4.2", "1.5.0"))
        assertFalse(AppUpdate.isNewer("1.5", "1.5.0"))
    }

    @Test
    fun `a prerelease does not outrank its release`() {
        assertFalse(AppUpdate.isNewer("1.5.0-rc1", "1.5.0"))
    }
}
