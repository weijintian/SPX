# iOS Feature Parity

This is a status map from the 187 design states in `../output/ios-ui-v1/catalog.js` to the current native project. The design catalog is illustrative and does not imply native implementation. “Implemented” means a native view and API path exist in source; it does not mean Xcode compilation, server integration, or device acceptance has passed.

| Design IDs | Native coverage | Status and gaps |
| --- | --- | --- |
| A01-A08 | `Features/Auth/AuthenticationView.swift`, `Features/Profile/Account.swift` | Login, email registration, verification, password recovery and unselected agreement consent exist. Chinese privacy/terms resources are bundled; the copy has not received legal approval. A dedicated launch/result flow still needs design acceptance. |
| B01-B20 | `Features/Chat/Conversations.swift`, `Features/Chat/SocialViews.swift`, `Features/Chat/QRCodeViews.swift` | Partial. Contacts, profile, calls, group create/join, QR and search are wired. Friend-request approval states in the design have no matching backend workflow. |
| C01-C38 | `Features/Chat/Conversations.swift`, `ConversationSettings.swift`, `ChatAccessories.swift`, `MessageHistory.swift`, `NativeMediaViews.swift`, `Core/BackgroundMediaTransfer.swift` | Core messaging, media, files, search, forwarding, schedules, group settings and member management exist. File-backed background uploads, server-confirmed chunk recovery and range-based video playback are implemented in source. Native background behavior, full delivery/read receipts and Android parity still require device acceptance. |
| D01-D09 | `Features/Calls/CallCoordinator.swift`, `CallOverlay.swift` | Partial. Foreground CallKit/Tencent one-to-one flows and movable mini-window exist. PushKit/APNs delivery is unavailable in the current backend; real two-device, Bluetooth, interruptions and weak-network behavior are unverified. |
| E01-E09 | `Features/Chat/RedPackets.swift`, `RedPacketHistory.swift`, `Features/Main/OverlayPresenter.swift` | Source flow exists for send, claim popup, result recovery and recent history. The history/detail route and API behavior have not been tested on devices. |
| F01-F28 | `Features/Mall/Catalog.swift`, `Orders.swift`, `Addresses.swift`, `ShopNavigation.swift` | Core buyer catalog, live SPX001 estimates, SKU, checkout, orders, addresses, after-sale request and logistics UI exist. Payment, server quote edge cases, map coordinate behavior and the complete order lifecycle remain device/server acceptance work. |
| G01-G28 | `Features/Merchant/MerchantDashboard.swift`, `MerchantOperations.swift`, `ProductEditor.swift` | Overview, product editing, batch media selection, drafts, inventory, orders and merchant reply UI exist. Batch offline and reapplication entries are wired. Refund/return approval is platform-admin only on the backend; the app cannot implement merchant approval. |
| H01-H17 | `Features/Wallet/Assets.swift`, `Features/Main/EcosystemView.swift` | Wallet, deposit/withdrawal, exchange, staking and transaction views exist. Real-chain, fee, quote-expiry and account-edge cases need acceptance tests. |
| I01-I19 | `Features/Profile/Account.swift`, `Support.swift` | Profile, preferences, password changes, support, referrals, cache actions and bundled Chinese policy/terms exist. Operator/contact details are intentionally omitted at the user's request; the text is not legally reviewed. Complete localized iOS release metadata and an account-deletion workflow remain outstanding. |
| J01-J11 | Shared design components and feature views | Shared loading/empty/error feedback exists, but each design state needs visual and accessibility review on actual iPhone sizes. |

## Known service constraints

- The service returns at most 100 red-packet history rows; there is no cursor pagination.
- The iOS update-history view reads Android release records and must not be treated as App Store update metadata.
- The upload-status action and existing chunk endpoint passed the isolated HTTP integration checks. Background URLSession relaunch, lock-screen transfer and force-quit/reopen recovery are implemented but not yet demonstrated on iOS hardware. Deploy the companion PHP changes before integration testing.
- Merchant after-sale replies exist; refund and return decisions are platform-controlled.
- APNs/PushKit token registration and delivery are absent, preventing reliable incoming calls while suspended or terminated.

## Current source validation

See `Validation/source-validation.json` for current Swift file/line counts, syntax-tree and API coverage results, and policy resource checks. `Validation/media-upload-integration.json` records the isolated PHP HTTP upload tests. Xcode compilation, XCTest execution, simulator testing and real-device testing have not run in this Windows workspace.
