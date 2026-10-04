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

- `app`: application shell
- `core:common`: shared primitives
- `core:designsystem`: visual language and reusable components
- `domain`: business-level URL normalization
- `feature:welcome`: animated first-run experience

## Planned boundaries

`feature:auth`, `feature:home`, `feature:analyzer`, `feature:media`, `feature:downloads`, `feature:history`, `feature:studio`, `feature:settings`, `data:local`, `data:remote`, `resolver`, and `downloader`.

Legacy resolver code, embedded Python, old storage logic, and previous UI are intentionally not carried forward.
