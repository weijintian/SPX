# iOS chat optimization validation — 2026-10-26

## Implemented locally

- Render server-marked system notices as centered secondary text in the main timeline and shared bubble view. Ordinary welcome text remains a user message.
- Filter upload byte callbacks before scheduling main-actor work, reject backward/stale progress and reset progress for a new attempt.
- Protect background-upload registration against cancellation before the serial queue installs its continuation and record.
- Reuse account-scoped local image/video thumbnails; guard asynchronous UI assignments after account changes.
- Move photo preparation off the main actor and preserve PNG/GIF/original JPEG bytes without unnecessary thumbnail decoding.
- Restrict keyboard-driven scrolling to readers already following the latest message.
- Request JSON upload responses explicitly with Accept and Accept-Language headers.

## Second review fixes

- Give video range readers independent cancellable continuations. Canceling a reader releases it promptly; the shared transfer is canceled only when its last registered reader leaves. Task IDs reject callbacks from replaced requests.
- Clearing a user's video range cache cancels active requests and resumes their pending readers with cancellation. Canceling full-file assembly now also cancels its detached disk task.
- Suppress history-load failures from a cleared revision or canceled operation; preserve failures from the current request. Apply the cancellation guard to normal conversation polling as well.
- Remove the duplicate call-state observer from the conversation view while keeping recording interruption on an active call.
- Throttle download progress before scheduling downstream UI work. Keep unknown-size downloads indeterminate, reject backward/duplicate progress, and deliver completion immediately.

## Checks actually run

- Windows / Python 3.13: `python ios-native/scripts/validate_sources.py` — passed.
- 68 Swift source and test files parsed without syntax errors.
- Final source validation counted 11,567 lines across those files. This is structural validation, not a Swift type check.
- 97 literal API actions matched both the local backend and the checked-in backend action snapshot.
- The validator parsed property-list resources and verified referenced asset files and bundled policy resources.
- See `source-validation.json` for the machine-readable result; the final run is authoritative for line counts.

## Regression cases added, not executed here

XCTest source now covers system notice metadata, throttled/stale upload progress, cancellation before upload registration, invalid photo input and account-scoped thumbnail reuse/clearing. Existing cases cover valid/malformed HTTP 201 responses, upload address compatibility, private/group media receipts and retention of attachments when no upload URL is returned.

The second review adds controlled pending-request cases for last-reader video cancellation and cache-clear cancellation, download-progress throttling with unknown content length, and history errors during cancellation or local history clearing. A current history failure must still appear. These tests were added but have not been executed in this Windows environment. The shared-reader preservation path and detached assembly cancellation still require the macOS/device checks below.

## Required macOS and device verification

The Windows environment has no Swift/Xcode compiler. Syntax parsing does not type-check Swift, build/link the app, execute XCTest, measure frame rate or confirm live-server behavior. Run the Xcode scheme tests and signed archive on macOS, then complete the chat/media rows in `DEVICE-TEST-MATRIX.md` on physical iOS devices, including an iOS-to-Android conversation.

In particular, hold concurrent poster/playback reads on the same range, cancel only one, and confirm the other finishes using the same network request. Then cancel the last reader and confirm network activity stops. Clear the account cache during a pending range and during assembly; verify prompt cancellation and no stale media reappearing. These behaviors have been reviewed in source but are not recorded as runtime passes.

The client already accepts HTTP 201 with a valid success envelope. The reported upload failure still requires a failing server response and device reproduction to establish its cause; the local checks do not prove that live video uploads now succeed. No build, release or distribution is recorded by this report.
