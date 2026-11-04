#!/usr/bin/env bash
# Compact debug-APK build for agents and humans.
# Runs Gradle quietly and prints only what matters: errors (if any) and a
# one-screen summary — version, APK path, size, sha256, byte-sync guard.
#
# Usage:
#   tools/build-apk.sh            # assembleDebug only
#   tools/build-apk.sh --test     # testDebugUnitTest + assembleDebug
#   tools/build-apk.sh --clean    # clean first (combine with --test if needed)
#
# Debug only by design: the project ships debug APKs only, there is no release build.
# A green build proves nothing on the head unit — see CLAUDE.md.
set -uo pipefail

cd "$(dirname "$0")/.."

tasks=()
for arg in "$@"; do
  case "$arg" in
    --test)  tasks+=(testDebugUnitTest) ;;
    --clean) tasks=(clean "${tasks[@]}") ;;
    -h|--help) sed -n 2,12p "$0"; exit 0 ;;
    *) echo "unknown arg: $arg" >&2; exit 2 ;;
  esac
done
tasks+=(assembleDebug)

log=$(mktemp -t build-apk.XXXXXX)
start=$(date +%s)
./gradlew "${tasks[@]}" -q --console=plain >"$log" 2>&1
rc=$?
dur=$(( $(date +%s) - start ))

if [ $rc -ne 0 ]; then
  echo "BUILD FAILED (rc=$rc, ${dur}s) — ${tasks[*]}"
  # Kotlin errors ("e: file://...") and failing tests first, then Gradle's summary.
  grep -E '^e: |FAILED$|Test.*FAILED|\* What went wrong' -A3 "$log" | head -60
  echo "--- tail ---"
  tail -15 "$log"
  echo "full log: $log"
  exit $rc
fi

vname=$(grep -m1 -E '^\s*versionName\s*=' app/build.gradle.kts | sed -E 's/.*"(.*)".*/\1/')
vcode=$(grep -m1 -E '^\s*versionCode\s*=' app/build.gradle.kts | sed -E 's/[^0-9]//g')
apk="app/build/outputs/apk/debug/VoltFlow-Mate-v${vname}.apk"

if [ ! -f "$apk" ]; then
  echo "BUILD OK but APK not found at $apk"; exit 1
fi

size=$(du -h "$apk" | cut -f1)
sha=$(shasum -a 256 "$apk" | cut -c1-16)
warns=$(grep -c '^w: ' "$log" || true)

if cmp -s tools/start_voltflow_cmd.sh app/src/main/assets/start_voltflow_cmd.sh; then
  sync="ok"
else
  sync="MISMATCH (tools/ vs assets/ start_voltflow_cmd.sh)"
fi

echo "BUILD OK (${dur}s) — ${tasks[*]}"
echo "version:  ${vname} (versionCode ${vcode})"
echo "apk:      ${apk} (${size}, sha256 ${sha}…)"
echo "warnings: ${warns}   daemon script sync: ${sync}"
echo "install:  adb connect 192.168.43.71:5555 — then install yourself (pv | pm install -S)"
rm -f "$log"
