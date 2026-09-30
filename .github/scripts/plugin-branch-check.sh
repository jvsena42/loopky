#!/bin/sh
# The `plugin` branch against the release it claims to be (#388).
#
#   .github/scripts/plugin-branch-check.sh <tag> [<branch-ref>]
#
# Plugin directories download a repository's whole archive, and this one's is ~112 MiB against a
# 50 MiB limit — so the directory is pointed at `plugin`, a branch holding nothing but the plugin.
# Nothing else in the build reads that branch, so this is what stops it drifting silently:
#
#   1. its plugins/loopky is byte-identical to the tag's;
#   2. it holds nothing else but its README, which is what keeps it small;
#   3. its archive is far under the directory's limit;
#   4. its plugin.json version is the tag's.
#
# Every failure is reported, not just the first. Needs both refs fetched; exits 1 on any failure.
set -eu

TAG=${1:?usage: plugin-branch-check.sh <tag> [<branch-ref>]}
BRANCH=${2:-origin/plugin}
LIMIT=$((10 * 1024 * 1024)) # a fifth of the directory's 50 MiB, so growth is caught long before it
FAILED=0

fail() { echo "::error::$1" >&2; FAILED=1; }

git rev-parse --verify --quiet "$TAG^{commit}" > /dev/null || { echo "::error::$TAG is not fetched" >&2; exit 1; }
git rev-parse --verify --quiet "$BRANCH^{commit}" > /dev/null || { echo "::error::$BRANCH is not fetched" >&2; exit 1; }

if git diff --quiet "$TAG" "$BRANCH" -- plugins/loopky; then
  echo "ok: $BRANCH:plugins/loopky matches $TAG"
else
  fail "$BRANCH:plugins/loopky differs from $TAG. The directory is serving a plugin that was never released:"
  git diff --stat "$TAG" "$BRANCH" -- plugins/loopky >&2
fi

STRAYS=$(git ls-tree -r --name-only "$BRANCH" | grep -vE '^(README\.md|plugins/loopky/.+)$' || true)
if [ -n "$STRAYS" ]; then
  fail "$BRANCH carries files outside plugins/loopky, and every one of them is downloaded by the directory:"
  printf '  %s\n' $STRAYS >&2
else
  echo "ok: $BRANCH holds only README.md and plugins/loopky"
fi

SIZE=$(git archive --format=tar.gz "$BRANCH" | wc -c | tr -d ' ')
if [ "$SIZE" -gt "$LIMIT" ]; then
  fail "$BRANCH's archive is $SIZE bytes, over this check's $LIMIT (the directory refuses 50 MiB)"
else
  echo "ok: $BRANCH's archive is $SIZE bytes"
fi

WANT=${TAG#v}
GOT=$(git show "$BRANCH:plugins/loopky/.claude-plugin/plugin.json" | sed -n 's/^ *"version": *"\([^"]*\)".*/\1/p')
if [ "$GOT" = "$WANT" ]; then
  echo "ok: plugin.json version is $GOT"
else
  fail "$BRANCH's plugin.json version is '$GOT', expected $WANT"
fi

exit "$FAILED"
