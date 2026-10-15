#!/usr/bin/env sh
set -eu
if ! command -v gradle >/dev/null 2>&1; then
  echo 'Gradle 8.13 is required. Install it or use the GitHub Actions workflow.' >&2
  exit 1
fi
gradle :app:assembleDebug :app:testDebugUnitTest
