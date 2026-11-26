package com.smarttubeupdater

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** A single downloadable file attached to a GitHub release. */
data class ReleaseAsset(
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long
)

/** A GitHub release of yuliskov/SmartTube. */
data class Release(
    val tag: String,
    val title: String,
    val publishedAt: Long,
    val notes: String,
    val pageUrl: String,
    val assets: List<ReleaseAsset>
) {
    /** "32.80s" -> "32.80" */
    val versionLabel: String
        get() = tag.trim().removeSuffix("s").removeSuffix("S").trim().ifBlank { tag.trim() }

    /** True when GitHub flagged it stable, i.e. it ships `SmartTube_stable_*` assets. */
    val isStable: Boolean
        get() = assets.any { it.name.contains("stable", ignoreCase = true) }

    /**
     * Picks the APK that matches the device CPU, falling back to the
     * universal build and finally to any APK in the release.
     */
    fun pickAsset(deviceAbis: List<String>): ReleaseAsset? {
        val apks = assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
        if (apks.isEmpty()) return null

        for (abi in deviceAbis) {
            apks.firstOrNull { it.name.contains(abi, ignoreCase = true) }?.let { return it }
        }
        return apks.firstOrNull { it.name.contains("universal", ignoreCase = true) } ?: apks.first()
    }
}

/**
 * Dependency-free GitHub Releases client for yuliskov/SmartTube.
 * Every public call already switches to [Dispatchers.IO].
 */
object GitHubClient {

    private const val REPO = "yuliskov/SmartTube"
    private const val RELEASES_URL =
        "https://api.github.com/repos/$REPO/releases?per_page=60"
    const val RELEASES_PAGE = "https://github.com/$REPO/releases"

    /**
     * Returns the newest stable release: not a draft, not a prerelease and
     * actually shipping `SmartTube_stable_*.apk` assets.
     */
    suspend fun fetchLatestStable(): Release = withContext(Dispatchers.IO) {
        val releases = parseReleases(httpGet(RELEASES_URL))
        selectLatestStable(releases)
            ?: throw IOException("No stable release published yet")
    }

    /** Every release GitHub returns, newest first. */
    suspend fun fetchReleases(): List<Release> = withContext(Dispatchers.IO) {
        parseReleases(httpGet(RELEASES_URL))
    }

    /** Picks the highest-versioned release that is not a draft and ships stable APKs. */
    internal fun selectLatestStable(releases: List<Release>): Release? {
        var best: Release? = null
        for (release in releases) {
            if (!release.isStable) continue
            val current = best
            if (current == null ||
                compareVersions(release.versionLabel, current.versionLabel) > 0 ||
                (compareVersions(release.versionLabel, current.versionLabel) == 0 &&
                    release.publishedAt > current.publishedAt)
            ) {
                best = release
            }
        }
        return best
    }

    /** Parses the raw JSON array returned by the GitHub releases endpoint. */
    internal fun parseReleases(body: String): List<Release> {
        val array = try {
            JSONArray(body)
        } catch (t: Throwable) {
            throw IOException("Unexpected response from GitHub", t)
        }

        val releases = ArrayList<Release>(array.length())
        for (index in 0 until array.length()) {
            val obj = array.optJSONObject(index) ?: continue
            if (obj.optBoolean("draft", false)) continue
            releases.add(parseRelease(obj))
        }
        return releases
    }

    private fun parseRelease(obj: JSONObject): Release {
        val assets = ArrayList<ReleaseAsset>()
        val assetArray = obj.optJSONArray("assets") ?: JSONArray()
        for (index in 0 until assetArray.length()) {
            val asset = assetArray.optJSONObject(index) ?: continue
            val name = asset.optString("name")
            val url = asset.optString("browser_download_url")
            if (name.isBlank() || url.isBlank()) continue
            assets.add(ReleaseAsset(name, url, asset.optLong("size")))
        }

        val tag = obj.optString("tag_name")
        return Release(
            // Stable builds are tagged "32.80s", betas "32.83".
            // [versionLabel] normalises the suffix away.
            tag = tag,
            title = obj.optString("name"),
            publishedAt = parseIso8601(obj.optString("published_at")),
            notes = obj.optString("body"),
            pageUrl = obj.optString("html_url").ifBlank { RELEASES_PAGE },
            assets = assets
        )
    }

    private fun httpGet(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "SmartTubeUpdater")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                val hint = when (code) {
                    403, 429 -> "GitHub rate limit reached, try again later"
                    404 -> "Repository or releases not found"
                    else -> "GitHub API returned HTTP $code"
                }
                throw IOException(hint)
            }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    /** Parses GitHub's `2026-11-19T13:56:02Z` timestamps without java.time. */
    private fun parseIso8601(value: String): Long {
        if (value.isBlank()) return 0L
        return try {
            val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            format.timeZone = TimeZone.getTimeZone("UTC")
            format.parse(value)?.time ?: 0L
        } catch (e: ParseException) {
            0L
        }
    }
}

private val NUMERIC_VERSION = Regex("\\d+")

/** Compares "32.80" / "32.80s" / "v32.9" numerically. Returns <0, 0 or >0. */
fun compareVersions(left: String, right: String): Int {
    val a = NUMERIC_VERSION.findAll(left).map { it.value.toIntOrNull() ?: 0 }.toList()
    val b = NUMERIC_VERSION.findAll(right).map { it.value.toIntOrNull() ?: 0 }.toList()
    val size = maxOf(a.size, b.size)
    for (index in 0 until size) {
        val x = a.getOrElse(index) { 0 }
        val y = b.getOrElse(index) { 0 }
        if (x != y) return if (x > y) 1 else -1
    }
    return 0
}

private val MARKDOWN_LINK = Regex("\\[([^\\]]+)]\\([^)]*\\)")

/**
 * Turns the raw Markdown release body into something readable on a TV.
 * Drops the long VirusTotal table, flattens links and trims the result.
 */
fun summarizeNotes(raw: String, maxChars: Int = 900): String {
    if (raw.isBlank()) return ""

    var text = raw
    val virusTotalIndex = text.indexOf("VirusTotal")
    if (virusTotalIndex > 0) text = text.substring(0, virusTotalIndex)

    text = text.replace("\r\n", "\n").replace('\r', '\n')
    text = MARKDOWN_LINK.replace(text, "$1")
    text = text.replace(Regex("[*`_#>]"), "")
    text = text.lines()
        .map { it.trim().removePrefix("- ").trim() }
        // Drop separator rows and emoji-only leftovers (e.g. the VirusTotal shield).
        .filter { line -> line.count { it.isLetterOrDigit() } >= 2 }
        .joinToString("\n")

    return if (text.length > maxChars) text.take(maxChars).trimEnd() + "\u2026" else text
}
