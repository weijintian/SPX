# iOS display caching

Display reads use `APIReadCache` through `APIClient.get`. Cache keys separate endpoint, account credential, language and sorted query parameters. Fresh memory/disk hits avoid an HTTP request; identical concurrent misses share one request.

## Freshness

| Display reads | Fresh lifetime |
| --- | --- |
| Chat and group lists | 20 seconds |
| Wallet, staking, plans, valuation and order summaries | 30 seconds |
| Catalog, products, transactions, addresses and favorites | 2 minutes |
| Contacts, group metadata and settings | 5 minutes |
| Mall configuration, FAQ and questions | 30 minutes |

Successful mutations and relevant realtime events invalidate affected entries before page notifications. Reconnection invalidates the current scope because events may have been missed. Pull-to-refresh bypasses fresh entries. Authentication, financial submissions, recovery checks and message cursor reads remain live. Expired financial data is not substituted for a failed refresh.

This is bounded freshness plus event invalidation. It does not guarantee that every remote change generates an event. After expiry, a read checks the server even when the data may be unchanged. Avoiding all unchanged reads would require server revisions or equivalent complete change notifications.

## Chat and media

Up to 12 conversation stores retain messages and sync cursors across navigation. Disk snapshots restore history; subsequent sync uses incremental cursors. Old canceled page runs cannot merge their results into the reopened conversation. Media thumbnails have bounded, clipped layouts. Video preview selection is initialized immediately, byte-range loading respects requested offsets, and playback resources follow page and app lifecycle.

## Verification

`validate_sources.py` checks syntax trees, resource references and API literals. `test_signing_configuration.py` checks signing script configuration. `APIReadCacheTests` and conversation reopening regression tests are present, but require macOS/Xcode to execute. Neither portable check proves Swift type correctness, video playback or scrolling performance.

The historical `verify_subpage_ui.py` checker permits only UI changes against an older backup. It reports intentional functional changes in the current tree; its baseline has not been changed to suppress those failures.

Before release, run `bash scripts/verify-macos.sh` and check on an iPhone: cache hits on repeated navigation, refresh after balance/order/contact changes, account changes, reconnect behavior, chat reopening and pagination, video start/seek/background return, and message overlap.
