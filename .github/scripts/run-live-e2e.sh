#!/usr/bin/env bash
set -u
mkdir -p live-e2e-artifacts

gradle --no-daemon --max-workers=2 -Dorg.gradle.parallel=false '-Dorg.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8' connectedDebugAndroidTest --stacktrace > live-e2e-artifacts/connected-debug-android-test.log 2>&1
test_status=$?

cat live-e2e-artifacts/connected-debug-android-test.log
adb logcat -d -v threadtime > live-e2e-artifacts/logcat.txt 2>/dev/null || true
adb logcat -d -v threadtime -s AH_LIVE_E2E:I AndroidRuntime:E > live-e2e-artifacts/live-test-logcat.txt 2>/dev/null || true
exit "$test_status"
