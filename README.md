# AHDownload

AHDownload is an Android media downloader focused on a reliable download lifecycle, real source extraction, persistent download history, and a unified Arabic-first UI.

## Architecture

- **UI:** Jetpack Compose + Material 3 + AHDownload design system.
- **Navigation:** Home, Downloads, Smart Studio, Settings, Accounts, Diagnostics.
- **Download lifecycle:** WorkManager `DirectDownloadWorker`.
- **Persistence:** app-scoped SQLite database with explicit migrations; legacy SharedPreferences download history is migrated once.
- **Reactive UI:** `DownloadRepository.jobs` exposes a `StateFlow`; Downloads does not poll the database.
- **Media extraction:** platform resolver backed by the embedded yt-dlp runtime plus WebView session fallback.
- **Direct media:** `DirectUrlResolver` returns the same `ResolvedMedia/ResolvedFormat` contract used by platform extraction.
- **Storage:** completed media is published through Android storage APIs; the UI stores only the resulting URI.
- **Diagnostics:** `AppLogger` records sanitized lifecycle events without cookies, tokens, passwords, or raw media URLs.

## Download lifecycle

`QUEUED → DOWNLOADING → COMPLETED`

Failures use bounded WorkManager retries and end in `FAILED`. The Downloads screen provides a real retry action for failed jobs. Two active media transfers are allowed at a time.

The Worker reports:

- progress
- downloaded/total bytes
- transfer speed
- estimated remaining time
- completion/failure notifications when notifications are enabled and permission is granted

No fake Pause/Resume control is exposed. `PAUSED` remains reserved until a real resumable lifecycle is implemented.

## Data retention

The database keeps a bounded history. Old terminal records are removed automatically after the internal retention limit; active downloads are not removed by retention.

Deleting a history record does not claim to delete user media outside the app's ownership.

## Build

The project uses Gradle 8.13, Android SDK 36, Java 17, Kotlin 2.2.20, Compose, WorkManager, OkHttp, Coil, and Chaquopy.

For a release build, production signing variables are required:

- `AH_KEYSTORE_FILE`
- `AH_KEYSTORE_PASSWORD`
- `AH_KEY_ALIAS`
- `AH_KEY_PASSWORD`

Never commit signing credentials.

## Verification policy

Before a release APK is published, CI must complete:

1. Gradle build.
2. APK integrity/metadata checks.
3. APK signature verification.
4. zipalign verification.
5. Android 15 emulator installation and launch test.
6. SHA-256 generation.

A green compile alone is not considered release verification.

## Privacy

Diagnostics intentionally exclude:

- user-entered URLs
- cookies
- authentication tokens
- passwords
- account credentials

Use the in-app **سجل التطبيق** screen to copy sanitized diagnostics for troubleshooting.
