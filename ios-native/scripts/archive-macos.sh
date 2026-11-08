#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [ "$(uname -s)" != "Darwin" ]; then echo "Archiving requires macOS and Xcode." >&2; exit 1; fi
: "${SPX_APPLE_TEAM_ID:?Set SPX_APPLE_TEAM_ID to your Apple Developer team ID}"
if [ ! -d SpxChatIOS.xcworkspace ]; then bash scripts/bootstrap-macos.sh; fi
xcodebuild -workspace SpxChatIOS.xcworkspace -scheme SpxChatIOS -configuration Release -destination 'generic/platform=iOS' -archivePath build/SpxChatIOS.xcarchive DEVELOPMENT_TEAM="$SPX_APPLE_TEAM_ID" archive
echo "Archive created. Use Xcode Organizer to validate and export with your distribution profile."
