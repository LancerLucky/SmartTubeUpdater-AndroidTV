package com.smarttubeupdater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the pure-Kotlin release logic against the real asset names that
 * yuliskov/SmartTube publishes (SmartTube_stable_32.80_arm64-v8a.apk, ...).
 */
class ReleaseSelectionTest {

    private fun stableRelease(tag: String = "32.80s") = Release(
        tag = tag,
        title = "SmartTube $tag",
        publishedAt = 1L,
        notes = "",
        pageUrl = "https://github.com/yuliskov/SmartTube/releases/tag/$tag",
        assets = listOf(
            "SmartTube_stable_32.80_arm64-v8a.apk",
            "SmartTube_stable_32.80_armeabi-v7a.apk",
            "SmartTube_stable_32.80_universal.apk",
            "SmartTube_stable_32.80_x86.apk"
        ).mapIndexed { index, name ->
            ReleaseAsset(name, "https://example.test/$name", 32_000_000L + index)
        }
    )

    @Test
    fun `stable tag loses its s suffix`() {
        assertEquals("32.80", stableRelease("32.80s").versionLabel)
        assertEquals("32.80", stableRelease("32.80S").versionLabel)
        assertEquals("32.83", stableRelease("32.83").versionLabel)
    }

    @Test
    fun `release is stable only when stable apks are attached`() {
        assertTrue(stableRelease().isStable)

        val beta = stableRelease().copy(
            assets = listOf(ReleaseAsset("SmartTube_beta_32.83_universal.apk", "u", 1L))
        )
        assertTrue(!beta.isStable)
    }

    @Test
    fun `arm64 device gets the arm64 apk`() {
        val asset = stableRelease().pickAsset(listOf("arm64-v8a", "armeabi-v7a", "armeabi"))
        assertEquals("SmartTube_stable_32.80_arm64-v8a.apk", asset?.name)
    }

    @Test
    fun `32-bit device gets the armeabi-v7a apk`() {
        val asset = stableRelease().pickAsset(listOf("armeabi-v7a", "armeabi"))
        assertEquals("SmartTube_stable_32.80_armeabi-v7a.apk", asset?.name)
    }

    @Test
    fun `x86_64 device falls back to the x86 apk`() {
        val asset = stableRelease().pickAsset(listOf("x86_64", "x86"))
        assertEquals("SmartTube_stable_32.80_x86.apk", asset?.name)
    }

    @Test
    fun `unknown abi falls back to the universal apk`() {
        val asset = stableRelease().pickAsset(listOf("mips"))
        assertEquals("SmartTube_stable_32.80_universal.apk", asset?.name)
    }

    @Test
    fun `release without apks returns null`() {
        val empty = stableRelease().copy(assets = emptyList())
        assertNull(empty.pickAsset(listOf("arm64-v8a")))
    }

    @Test
    fun `versions compare numerically not lexicographically`() {
        assertTrue(compareVersions("32.80", "32.79") > 0)
        assertTrue(compareVersions("32.9", "32.10") < 0)
        assertEquals(0, compareVersions("32.80s", "32.80"))
        assertEquals(0, compareVersions("v32.80", "32.80"))
        assertTrue(compareVersions("33.0", "32.99") > 0)
    }

    @Test
    fun `notes are flattened and sanitised`() {
        val raw = """
            ### Changelog
            - Fixed [player crash](https://example.test/issue/1)
            - **Improved** seek bar
            | APK | VirusTotal |
            | --- | --- |
            | arm64 | https://virustotal.test/abc |
        """.trimIndent()

        val text = summarizeNotes(raw)

        assertTrue(text.contains("Fixed player crash"))
        assertTrue(!text.contains("https://"))
        assertTrue(!text.contains("VirusTotal"))
        assertTrue(!text.contains("**"))
        assertTrue(!text.contains("###"))
    }

    @Test
    fun `long notes are truncated with an ellipsis`() {
        val long = (1..200).joinToString("\n") { "line $it" }
        val text = summarizeNotes(long, maxChars = 50)
        assertTrue(text.length <= 51)
        assertTrue(text.endsWith("\u2026"))
    }
}
