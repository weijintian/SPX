# Chat upgrade: A design, product cards and automation

## Client and API must be upgraded together

The native app now sends product cards with message_type=product and product_id. The API creates the immutable display snapshot from the visible catalog. Prices in cards are snapshots; checkout always uses the current catalog. Old app versions do not understand product cards. Deploy the backend before distributing the new APK and arrange a coordinated client upgrade.

Normal HTTP/HTTPS links open a browser. In-app product cards open the native product detail. External sharing continues to use the existing browser product page and verified App Link.

## Server files

Deploy index.php, db.php, chat_content.php, chat_delivery.php, chat_schema.php, chat_automation.php and chat-worker.php together. The normal schema installer adds message_origin and the schedule/rule/cooldown tables under the existing schema advisory lock. Back up the database first. Existing messages and balances are preserved.

## Scheduled delivery

Install deploy/spx-chat-scheduler.service and deploy/spx-chat-scheduler.timer under /etc/systemd/system, adjusting the PHP executable, site directory and web-service account to the deployment. Then reload systemd and enable/start spx-chat-scheduler.timer. These files are provided locally; this change does not deploy or activate the timer automatically.

The timer runs every 10 seconds. Delivery can be delayed by service downtime or queue load; overdue pending jobs are sent on recovery. The worker records sent/failed states. Failed permission checks are not automatically retried. Users can inspect failure reasons and create a new schedule. Only text and image messages may be scheduled, from 30 seconds to 90 days ahead, up to 50 pending jobs per user. Send times are Unix seconds; the API stores Beijing time and returns both formats.

Delivery, schedule cancellation and bot-rule changes use an advisory lock plus transactions. The mirrored private messages, unread counts and task state commit together. Concurrent workers and retries reuse the delivery identifier. Current friend, membership, mute, private-chat and block rules are rechecked when due.

## Automatic replies

Personal replies are scoped to the account and rate limited per peer. Group rules require current owner/admin authority; demoted/departed administrators cannot continue triggering their rules. Group matching supports exact or contains, case insensitive, one response per incoming human message, with a configurable 30–86400 second cooldown. Automatic and scheduled messages never trigger another automatic reply. Automation origin is server controlled.

## Verification

Run tests/mall_integration.py with the existing local PHP/MariaDB harness. It invokes tests/chat_scenarios.py after the commerce tests. No production database is used. Android compilation uses the existing release build configuration. Device UI checks use a separate application id and isolated sample data, never the production user's account.
