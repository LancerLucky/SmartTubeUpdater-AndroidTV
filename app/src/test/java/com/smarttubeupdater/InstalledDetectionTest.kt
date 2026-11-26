package com.smarttubeupdater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SmartTube has changed its applicationId over time, so detection must accept
 * both the current scheme and any renamed build it can still launch.
 */
class InstalledDetectionTest {

    private val self = "com.smarttubeupdater"

    @Test
    fun `current upstream ids are known`() {
        assertTrue(InstalledSmartTube.PACKAGE_IDS.contains("org.smarttube.stable"))
        assertTrue(InstalledSmartTube.PACKAGE_IDS.contains("org.smarttube.beta"))
        assertTrue(InstalledSmartTube.PACKAGE_IDS.contains("app.smarttube"))
        assertTrue(InstalledSmartTube.PACKAGE_IDS.contains("app.smarttube.fdroid"))
        // legacy ids stay supported
        assertTrue(InstalledSmartTube.PACKAGE_IDS.contains("com.teamsmart.videomanager.tv"))
        assertTrue(InstalledSmartTube.PACKAGE_IDS.contains("com.liskovsoft.smarttubetv.beta"))
    }

    @Test
    fun `launcher labels and package names identify smarttube`() {
        assertTrue(InstalledSmartTube.looksLikeSmartTube("org.smarttube.stable", "SmartTube", self))
        assertTrue(InstalledSmartTube.looksLikeSmartTube("com.teamsmart.videomanager.tv", "SmartTube", self))
        // renamed package, same label
        assertTrue(InstalledSmartTube.looksLikeSmartTube("com.example.playertv", "SmartTube", self))
        // unknown package with a fork-ish name
        assertTrue(InstalledSmartTube.looksLikeSmartTube("com.fork.smarttubeplus", "Player", self))
    }

    @Test
    fun `this updater app is never treated as smarttube`() {
        assertFalse(InstalledSmartTube.looksLikeSmartTube(self, "SmartTube Updater", self))
        assertFalse(
            InstalledSmartTube.looksLikeSmartTube(
                "com.smarttubeupdater.debug",
                "SmartTube Updater",
                self
            )
        )
    }

    @Test
    fun `unrelated apps are ignored`() {
        assertFalse(InstalledSmartTube.looksLikeSmartTube("com.netflix.ninja", "Netflix", self))
        assertFalse(InstalledSmartTube.looksLikeSmartTube("org.videolan.vlc", "VLC", self))
    }

    @Test
    fun `newest installed build wins`() {
        val newest = InstalledSmartTube.pickNewest(
            listOf(
                InstalledApp("com.teamsmart.videomanager.tv", "32.79"),
                InstalledApp("org.smarttube.stable", "32.80"),
                InstalledApp("org.smarttube.beta", "32.83")
            )
        )
        assertEquals("32.83", newest?.versionName)
        assertEquals("org.smarttube.beta", newest?.packageName)
    }

    @Test
    fun `no candidates means not installed`() {
        assertNull(InstalledSmartTube.pickNewest(emptyList()))
    }
}
