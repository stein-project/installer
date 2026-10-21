#!/usr/bin/env bash
# Publishes the pack release built by release.yml (dist/ and notes.md) in this repository, with stein.json, the file
# the Stein Client, the installer and the Loader read (index.sh), with the token in GH_TOKEN.
#
#   publish.sh <owner/repo>
#   env: VERSION, TAG (v<version> or v<version>-<label>), TESTE (the label, empty for a stable release)
set -euo pipefail
REPO="${1:?repository}"
here="$(cd "$(dirname "$0")" && pwd)"
. "$here/dist.sh"
: "${VERSION:?}" "${TAG:?}"
TESTE="${TESTE:-}"

if [ -z "$TESTE" ]; then
  # A stable release carries stein.json from the start: it becomes releases/latest the moment it is published.
  bash "$here/index.sh" "$REPO" dist/stein.json "$VERSION"
  echo "== Stein Loader $VERSION -> $REPO ($TAG)"
  dist_release "$REPO" "$TAG" "Stein Loader $VERSION" notes.md 0 dist/*
  exit 0
fi

# A pre-release: releases/latest ignores it, so every usual link stays on the stable one. The test channel learns
# about it through "test.loader" in the stein.json of the latest stable release.
{ echo "> **Test version ($TESTE)**, only for the test channel. The version for everyone comes later."
  echo; cat notes.md; } > notes-teste.md
echo "== Stein Loader $VERSION ($TESTE) -> $REPO ($TAG)"
dist_release "$REPO" "$TAG" "Stein Loader $VERSION ($TESTE)" notes-teste.md 1 dist/*
bash "$here/index.sh" "$REPO" stein.json "$VERSION-$TESTE"
stable="$(gh release view --repo "$REPO" --json tagName -q .tagName)"
gh release upload "$stable" stein.json --repo "$REPO" --clobber
echo "stein.json of $stable updated"
