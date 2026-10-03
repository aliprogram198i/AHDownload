# AHDownload official Release signing

The repository never stores the Android signing keystore or passwords.

GitHub Actions expects these repository secrets:

- `AH_KEYSTORE_BASE64`: base64-encoded `AHDownload-release.keystore`
- `AH_KEYSTORE_PASSWORD`: keystore password
- `AH_KEY_ALIAS`: signing alias
- `AH_KEY_PASSWORD`: key password

The release workflow decodes the keystore into the runner's temporary directory, validates the alias, builds `assembleRelease`, and runs `apksigner verify` before uploading the APK.

## Local release build

Set:

```text
AH_KEYSTORE_FILE=/absolute/path/AHDownload-release.keystore
AH_KEYSTORE_PASSWORD=...
AH_KEY_ALIAS=...
AH_KEY_PASSWORD=...
```

Then run:

```bash
gradle --no-daemon assembleRelease
```

Never commit the keystore, passwords, or a decoded secret file.

## Key continuity

The same keystore must be retained for every future AHDownload update. Losing it means future APKs cannot be signed with the same application signing identity.
