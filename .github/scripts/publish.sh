#!/usr/bin/env bash
# Publishes the pack release built by release.yml (dist/ and notes.md) in this repository, with the token in GH_TOKEN,
# and writes "latest" (or "test.loader") into stein.json of the Stein registry, which the Stein Client, the installer
# and the Loader read (index.sh, registry.sh; REGISTRY_TOKEN). The release keeps carrying its own stein.json as a
# bridge for programs older than the registry.
#
#   publish.sh <owner/repo>
#   env: VERSION, TAG (v<version> or v<version>-<label>), TESTE (the label, empty for a stable release),
#        REGISTRY_TOKEN, ENSAIO (true: only prints what would change in the registry)
set -euo pipefail
REPO="${1:?repository}"
here="$(cd "$(dirname "$0")" && pwd)"
. "$here/dist.sh"
. "$here/registry.sh"

# stein.json of the registry with this release in it: the registry's own copy is the base, so its discord, news and
# sources stay as they were edited there. registry_update rebuilds it from the copy it is about to replace.
registry_index() {
  _REGISTRY_RELEASE="$1"
  if ! registry_update stein.json "Stein Loader $1 (installer release)" _registry_stein; then
    # A dry run only reports it; a real release must not finish without the registry knowing about it.
    [ "${ENSAIO:-false}" = true ] && { echo "::warning::stein.json of $REGISTRY_REPO could not be read"; return 0; }
    return 1
  fi
}

# registry_update's build function: <current stein.json of the registry> <out file>.
_registry_stein() {
  [ -n "$1" ] || { echo "::error::$REGISTRY_REPO has no stein.json"; return 1; }
  bash "$here/index.sh" "$REPO" "$2" "$_REGISTRY_RELEASE" "$1"
}

: "${VERSION:?}"
TESTE="${TESTE:-}"

# The dry run publishes nothing: it only shows stein.json of the registry as this release would leave it.
if [ "${ENSAIO:-false}" = true ]; then
  registry_index "$VERSION${TESTE:+-$TESTE}"
  exit 0
fi
: "${TAG:?}"

if [ -z "$TESTE" ]; then
  # A stable release carries stein.json from the start: it becomes releases/latest the moment it is published.
  bash "$here/index.sh" "$REPO" dist/stein.json "$VERSION"
  echo "== Stein Loader $VERSION -> $REPO ($TAG)"
  dist_release "$REPO" "$TAG" "Stein Loader $VERSION" notes.md 0 dist/*
  registry_index "$VERSION"
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
registry_index "$VERSION-$TESTE"
