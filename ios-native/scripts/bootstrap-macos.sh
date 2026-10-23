#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [ "$(uname -s)" != "Darwin" ]; then
  echo "This script requires macOS and Xcode." >&2
  exit 1
fi
xcodebuild -version
command -v xcodegen >/dev/null || { echo "Install XcodeGen before continuing." >&2; exit 1; }
command -v pod >/dev/null || { echo "Install CocoaPods before continuing." >&2; exit 1; }
xcodegen generate --spec project.yml
pod install
echo "Open SpxChatIOS.xcworkspace, select your team and an iPhone simulator."
