#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [ "$(uname -s)" != "Darwin" ]; then
  echo "Signed IPA export requires macOS and Xcode." >&2
  exit 1
fi
exec python3 scripts/build_signed_ipa.py "$@"
