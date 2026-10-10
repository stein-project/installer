#!/usr/bin/env bash
# Publishes the pack release built by release.yml (dist/ and notes.md) in the distribution repository
# (Stein-Project/stein-loader) and updates its stein.json, with the token in GH_TOKEN.
#
#   publish-loader.sh <owner/stein-loader> <name of the secret, for the error message>
#   env: VERSION, TAG (v<version> or v<version>-<label>), TESTE (the label, empty for a stable release),
#        DIST_OWNER (where "latest.url" points)
set -euo pipefail
REPO="${1:?repository}"
here="$(cd "$(dirname "$0")" && pwd)"
. "$here/dist.sh"
dist_need_token "${2:-DIST_TOKEN}"
: "${VERSION:?}" "${TAG:?}" "${DIST_OWNER:?}"
TESTE="${TESTE:-}"

notes=notes.md
pre=0
title="Stein Loader $VERSION"
if [ -n "$TESTE" ]; then
  # A pre-release: releases/latest ignores it, so every usual link stays on the stable one.
  notes="notes-teste.md"
  { echo "> **Test version ($TESTE)**, only for the Stein Client's test channel. The version for everyone comes later."
    echo; cat notes.md; } > "$notes"
  pre=1
  title="Stein Loader $VERSION ($TESTE)"
fi

echo "== $title -> $REPO ($TAG)"
dist_release "$REPO" "$TAG" "$title" "$notes" "$pre" dist/*

# stein.json: only "latest" (stable) or "test.loader" (test); the mods index, discord and news are not touched.
if [ -n "$TESTE" ]; then
  dist_hub "$REPO" "$title" '.test.loader = {version: $v, tag: $t, url: $u}' \
    --arg v "$VERSION" --arg t "$TAG" --arg u "https://github.com/$REPO/releases/tag/$TAG"
else
  # The stable one is out: the test of the same version leaves the test channel.
  dist_hub "$REPO" "$title" \
    '.latest.version = $v | .latest.url = $u | if .test.loader.version == $v then del(.test.loader) else . end' \
    --arg v "$VERSION" --arg u "https://github.com/$DIST_OWNER/stein-loader/releases/latest"
fi
