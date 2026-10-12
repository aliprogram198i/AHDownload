# AHDownload

AHDownload is being rebuilt from an empty repository with a modular Android architecture focused on social-media media download workflows.

## Foundation

- Kotlin 2.4.20
- Jetpack Compose + Material 3
- Android Gradle Plugin 9.3.0
- Gradle 9.5
- JVM 17
- Feature-oriented modules
- Separate domain, design system, and application shell
- Motion-friendly reusable UI components

## Current modules

- `app`: application composition root, download execution, settings, storage, and diagnostics
- `core:common`: shared primitives and diagnostic contracts
- `core:designsystem`: visual language and reusable components
- `domain`: URL normalization, media models, resolver contracts, platform adapters, and validation
- `feature:welcome`: animated first-run experience
- `feature:home`: URL analysis, YouTube search, platform resolution, and the unified video/audio result card
- `feature:downloads`: download queue and download-history UI
- `feature:studio`: inspection tools for downloaded media

## Planned boundaries

Future work includes `feature:auth`, `feature:analyzer`, `feature:media`, `feature:history`, and further separation of `data:local`, `data:remote`, resolver, and downloader implementation details where that reduces coupling.

Legacy resolver code, embedded Python, old storage logic, and previous UI are intentionally not carried forward.
## Transport and device compatibility

- The app minimum OS level is Android 7.0 (API 24). OS version support and CPU ABI support are separate requirements.
- The currently pinned custom FFmpeg Full + HTTPS runtime is built with `armeabi-v7a`, `armeabi-v7a-neon`, and `x86` disabled. Native FFmpeg processing is therefore intended for `arm64-v8a` and `x86_64`; 32-bit-only devices need a separately built and tested runtime before they can be advertised as fully supported.
- URL inputs and media candidates must use HTTPS. Scheme-less input is normalized to HTTPS; explicit HTTP links and downgrade targets are rejected rather than enabling global cleartext traffic.
- App backup is disabled so local download records, preferences, favorites, and diagnostic data are not automatically included in Android backup.
