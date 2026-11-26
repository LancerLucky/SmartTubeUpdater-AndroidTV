package com.smarttubeupdater

import android.Manifest
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.smarttubeupdater.databinding.ActivityMainBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * Single screen updater for yuliskov/SmartTube.
 *
 * - shows the newest stable release (version, date, APK, release notes)
 * - compares it with the SmartTube build installed on this device
 * - downloads the matching APK with DownloadManager and opens the installer
 * - falls back to an in-app download when the system download never starts
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: UpdaterPrefs

    private var release: Release? = null
    private var asset: ReleaseAsset? = null

    /** Releases (stable + beta) offered by the "Versions" chooser. */
    private var releases: List<Release> = emptyList()

    private var pendingDownloadId: Long = -1L
    private var pendingApkFile: File? = null
    private var completionHandled = false

    private var pollJob: Job? = null
    private var downloadJob: Job? = null
    private var receiverRegistered = false

    /** What the download button is currently doing. */
    private enum class DownloadState { IDLE, RUNNING, PAUSED }

    private var downloadState = DownloadState.IDLE
    private var pausedBytes = 0L
    private var pausedTotal = -1L

    // Byte-rate sampling for the "1.2 MB/s" readout.
    private var speedSampleBytes = 0L
    private var speedSampleAt = 0L
    private var downloadSpeedBytesPerSec = 0.0

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (id <= 0L || id != pendingDownloadId) return
            onDownloadFinished()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = UpdaterPrefs(this)
        pendingDownloadId = prefs.pendingDownloadId
        pendingApkFile = prefs.pendingApkPath?.let(::File)?.takeIf { it.exists() }

        binding.buttonDownload.setOnClickListener { onDownloadClicked() }
        binding.buttonInstall.setOnClickListener { promptInstall() }
        binding.buttonVersions.setOnClickListener { chooseVersion() }
        binding.buttonReleasePage.setOnClickListener { openReleasePage() }
        binding.buttonRefresh.setOnClickListener { loadLatestStable() }

        requestNotificationPermission()
        loadLatestStable()
    }

    override fun onStart() {
        super.onStart()
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                downloadReceiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiverRegistered = true
        }
        // Coming back from the installer: re-read the installed build so the
        // card, the "Up to date" pill and the action labels all stay truthful.
        release?.let { renderInstalledState(it) }
        refreshDownloadState()
        // Resume progress reporting for a download that started earlier.
        if (pendingDownloadId > 0L && !completionHandled) {
            watchSystemDownload(pendingDownloadId)
        }
    }

    override fun onStop() {
        pollJob?.cancel()
        pollJob = null
        if (receiverRegistered) {
            try {
                unregisterReceiver(downloadReceiver)
            } catch (t: Throwable) {
                // Already gone.
            }
            receiverRegistered = false
        }
        super.onStop()
    }

    // ------------------------------------------------------------------ loading

    /** Asks GitHub for the newest stable release and renders it. */
    private fun loadLatestStable() {
        release = null
        asset = null
        releases = emptyList()
        binding.card.visibility = View.GONE
        setBusy(true, getString(R.string.status_checking))

        lifecycleScope.launch {
            try {
                val fetched = GitHubClient.fetchLatestStable()
                val chosen = fetched.pickAsset(Build.SUPPORTED_ABIS.toList())

                release = fetched
                asset = chosen
                prefs.lastSeenStableVersion = fetched.versionLabel

                render(fetched, chosen)
                setBusy(false, getString(R.string.status_ready))
                refreshDownloadState()
                binding.buttonDownload.requestFocus()
            } catch (t: Throwable) {
                release = null
                asset = null
                binding.card.visibility = View.GONE
                setBusy(
                    false,
                    getString(R.string.status_error, t.message ?: t.javaClass.simpleName)
                )
                binding.buttonRefresh.requestFocus()
            }
        }
    }

    // ----------------------------------------------------------------- rendering

    private fun render(release: Release, chosen: ReleaseAsset?) {
        binding.textVersion.text = getString(R.string.version_value, release.versionLabel)
        binding.textTitle.text = release.title.ifBlank { release.tag }
        binding.textPublished.text = if (release.publishedAt > 0L) {
            getString(
                R.string.published_value,
                DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.getDefault())
                    .format(Date(release.publishedAt))
            )
        } else {
            getString(R.string.published_unknown)
        }

        binding.textAsset.text = if (chosen != null) {
            getString(R.string.asset_value, chosen.name, formatSize(chosen.sizeBytes))
        } else {
            getString(R.string.asset_missing)
        }

        binding.textNotes.text =
            summarizeNotes(release.notes).ifBlank { getString(R.string.notes_empty) }

        renderInstalledState(release)
        binding.card.visibility = View.VISIBLE
    }

    /** Shows what is installed locally and whether the release is an update. */
    private fun renderInstalledState(release: Release) {
        val installed = InstalledSmartTube.detect(this)
        val text = binding.textInstalled
        val comparison = installed?.let { compareVersions(it.versionName, release.versionLabel) }

        // "Up to date": the release on screen (the latest stable one when the app
        // opens) is already installed, so show the inert pill at the row's left.
        binding.buttonUpToDate.visibility =
            if (comparison != null && comparison >= 0) View.VISIBLE else View.GONE

        if (installed == null || comparison == null) {
            text.setText(R.string.installed_none)
            text.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            return
        }

        when {
            comparison > 0 -> {
                text.text = getString(R.string.installed_ahead, installed.versionName)
                text.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            }

            comparison == 0 -> {
                text.text = getString(R.string.installed_up_to_date, installed.versionName)
                text.setTextColor(ContextCompat.getColor(this, R.color.success))
            }

            else -> {
                text.text = getString(
                    R.string.installed_update_available,
                    installed.versionName
                )
                text.setTextColor(ContextCompat.getColor(this, R.color.warning))
            }
        }
    }

    private fun setBusy(busy: Boolean, message: String) {
        binding.progress.visibility = if (busy) View.VISIBLE else View.GONE
        binding.textStatus.text = message
        binding.buttonRefresh.isEnabled = !busy
        binding.buttonVersions.isEnabled = !busy
        binding.buttonDownload.isEnabled = !busy && asset != null
        binding.buttonReleasePage.isEnabled = !busy && release?.pageUrl?.isNotBlank() == true
        binding.buttonInstall.isEnabled = !busy
        if (busy) binding.buttonInstall.visibility = View.GONE
    }

    // ------------------------------------------------------------- version picker

    /** Loads (once) and shows the list of releases the user can choose from. */
    private fun chooseVersion() {
        if (releases.isNotEmpty()) {
            showVersionDialog(releases)
            return
        }

        setBusy(true, getString(R.string.status_loading_versions))
        lifecycleScope.launch {
            try {
                val fetched = GitHubClient.fetchReleases()
                    .filter { candidate ->
                        candidate.assets.any { it.name.endsWith(".apk", ignoreCase = true) }
                    }
                releases = fetched

                setBusy(false, getString(R.string.status_ready))
                refreshDownloadState()
                if (fetched.isEmpty()) {
                    binding.textStatus.setText(R.string.asset_missing)
                } else {
                    showVersionDialog(fetched)
                }
            } catch (t: Throwable) {
                setBusy(
                    false,
                    getString(R.string.status_error, t.message ?: t.javaClass.simpleName)
                )
                refreshDownloadState()
            }
        }
    }

    private fun showVersionDialog(all: List<Release>) {
        val selectedTag = release?.tag
        // Cap the list: a long D-pad list is unpleasant on a TV.
        val shown = all.take(MAX_VERSIONS_SHOWN)
        val labels = shown.map { candidate ->
            val kind = getString(
                if (candidate.isStable) R.string.kind_stable else R.string.kind_beta
            )
            if (candidate.tag == selectedTag) {
                getString(R.string.version_choice_current, candidate.versionLabel, kind)
            } else {
                getString(R.string.version_choice, candidate.versionLabel, kind)
            }
        }.toTypedArray()

        tvDialog()
            .setTitle(R.string.dialog_versions_title)
            .setItems(labels) { _, which -> selectRelease(shown[which]) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Shows the given release in the card and makes it downloadable. */
    private fun selectRelease(chosen: Release) {
        release = chosen
        asset = chosen.pickAsset(Build.SUPPORTED_ABIS.toList())

        render(chosen, asset)
        setBusy(false, getString(R.string.status_release_selected, chosen.versionLabel))
        refreshDownloadState()
        binding.buttonDownload.requestFocus()
    }

    // ------------------------------------------------------------------ download

    /** Lets the user pick the APK variant (ABI) before downloading. */
    private fun onDownloadClicked() {
        when (downloadState) {
            DownloadState.RUNNING -> {
                pauseDownload()
                return
            }

            DownloadState.PAUSED -> {
                if (resumeDownload()) return
                // Nothing to resume (e.g. state lost) - fall through to a new download.
            }

            DownloadState.IDLE -> Unit
        }

        val currentRelease = release ?: return
        val apks = currentRelease.assets
            .filter { it.name.endsWith(".apk", ignoreCase = true) }

        if (apks.size <= 1) {
            asset = apks.firstOrNull()
            showSelectedAsset()
            downloadAsset()
            return
        }

        val recommended = currentRelease.pickAsset(Build.SUPPORTED_ABIS.toList())
        val labels = apks.map { candidate ->
            if (candidate == recommended) {
                getString(
                    R.string.asset_choice_recommended,
                    candidate.name,
                    formatSize(candidate.sizeBytes)
                )
            } else {
                getString(R.string.asset_choice, candidate.name, formatSize(candidate.sizeBytes))
            }
        }.toTypedArray()

        tvDialog()
            .setTitle(R.string.dialog_apk_title)
            .setItems(labels) { _, which ->
                asset = apks[which]
                showSelectedAsset()
                downloadAsset()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Reflects the chosen APK in the info card. */
    private fun showSelectedAsset() {
        val chosen = asset ?: return
        binding.textAsset.text =
            getString(R.string.asset_value, chosen.name, formatSize(chosen.sizeBytes))
    }

    private fun tvDialog(): AlertDialog.Builder =
        AlertDialog.Builder(this, R.style.Theme_SmartTubeUpdater_Dialog)

    private fun downloadAsset() {
        val currentRelease = release ?: return
        val currentAsset = asset ?: return

        // Keep APKs that are already on the device - they are purged once the new
        // build has been installed. Only a request for the same file is replaced.
        val previousVersion = prefs.pendingDownloadVersion
        if (pendingDownloadId > 0L) {
            if (previousVersion == currentRelease.versionLabel) {
                ApkDownloader.remove(this, pendingDownloadId)
            } else {
                // Remember one superseded download so it can be purged later;
                // the previous "older" one is dropped now.
                val staleOlderId = prefs.olderDownloadId
                if (staleOlderId > 0L) ApkDownloader.remove(this, staleOlderId)
                prefs.olderDownloadId = pendingDownloadId
            }
        }
        pendingApkFile = null
        pendingDownloadId = -1L
        completionHandled = false
        prefs.clearPendingDownload()
        prefs.pendingDownloadName = currentAsset.name
        prefs.pendingDownloadVersion = currentRelease.versionLabel
        downloadState = DownloadState.RUNNING
        pausedBytes = 0L
        pausedTotal = -1L
        binding.buttonInstall.visibility = View.GONE
        refreshDownloadState()

        try {
            val id = ApkDownloader.enqueue(this, currentRelease, currentAsset)
            pendingDownloadId = id
            prefs.pendingDownloadId = id
            prefs.pendingDownloadName = currentAsset.name
            prefs.pendingDownloadVersion = currentRelease.versionLabel

            binding.textStatus.text = getString(R.string.status_waiting_network)

            Toast.makeText(
                this,
                getString(R.string.download_started, currentAsset.name),
                Toast.LENGTH_LONG
            ).show()

            watchSystemDownload(id)
        } catch (t: Throwable) {
            // DownloadManager refused the request - download it ourselves.
            startInAppDownload(currentRelease, currentAsset)
        }
    }

    /** Follows a DownloadManager request and takes over if it never starts. */
    private fun watchSystemDownload(id: Long) {
        pollJob?.cancel()
        resetSpeedTracker()
        if (!ApkDownloader.isSuccessful(this, id)) {
            downloadState = DownloadState.RUNNING
            refreshDownloadState()
        }
        pollJob = lifecycleScope.launch {
            val startedAt = SystemClock.elapsedRealtime()
            var lastProgress: DownloadProgress? = null

            while (isActive) {
                val progress = ApkDownloader.progress(this@MainActivity, id)
                if (progress == null) return@launch

                when (progress.status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        onDownloadFinished()
                        return@launch
                    }

                    DownloadManager.STATUS_FAILED -> return@launch

                    DownloadManager.STATUS_RUNNING -> {
                        lastProgress = progress
                        showProgress(progress)
                    }

                    DownloadManager.STATUS_PAUSED -> {
                        binding.textStatus.setText(R.string.status_download_paused)
                    }

                    else -> {
                        // PENDING: give the system service a moment, then take over.
                        val stalled = SystemClock.elapsedRealtime() - startedAt >= STALL_TIMEOUT_MS
                        if (stalled && (progress.downloadedBytes <= 0L)) {
                            takeOverDownload(id)
                            return@launch
                        }
                        if (lastProgress == null) {
                            binding.textStatus.setText(R.string.status_waiting_network)
                        }
                    }
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    /** Cancels the stalled system request and downloads the APK in-process. */
    private fun takeOverDownload(systemDownloadId: Long) {
        ApkDownloader.remove(this, systemDownloadId)
        pendingDownloadId = -1L
        prefs.pendingDownloadId = -1L

        val currentRelease = release
        val currentAsset = asset
        if (currentAsset == null || currentRelease == null) return

        Toast.makeText(this, R.string.download_fallback, Toast.LENGTH_LONG).show()
        startInAppDownload(currentRelease, currentAsset)
    }

    private fun startInAppDownload(release: Release, asset: ReleaseAsset, resume: Boolean = false) {
        downloadJob?.cancel()
        downloadState = DownloadState.RUNNING
        if (!resume) resetSpeedTracker()
        prefs.pendingDownloadName = asset.name
        prefs.pendingDownloadVersion = release.versionLabel
        binding.buttonInstall.visibility = View.GONE
        binding.textStatus.text = getString(R.string.status_downloading, asset.name)
        refreshDownloadState()

        downloadJob = lifecycleScope.launch {
            try {
                val file = ApkFallbackDownloader.download(this@MainActivity, asset, resume) { done, total ->
                    kotlinx.coroutines.withContext(Dispatchers.Main) {
                        showProgress(DownloadProgress(DownloadManager.STATUS_RUNNING, done, total))
                    }
                }

                downloadState = DownloadState.IDLE
                pendingApkFile = file
                prefs.pendingApkPath = file.absolutePath
                prefs.pendingDownloadName = asset.name
                prefs.pendingDownloadVersion = release.versionLabel

                binding.textStatus.setText(R.string.download_complete)
                // Older APKs are kept until this build is actually installed.
                refreshDownloadState()
                binding.buttonInstall.requestFocus()

                Toast.makeText(this@MainActivity, R.string.download_complete, Toast.LENGTH_LONG)
                    .show()
                promptInstall()
            } catch (cancelled: CancellationException) {
                // Pause: the part file is kept, pauseDownload() updates the UI.
                throw cancelled
            } catch (t: Throwable) {
                downloadState = DownloadState.IDLE
                binding.textStatus.text = getString(
                    R.string.download_error,
                    t.message ?: t.javaClass.simpleName
                )
                refreshDownloadState()
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.download_error, t.message ?: t.javaClass.simpleName),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /** Stops the transfer, keeping the bytes already received for a resume. */
    private fun pauseDownload() {
        pollJob?.cancel()
        pollJob = null
        downloadJob?.cancel()
        downloadJob = null

        if (pendingDownloadId > 0L) {
            // The system request cannot be paused - drop it, Resume downloads in-app.
            ApkDownloader.remove(this, pendingDownloadId)
            pendingDownloadId = -1L
            prefs.pendingDownloadId = -1L
        }

        downloadState = DownloadState.PAUSED
        binding.textStatus.text = when {
            pausedTotal > 0L -> getString(
                R.string.status_paused_progress,
                formatSize(pausedBytes),
                formatSize(pausedTotal)
            )

            pausedBytes > 0L -> getString(R.string.status_paused_bytes, formatSize(pausedBytes))

            else -> getString(R.string.status_user_paused)
        }
        refreshDownloadState()
        binding.buttonDownload.requestFocus()
    }

    /** Continues a paused transfer from the bytes already on disk. */
    private fun resumeDownload(): Boolean {
        val currentRelease = release ?: return false
        val currentAsset = asset ?: return false
        startInAppDownload(currentRelease, currentAsset, resume = true)
        return true
    }

    private fun showProgress(progress: DownloadProgress) {
        val total = progress.totalBytes
        pausedBytes = progress.downloadedBytes
        pausedTotal = total
        val speed = updateDownloadSpeed(progress.downloadedBytes)
        val percent = if (total > 0L) {
            ((progress.downloadedBytes * 100) / total).toInt().coerceIn(0, 100)
        } else {
            0
        }

        binding.textStatus.text = when {
            total > 0L && speed > 0.0 -> getString(
                R.string.status_download_progress_speed,
                formatSize(progress.downloadedBytes),
                formatSize(total),
                percent,
                formatSpeed(speed)
            )

            total > 0L -> getString(
                R.string.status_download_progress,
                formatSize(progress.downloadedBytes),
                formatSize(total),
                percent
            )

            speed > 0.0 -> getString(
                R.string.status_downloading_speed,
                formatSize(progress.downloadedBytes),
                formatSpeed(speed)
            )

            progress.downloadedBytes > 0L -> getString(
                R.string.status_downloading_size,
                formatSize(progress.downloadedBytes)
            )

            else -> getString(R.string.status_downloading, asset?.name ?: "")
        }
    }

    /** Smooths the transfer rate between progress samples. Returns bytes/second. */
    private fun updateDownloadSpeed(downloadedBytes: Long): Double {
        val now = SystemClock.elapsedRealtime()

        if (speedSampleAt == 0L) {
            speedSampleAt = now
            speedSampleBytes = downloadedBytes
            return 0.0
        }

        val elapsedMs = now - speedSampleAt
        if (elapsedMs < MIN_SPEED_SAMPLE_MS) return downloadSpeedBytesPerSec

        val delta = downloadedBytes - speedSampleBytes
        speedSampleAt = now
        speedSampleBytes = downloadedBytes

        if (delta < 0L) {
            // A new request took over - drop the stale rate.
            downloadSpeedBytesPerSec = 0.0
            return 0.0
        }

        val instant = delta * 1000.0 / elapsedMs
        downloadSpeedBytesPerSec = if (downloadSpeedBytesPerSec <= 0.0) {
            instant
        } else {
            downloadSpeedBytesPerSec * 0.6 + instant * 0.4
        }
        return downloadSpeedBytesPerSec
    }

    private fun resetSpeedTracker() {
        speedSampleAt = 0L
        speedSampleBytes = 0L
        downloadSpeedBytesPerSec = 0.0
    }

    private fun formatSpeed(bytesPerSecond: Double): String = when {
        bytesPerSecond >= 1024 * 1024 ->
            String.format(Locale.US, "%.1f MB/s", bytesPerSecond / (1024.0 * 1024.0))

        bytesPerSecond >= 1024 ->
            String.format(Locale.US, "%.0f KB/s", bytesPerSecond / 1024.0)

        else -> String.format(Locale.US, "%.0f B/s", bytesPerSecond)
    }

    private fun onDownloadFinished() {
        if (completionHandled) return
        completionHandled = true
        downloadState = DownloadState.IDLE

        if (ApkDownloader.isSuccessful(this, pendingDownloadId)) {
            Toast.makeText(this, R.string.download_complete, Toast.LENGTH_LONG).show()
            binding.textStatus.setText(R.string.download_complete)
            refreshDownloadState()
            binding.buttonInstall.requestFocus()
            // Do not nag when this build is already the installed one, e.g. the
            // user installed it, left and came back.
            if (!isDownloadedBuildInstalled()) promptInstall()
        } else {
            pendingDownloadId = -1L
            prefs.clearPendingDownload()
            refreshDownloadState()
            binding.textStatus.setText(R.string.download_failed)
            Toast.makeText(this, R.string.download_failed, Toast.LENGTH_LONG).show()
        }
    }

    /** Keeps the install/download labels in sync with what is on the device. */
    private fun refreshDownloadState() {
        val installed = InstalledSmartTube.detect(this)
        cleanupAfterInstall(installed)

        val downloadedVersion = prefs.pendingDownloadVersion
        val apkOnDisk = pendingApkFile?.exists() == true
        val systemReady =
            pendingDownloadId > 0L && ApkDownloader.isSuccessful(this, pendingDownloadId)
        val downloaded = apkOnDisk || systemReady

        // The APK on the device is exactly the installed build, so the action is
        // a reinstall rather than an upgrade.
        val installedFromDownload = installed != null && downloadedVersion != null &&
            compareVersions(downloadedVersion, installed.versionName) == 0

        binding.buttonInstall.visibility = if (downloaded) View.VISIBLE else View.GONE
        binding.buttonInstall.text = when {
            !downloaded || downloadedVersion.isNullOrBlank() ->
                getString(R.string.action_install)

            installedFromDownload ->
                getString(R.string.action_reinstall_version, downloadedVersion)

            else -> getString(R.string.action_install_version, downloadedVersion)
        }

        // The download button names the release it would fetch, offers a
        // redownload when that release is installed, or becomes pause/resume.
        val shownVersion = release?.versionLabel
        val shownInstalled = installed != null && shownVersion != null &&
            compareVersions(installed.versionName, shownVersion) >= 0
        binding.buttonDownload.text = when {
            downloadState == DownloadState.RUNNING -> getString(R.string.action_pause)
            downloadState == DownloadState.PAUSED -> getString(R.string.action_resume)
            shownVersion.isNullOrBlank() -> getString(R.string.action_download)
            shownInstalled -> getString(R.string.action_redownload, shownVersion)
            else -> getString(R.string.action_download_version, shownVersion)
        }
    }

    /**
     * Once the downloaded build is the installed one, the superseded downloads
     * are obsolete. The APK of the build just installed is kept on purpose so a
     * reinstall needs no new download.
     */
    private fun cleanupAfterInstall(installed: InstalledApp?) {
        if (downloadJob?.isActive == true || pollJob?.isActive == true) return
        if (installed == null) return

        val downloadedVersion = prefs.pendingDownloadVersion ?: return
        // Only once this exact build is the one installed: an older download that
        // was never installed is left alone.
        if (compareVersions(downloadedVersion, installed.versionName) != 0) return

        // Older APKs in the app's own download folder go away; the one matching
        // the installed version stays.
        ApkFallbackDownloader.pruneOlderThan(this, installed.versionName)

        // Only the superseded DownloadManager request is removed. The current
        // one is kept so its APK survives for "Reinstall 32.80".
        val olderId = prefs.olderDownloadId
        if (olderId > 0L) {
            ApkDownloader.remove(this, olderId)
            prefs.olderDownloadId = -1L
        }

        // If the APK we downloaded was an older build it is gone now - drop the
        // stale pending state so no ghost "Install" button stays behind.
        if (pendingApkFile?.exists() != true) {
            pendingApkFile = null
            prefs.pendingApkPath = null
        }
    }

    /** True when the pending download is exactly the build that is installed. */
    private fun isDownloadedBuildInstalled(): Boolean {
        val installed = InstalledSmartTube.detect(this) ?: return false
        val downloadedVersion = prefs.pendingDownloadVersion ?: return false
        return compareVersions(downloadedVersion, installed.versionName) == 0
    }

    private fun promptInstall() {
        val file = pendingApkFile
        if (file != null && file.exists()) {
            handleInstallOutcome(ApkFallbackDownloader.install(this, file))
            return
        }
        if (pendingDownloadId > 0L) {
            handleInstallOutcome(ApkDownloader.install(this, pendingDownloadId))
        }
    }

    private fun handleInstallOutcome(outcome: InstallOutcome) {
        when (outcome) {
            InstallOutcome.STARTED -> Unit

            InstallOutcome.NOT_ALLOWED -> {
                Toast.makeText(this, R.string.install_permission_needed, Toast.LENGTH_LONG).show()
                if (!ApkDownloader.openInstallPermissionSettings(this)) {
                    openAppSettings()
                }
            }

            InstallOutcome.UNAVAILABLE -> {
                Toast.makeText(this, R.string.install_unavailable, Toast.LENGTH_LONG).show()
                pendingApkFile = null
                pendingDownloadId = -1L
                prefs.clearPendingDownload()
                refreshDownloadState()
            }
        }
    }

    private fun openAppSettings() {
        val intent = Intent(
            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:$packageName")
        )
        try {
            startActivity(intent)
        } catch (t: Throwable) {
            // Nothing else we can do.
        }
    }

    // -------------------------------------------------------------------- misc

    private fun openReleasePage() {
        val url = release?.pageUrl
        if (url.isNullOrBlank()) return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (t: Throwable) {
            Toast.makeText(this, R.string.no_browser, Toast.LENGTH_LONG).show()
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) return

        try {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_NOTIFICATIONS
            )
        } catch (t: Throwable) {
            // Notifications stay disabled; in-app checks still work.
        }
    }

    private fun formatSize(bytes: Long): String =
        if (bytes <= 0L) {
            getString(R.string.size_unknown)
        } else {
            String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        }

    private companion object {
        const val REQUEST_NOTIFICATIONS = 1001

        /** How many releases the version chooser lists. */
        const val MAX_VERSIONS_SHOWN = 20

        /** How long DownloadManager may sit idle before we download in-process. */
        const val STALL_TIMEOUT_MS = 20_000L
        const val POLL_INTERVAL_MS = 700L

        /** Minimum window before recomputing the transfer rate. */
        const val MIN_SPEED_SAMPLE_MS = 400L
    }
}
