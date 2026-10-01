---
name: ios-ipa
description: Build the signed App Store .ipa for the current version and put it on the Desktop for upload with Transporter
disable-model-invocation: true
argument-hint: "[output dir, default ~/Desktop]"
---

Build the iOS `.ipa` for App Store Connect. Output directory: $ARGUMENTS (default `~/Desktop`).

iOS is not built by `release.yml`; the `.ipa` is built locally and uploaded by hand. Run this after
the version bump from the `release` skill has merged, so the build number matches Android's
`versionCode`.

1. **Check out the release commit.** `git checkout main && git pull`. Read `MARKETING_VERSION` and
   `CURRENT_PROJECT_VERSION` from `iosApp/Configuration/Config.xcconfig` and say which version is
   being built. App Store Connect refuses a build number it has already accepted, so if this one
   was uploaded before, stop: the version needs a new bump through the `release` skill.

2. **Build.** Run `.claude/skills/ios-ipa/build-ipa.sh $ARGUMENTS` in the background; the release
   Kotlin framework makes it take several minutes. It stops on its own if:
   - `iosApp/Configuration/Local.xcconfig` has no `TEAM_ID`. That file is gitignored, and a real
     Team ID is never committed.
   - `iosApp/` has uncommitted changes. Xcode's Signing tab replaces `${TEAM_ID}` in
     `project.pbxproj` with the literal ID; discard that with
     `git checkout iosApp/iosApp.xcodeproj/project.pbxproj` and run it again.

   The script calls `xcodebuild` directly, an exception to the repo's xcodebuildmcp-only rule:
   xcodebuildmcp has no archive or export. Its last step checks the bundle (version, bundle ID,
   no `ITSAppUsesNonExemptEncryption`, privacy manifest, Apple Distribution signature) and fails
   if any of them is wrong.

3. **Report.** Give the path of the `.ipa`, the version and build number, and the signing
   identity the script printed. Do not upload it; the user uploads it with Transporter.

After the upload the build shows "Missing Compliance" in TestFlight until the encryption questions
are answered (standard encryption algorithms).
