package com.smarttubeupdater

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parses a captured response from the live GitHub releases endpoint so that
 * field-name mistakes in the JSON mapping are caught without network access.
 *
 * Fixture: app/src/test/resources/releases.json
 * Captured from https://api.github.com/repos/yuliskov/SmartTube/releases
 */
class GitHubParsingTest {

    private fun fixture(): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("releases.json")) {
            "releases.json fixture is missing"
        }.bufferedReader().use { it.readText() }

    @Test
    fun `fixture is a JSON array of releases`() {
        assertTrue(JSONArray(fixture()).length() > 0)
    }

    @Test
    fun `releases are parsed with tag, date, page url and assets`() {
        val releases = GitHubClient.parseReleases(fixture())

        assertTrue(releases.isNotEmpty())

        val parsed = releases.first { it.tag.startsWith("32.80s") }
        assertEquals("32.80", parsed.versionLabel)
        assertTrue(parsed.publishedAt > 0L)
        assertTrue(parsed.pageUrl.startsWith("https://github.com/yuliskov/SmartTube/releases/tag/"))
        assertEquals(4, parsed.assets.size)
        assertTrue(parsed.assets.all { it.downloadUrl.startsWith("https://github.com/") })
        assertTrue(parsed.assets.all { it.sizeBytes > 0L })
    }

    @Test
    fun `latest stable release is the newest non prerelease build`() {
        val releases = GitHubClient.parseReleases(fixture())
        val stable = GitHubClient.selectLatestStable(releases)

        assertNotNull(stable)
        assertEquals("32.80", stable!!.versionLabel)
        assertTrue(stable.assets.any { it.name.startsWith("SmartTube_stable_") })
        assertTrue(stable.assets.none { it.name.contains("beta", ignoreCase = true) })

        // Newer builds in the feed must be betas only - none of them may win.
        val newerStable = releases.filter {
            compareVersions(it.versionLabel, stable.versionLabel) > 0 && it.isStable
        }
        assertTrue(newerStable.isEmpty())
    }

    @Test
    fun `arm64 device selects the arm64 stable apk`() {
        val releases = GitHubClient.parseReleases(fixture())
        val stable = checkNotNull(GitHubClient.selectLatestStable(releases))
        val asset = stable.pickAsset(listOf("arm64-v8a", "armeabi-v7a", "armeabi"))

        assertEquals("SmartTube_stable_32.80_arm64-v8a.apk", asset?.name)
    }

    @Test
    fun `broken payload fails with a readable error`() {
        val error = runCatching { GitHubClient.parseReleases("{\"message\":\"rate limited\"}") }
            .exceptionOrNull()
        assertTrue(error is java.io.IOException)
    }
}
