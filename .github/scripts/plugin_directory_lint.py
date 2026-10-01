#!/usr/bin/env python3
"""A local stand-in for Anthropic's plugin directory lints (#388).

    python3 .github/scripts/plugin_directory_lint.py plugins/loopky [--strict]

The directory runs checks `claude plugin validate --strict` does not, and publishes neither the
checks nor a way to run them. These are the rules it has actually applied to this plugin, taken
from its validation reports, so a change that would draw one is seen in a PR rather than after a
release moves the `plugin` branch. It is an approximation and says so. Replayed against the three
`plugin` commits the directory judged, it reproduces every finding but one: the directory also
flagged README.md at fa7b8b03 (v1.2.3), whose only install content is prose — and not SKILL.md,
which carries nearly the same prose. No pattern here can tell those apart.

Levels follow the directory's: an error fails validation, a warning is shown to reviewers and
users, a note is information. Exit 1 on an error, or on a warning with --strict.
"""
import json
import re
import sys
from pathlib import Path

MAX_FILE = 5 * 1024 * 1024
MAX_FOLDER = 200 * 1024 * 1024
TEXT = {'.md', '.sh', '.ps1', '.json', '.txt', '.yaml', '.yml', '.py', '.js', '.ts'}
BINARY_ASSET = {'.png', '.jpg', '.jpeg', '.gif', '.webp', '.svg', '.woff', '.woff2', '.ttf', '.otf'}
PACKAGE_RUNNERS = re.compile(r'\b(npx|uvx|pipx\s+run|npm\s+(i|install)|pip3?\s+install|bunx)\b')

# RUNTIME_FETCH_EXEC: "a command that downloads code and runs it straight away". The directory
# flagged the one-liners in SKILL.md and README.md, and then the shipped installers, which pipe
# nothing: fetching an executable to disk and marking it runnable counts too, and v1.3.0's
# rejection made it a blocker rather than a warning.
FETCH_EXEC = [
    ('pipes a download into a shell',
     re.compile(r'\b(curl|wget)\b[^|\n]*\|\s*(sudo\s+)?(sh|bash|zsh|python3?)\b')),
    ('pipes a download into Invoke-Expression',
     re.compile(r'\b(irm|iwr|Invoke-RestMethod|Invoke-WebRequest)\b[^|\n]*\|\s*(iex|Invoke-Expression)\b', re.I)),
    ('runs a downloaded script through a shell',
     re.compile(r'\b(sh|bash)\s+-c\s+"?\$\((curl|wget)\b')),
]
DOWNLOAD_TO_FILE = re.compile(r'\b(curl|wget)\b[^\n|]*(\s-o\b|\s-O\b|--output\b)'
                              r'|\b(Invoke-WebRequest|iwr|irm|Invoke-RestMethod)\b[^\n|]*-OutFile\b', re.I)
# Any invocation at all, with or without an output flag: `wget URL` and `curl -fsSLo f URL` write
# to disk too, and nothing under the plugin has a reason to name a downloader.
DOWNLOADER = re.compile(r'\b(curl|wget|iwr|irm|Invoke-WebRequest|Invoke-RestMethod|Start-BitsTransfer)\b', re.I)
NAMES_EXE = re.compile(r'\.exe\b', re.I)
MAKE_EXECUTABLE = re.compile(r'\bchmod\s+(\+x|[0-7]?[157][0-7][0-7])\b')


def lint(root: Path):
    findings = []

    def add(level, code, path, message):
        findings.append((level, code, path.relative_to(root).as_posix() if path != root else '.', message))

    files = [p for p in root.rglob('*') if p.is_file() and '.git' not in p.parts]
    total = 0
    for path in files:
        size = path.stat().st_size
        total += size
        if size > MAX_FILE:
            add('error', 'FILE_TOO_LARGE', path, f'{size} bytes, over the 5 MiB limit')
        if path.suffix.lower() in BINARY_ASSET:
            add('note', 'BINARY_ASSET', path, 'image or font: screened for text, not read as code')
    if total > MAX_FOLDER:
        add('error', 'FOLDER_TOO_LARGE', root, f'{total} bytes, over the 200 MiB limit')

    for path in files:
        if path.suffix.lower() not in TEXT:
            continue
        text = path.read_text(encoding='utf-8', errors='replace')
        hits = [label for label, rx in FETCH_EXEC if rx.search(text)]
        # Anywhere in the file, not on one line: the name of what is fetched is often a variable.
        if DOWNLOAD_TO_FILE.search(text) and MAKE_EXECUTABLE.search(text):
            hits.append('downloads a file and marks it executable')
        elif DOWNLOAD_TO_FILE.search(text) and NAMES_EXE.search(text):
            hits.append('downloads an executable to disk')
        elif DOWNLOADER.search(text):
            # The v1.3.0 review rejected installers that verified a pinned checksum: any download
            # the plugin performs fetches code nobody reviewed with it.
            hits.append('invokes a downloader')
        # An error, not a warning: since v1.3.0 the directory refuses on it rather than annotating.
        for label in hits:
            add('error', 'RUNTIME_FETCH_EXEC', path, label)

    for hooks in root.glob('hooks/*.json'):
        for event in json.loads(hooks.read_text()).get('hooks', {}).values():
            for group in event:
                for hook in group.get('hooks', []):
                    command = hook.get('command', '')
                    literal = re.fullmatch(r'"?\$\{CLAUDE_PLUGIN_ROOT\}/[\w./-]+"?', command.strip())
                    if hook.get('type') == 'command' and not literal:
                        add('warning', 'HOOK_COMMAND', hooks,
                            f'point the command at a script by literal path under CLAUDE_PLUGIN_ROOT: {command}')
                    if PACKAGE_RUNNERS.search(command):
                        add('warning', 'HOOK_COMMAND', hooks, f'keep package runners and installs out of hooks: {command}')
    return findings


def main(argv):
    strict = '--strict' in argv
    paths = [a for a in argv if not a.startswith('--')]
    if len(paths) != 1:
        sys.exit(__doc__)
    root = Path(paths[0]).resolve()
    findings = lint(root)
    order = {'error': 0, 'warning': 1, 'note': 2}
    for level, code, path, message in sorted(findings, key=lambda f: (order[f[0]], f[2])):
        prefix = {'error': '::error', 'warning': '::warning', 'note': '::notice'}[level]
        print(f'{prefix} title={code}::{path}: {message}')
    errors = sum(f[0] == 'error' for f in findings)
    warnings = sum(f[0] == 'warning' for f in findings)
    print(f'{errors} errors, {warnings} warnings, {len(findings) - errors - warnings} notes')
    return 1 if errors or (strict and warnings) else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
