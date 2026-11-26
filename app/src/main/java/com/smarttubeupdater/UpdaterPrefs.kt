package com.smarttubeupdater

import android.content.Context
import androidx.core.content.edit

/**
 * Small SharedPreferences wrapper holding the state the app has to remember
 * between launches (and between the UI and the background worker).
 */
class UpdaterPrefs(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("smarttube_updater", Context.MODE_PRIVATE)

    /** Newest stable version the app already knows about. */
    var lastSeenStableVersion: String?
        get() = prefs.getString(KEY_LAST_SEEN, null)
        set(value) = prefs.edit { putString(KEY_LAST_SEEN, value) }

    /** DownloadManager id of the APK currently being downloaded / installed. */
    var pendingDownloadId: Long
        get() = prefs.getLong(KEY_PENDING_ID, -1L)
        set(value) = prefs.edit { putLong(KEY_PENDING_ID, value) }

    var pendingDownloadName: String?
        get() = prefs.getString(KEY_PENDING_NAME, null)
        set(value) = prefs.edit { putString(KEY_PENDING_NAME, value) }

    var pendingDownloadVersion: String?
        get() = prefs.getString(KEY_PENDING_VERSION, null)
        set(value) = prefs.edit { putString(KEY_PENDING_VERSION, value) }

    /**
     * DownloadManager request of a superseded release. It is only purged after
     * the newer build has been installed; the current request is always kept.
     */
    var olderDownloadId: Long
        get() = prefs.getLong(KEY_OLDER_ID, -1L)
        set(value) = prefs.edit { putLong(KEY_OLDER_ID, value) }

    /** Path of an APK downloaded by the in-app fallback downloader. */
    var pendingApkPath: String?
        get() = prefs.getString(KEY_PENDING_APK, null)
        set(value) = prefs.edit { putString(KEY_PENDING_APK, value) }

    fun clearPendingDownload() = prefs.edit {
        remove(KEY_PENDING_ID)
        remove(KEY_PENDING_NAME)
        remove(KEY_PENDING_VERSION)
        remove(KEY_PENDING_APK)
    }

    private companion object {
        const val KEY_LAST_SEEN = "last_seen_stable_version"
        const val KEY_PENDING_ID = "pending_download_id"
        const val KEY_PENDING_NAME = "pending_download_name"
        const val KEY_PENDING_VERSION = "pending_download_version"
        const val KEY_PENDING_APK = "pending_apk_path"
        const val KEY_OLDER_ID = "older_download_id"
    }
}
