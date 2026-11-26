package com.smarttubeupdater

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import java.util.Locale

/** A SmartTube package that is currently installed on this device. */
data class InstalledApp(
    val packageName: String,
    val versionName: String
)

/**
 * Detects whether SmartTube is already installed so the UI can tell the user
 * whether the published release is an update or a fresh install.
 *
 * SmartTube has shipped under several package ids over time and renaming them
 * again is easy, so detection works in two passes:
 *  1. the known ids below (cheap, exact);
 *  2. if none matched, every launcher app declared in `<queries>` is scanned and
 *     matched by package name / label, which catches renamed or forked builds.
 */
object InstalledSmartTube {

    /** Current upstream scheme first, then the historical ids. */
    val PACKAGE_IDS = listOf(
        // smarttubetv/build.gradle.kts, 32.8x+
        "org.smarttube.stable",
        "org.smarttube.beta",
        "app.smarttube",
        "app.smarttube.fdroid",
        // Legacy ids still used by older installed builds
        "com.teamsmart.videomanager.tv",
        "com.liskovsoft.smarttubetv.beta",
        "com.liskovsoft.smarttube"
    )

    fun detect(context: Context): InstalledApp? {
        val pm = context.packageManager

        val byId = PACKAGE_IDS.mapNotNull { id ->
            versionNameOf(pm, id)?.let { InstalledApp(id, it) }
        }
        if (byId.isNotEmpty()) return pickNewest(byId)

        // Unknown id: look at the apps the user can actually launch.
        return pickNewest(scanLauncherApps(context))
    }

    private fun scanLauncherApps(context: Context): List<InstalledApp> {
        val pm = context.packageManager
        val found = ArrayList<InstalledApp>()
        val seen = HashSet<String>()

        for (category in arrayOf(
            Intent.CATEGORY_LEANBACK_LAUNCHER,
            Intent.CATEGORY_LAUNCHER
        )) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
            val resolved = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    pm.queryIntentActivities(intent, 0)
                }
            } catch (t: Throwable) {
                emptyList()
            }

            for (info in resolved) {
                val packageName = info.activityInfo?.packageName ?: continue
                if (!seen.add(packageName)) continue

                val label = try {
                    info.loadLabel(pm)?.toString().orEmpty()
                } catch (t: Throwable) {
                    ""
                }
                if (!looksLikeSmartTube(packageName, label, context.packageName)) continue

                versionNameOf(pm, packageName)?.let { found.add(InstalledApp(packageName, it)) }
            }
        }
        return found
    }

    private fun versionNameOf(pm: PackageManager, packageName: String): String? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0)).versionName
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, 0).versionName
        }
    } catch (t: Throwable) {
        // Not installed, or not visible through <queries>.
        null
    }

    /** The newest of several installed SmartTube builds (e.g. stable + beta). */
    internal fun pickNewest(candidates: List<InstalledApp>): InstalledApp? =
        candidates.maxWithOrNull(
            Comparator { left, right ->
                compareVersions(left.versionName, right.versionName)
            }
        )

    /** Package-name / label heuristic used for unknown SmartTube ids. */
    internal fun looksLikeSmartTube(
        packageName: String,
        label: String,
        selfPackage: String
    ): Boolean {
        if (packageName == selfPackage) return false

        val pkg = packageName.lowercase(Locale.US)
        val lbl = label.lowercase(Locale.US)

        // Never match this updater app or its .debug variant.
        if (pkg.contains("smarttubeupdater")) return false
        if ((lbl.contains("smarttube") && lbl.contains("updater"))) return false

        return pkg.contains("smarttube") ||
            pkg.contains("videomanager") ||
            lbl.contains("smarttube")
    }
}
