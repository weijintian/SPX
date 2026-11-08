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
for attempt in 1 2 3 4; do
  if pod install; then
    break
  fi
  if [ "$attempt" -eq 4 ]; then
    echo "CocoaPods dependency resolution failed after 4 attempts." >&2
    exit 1
  fi
  wait_seconds=$((attempt * 8))
  echo "CocoaPods dependency resolution failed (attempt ${attempt}/4); retrying in ${wait_seconds}s." >&2
  sleep "$wait_seconds"
done
echo "Open SpxChatIOS.xcworkspace, select your team and an iPhone simulator."
