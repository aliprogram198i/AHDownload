#!/usr/bin/env bash
set -uo pipefail

mkdir -p tiktok-e2e-results
TIKTOK_URL="${TIKTOK_URL:-https://www.tiktok.com/@udhaybaah/video/7698296640272125217}"

gradle --no-daemon --no-parallel --max-workers=2 :app:connectedDebugAndroidTest \
  "-Pandroid.testInstrumentationRunnerArguments.class=com.ahdownload.app.download.LiveSocialMediaDownloadE2ETest" \
  "-Pandroid.testInstrumentationRunnerArguments.targetPlatform=TikTok" \
  "-Pandroid.testInstrumentationRunnerArguments.tiktokUrl=${TIKTOK_URL}" \
  2>&1 | tee tiktok-e2e-results/instrumentation.log
test_exit_code=$?

adb logcat -d -v threadtime -s AH_LIVE_E2E:I AndroidRuntime:E \
  > tiktok-e2e-results/logcat.txt 2>&1 || echo "Logcat collection failed" >> tiktok-e2e-results/logcat.txt
printf '%s\n' "$test_exit_code" > tiktok-e2e-results/instrumentation-exit-code.txt
exit "$test_exit_code"
