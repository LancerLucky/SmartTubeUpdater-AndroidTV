package com.smarttubeupdater

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Direct HTTP download used when the system [android.app.DownloadManager] never
 * starts transferring (for example on a TV box whose network Android does not
 * consider "validated"). The APK is stored in the app's own external files dir
 * and handed to the installer through a FileProvider URI.
 */
object ApkFallbackDownloader {

    private const val FOLDER = "SmartTube"
    private const val APK_MIME = "application/vnd.android.package-archive"
    private const val REPORT_STEP_BYTES = 256L * 1024L
    private const val BUFFER_BYTES = 64 * 1024
    private const val HTTP_PARTIAL = 206

    private const val CONNECT_TIMEOUT_MS = 20_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val MAX_ATTEMPTS = 4
    private const val RETRY_DELAY_MS = 1_500L

    /** Where the APKs live: Android/data/<pkg>/files/Download/SmartTube/. */
    fun directory(context: Context): File =
        File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), FOLDER)

    /**
     * Downloads [asset] and returns the finished file.
     *
     * TV boxes sit on flaky links, so a stalled transfer is resumed with a
     * `Range` request instead of starting over. [resume] keeps the bytes of a
     * paused transfer; otherwise any leftover part file is discarded first.
     * [onProgress] is invoked from a background thread, so callers must switch
     * dispatchers before touching the UI.
     */
    suspend fun download(
        context: Context,
        asset: ReleaseAsset,
        resume: Boolean = false,
        onProgress: suspend (downloaded: Long, total: Long) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val dir = directory(context)
        if (!dir.exists() && !dir.mkdirs()) throw IOException("Cannot create ${dir.path}")

        val target = File(dir, safeFileName(asset.name))
        val partial = File(dir, "${target.name}.part")
        if (!resume && partial.exists()) partial.delete()

        var knownTotal = -1L
        var lastError: Throwable? = null

        for (attempt in 1..MAX_ATTEMPTS) {
            if (attempt > 1) kotlinx.coroutines.delay(RETRY_DELAY_MS * (attempt - 1))

            val alreadyDownloaded = if (partial.exists()) partial.length() else 0L
            val connection = openConnection(asset.downloadUrl, alreadyDownloaded)

            try {
                val code = connection.responseCode
                if (code != HttpURLConnection.HTTP_OK && code != HTTP_PARTIAL) {
                    throw IOException("HTTP $code")
                }

                @Suppress("DEPRECATION") // contentLengthLong() needs API 24
                val serverLength = connection.contentLength.toLong()
                val resuming = code == HTTP_PARTIAL && alreadyDownloaded > 0L

                knownTotal = when {
                    resuming && serverLength > 0L -> alreadyDownloaded + serverLength
                    serverLength > 0L -> serverLength
                    else -> knownTotal
                }

                if (!resuming && partial.exists()) partial.delete()

                var downloaded = if (resuming) alreadyDownloaded else 0L
                var reported = downloaded - REPORT_STEP_BYTES // report the first chunk
                var sawAnyByte = downloaded > 0L

                connection.inputStream.use { input ->
                    FileOutputStream(partial, resuming).use { output ->
                        val buffer = ByteArray(BUFFER_BYTES)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            if (read == 0) continue
                            output.write(buffer, 0, read)
                            downloaded += read
                            sawAnyByte = true
                            if (downloaded - reported >= REPORT_STEP_BYTES) {
                                reported = downloaded
                                onProgress(downloaded, knownTotal)
                            }
                        }
                    }
                }

                val size = partial.length()
                if (size <= 0L) throw IOException("Empty response")
                onProgress(size, if (knownTotal > 0L) knownTotal else size)

                if (knownTotal > 0L && size < knownTotal) {
                    // Truncated transfer: keep the bytes and resume.
                    throw IOException("Incomplete download ($size of $knownTotal bytes)")
                }
                if (!sawAnyByte && size <= 0L) throw IOException("Empty response")

                if (target.exists() && !target.delete()) {
                    throw IOException("Cannot replace ${target.name}")
                }
                if (!partial.renameTo(target)) throw IOException("Cannot save ${target.name}")
                return@withContext target
            } catch (t: IOException) {
                lastError = t
            } catch (t: SecurityException) {
                lastError = t
            } finally {
                connection.disconnect()
            }
        }

        if (partial.exists()) partial.delete()
        throw IOException(lastError?.message ?: "Download failed")
    }

    private fun openConnection(url: String, alreadyDownloaded: Long): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "SmartTubeUpdater")
            if (alreadyDownloaded > 0L) {
                setRequestProperty("Range", "bytes=$alreadyDownloaded-")
            }
        }

    /** Opens the package installer for a downloaded file. */
    fun install(context: Context, file: File): InstallOutcome {
        if (!file.exists()) return InstallOutcome.UNAVAILABLE
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            return InstallOutcome.NOT_ALLOWED
        }

        return try {
            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, APK_MIME)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            InstallOutcome.STARTED
        } catch (t: Throwable) {
            InstallOutcome.UNAVAILABLE
        }
    }

    /**
     * Removes every APK in the download folder that belongs to a release older
     * than [installedVersion]. Leftovers (.part files, unparsable names) go too.
     */
    fun pruneOlderThan(context: Context, installedVersion: String): Int =
        pruneOlderThan(directory(context), installedVersion)

    internal fun pruneOlderThan(dir: File, installedVersion: String): Int {
        val files = dir.listFiles() ?: return 0
        var removed = 0
        for (file in files) {
            val version = versionFromFileName(file.name)
            val stale = version == null ||
                file.name.endsWith(".part", ignoreCase = true) ||
                compareVersions(version, installedVersion) < 0
            if (stale && file.delete()) removed++
        }
        return removed
    }

    /** "SmartTube_stable_32.80_arm64-v8a.apk" -> "32.80" */
    internal fun versionFromFileName(fileName: String): String? =
        VERSION_IN_NAME.find(fileName)?.groupValues?.getOrNull(1)

    private val VERSION_IN_NAME = Regex("_(\\d+(?:\\.\\d+)*)_")

    private fun safeFileName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "SmartTube.apk" }
}
