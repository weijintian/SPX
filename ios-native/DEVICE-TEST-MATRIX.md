# iOS Acceptance Test Matrix

Run these checks on a current iPhone simulator for layout and on two physical devices for camera, microphone, calls, media library and network behavior. Include one iOS-to-Android pairing for cross-platform chat and calls.

| Area | Acceptance path | Expected result |
| --- | --- | --- |
| Authentication | Register, email verification, sign in/out, restore session, reset password | Clear validation, one session per account, no stale private cache after account switch |
| Chat | Text, emoji, reply, edit, delete, forward, multi-select, group mentions, draft | Correct ordering and ownership; reopen at latest message; no duplicate messages after reconnect |
| Media | Multi-select photos/videos, camera, file import, large video, interrupted upload, retry, save/forward in viewer | Correct iOS picker, visible progress, recoverable failures, valid saved media and no chat re-fetch loss |
| Video streaming | Open a large remote video before it is fully downloaded, seek to middle/end, deny network, retry, leave viewer, use a cached complete file | Bounded 206 byte ranges; prompt playback or a visible retry error; no compulsory full-file download; pending ranges cancel when the viewer closes |
| Upload recovery | Start a large chat attachment, lock device/app switch, simulate system termination and background relaunch, then force-quit/reopen, retry after checkpoint expiry, sign out while uploading | Acknowledged chunks are reused; one pending message/client ID remains; system scheduling is respected; force-quit resumes only after reopen; no cross-account uploads; a 24-hour-expired checkpoint restarts consistently |
| Policies | Read both texts while logged out and in settings, toggle consent, register/sign in | Bundled texts load offline; consent starts unchecked; account deletion is not misrepresented as an implemented workflow |
| Group | Create/join, announcement, members, roles, mute, remove, owner transfer, leave/dissolve | Actions respect server permissions and refresh the current group/list |
| Calls | Foreground audio/video, reject/timeout, minimize/move/restore, end, permission denial, Bluetooth route | Correct two-device state and audio ownership; no stale SDK callback ends a newer call |
| Calls in background | Lock screen, app switch, terminated app, network transition | Incoming call only passes when APNs/PushKit and server delivery are integrated; otherwise record as blocked |
| Red packets | Direct and group send, ordinary/random, claim race, expired/exhausted, uncertain response, history | No duplicate debit; received amount and claim list match server; history opens the correct packet |
| Mall / buyer | Product CNY price, live token estimate, SKU/stock races, checkout quote, payment result, delivery, cancel, after-sale and return | CNY stays fixed; server quote controls payment; each permitted status transition appears once |
| Merchant | Reapply after rejection, batch image upload/reorder, draft, publish, batch offline (50 max), inventory, ship/deliver/redeem, after-sale reply | Server validation is surfaced, inventory is not stale, no merchant-side refund approval is exposed |
| Wallet | Bind, deposit verification, withdraw, fee truncation, exchange, staking/redeem, interrupted request | Idempotency/recovery protection prevents duplicate financial operations; displayed amounts match server |
| Accessibility/layout | Dynamic Type, VoiceOver, light/dark, narrow iPhone, iPad, keyboard, safe areas | No clipped controls or overlapping content; destructive/financial actions are labeled and confirmed |

Attach Xcode build/test logs and device/OS versions when recording results. No test should use real funds or publish a live product without an explicitly configured test account and sandbox procedure.
