#!/usr/bin/env python3
"""Print JUnit failures from `**/build/test-results/**/*.xml` into the job log.

Deliberately **cannot fail**. It runs in an `if: failure()` step, so a diagnostic that raises
replaces the real error with its own — the one outcome worse than not having it. Every parse is
guarded and the exit status is always 0.
"""

import glob
import sys
import xml.etree.ElementTree as ET

# Caps, so a suite that fails wholesale reports the first failures rather than burying them under
# ten thousand lines the reader has to scroll past. The artifact is still uploaded either way.
MAX_FAILURES = 100
MAX_MESSAGE_CHARS = 600
MAX_TRACE_LINES = 12


def failures(path):
    """Every `<failure>`/`<error>` in one report, as (test, message, trace)."""
    try:
        root = ET.parse(path).getroot()
    except (ET.ParseError, OSError) as exc:
        print(f"(could not read {path}: {exc})")
        return
    for case in root.iter("testcase"):
        name = f"{case.get('classname', '?')}.{case.get('name', '?')}"
        for bad in list(case.iter("failure")) + list(case.iter("error")):
            yield name, (bad.get("message") or "").strip(), (bad.text or "").strip()


def main():
    reports = sorted(glob.glob("**/build/test-results/**/*.xml", recursive=True))
    if not reports:
        print("No JUnit reports were written — the failure is upstream of the tests")
        print("(a compile error, or a task that never ran). The job log above has it.")
        return

    shown = 0
    total = 0
    for path in reports:
        for name, message, trace in failures(path):
            total += 1
            if shown >= MAX_FAILURES:
                continue
            shown += 1
            print(f"FAILED {name}")
            if message:
                print(f"  {message[:MAX_MESSAGE_CHARS]}")
            for line in trace.splitlines()[:MAX_TRACE_LINES]:
                print(f"  | {line}")
            print()

    if not total:
        print(f"{len(reports)} JUnit report(s), none of them failing —")
        print("whatever failed in this job was not a test.")
    elif total > shown:
        print(f"{total} failing test(s), {shown} shown. The rest are in the uploaded report.")
    else:
        print(f"{total} failing test(s).")


if __name__ == "__main__":
    main()
    sys.exit(0)
