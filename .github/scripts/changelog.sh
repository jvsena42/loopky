#!/usr/bin/env bash
#
# The release notes for a tag, from the commit subjects behind it.
#
# A script rather than a block inside `release.yml` so that the notes a tag is *going* to get can
# be read before the tag is pushed — `.github/scripts/changelog.sh v0.9.0 v0.8.1` off a local
# checkout prints exactly what the workflow will publish. A release note nobody could preview is
# one that gets reviewed after it is public.
#
# Conventional-commit types decide what is user-facing; everything else (chore, ci, docs, test,
# refactor, style, build) is dropped, including the release's own version bump.
set -euo pipefail
# Command substitutions do not inherit `errexit` without this, and every section below is one. A
# failing `git log` would otherwise print its fatal to stderr, return empty, and let the script
# exit 0 having announced "no user-facing changes" — a generation failure wearing the costume of a
# successful empty changelog. Guarded because macOS still ships bash 3.2, which has no such option.
shopt -s inherit_errexit 2>/dev/null || true

VERSION="${1:?usage: changelog.sh <version> [previous-tag]}"
PREV="${2:-}"

die() { echo "changelog.sh: $*" >&2; exit 1; }
is_commit() { git rev-parse --verify --quiet "$1^{commit}" >/dev/null 2>&1; }

# **The version names the release; the endpoint is what gets read.** They are the same thing in CI,
# where the tag exists. They are not when previewing before the tag is created, which is the whole
# reason this is a file rather than a block inside the workflow — so an unknown <version> reads
# HEAD, and says so, while the compare link below still names the version the tag will carry.
# Silently reading nothing is what made the preview show an empty changelog for every release.
if is_commit "$VERSION"; then
    ENDPOINT="$VERSION"
    PREV_FROM="${VERSION}^"
else
    ENDPOINT=HEAD
    PREV_FROM=HEAD
    echo "changelog.sh: $VERSION is not a revision here — reading HEAD instead" >&2
fi

# `<version>^` rather than `<version>`, or `describe` answers with the tag we are writing notes
# for. Empty is a legitimate answer — the first release has nothing behind it.
if [ -z "$PREV" ]; then
    PREV=$(git describe --tags --abbrev=0 "$PREV_FROM" 2>/dev/null || true)
elif ! is_commit "$PREV"; then
    die "$PREV is not a revision in this repository (a shallow clone, or a tag not fetched?)"
fi

RANGE="$ENDPOINT"
[ -n "$PREV" ] && RANGE="$PREV..$ENDPOINT"

# Proves the range before three command substitutions each swallow the failure separately.
git log "$RANGE" --no-merges --format='%s' >/dev/null 2>&1 ||
    die "cannot read the commit range '$RANGE'"

subjects_for() {
    local types="$1"
    git log "$RANGE" --no-merges --format='%s' |
        awk -v types="$types" '
            BEGIN { split(types, want, "|"); for (i in want) keep[want[i]] = 1 }
            match($0, /^[a-z]+(\([^)]*\))?!?:[[:space:]]*/) {
                head = substr($0, 1, RLENGTH)
                body = substr($0, RLENGTH + 1)
                type = head; sub(/[(!:].*$/, "", type)
                if (!(type in keep) || body == "") next
                scope = ""
                if (match(head, /\(([^)]*)\)/)) scope = substr(head, RSTART + 1, RLENGTH - 2)
                body = toupper(substr(body, 1, 1)) substr(body, 2)
                if (scope != "") printf "- **%s**: %s\n", scope, body
                else printf "- %s\n", body
            }
        '
}

section() {
    local heading="$1" types="$2" lines
    lines=$(subjects_for "$types")
    [ -z "$lines" ] && return 0
    printf '### %s\n\n%s\n\n' "$heading" "$lines"
}

NOTES=$(
    section 'Added' 'feat'
    section 'Fixed' 'fix'
    section 'Performance' 'perf'
)

# Never an empty body. A release whose commits were all chores is a real thing to publish and
# "nothing user-facing" is the honest note for it; a blank one reads as a generation failure.
if [ -z "$NOTES" ]; then
    NOTES='No user-facing changes — maintenance, tooling and documentation only.'
fi

# `$(…)` has already eaten the trailing blank line the sections wrote, so put one back rather than
# letting the compare link run onto the last bullet.
printf '%s\n\n' "$NOTES"

if [ -n "$PREV" ]; then
    REPO="${GITHUB_REPOSITORY:-jvsena42/loopky}"
    printf '**Full changelog**: https://github.com/%s/compare/%s...%s\n' "$REPO" "$PREV" "$VERSION"
fi
