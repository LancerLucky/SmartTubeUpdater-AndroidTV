package com.smarttubeupdater

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings

/** Result of asking the system to install a downloaded APK. */
enum class InstallOutcome {
    /** The package installer was opened. */
    STARTED,

    /** This app is not allowed to install unknown apps yet. */
    NOT_ALLOWED,

    /** The APK is gone or cannot be handed to the installer. */
    UNAVAILABLE
}

/** Snapshot of a DownloadManager request. */
data class DownloadProgress(
    val status: Int,
    val downloadedBytes: Long,
    val totalBytes: Long
)

/**
 * Hands APK downloads to the system [DownloadManager] so they survive the app
 * being closed and land in the public Downloads folder. APKs are installed
 * through the DownloadManager's own content URIs, no FileProvider needed.
 */
object ApkDownloader {

    private const val APK_MIME = "application/vnd.android.package-archive"

    /** Downloads/SmartTube/ on the shared storage. */
    const val TARGET_FOLDER = "SmartTube"

    /** Enqueues the download and returns the DownloadManager request id. */
    fun enqueue(context: Context, release: Release, asset: ReleaseAsset): Long {
        val request = DownloadManager.Request(Uri.parse(asset.downloadUrl))
            .setTitle("SmartTube ${release.versionLabel}")
            .setDescription(asset.name)
            .setMimeType(APK_MIME)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
            .setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
            )
            .setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS,
                "$TARGET_FOLDER/${safeFileName(asset.name)}"
            )

        val manager =
            context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        return manager.enqueue(request)
    }

    /** True when the download finished successfully. */
    fun isSuccessful(context: Context, downloadId: Long): Boolean =
        statusOf(context, downloadId) == DownloadManager.STATUS_SUCCESSFUL

    /** Current status and byte counters, or null when the request is gone. */
    fun progress(context: Context, downloadId: Long): DownloadProgress? {
        if (downloadId <= 0L) return null
        val manager =
            context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val query = DownloadManager.Query().setFilterById(downloadId)
        return try {
            val cursor = manager.query(query) ?: return null
            cursor.use {
                if (!it.moveToFirst()) return null
                val statusIndex = it.getColumnIndex(DownloadManager.COLUMN_STATUS)
                if (statusIndex < 0) return null
                val downloadedIndex =
                    it.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val totalIndex = it.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                DownloadProgress(
                    status = it.getInt(statusIndex),
                    downloadedBytes = if (downloadedIndex >= 0) it.getLong(downloadedIndex) else 0L,
                    totalBytes = if (totalIndex >= 0) it.getLong(totalIndex) else -1L
                )
            }
        } catch (t: Throwable) {
            null
        }
    }

    /** Opens the system package installer for a finished download. */
    fun install(context: Context, downloadId: Long): InstallOutcome {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            return InstallOutcome.NOT_ALLOWED
        }

        val manager =
            context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val uri = manager.getUriForDownloadedFile(downloadId)
            ?: return InstallOutcome.UNAVAILABLE

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            context.startActivity(intent)
            InstallOutcome.STARTED
        } catch (t: Throwable) {
            InstallOutcome.UNAVAILABLE
        }
    }

    /** Sends the user to the "install unknown apps" screen for this app. */
    fun openInstallPermissionSettings(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (t: Throwable) {
            false
        }
    }

    /** Removes a download (request record + file) when it is no longer needed. */
    fun remove(context: Context, downloadId: Long) {
        try {
            val manager =
                context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            manager.remove(downloadId)
        } catch (t: Throwable) {
            // Nothing to clean up.
        }
    }

    private fun statusOf(context: Context, downloadId: Long): Int? {
        if (downloadId <= 0L) return null
        val manager =
            context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val query = DownloadManager.Query().setFilterById(downloadId)
        return try {
            val cursor = manager.query(query) ?: return null
            cursor.use {
                if (!it.moveToFirst()) return null
                val index = it.getColumnIndex(DownloadManager.COLUMN_STATUS)
                if (index < 0) null else it.getInt(index)
            }
        } catch (t: Throwable) {
            null
        }
    }

    private fun safeFileName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "SmartTube.apk" }
}
