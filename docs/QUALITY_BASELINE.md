# AHDownload Quality Baseline

Baseline revision: `02b710a3793b9040f103fda4c5fc7c7822fc4423`  
Baseline app version: `1.5.7 (129)`  
Baseline date: 2026-10-23

## Recorded baseline evidence

The GitHub Actions run below completed successfully for the baseline revision:

- [Android CI run 39933485929](https://github.com/aliprogram198i/AHDownload/actions/runs/39933485929)
- `verify`: passed, including JVM unit tests, debug APK assembly, and Android test APK assembly.
- `Required Android emulator smoke tests`: passed, including build/install and Android instrumentation execution.
- `release`: passed, including release signing, APK integrity/installability checks, minimum-OS and packaged-ABI verification, and direct APK publication.

This records CI evidence for the baseline commit; it is not a claim that every third-party site or every physical device was tested.

## Required local/CI baseline commands

Run the same deterministic build/test gate used by the Android CI workflow:

```bash
gradle --no-daemon testDebugUnitTest assembleDebug assembleDebugAndroidTest
```

For emulator instrumentation, use the repository's [cloud Android smoke workflow](../.github/workflows/cloud-android-smoke.yml). Before publishing a release, the main Android CI workflow must also pass its signed-APK and installability checks.

## Critical regression matrix

| Area | Existing coverage to preserve | Acceptance expectation |
|---|---|---|
| YouTube search | `domain/src/test/kotlin/com/ahdownload/domain/resolver/youtube/YouTubeSearchProviderTest.kt`; `feature/home/src/test/kotlin/com/ahdownload/feature/home/YouTubeSearchPresentationTest.kt` | Results are deduplicated; regular videos, Shorts, and channels are parsed and filtered correctly; search failures terminate with a visible error. |
| Media resolution and validation | `domain/src/test/kotlin/com/ahdownload/domain/resolver/youtube/YouTubeResolverTest.kt`; `domain/src/test/kotlin/com/ahdownload/domain/validation/CandidateValidatorTest.kt`; `domain/src/test/kotlin/com/ahdownload/domain/network/PublicInternetDnsTest.kt` | Reject invalid/non-media sources; retain explicit authentication and validation failures; do not present an unverified source as ready. |
| Secure download transport | `domain/src/test/kotlin/com/ahdownload/domain/download/DownloadUrlPolicyTest.kt`; `domain/src/test/kotlin/com/ahdownload/domain/download/StreamingDownloadEngineTest.kt`; `app/src/androidTest/java/com/ahdownload/app/download/DownloadLauncherSecurityTest.kt`; `app/src/androidTest/java/com/ahdownload/app/download/FfmpegHttpsProtocolSmokeTest.kt` | Media downloads require HTTPS; HTTPS-to-HTTP downgrade is not followed; incomplete/mismatched responses are not committed as completed files. |
| Pause/resume/cancel and recovery | `app/src/test/java/com/ahdownload/app/download/DownloadControlPolicyTest.kt`; `app/src/androidTest/java/com/ahdownload/app/download/DownloadControlStoreTest.kt`; `domain/src/test/kotlin/com/ahdownload/domain/download/PersistentDownloadQueueTest.kt` | Explicit user intent wins over stale worker callbacks; interrupted work can be reconciled without falsely marking it completed or cancelled. |
| Storage and filenames | `app/src/test/java/com/ahdownload/app/download/DownloadFileNamePolicyTest.kt`; `app/src/test/java/com/ahdownload/app/download/StorageFailureDiagnosticsTest.kt`; `app/src/androidTest/java/com/ahdownload/app/settings/SelectedDirectoryStorageUriTest.kt` | UTF-8 filenames remain filesystem-safe; destination permission and low-storage failures remain observable. |
| Browser navigation/rendering | `app/src/test/java/com/ahdownload/app/browser/BrowserUrlUtilsTest.kt`; `app/src/test/java/com/ahdownload/app/browser/BrowserRenderStatusClassifierTest.kt`; `app/src/androidTest/java/com/ahdownload/app/browser/BrowserPageLoadRegressionTest.kt`; `app/src/androidTest/java/com/ahdownload/app/browser/BrowserPublicSitesAcceptanceTest.kt` | Invalid schemes and unsafe redirects are rejected; expected pages render; browser inspection/logging does not hang navigation. |
| Local playback | `app/src/test/java/com/ahdownload/app/player/PlaybackPositionPolicyTest.kt`; `app/src/test/java/com/ahdownload/app/player/PlaybackTimerPolicyTest.kt`; `app/src/androidTest/java/com/ahdownload/app/player/LocalMediaPlayerSmokeTest.kt` | Supported completed files play; queue/position/timer behavior remains stable through lifecycle events. |
| Diagnostics/privacy | `app/src/test/java/com/ahdownload/app/diagnostics/DiagnosticDataSanitizerTest.kt`; `app/src/test/java/com/ahdownload/app/diagnostics/DiagnosticReportFormatterTest.kt` | Logs preserve correlation and failure categories while redacting credentials, cookies, signed URLs, and tokens. |
| Shared design system | `app/src/androidTest/java/com/ahdownload/app/designsystem/AHEmptyStateTest.kt`; shared-component code in `core/designsystem` | Empty, loading, disabled, success, and failure states remain readable and use the canonical theme/components. |

## External-service acceptance (not equivalent to deterministic unit tests)

- [YouTube live search acceptance](../.github/workflows/youtube-live-search-acceptance.yml) explicitly calls the live YouTube service and checks for both regular video and channel results. It is scheduled/manual because external responses and rate limits vary.
- [Browser target-site acceptance](../.github/workflows/browser-sites-acceptance.yml) exercises target sites in an Android WebView emulator.
- Social-media and public-media acceptance workflows exercise live/public endpoints where the workflow and endpoint are available. A successful deterministic unit-test run alone does not prove that a third-party platform is currently downloadable.

## Merge/release gate

1. Keep the baseline behavior stable; prefer focused changes over broad rewrites.
2. Run deterministic unit tests and assemble debug plus Android test APKs.
3. Run relevant instrumentation tests for changed behavior.
4. Run security-specific regression tests for URL/DNS/redirect/transfer changes.
5. Publish only after the required CI and release integrity checks pass.
6. Report the exact revision and separate passed checks from external/device tests that were not run.


## Phase 1 changes on the current work branch

- `OkHttpTextClient` uses OkHttp's asynchronous call API and propagates coroutine cancellation to `Call.cancel()`. Superseded resolver/search jobs no longer depend on the 20-second call timeout to release an in-flight request. HTTP outcome logging and the configured call timeout remain in place.
- `PublicInternetDns` rejects additional non-global IPv6 destinations, IETF protocol-assignment and documentation prefixes, 6to4 transition addresses, local-use NAT64 destinations, and IPv4-mapped/NAT64 addresses whose embedded IPv4 target is non-public. Ordinary global IPv6 and the well-known NAT64 prefix embedding a public IPv4 address remain allowed.
- Regression tests cover explicit coroutine-to-call cancellation and representative private, reserved, documentation, mapped-IPv4, and NAT64 address cases.
- The Android manifest still allows cleartext navigation because the embedded browser intentionally supports HTTP pages. This does not relax the HTTPS-only media transfer policy enforced by `DownloadUrlPolicy`, validation, and the streaming transfer engine; changing browser transport policy would be a separate compatibility-sensitive change and is not silently folded into this phase.
