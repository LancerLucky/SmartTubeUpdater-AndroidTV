# SmartTube Updater (Android TV)

A single-screen Android TV app that watches
[yuliskov/SmartTube](https://github.com/yuliskov/SmartTube), shows the newest
**stable** release and installs its APK straight to the device.

<img src="docs/banner.png" width="640" alt="SmartTube Updater banner">

## Features

- **Release feed** — GitHub Releases API, drafts excluded, versions ordered by
  semantic version.
- **Version picker** — *Versions* lists the 20 newest releases with stable/beta
  labels; the one on screen is marked. Betas can be picked too.
- **APK picker** — lists every APK of the selected release (`arm64-v8a`,
  `armeabi-v7a`, `x86`, `universal`) with its size, and marks the build that
  matches this device's CPU as recommended.
- **Installed state** — reads the installed SmartTube version on launch and
  reports `Installed 32.80 · update available`, `· up to date` or `· newer than
  the stable release`. Detection covers the current upstream ids
  (`org.smarttube.stable`, `org.smarttube.beta`, `app.smarttube`,
  `app.smarttube.fdroid`), the legacy ones (`com.teamsmart.videomanager.tv`,
  `com.liskovsoft.smarttubetv.beta`) and falls back to scanning launcher apps by
  name/label, so renamed or forked builds are still recognised.
- **Up to date pill** — when the release on screen is already installed, an inert
  `Up to date` button sits at the left of the action row; it is disabled and
  skipped by D-pad focus.
- **Download / Redownload** — the button carries the version it will fetch
  (`Download 32.80`, or `Redownload 32.80` once that build is installed). The
  transfer runs through `DownloadManager` so it survives the app being closed;
  if the system service stays idle (common on TV boxes whose network Android
  does not consider "validated"), the app takes over in-process after 20 s.
- **Pause / Resume** — while a transfer runs the button reads `Pause`; paused it
  reads `Resume`. Bytes already received are kept, so Resume continues with a
  `Range` request instead of starting over.
- **Live progress** — `Downloading 12.3 MB of 25.5 MB (48%) · 1.2 MB/s`; when the
  mirror sends no `Content-Length` it still shows the received size and speed.
- **Install / Reinstall** — `Install 32.80` for an upgrade, `Reinstall 32.80`
  once that build is the one on the device; both hand the finished APK to the
  system package installer.
- **Cleanup** — after the download becomes the installed build, every *older*
  release APK is deleted (app folder and superseded `DownloadManager` request).
  The APK of the installed build is kept on purpose, so `Reinstall 32.80` needs
  no new download.
- **Background check** — a `WorkManager` job re-checks every 12 hours and posts a
  notification when a newer stable release appears.
- **D-pad only** — one screen that fits 1080p without scrolling, a Leanback
  launcher entry with a 320×180 banner, large focus states and oversized dark
  dialogs for the choosers.

## Requirements

- Android TV / Google TV / Fire TV on **Android 5.0 (API 21)** or newer.
- The *Install unknown apps* permission for in-app installs (requested the first
  time you press **Install**).
- To build: JDK 17+ and the Android SDK with compileSdk 35.

## Install

Signed builds are published on the [Releases](../../releases) page — the
repository tracks only source, not APKs. Every release attaches
`SmartTubeUpdater-<version>-release.apk` (package id `com.smarttubeupdater`;
debug builds use `com.smarttubeupdater.debug`).

Over `adb`, with *Developer options → USB/Network debugging* enabled on the TV:

```bash
adb connect <tv-ip>:5555
adb install -r SmartTubeUpdater-1.0.0-release.apk
```

Without adb, copy the APK to the device (USB drive, or a downloader app on the
TV), open it from a file manager and allow *Install unknown apps* when prompted.

To build it yourself instead, see [Build](#build) —
`./gradlew :app:assembleRelease` produces
`app/build/outputs/apk/release/app-release.apk`.

An update only installs over an existing copy if it is signed with the same
key; a differently signed build is rejected until the old app is uninstalled.

## Build

```bash
# open in Android Studio (recommended) or:
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties
./gradlew :app:assembleDebug     # debug, package id com.smarttubeupdater.debug
./gradlew :app:assembleRelease   # production, signed + R8 + resource shrinking
```

| Variant | Output | Notes |
|---|---|---|
| debug | `app/build/outputs/apk/debug/app-debug.apk` | `.debug` package id, debuggable |
| release | `app/build/outputs/apk/release/app-release.apk` | ~1.1 MB, minified, signed (v1+v2+v3) |

### Signing

`keystore.properties` and `keystore/*.jks` are **not** in version control. To
recreate them (or sign on another machine):

```bash
keytool -genkeypair -keystore keystore/smarttube-monitor.jks \
  -alias smarttube-monitor -keyalg RSA -keysize 4096 -validity 10950 \
  -dname "CN=SmartTube Updater, OU=Personal, O=SmartTube Updater, C=US"
# then, with the password you chose:
printf 'storeFile=keystore/smarttube-monitor.jks\nstorePassword=%s\nkeyAlias=smarttube-monitor\nkeyPassword=%s\n' "$PW" "$PW" > keystore.properties
```

Keep that keystore: Android only installs an update over an existing app when the
signing certificate matches. A debug build signed with the debug key cannot
replace a release install — uninstall first (the debug variant uses the `.debug`
package id, so both can coexist).

### Release build safety

- R8 minification and resource shrinking are on; `app/proguard-rules.pro` keeps
  `UpdateWorker` (WorkManager instantiates it reflectively) and source line
  numbers.
- `lintVitalRelease` runs as part of `assembleRelease` and fails on errors.
- `./gradlew build` runs the unit tests plus lint for both build types.

### Releasing

`.github/workflows/release.yml` builds the APK on GitHub-hosted runners, so no
local Android SDK is needed to ship a version:

```bash
# 1. bump versionCode / versionName in app/build.gradle.kts
# 2. tag and push
git commit -am "Bump to 1.0.1"
git tag -a v1.0.1 -m "SmartTube Updater 1.0.1"
git push origin main v1.0.1
```

The workflow runs the unit tests, builds the signed release, and attaches
`SmartTubeUpdater-1.0.1-release.apk` to the GitHub Release for that tag (a tag
containing `-`, e.g. `v1.1.0-beta1`, is published as a prerelease). A manual
*Release APK → Run workflow* only uploads the artifact, which is the quickest way
to verify the toolchain.

Signing on CI needs four repository secrets (`gh secret set NAME`):

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | `base64 -i keystore/smarttube-monitor.jks` |
| `KEYSTORE_PASSWORD` | `storePassword` from `keystore.properties` |
| `KEY_ALIAS` | `keyAlias` (currently `smarttube-monitor`) |
| `KEY_PASSWORD` | `keyPassword` from `keystore.properties` |

If `KEYSTORE_BASE64` is missing the build still succeeds and emits an unsigned
APK with a workflow warning — never commit the keystore or its passwords.

## Project layout

```
app/src/main/java/com/smarttubeupdater/
├── GitHubClient.kt          # GitHub Releases API, version compare, ABI selection, note cleanup
├── InstalledSmartTube.kt    # installed-version detection (package visibility queries)
├── ApkDownloader.kt         # DownloadManager enqueue / status / progress / install
├── ApkFallbackDownloader.kt # in-app download (resume, part files), FileProvider install handoff
├── UpdaterPrefs.kt          # last seen version + pending download state
├── UpdateWorker.kt          # 12-hour periodic check (WorkManager)
├── Notifications.kt         # notification channel + "new release" notification
├── UpdaterApp.kt            # schedules the background check on startup
└── MainActivity.kt          # the single TV screen (card, choosers, download, install)
```

## Tests

```bash
./gradlew :app:testDebugUnitTest
```

26 JVM tests cover version parsing/comparison, release selection, ABI selection,
APK cleanup rules and installed-package detection; the GitHub JSON mapping is
tested against a captured real API response in
`app/src/test/resources/releases.json`.

| Test | Covers |
|---|---|
| `ReleaseSelectionTest` | stable release choice, version ordering, notes |
| `GitHubParsingTest` | releases JSON → model mapping |
| `ApkCleanupTest` | which APKs are pruned, which are kept |
| `InstalledDetectionTest` | package-id matching for current/legacy/renamed builds |

## Languages

The UI follows the system language; anything not translated falls back to
English.

| Locale | Folder |
|---|---|
| English (default) | `values/` |
| Simplified Chinese | `values-zh-rCN/` |
| Traditional Chinese (TW/HK/MO) | `values-b+zh+Hant/` |
| Japanese | `values-ja/` |
| Korean | `values-ko/` |
| Russian | `values-ru/` |
| German | `values-de/` |
| French | `values-fr/` |
| Spanish | `values-es/` |
| Italian | `values-it/` |
| Portuguese (Brazil) | `values-pt-rBR/` |

Every locale carries all 60 translatable strings (`app_name` is marked
`translatable="false"`), and progress messages keep the same format args
(`%1$s`, `%2$s`, `%3$d%%`), so a new string has to be added to `values/` plus each
locale folder. Release titles and notes come from GitHub and stay in whatever
language upstream used.

## Notes

- No third-party networking library: the GitHub call uses `HttpURLConnection` and
  `org.json`, which keeps the APK at ~1.1 MB.
- Unauthenticated GitHub API requests are limited to 60/hour per IP; the app makes
  about two per day (launch + 12-hour job), well inside that budget.
- Release APKs live on the Releases page; `dist/`, `keystore/` and
  `keystore.properties` are gitignored local-only paths.

## License

MIT — see [LICENSE](LICENSE). Copyright (c) 2026 SmartTube Updater.

### Third-party notices

- **SmartTube** ([yuliskov/SmartTube](https://github.com/yuliskov/SmartTube)) is a
  separate project under its own license (GPL-3.0 upstream). This updater is not
  affiliated with it, contains none of its code, and only downloads and hands its
  published APKs to the Android package installer.
- **AndroidX, Kotlin, Kotlin Coroutines, WorkManager** are used under the Apache
  License 2.0.
- Android TV, Google TV, Fire TV and Samsung are trademarks of their respective
  owners.
