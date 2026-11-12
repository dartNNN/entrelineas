#!/usr/bin/env bash
# Runs all tests: builder (pytest), reader unit tests (JVM), web reader (Vitest, oxlint, Playwright in Chromium), reader
# UI and pagination tests on the reno5 emulator.
# Usage: ./test.sh [--no-emulator]
set -euo pipefail
cd "$(dirname "$0")"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/.local/android-sdk}"
PY="${PYTHON:-$([[ -x .venv/bin/python ]] && echo .venv/bin/python || echo python3)}"

echo "== builder"
"$PY" -m pytest -q builder/tests

echo "== reader unit tests"
(cd reader && gradle --no-daemon -q testDebugUnitTest)

echo "== web reader"
(cd reader-web && { [[ -d node_modules ]] || npm ci --silent; } && npm test --silent && npm run lint --silent)

echo "== web reader in a browser (Playwright)"
# Playwright's own Chromium, or a system one (PLAYWRIGHT_CHROMIUM, else chromium on the PATH).
(cd reader-web && PLAYWRIGHT_CHROMIUM="${PLAYWRIGHT_CHROMIUM:-$(command -v chromium || true)}" npm run e2e --silent)

[[ "${1:-}" == "--no-emulator" ]] && exit 0

echo "== emulator"
# Instrumented tests replace the app and its data: never run them on a connected phone, only on the emulator.
if ! adb devices | grep -q "emulator-"; then
  "$ANDROID_HOME/emulator/emulator" -avd reno5 -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -no-snapshot >/dev/null 2>&1 &
fi
until adb devices | grep -q "^emulator-"; do sleep 2; done
export ANDROID_SERIAL="$(adb devices | awk '/^emulator-/ {print $1; exit}')"
until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 5; done

# The real chapter is used for pagination timing when present (copyrighted, not in git).
built="$(ls build/*.bbook.zip 2>/dev/null | grep -v '\.offline\.' | head -1 || true)"
if [[ -n "$built" ]]; then
  mkdir -p reader/app/src/androidTest/assets
  cp "$built" reader/app/src/androidTest/assets/book.bbook.zip
fi

echo "== reader instrumented tests"
(cd reader && gradle --no-daemon -q connectedDebugAndroidTest)
echo "all tests passed"
