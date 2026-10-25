# iOS staking page optimization — 2026-10-26

## Implemented locally

- Reworked StakingView in Features/Wallet/Assets.swift with a dynamic phase badge/name, total and remaining rewards quota, and a remaining-quota indicator. Phase/rates/amounts come from API responses, not the reference screenshot.
- Added a blue/purple active-principal summary and record counts, a separate stakeable-balance card, complete rules, selectable plan cards and a full-term dividend estimate.
- Expanded records with principal, APY, reference daily and ten-day dividends, expected full-term dividend, actual paid dividend, creation time and server status. Short plans cap the ten-day estimate at their actual duration.
- Added adaptive quota grids and vertically stacking detail rows for narrow widths and accessibility text sizes. Semantic colors follow the existing app theme. This layout is implemented in source but has not been visually verified in a simulator or on a device.
- Load plans, records and wallet concurrently; reject canceled, replaced and wrong-account responses. Changing accounts clears the previous account's data and dialogs. New submissions require a fresh complete snapshot.
- Validate integer amounts, 100000 increments, plan minimum, actual SPX001/BSC stakeable balance and the 10000000 active-principal cap. Missing plan/balance values block submission.
- Preserve financial request reservation, completion, original-request retry and resolution. Reject account changes before a recovery request starts; preserve unresolved-submission protection and display success separately from errors.

## Contract and presentation decisions

- Local backend: spxchat/index.php, staking.plans / staking / staking.create / staking.redeem. Android reference: MainActivity.java staking page and I18n.java quota label.
- phase.total_quota and phase.remaining_quota are dividend plus referral reward capacity. They are not a principal deposit limit. The server reserves the full dividend and applicable referral reward; it remains authoritative for the exact available quota at submission time.
- The personal summary explicitly means active principal. Redeemed historical records remain visible in the record list but are excluded from that summary and the active-principal cap.
- The 100000 increment and 10000000 cap mirror fixed rules in the local staking.create handler. Plan duration, APY, minimum and phase quota remain server-provided.
- stakeable_balance controls new staking; rewards, dividends and returned principal cannot be staked again under the current local backend rules.
- spxchat/staking_settlement.php implements ten-day dividend periods, settlement of the final partial period at maturity, and principal return at maturity. The page explains that actual posting/processing time must be checked in transaction records and retains the server-authorized manual fallback. Live deployment and scheduling have not been verified.
- Dividend estimates use principal × APY × days / 36500. Display formatting truncates to two decimal places and preserves missing/invalid values as a dash; positive dust is shown as <0.01 rather than zero. Calculations retain Decimal precision.

## Verification

- Ran on Windows with Python 3.13: python ios-native/scripts/validate_sources.py. Exit 0; 68 Swift source/test files parsed with no syntax errors; 97 literal API actions matched the local backend and action snapshot.
- The validator also checks property lists, referenced assets and bundled policy resources. source-validation.json contains the final machine-readable result; its line count is authoritative.
- Added seven focused XCTest methods in ReliabilityTests.swift covering phase/quota, amount presentation, active totals, integer/increment rules, minimum/balance/cap boundaries, incomplete plans and dividend periods. These tests were authored but not executed.
- Added five staking acceptance rows to DEVICE-TEST-MATRIX.md. These are pending acceptance paths, not recorded runtime passes.

## Remaining execution checks

The current Windows environment has no Swift/Xcode compiler. Tree-sitter source parsing does not type-check Swift or SwiftUI, compile/link the application, execute XCTest, measure responsiveness or confirm live financial behavior. Run Xcode tests and a signed archive on macOS, then complete the staking acceptance rows with a configured test account and physical iOS devices. Verify phase/quota against the same Android/API account, narrow-width and large-text layouts, account switching, interrupted submission/recovery and maturity settlement. No IPA/APK, deployment or publication is produced by this change.
