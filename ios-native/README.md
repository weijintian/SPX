# SPX Chat iOS

Native SwiftUI client compatible with iOS 15 and later. It uses the SPX Chat HTTP/WebSocket API and Tencent TRTC calling SDK. The media resume flow also requires the companion server upload-status action; Android clients remain compatible with the chunk-upload contract.

## Build on macOS

Required: a Mac with Xcode, an iOS 15-compatible SDK toolchain, XcodeGen, CocoaPods, and network access to resolve the pinned Tencent pods. The project can be built with a newer Xcode SDK while keeping the app deployment target at iOS 15.

```sh
cd ios-native
bash scripts/bootstrap-macos.sh
open SpxChatIOS.xcworkspace
```

Select the `SpxChatIOS` scheme and an iPhone simulator in Xcode. Then run:

```sh
bash scripts/verify-macos.sh
```

The verification script builds the app and runs the XCTest target. The current Windows workspace cannot run Xcode, Swift type checking, simulator tests, or archive creation. `scripts/validate_sources.py` only checks Swift syntax trees, plist/assets, and literal API action names; a successful run does not mean the app compiles.

## Distribution

An Apple Developer team, bundle identifiers, signing certificates, provisioning, App Store Connect records, and Apple review are required before TestFlight or App Store distribution. `scripts/archive-macos.sh` only creates an archive; it does not upload or publish it.

For an existing P12 and provisioning profile, use the manual **iOS signed IPA**
workflow or `scripts/build-signed-ipa-macos.sh`. See [SIGNING.md](SIGNING.md) for
secret configuration, signed IPA export and device restrictions.

## Integration boundaries

- Deploy the companion `spxchat/chat_upload.php` and `spxchat/index.php` changes before using this media resume build. `upload.message.status` returns authenticated per-account checkpoints and completed upload results; the existing `upload.message.chunk` contract remains compatible.
- Chat videos use bounded HTTPS byte-range reads through the same server trust evaluator as the API. Cached complete files play locally. The media host must return valid HTTP 206/Content-Range responses; playback errors offer retry instead of downloading the whole file first.
- File-backed background URLSession uploads persist their job records and acknowledge 1 MiB chunks. System-initiated relaunch reattaches the background session and resumes pending jobs; retries are bounded and failed jobs await manual retry. An interrupted chunk may be retransmitted. Server partial checkpoints expire after 24 idle hours, after which a fresh upload is required.
- User force-quit cancels iOS background transfers; they can resume when the user reopens the app. Do not claim uninterrupted transfer after user force-quit. Completion of a background file upload does not itself prove that message submission or product publication has finished.
- Incoming calls while the app is suspended or terminated require APNs/PushKit device registration and server delivery. The existing service has no such registration endpoint, so background incoming-call delivery is not complete.
- The app bundles Chinese privacy-policy and service-agreement text in `SpxChatIOS/Resources`. Review the copy against the actual service operator, retention rules, third-party SDK disclosures, and local law before distribution. Account deletion is not implemented in the current client or API.
- `app.update.history` is the server's Android release history, so the iOS settings page labels it as platform/Android history.
- Review the privacy manifest against the resolved SDK versions and App Store privacy disclosures before release.

See [FEATURE-PARITY.md](FEATURE-PARITY.md) and [DEVICE-TEST-MATRIX.md](DEVICE-TEST-MATRIX.md) for implementation status and required acceptance testing.

## Local validation

Run `python scripts/validate_sources.py` with Python 3.12 and the installed `.tools/python` grammar bindings. It validates the Swift syntax trees, assets, API action names, and bundled legal resources. The integration harness `node scripts/test-media-upload.mjs <php-executable>` needs PHP 8.3 with Fileinfo and tests uploads against a temporary loopback server, without a production account or database. Reports are written to `Validation/`. Neither check replaces an Xcode build, XCTest execution, or iPhone background-transfer/playback acceptance testing.

See [CACHE-BEHAVIOR.md](CACHE-BEHAVIOR.md) for display-cache lifetimes, invalidation rules and acceptance checks.
