#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [ "$(uname -s)" != "Darwin" ]; then echo "Archiving requires macOS and Xcode." >&2; exit 1; fi
if [ ! -d SpxChatIOS.xcworkspace ]; then bash scripts/bootstrap-macos.sh; fi
xcodebuild -workspace SpxChatIOS.xcworkspace -scheme SpxChatIOS -configuration Release \
  -destination 'generic/platform=iOS' -archivePath build/SpxChatIOS-unsigned.xcarchive \
  CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO CODE_SIGN_IDENTITY="" archive
echo "Unsigned archive created for build verification. Apple signing is required before installation."
