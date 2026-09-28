#!/usr/bin/env bash
# Run profiles through run.sh and fail on any exit code that differs from expected.tsv (#364).
#
#   ./check.sh jar                     every profile in expected.tsv, against ../build/install/loopky
#   LOOPKY_DIST=<dir> ./check.sh native   the same rows against a directory holding bin/loopky
#   ./check.sh jar offline claude-custom  only the profiles named
#
# A profile that drifts is run once more before it counts: `doctor` probes each host with a 5s
# timeout, so one slow answer from production reads as unreachable (#369). Drift twice is drift.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
expected="$here/expected.tsv"

build=${1:-}
case "$build" in
  jar) col=3 ;;
  native) col=4 ;;
  *) sed -n '2,9p' "$0"; exit 2 ;;
esac
shift

if [ $# -gt 0 ]; then
  profiles=("$@")
else
  # Not mapfile: macOS ships bash 3.2.
  profiles=()
  while IFS= read -r p; do profiles+=("$p"); done < <(grep -v '^#' "$expected" | cut -f1 | awk 'NF && !seen[$0]++')
fi

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

# Prints one line per command whose exit code is not the expected one; nothing when they all match.
drift() {
  local profile=$1 actual=$2
  awk -F'\t' -v profile="$profile" -v col="$col" '
    FNR == NR { if ($0 !~ /^#/ && $1 == profile) want[$2] = $col; next }
    { got[$2] = $3 }
    END {
      for (c in want) {
        if (!(c in got)) printf "  %-18s %-18s expected %s, got no row\n", profile, c, want[c]
        else if (got[c] != want[c]) printf "  %-18s %-18s expected %s, got %s\n", profile, c, want[c], got[c]
      }
      for (c in got) if (!(c in want)) printf "  %-18s %-18s got %s, not in expected.tsv\n", profile, c, got[c]
    }' "$expected" "$actual"
}

failed=()
for profile in "${profiles[@]}"; do
  if ! grep -q "^$profile	" "$expected"; then
    echo "check.sh: $profile has no rows in expected.tsv" >&2
    exit 2
  fi
  for attempt in 1 2; do
    echo "== $build / $profile (attempt $attempt)"
    # run.sh exits non-zero only when it could not run at all — a preflight refusal (3) or a proxy
    # that did not start — which is never drift and never retried.
    SIM_SUMMARY="$work/$profile.tsv" "$here/run.sh" "$profile"
    report=$(drift "$profile" "$work/$profile.tsv")
    [ -z "$report" ] && break
    echo "$report"
    [ "$attempt" = 2 ] && failed+=("$report")
  done
done

if [ ${#failed[@]} -gt 0 ]; then
  echo
  echo "check.sh: $build drifted from cli/sandbox-sim/expected.tsv on two runs in a row:" >&2
  printf '%s\n' "${failed[@]}" >&2
  echo "check.sh: if the new code is the right one, change expected.tsv in the same PR and say why." >&2
  exit 1
fi
echo "check.sh: $build matches expected.tsv for ${profiles[*]}"
