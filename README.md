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
