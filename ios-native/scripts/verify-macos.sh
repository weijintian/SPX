#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [ "$(uname -s)" != "Darwin" ]; then echo "Xcode validation requires macOS." >&2; exit 1; fi
if [ ! -d SpxChatIOS.xcworkspace ]; then bash scripts/bootstrap-macos.sh; fi
destination="${SPX_TEST_DESTINATION:-}"
if [ -z "$destination" ]; then
  device_id="$(xcrun simctl list devices available --json | python3 -c 'import json,sys; d=json.load(sys.stdin); ids=[x["udid"] for runtime,devices in d["devices"].items() if "iOS" in runtime for x in devices if x.get("isAvailable") and x["name"].startswith("iPhone")]; print(ids[0] if ids else "")')"
  if [ -z "$device_id" ]; then echo "No available iPhone simulator. Install an iOS Simulator runtime in Xcode." >&2; exit 1; fi
  destination="platform=iOS Simulator,id=$device_id"
fi
xcodebuild -workspace SpxChatIOS.xcworkspace -scheme SpxChatIOS -configuration Debug -destination "$destination" -derivedDataPath build/DerivedData CODE_SIGNING_ALLOWED=NO build test
