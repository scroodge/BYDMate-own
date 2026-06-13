#!/usr/bin/env bash
# Publish the current VoltFlow Mate build to the VoltFlow web app's release
# catalog (Supabase mate_app_releases). The web dashboard compares the version
# running on the car (telemetry mate_version) against the latest row here and
# shows an "update available" banner.
#
# Usage:
#   SUPABASE_URL=https://xyz.supabase.co \
#   SUPABASE_SERVICE_ROLE_KEY=eyJ... \
#   tools/publish-mate-release.sh ["release notes"]
#
# Reads versionName / versionCode straight from app/build.gradle.kts, so the
# published version always matches what git built. Run it right after bumping
# the version (and ideally after `adb install -r` puts the new APK on the car).

set -euo pipefail

cd "$(dirname "$0")/.."

GRADLE="app/build.gradle.kts"
NOTES="${1:-}"

if [[ -z "${SUPABASE_URL:-}" || -z "${SUPABASE_SERVICE_ROLE_KEY:-}" ]]; then
  echo "error: set SUPABASE_URL and SUPABASE_SERVICE_ROLE_KEY env vars" >&2
  exit 1
fi

VERSION_NAME="$(grep -E 'versionName\s*=' "$GRADLE" | head -1 | sed -E 's/.*versionName\s*=\s*"([^"]+)".*/\1/')"
VERSION_CODE="$(grep -E 'versionCode\s*=' "$GRADLE" | head -1 | sed -E 's/[^0-9]*([0-9]+).*/\1/')"

if [[ -z "$VERSION_NAME" || -z "$VERSION_CODE" ]]; then
  echo "error: could not parse versionName/versionCode from $GRADLE" >&2
  exit 1
fi

echo "Publishing VoltFlow Mate v$VERSION_NAME (code $VERSION_CODE)..."

# Build JSON payload safely (jq if available, else minimal escaping).
if command -v jq >/dev/null 2>&1; then
  PAYLOAD="$(jq -n \
    --arg version "$VERSION_NAME" \
    --argjson version_code "$VERSION_CODE" \
    --arg notes "$NOTES" \
    '{version: $version, version_code: $version_code, release_notes: (if $notes == "" then null else $notes end)}')"
else
  ESCAPED_NOTES="${NOTES//\"/\\\"}"
  if [[ -z "$NOTES" ]]; then NOTES_JSON="null"; else NOTES_JSON="\"$ESCAPED_NOTES\""; fi
  PAYLOAD="{\"version\":\"$VERSION_NAME\",\"version_code\":$VERSION_CODE,\"release_notes\":$NOTES_JSON}"
fi

# Upsert on the unique `version` column.
HTTP_CODE="$(curl -sS -o /tmp/mate_release_resp.txt -w '%{http_code}' \
  -X POST "${SUPABASE_URL%/}/rest/v1/mate_app_releases?on_conflict=version" \
  -H "apikey: $SUPABASE_SERVICE_ROLE_KEY" \
  -H "Authorization: Bearer $SUPABASE_SERVICE_ROLE_KEY" \
  -H "Content-Type: application/json" \
  -H "Prefer: resolution=merge-duplicates,return=representation" \
  -d "$PAYLOAD")"

if [[ "$HTTP_CODE" =~ ^2 ]]; then
  echo "OK ($HTTP_CODE): v$VERSION_NAME published."
  cat /tmp/mate_release_resp.txt
  echo
else
  echo "error: Supabase returned HTTP $HTTP_CODE" >&2
  cat /tmp/mate_release_resp.txt >&2
  echo >&2
  exit 1
fi
