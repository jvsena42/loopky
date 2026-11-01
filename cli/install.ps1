# There is no Windows build of loopky any more. This file is a tombstone: the apps, the README and
# the landing page of 1.9.0 and earlier still hand out
# `irm https://github.com/jvsena42/loopky/releases/latest/download/install.ps1 | iex`, and a 404
# page there would point nobody at the replacement. It installs nothing and changes nothing.
#
# `throw` rather than `exit 1`: under `irm | iex` this runs inside the caller's own session, and
# `exit` would close their PowerShell window along with the message. Remove after a few releases.

Write-Host @'
loopky: there is no Windows build of loopky any more, so nothing was installed.

On Windows, use the Loopky connector for Claude instead. It needs no install:
    https://claude.ai/directory/loopky
or add https://mcp.loopky.app/mcp as a custom connector, then sign in once with Pubky Ring.

If you need the command line tool itself, run the Linux build inside WSL:
    curl -fsSL https://github.com/jvsena42/loopky/releases/latest/download/install.sh | sh

Coming from an older Windows install? See "Coming from the Windows build" in
https://github.com/jvsena42/loopky/tree/main/cli#install
'@
throw 'loopky: no Windows build — see the message above.'
