#!/usr/bin/env bash
# Builds the published stein.json from the one in this repository (discord, news and "sources", edited by hand) and
# the releases: "latest" is the newest stable release, "test.loader" a test release newer than it.
#
#   index.sh <owner/repo> <out file> [version of a release being created now]
#
# The Stein Client, the installer and the Loader in the game read it at
# releases/latest/download/stein.json, so it travels as a file of the latest stable release. A release is published
# with it inside (release.yml); index.yml uploads it again when the file here changes, or a test release comes out.
set -euo pipefail
REPO="${1:?repository}"; OUT="${2:?out file}"; NEW="${3:-}"
here="$(cd "$(dirname "$0")" && pwd)"

# jq on the Windows runner ends lines with CRLF, and a \r at the end of a value breaks comparisons.
jq() { command jq "$@" | tr -d '\r'; }

releases="$(gh api "repos/$REPO/releases?per_page=100" --jq '[.[] | select(.draft | not) | {tag: .tag_name, pre: .prerelease}]')"
latest="$(jq -r '[.[] | select(.pre | not)][0].tag // empty' <<< "$releases")"
test_tag="$(jq -r '[.[] | select(.pre)][0].tag // empty' <<< "$releases")"
if [ -n "$NEW" ]; then
  case "$NEW" in *-*) test_tag="v$NEW" ;; *) latest="v$NEW" ;; esac
fi
[ -n "$latest" ] || { echo "::error::no stable release of $REPO yet: nothing to point latest at"; exit 1; }

version="${latest#v}"
test_version="${test_tag#v}"; test_version="${test_version%%-*}"
filter='.latest.version = $v | .latest.url = $u'
args=(--arg v "$version" --arg u "https://github.com/$REPO/releases/latest")
# A test release counts only while it is ahead of the stable one (the stable of the same version replaces it).
if [ -n "$test_tag" ] && [ "$(printf '%s\n%s\n' "$version" "$test_version" | sort -V | tail -1)" = "$test_version" ] \
    && [ "$test_version" != "$version" ]; then
  filter="$filter | .test.loader = {version: \$tv, tag: \$tt, url: \$tu}"
  args+=(--arg tv "$test_version" --arg tt "$test_tag" --arg tu "https://github.com/$REPO/releases/tag/$test_tag")
fi
jq "${args[@]}" "$filter" "$here/../../stein.json" > "$OUT"
echo "stein.json: latest $version${test_tag:+, test $test_tag}"
