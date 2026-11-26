package com.smarttubeupdater

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Covers the "delete old release APKs once the new build is installed" logic
 * against the real asset naming scheme.
 */
class ApkCleanupTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("smarttube-apks").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun apk(name: String): File = File(dir, name).apply { writeText("stub") }

    @Test
    fun `version is parsed from real asset names`() {
        assertEquals(
            "32.80",
            ApkFallbackDownloader.versionFromFileName(
                "SmartTube_stable_32.80_arm64-v8a.apk"
            )
        )
        assertEquals(
            "32.83",
            ApkFallbackDownloader.versionFromFileName(
                "SmartTube_beta_32.83_universal.apk"
            )
        )
        assertNull(ApkFallbackDownloader.versionFromFileName("notes.txt"))
    }

    @Test
    fun `older apks are deleted and the installed one is kept`() {
        val old1 = apk("SmartTube_stable_32.77_arm64-v8a.apk")
        val old2 = apk("SmartTube_stable_32.79_universal.apk")
        val current = apk("SmartTube_stable_32.80_arm64-v8a.apk")

        val removed = ApkFallbackDownloader.pruneOlderThan(dir, "32.80")

        assertEquals(2, removed)
        assertFalse(old1.exists())
        assertFalse(old2.exists())
        assertTrue(current.exists())
    }

    @Test
    fun `newer apk is kept`() {
        val newer = apk("SmartTube_beta_32.83_universal.apk")
        val removed = ApkFallbackDownloader.pruneOlderThan(dir, "32.80")
        assertEquals(0, removed)
        assertTrue(newer.exists())
    }

    @Test
    fun `partial downloads and unparsable files are removed`() {
        val part = apk("SmartTube_stable_32.80_arm64-v8a.apk.part")
        val junk = apk("download-temp")

        val removed = ApkFallbackDownloader.pruneOlderThan(dir, "32.80")

        assertEquals(2, removed)
        assertFalse(part.exists())
        assertFalse(junk.exists())
    }

    @Test
    fun `missing folder is a no-op`() {
        assertEquals(0, ApkFallbackDownloader.pruneOlderThan(File(dir, "nope"), "32.80"))
    }
}
