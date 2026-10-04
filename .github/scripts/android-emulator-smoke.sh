#!/usr/bin/env bash
set -euo pipefail

adb wait-for-device
boot_completed=""
for _ in $(seq 1 90); do
  boot_completed="$(adb shell getprop sys.boot_completed | tr -d '\r')" || true
  if [ "$boot_completed" = "1" ]; then break; fi
  sleep 2
done

diagnose() {
  echo "::group::ADB device state"
  adb devices -l || true
  adb shell getprop ro.build.version.sdk || true
  adb shell getprop sys.boot_completed || true
  echo "::endgroup::"
  echo "::group::Package state"
  adb shell dumpsys package com.ahdownload.app || true
  adb shell dumpsys activity activities | tail -n 120 || true
  echo "::endgroup::"
  echo "::group::Recent logcat"
  adb logcat -d -t 1000 || true
  echo "::endgroup::"
}

if [ "$boot_completed" != "1" ]; then
  echo "ANDROID_EMULATOR_BOOT_TIMEOUT"
  diagnose
  exit 1
fi

adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
adb shell pm path android >/dev/null

APK=app/build/outputs/apk/release/app-release.apk
echo "APK size: $(stat -c%s "$APK")"
echo "APK SHA-256: $(sha256sum "$APK" | cut -d ' ' -f1)"

if ! timeout 120s adb install -r "$APK"; then
  echo "APK_INSTALL_TIMEOUT_OR_FAILURE"
  diagnose
  exit 1
fi

adb shell pm path com.ahdownload.app

if ! timeout 60s adb shell monkey -p com.ahdownload.app 1; then
  echo "APK_LAUNCH_TIMEOUT_OR_FAILURE"
  diagnose
  exit 1
fi

sleep 3

if ! adb shell dumpsys package com.ahdownload.app | grep -q 'versionName=1.10.0'; then
  echo "APK_PACKAGE_METADATA_CHECK_FAILED"
  diagnose
  exit 1
fi

mkdir -p visual-audit

if ! timeout 8m gradle --no-daemon connectedDebugAndroidTest --console=plain; then
  echo "ANDROID_INSTRUMENTATION_TEST_TIMEOUT_OR_FAILURE"
  diagnose
  exit 1
fi

for screen in home downloads studio settings; do
  if ! timeout 30s adb exec-out run-as com.ahdownload.app.debug cat "files/visual-audit/$screen.png" > "visual-audit/$screen.png"; then
    echo "VISUAL_EVIDENCE_EXTRACTION_FAILED screen=$screen"
    diagnose
    exit 1
  fi
  test -s "visual-audit/$screen.png"
  file "visual-audit/$screen.png"
done
