# Releasing

**A tag is the release.** `.github/workflows/release.yml` builds, signs, verifies and publishes
everything a version consists of; the human half is bumping four numbers, merging that, pushing the
tag, and doing the two things a workflow cannot do. `.claude/skills/release/SKILL.md` is the
runbook that walks it.

## What the tag produces

| Artifact | Where it ends up |
| --- | --- |
| `Loopky-<version>.apk` | attached to the GitHub release |
| `Loopky-<version>.aab` | the **`play-bundle` workflow artifact** on the run — never a release asset, because an AAB cannot be installed. Download it from the run page and upload it to the Play Console. |
| `loopky-linux-x86-64`, `loopky-macos-aarch64`, `loopky-windows-x86-64.exe`, their `.sha256`s | attached to the release |
| `loopky_<version>_amd64.deb` | attached to the release |
| `install.sh`, `install.ps1`, `latest.json` | attached to the release — the two installers *at this tag*, and the manifest every installed `loopky` reads to learn it is stale |
| `ghcr.io/<owner>/loopky:<version>` | pushed to the registry |
| `Formula/loopky.rb` in `<owner>/homebrew-loopky` | pushed, if `HOMEBREW_TAP_TOKEN` is set |

`latest` — the GitHub release flag, the `:latest` image tag and the Homebrew formula — moves only
for a final version. A pre-release tag (`v1.0.0-rc1`) leaves all three where they are, so
`curl …/releases/latest/download/…` and `docker run … ghcr.io/…/loopky` keep handing people the
last stable build.

## One-time setup

### Repository secrets

`Settings → Secrets and variables → Actions → New repository secret`. Four are required; without
`KEYSTORE_BASE64` a tag fails on the `Signed Android artifacts` job rather than publishing a
release with no APK in it.

| Secret | Required | What it is |
| --- | --- | --- |
| `KEYSTORE_BASE64` | **yes** | the upload keystore, base64-encoded — see below |
| `KEYSTORE_PASSWORD` | **yes** | `KEYSTORE_PASSWORD` from your `local.properties` |
| `KEY_ALIAS` | **yes** | `KEY_ALIAS` from your `local.properties` |
| `KEY_PASSWORD` | **yes** | `KEY_PASSWORD` from your `local.properties` |
| `UNSPLASH_ACCESS_KEY` | no, but | `UNSPLASH_ACCESS_KEY` from your `local.properties`. Left unset the release builds fine and the "from web" image search silently degrades to gallery-only — `UnsplashClient.hasFallbackKey` just answers false. |
| `HOMEBREW_TAP_TOKEN` | no | a PAT that can push to `<owner>/homebrew-loopky`. Unset, the tap is skipped with a warning in the run summary. |

The keystore is a binary file, so it goes in as base64. From the repository root, with
`KEYSTORE_FILE` pointing at the same `.jks` your local release builds already use:

```shell
base64 -i "$(sed -n 's/^KEYSTORE_FILE=//p' local.properties)" | pbcopy
```

Paste that as `KEYSTORE_BASE64`. `base64` on macOS emits one long line; on Linux use
`base64 -w0`. The workflow decodes it into `$RUNNER_TEMP`, never the workspace, so no artifact glob
can pick it up.

**The same key that signed every previous release.** Play App Signing pins the upload key: a new
one is rejected at upload time, not at build time. The workflow asserts the key is RSA at 2048 bits
or more with a certificate valid past 2033-10-22 — that check exists because v0.6.1 published
cleanly and only the Play upload rejected it, with a message naming neither the key nor the cause.

### The Homebrew tap, once

`HOMEBREW_TAP_TOKEN` needs somewhere to push. Create `<owner>/homebrew-loopky` as a public
repository and **give it at least one commit** — a README will do. An empty repository has no
default branch, and `actions/checkout` cannot check out a ref that does not exist, so the job
fails on a tag before it renders anything. Do not try to satisfy this with an empty `Formula/`
directory: git cannot hold one, and the job creates it anyway.

Then mint a fine-grained PAT scoped to that one repository with **Contents: read and write** and
add it as the secret. `cli/packaging/loopky.rb` is the template the job renders; the `__…__`
placeholders are filled from the release's own `.sha256` assets, so the formula can only ever
describe bytes that were actually published.

Skipping this is a fine outcome to have, and a bad one to have by accident: `loopky update`
*refuses* on a Homebrew install with exit 11 and tells the user to run `brew upgrade`
(Architecture.md §13.12), so a stale tap makes the one instruction the binary gives them lead
nowhere. The run summary says so on every release where the token is missing.

## Cutting a release

1. Bump the four numbers on a `chore/version-<x.y.z>` branch and merge the PR. `main` is protected;
   nothing is committed to it directly.
   - `androidApp/build.gradle.kts` — `versionName`, and `versionCode` + 1
   - `iosApp/Configuration/Config.xcconfig` — `MARKETING_VERSION`, and `CURRENT_PROJECT_VERSION` to
     the **same number as the new `versionCode`**
   - `gradle.properties` — `loopkyCliVersion`

   The `check-version` job compares all four against the tag before anything is built. Half a bump
   is the normal way this fails and the tag is already public by then, so it reports every mismatch
   at once rather than one per re-tag.

2. Preview the notes the tag will publish — the same script the workflow runs:

   ```shell
   .github/scripts/changelog.sh v0.9.0
   ```

   The tag does not exist yet at this point, so the script reads `HEAD` instead and says so on
   stderr; the version is still what names the compare link, so what you see is what the tag will
   publish. Pass the previous tag as a second argument to override the one it works out itself.

   It groups `feat:`/`fix:`/`perf:` subjects and drops everything else. It is a floor, not a
   ceiling: rewrite them afterwards with `gh release edit <tag> --notes-file <file>`, which a
   workflow re-run then leaves alone.

3. Tag the merge commit and push it. **This is the irreversible step** — it publishes a public
   release and pushes a container image to `ghcr.io`, and a registry push cannot be taken back.

   ```shell
   git switch main && git pull origin main
   git tag -a v0.9.0 -m "Release v0.9.0"
   git push origin v0.9.0
   ```

4. Watch it, then do the two manual things:

   ```shell
   gh run watch "$(gh run list --workflow=release.yml --limit 1 --json databaseId -q '.[0].databaseId')"
   ```

   - Download the **`play-bundle`** artifact from the run and upload the `.aab` to the Play Console.
   - Read the generated notes and rewrite them if they need it.

   The run summary lists both, with the release URL and the image tag.

## When it goes wrong

- **`check-version` fails.** The tag is public but nothing was built and nothing was published.
  Bump the missing number on a branch, merge it, delete the tag locally and on the remote, and
  re-tag the new merge commit.
- **A build job fails.** No release exists yet — the `release` job runs behind all of them. Fix
  forward and re-run the workflow; it is idempotent, and a re-run against an existing release
  uploads assets with `--clobber` without touching the notes.
- **The release exists but is missing assets.** Re-run the failed jobs. The `release` job asserts
  every asset name `cli/install.sh` fetches and downloads-and-runs the published Linux binary
  before moving `latest`, so a release that reached `latest` has been checked; one that did not is
  visible as a red run with the previous version still installable.
