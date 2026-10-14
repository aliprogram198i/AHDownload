#!/usr/bin/env bash
set -euo pipefail

validate_instrumentation_results() {
  local label="$1"
  local report
  local scheduled_count
  local test_count
  local passed_count
  local failed_count

  report="$(find app/build/outputs/androidTest-results/connected -name test-result.textproto -print -quit)"
  if [[ -z "$report" || ! -s "$report" ]]; then
    echo "Instrumentation report missing for $label; execution is not verified." >&2
    return 1
  fi

  if ! grep -q '^test_status: PASSED$' "$report"; then
    echo "Instrumentation report did not pass for $label." >&2
    cat "$report"
    return 1
  fi

  scheduled_count="$(sed -n 's/^  scheduled_test_case_count: \([0-9][0-9]*\)$/\1/p' "$report" | head -n 1)"
  test_count="$(grep -c '^  test_case {' "$report" || true)"
  passed_count="$(grep -c '^  test_status: PASSED$' "$report" || true)"
  failed_count="$(grep -c '^  test_status: FAILED$' "$report" || true)"

  echo "$label test counts: scheduled=$scheduled_count; executed=$test_count; passed=$passed_count; failed=$failed_count"
  if [[ -z "$scheduled_count" || "$scheduled_count" -le 0 ||
        "$test_count" -ne "$scheduled_count" ||
        "$passed_count" -ne "$test_count" ||
        "$failed_count" -ne 0 ]]; then
    echo "Every scheduled instrumentation test must execute and pass." >&2
    return 1
  fi
}

run_instrumented_class() {
  local test_class="$1"
  local label="$2"
  local log_file="$3"
  local logcat_tag="$4"
  local status

  if gradle --no-daemon --max-workers=2 connectedDebugAndroidTest \
      "-Pandroid.testInstrumentationRunnerArguments.class=$test_class" 2>&1 | tee "$log_file"; then
    status=0
  else
    status=$?
  fi

  adb logcat -d -v threadtime -s "$logcat_tag:I" AndroidRuntime:E > "${log_file%.log}-logcat.txt" || true
  if [[ "$status" -ne 0 ]]; then
    echo "$label instrumentation command failed with exit code $status." >&2
    return "$status"
  fi

  validate_instrumentation_results "$label"
}

# Run both media suites even if one fails, then aggregate their statuses.
set +e
run_instrumented_class \
  "com.ahdownload.app.download.FfmpegHttpsProtocolSmokeTest" \
  "FFmpeg HTTPS fixture matrix" \
  "ffmpeg-https-matrix.log" \
  "AH_FFMPEG_HTTPS_MATRIX"
https_status=$?

rm -rf app/build/outputs/androidTest-results/connected app/build/reports/androidTests/connected

run_instrumented_class \
  "com.ahdownload.app.download.PublicMediaDownloadMatrixE2ETest" \
  "requested XNXX end-to-end test" \
  "single-url-download.log" \
  "AH_MEDIA_MATRIX"
download_status=$?
set -e

printf 'Acceptance summary: https_matrix=%s requested_download=%s\n' "$https_status" "$download_status"
if [[ "$https_status" -ne 0 || "$download_status" -ne 0 ]]; then
  echo "Media acceptance failed; inspect the HTTPS matrix and full-download logs." >&2
  exit 1
fi
