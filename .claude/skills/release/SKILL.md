---
name: release
description: Bump the four version numbers on a release branch, merge, and tag — the tag workflow builds, signs and publishes everything
disable-model-invocation: true
argument-hint: "<version> (e.g. v0.2.0)"
---

Release process for Loopky. Version: $ARGUMENTS

**A tag is the release.** `.github/workflows/release.yml` builds, signs, verifies and publishes
every artifact a version consists of — the signed APK, the two `loopky` binaries, the `.deb`, the
installer, the update manifest, the container image and the Homebrew formula — and moves `latest`
only after it has downloaded the published binary and run it. `docs/releasing.md` is the reference
for what comes out and how the repository is configured; this file is the runbook.

Your job is four numbers, a merge, a tag, and reading what came out. Nothing here builds an
artifact by hand, and nothing here creates or edits the release before the workflow has made it.

`main` is protected — nothing is committed or pushed to it directly. The version bump goes on a
release branch and reaches `main` through a pull request; the tag is then created on the resulting
merge commit.

## Steps

1. **Validate the version argument**: it must start with `v` followed by semver (e.g. `v0.2.0`).
   Abort if missing or malformed. Derive the numeric version by stripping the `v`. Abort if the tag
   already exists (`git tag -l`).

2. **Pre-flight checks**:
   - Working tree clean (`git status`), on `main`, in sync with `origin/main` (`git fetch origin`).
   - `./gradlew detektAll` — abort if it reports issues.
   - `./gradlew :shared:allTests` and `./gradlew :cli:test` — abort if either fails.
   - Confirm the release secrets exist, because a tag pushed without them fails *after* it is
     public: `gh secret list` must show `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` and
     `KEY_PASSWORD`. If any is missing, stop and point the user at `docs/releasing.md` — the
     workflow signs the APK, so the keys have to be in the repository, not on the laptop.

3. **Create the release branch**: `git switch -c chore/version-<numeric_version>` off `main`.

4. **Bump the four numbers (one commit)**:
   - `androidApp/build.gradle.kts` — `versionName` to the numeric version, `versionCode` + 1.
   - `iosApp/Configuration/Config.xcconfig` — `MARKETING_VERSION` to the numeric version and
     `CURRENT_PROJECT_VERSION` to the **same number as the new Android `versionCode`**, keeping the
     two build numbers in lockstep. This xcconfig is the base configuration for the project's build
     configs; `Info.plist` carries no version keys.
   - `gradle.properties` — `loopkyCliVersion` to the numeric version. This is the **single source**
     for `loopky --version` (compiled in by `:cli:generateCliVersion`), the `.deb`'s `Version:`,
     and the container image tag.

   `release.yml`'s first job compares all four against the tag and refuses to build anything when
   they disagree, because the alternative is worse: a `v0.8.0` release whose binary answers
   `loopky 0.1.0`, and `install.sh` ends by printing `--version`, so the wrong number is the first
   thing a new user sees. It reports **every** mismatch rather than the first — the tag is already
   public by the time the job runs, and being told about one number, re-tagging, and being told
   about the next is the same public mistake made twice.

   `Config.xcconfig` is tracked in git and its `TEAM_ID` is intentionally empty — a locally filled
   `TEAM_ID` must never be committed. Verify by inspecting **added lines only**:
   `git diff | grep -E "^\+[^+]"` must show exactly the five version lines and nothing else. Do not
   grep the whole diff: `TEAM_ID=` sits a few lines above the version block, so it always appears
   as an unchanged context line and a naive grep aborts the release on every run.

   Commit: `chore: bump version to <numeric_version>`.

5. **Open the pull request**:
   - `git push -u origin chore/version-<numeric_version>`.
   - `gh pr create` targeting `main`, title `chore: bump version to <numeric_version>`, with a body
     covering Summary, Changes and Test plan. There is no PR template in this repo.
   - Print the PR URL.

6. **Wait for the PR to merge**: `main` *requires* two CI contexts — `Kotlin lint (detekt)` and
   `Unit tests + Android build` — and requires the branch to be up to date with `main`, but
   requires no approving review. Two more jobs run without being required, `CLI on Linux x86_64`
   and `CLI as a native binary`; the second builds the artifact the release is about to publish, so
   **do not merge past a failure in it** even though GitHub will let you. Watch with
   `gh pr checks --watch`, then merge, or `gh pr merge --auto` if the user asks. Do not proceed
   until `gh pr view --json state` reports `MERGED`. Then `git switch main && git pull origin main`.

7. **Preview the release notes** — the same script the workflow runs, so what you show the user is
   what the tag will publish:

   ```shell
   .github/scripts/changelog.sh <version>
   ```

   The tag does not exist yet, so the script reads `HEAD` and says so on stderr, while still naming
   `<version>` in the compare link. Do not "fix" that warning by tagging first — the confirmation
   in step 8 is what the preview exists to inform.

   It groups `feat:`/`fix:`/`perf:` subjects by scope and drops chore/ci/docs/test/refactor,
   including this release's own version bump. Read it. If a subject is mislabelled or a group of
   commits deserves one sentence rather than six, note that now — step 9 is where you rewrite it,
   after the release exists, so there is one writer and no race.

8. **Confirm, then tag**. Show the user the previewed notes and state plainly what pushing the tag
   does: it builds and **publishes a public GitHub release**, and it **pushes a container image to
   `ghcr.io`**. Say both out loud. A registry push cannot be taken back the way an unpublished
   artifact can. Get explicit approval before continuing.

   - `git tag -a <version> -m "Release <version>"`.
   - Confirm it landed on the merge commit: `git rev-list -n1 <version>` equals `main`'s tip.
   - `git push origin <version>` (tags push fine; only branch pushes to `main` are blocked).

9. **Watch the workflow, then do the two things it cannot**:

   ```shell
   gh run watch "$(gh run list --workflow=release.yml --limit 1 --json databaseId -q '.[0].databaseId')"
   ```

   It takes roughly fifteen minutes. When it is green the release is complete and verified — the
   workflow asserts every asset name the two installers fetch, downloads the published Linux binary
   over its public URL and runs `--version` on it, installs the Windows one on `windows-latest`
   using the published `install.ps1`, and only then moves `latest` from `mark-latest`. Do not
   re-check those by hand; read the run summary, which prints the release URL, the image tag and
   the two remaining manual steps.

   - **Download the `play-bundle` artifact** from the run and hand the user the path to
     `Loopky-<numeric_version>.aab`. It is deliberately not a release asset — an AAB cannot be
     installed — and it is what gets uploaded to the Play Console by hand.

     ```shell
     gh run download <run-id> --name play-bundle --dir .
     ```

   - **Rewrite the notes if step 7 said they needed it**:
     `gh release edit <version> --notes-file <file>`. A workflow re-run leaves edited notes alone.

   If the run is red, `docs/releasing.md` has the recovery per failure. The short version: nothing
   is published until every build job is green, so a failed run leaves the previous release
   installable and `latest` where it was.

10. **Summarize**: delete the merged release branch (`git branch -d chore/version-<numeric_version>`)
    and print the release URL, the new Android `versionCode`/`versionName`, the iOS
    `MARKETING_VERSION`/`CURRENT_PROJECT_VERSION`, `loopkyCliVersion`, the path to the `.aab`
    waiting for the Play Console, and the install one-liner from `cli/README.md`.

    Say out loud whether the Homebrew tap was updated. The workflow pushes it when
    `HOMEBREW_TAP_TOKEN` is set and warns in the run summary when it is not — "Homebrew not
    updated, no tap token" is a fine outcome to report and a bad one to leave implied, because
    `loopky update` refuses on a Homebrew install with exit 11 and tells the user to run
    `brew upgrade` (Architecture.md §13.12). Stale, that instruction leads nowhere.

## Important

- Abort immediately if any step fails.
- Never commit or push directly to `main` — it is protected. The version bump always goes through
  the release branch and its PR.
- Ask for confirmation before pushing the tag (step 8), and say explicitly that it publishes a
  public release **and** a container image to `ghcr.io`. That one is not retractable.
- Run `git commit`, `git push` and `gh pr create` as **separate** commands, never chained into one.
  Chained, a declined or interrupted call can leave the commit and push already done while the PR
  is missing, and the run then looks inconsistent. If a step seems to have half-run, re-check the
  real state (`git log`, `git ls-remote --heads origin <branch>`, `gh pr list`) before redoing
  anything — `git rev-parse @{u}` can fail on a branch that *was* pushed, when its remote-tracking
  ref is simply not fetched yet.
- **Do not build, sign, verify or upload any artifact by hand, and do not create the release.** The
  workflow owns all of it, and a second writer is what the old runbook spent a page working around.
  If something is missing from the release, the fix is to re-run the workflow, not to `gh release
  upload` past it.
- **The four numbers are one release.** The workflow refuses the build when the tag and the source
  disagree, which is the good failure; the bad one is skipping a bump, having the workflow stop, and
  hand-fixing the release page so the binary and the tag say different things.
- Never print, log, or commit any value from `local.properties` — it holds the signing credentials
  and the Unsplash key, and it is gitignored for that reason. Refer to the constants by name only,
  and never commit a real `TEAM_ID`. The workflow reads its copies from repository secrets; those
  are set once, by hand, per `docs/releasing.md`.
