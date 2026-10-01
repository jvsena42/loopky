#!/usr/bin/env bash
# Builds the App Store .ipa for the version on the current checkout and copies it to ~/Desktop.
# Usage: .claude/skills/ios-ipa/build-ipa.sh [output-dir]
#
# The archive is unsigned and the export signs it. Archiving with signing on needs an iOS
# Development profile, which Xcode can only create once a physical device is registered; the
# App Store profile the export asks for needs none.
set -euo pipefail

repo="$(git rev-parse --show-toplevel)"
cd "$repo"
out_dir="${1:-$HOME/Desktop}"
xcconfig="iosApp/Configuration/Config.xcconfig"
local_xcconfig="iosApp/Configuration/Local.xcconfig"

fail() { echo "error: $*" >&2; exit 1; }

team_id="$(sed -n 's/^TEAM_ID=//p' "$local_xcconfig" 2>/dev/null | tr -d '[:space:]')"
[ -n "$team_id" ] || fail "no TEAM_ID in $local_xcconfig (create it with TEAM_ID=<your team id>)"

marketing="$(sed -n 's/^MARKETING_VERSION=//p' "$xcconfig" | tr -d '[:space:]')"
build="$(sed -n 's/^CURRENT_PROJECT_VERSION=//p' "$xcconfig" | tr -d '[:space:]')"
[ -n "$marketing" ] && [ -n "$build" ] || fail "could not read the version from $xcconfig"

# Xcode's Signing tab rewrites DEVELOPMENT_TEAM in the project; that edit must never be shipped
# or committed, and an archive built over it would not match main.
if ! git diff --quiet -- iosApp/; then
  git status --short -- iosApp/
  fail "iosApp/ has uncommitted changes; commit or discard them first"
fi

work="$(mktemp -d "${TMPDIR:-/tmp}/loopky-ipa.XXXXXX")"
archive="$work/Loopky.xcarchive"
echo "Building Loopky $marketing ($build) in $work"

cat > "$work/ExportOptions.plist" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>method</key>
	<string>app-store-connect</string>
	<key>destination</key>
	<string>export</string>
	<key>teamID</key>
	<string>$team_id</string>
	<key>signingStyle</key>
	<string>automatic</string>
	<key>uploadSymbols</key>
	<true/>
	<key>manageAppVersionAndBuildNumber</key>
	<false/>
</dict>
</plist>
EOF

xcodebuild archive -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Release \
  -destination 'generic/platform=iOS' -archivePath "$archive" \
  -derivedDataPath "$work/DerivedData" CODE_SIGNING_ALLOWED=NO > "$work/archive.log" 2>&1 \
  || { tail -30 "$work/archive.log"; fail "archive failed; full log: $work/archive.log"; }

xcodebuild -exportArchive -archivePath "$archive" -exportOptionsPlist "$work/ExportOptions.plist" \
  -exportPath "$work/export" -allowProvisioningUpdates > "$work/export.log" 2>&1 \
  || { tail -30 "$work/export.log"; fail "export failed; full log: $work/export.log"; }

ipa="$work/export/Loopky.ipa"
[ -f "$ipa" ] || fail "export produced no Loopky.ipa; see $work/export.log"

check="$work/check"
mkdir -p "$check"
unzip -q "$ipa" -d "$check"
app="$(ls -d "$check"/Payload/*.app)"
plist="$app/Info.plist"
read_key() { /usr/libexec/PlistBuddy -c "Print :$1" "$plist" 2>/dev/null || true; }

[ "$(read_key CFBundleShortVersionString)" = "$marketing" ] || fail "ipa version is not $marketing"
[ "$(read_key CFBundleVersion)" = "$build" ] || fail "ipa build number is not $build"
[ "$(read_key CFBundleIdentifier)" = "com.github.jvsena42.loopky" ] || fail "unexpected bundle identifier"
# true without ITSEncryptionExportComplianceCode is rejected at upload with 90592.
[ -z "$(read_key ITSAppUsesNonExemptEncryption)" ] || fail "ITSAppUsesNonExemptEncryption is set; see the comment in Info.plist"
[ -f "$app/PrivacyInfo.xcprivacy" ] || fail "PrivacyInfo.xcprivacy is missing from the bundle"
codesign --verify --deep --strict "$app" || fail "signature does not verify"
signing="$(codesign -dvv "$app" 2>&1)"
[[ "$signing" == *"Authority=Apple Distribution"* ]] || fail "not signed with an Apple Distribution certificate"

mkdir -p "$out_dir"
dest="$out_dir/Loopky-$marketing-$build.ipa"
cp -f "$ipa" "$dest"
echo "Built $dest"
echo "  version $marketing ($build), bundle com.github.jvsena42.loopky"
echo "  $(printf '%s\n' "$signing" | sed -n '/^Authority=/{p;q;}')"
echo "  minimum iOS $(read_key MinimumOSVersion)"
