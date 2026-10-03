# AHDownload implementation notes

## Product behavior
- One APK, one Android app module.
- A downloaded video remains a single original file by default.
- Split/trim/convert are explicit Smart Studio actions only.
- The UI must display only formats returned by a resolver; it must never invent quality/codec/bitrate options.
- Provider-specific extraction is isolated behind SourceResolver and should be supplied by an approved backend/provider implementation.

## Release gates
- Android SDK/Gradle build
- Kotlin/Compose compilation
- lint
- unit tests
- device smoke test
- background download test
- network loss/resume test
- storage-full test
- RTL and dark-mode test
- release signing
