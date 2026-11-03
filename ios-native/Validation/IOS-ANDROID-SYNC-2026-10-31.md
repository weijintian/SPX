# iOS and Android repair synchronization

## Audit result

- Payment password: already present on iOS before this change. `PaymentPasswordView` and the red-packet confirmation view both use `.keyboardType(.numberPad)` and validate exactly six ASCII digits. First setup uses `payment.password.set`; later changes use email verification through `payment.password.change.email`.
- Default avatar: missing on iOS. iOS previously rendered a generic person icon when `avatar_url` was empty. It now renders the first visible Unicode character of the resolved display name, with the generic icon retained only for an empty name.
- Recording composer: iOS did not have Android's 200 ms timer rewrite, but its drag gesture assigned `cancelRecording` on every movement. It now writes that state only when the cancel threshold is crossed, preventing unnecessary SwiftUI input-bar redraws.

## Files changed

- `SpxChatIOS/Design/Components.swift`
- `SpxChatIOS/Features/Chat/Conversations.swift`
- `SpxChatIOSTests/CoreTests.swift`

## Verification

- Source audit confirms iOS payment-password number-pad coverage in settings and red-packet confirmation.
- Added unit coverage for Chinese, Latin, emoji, and empty avatar names.
- iOS Xcode compilation and device testing were not available in this Windows workspace. The existing offline validation script could not run because the bundled tree-sitter Python package is incompatible with the active Python runtime; no IPA was claimed or published.
