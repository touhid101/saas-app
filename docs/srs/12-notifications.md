# Phase 12 — Notifications and Communication

> Friends Pharma ERP · SRS v2.0 · 9 September 2026

| Channel | What it does | Implementation | Status |
|---|---|---|---|
| Toast (in-page) | Success, error and warning on every action | Frontend useToast | IMPLEMENTED |
| In-app notification bell | Persisted notifications listed in the top bar | POS_NOTIFICATION · /api/notifications | PARTIALLY IMPLEMENTED |
| Low-stock alert | Pushed every 6 hours by a scheduled job | CartCleanupScheduler.pushLowStockAlerts | IMPLEMENTED |
| Expiry alert | Pushed daily at 08:00 | CartCleanupScheduler.pushExpiryAlerts | IMPLEMENTED |
| Stale cart cleanup | Abandoned carts cancelled every 30 minutes | CartCleanupScheduler.cancelStaleCarts | IMPLEMENTED |
| Subscription sweep | Daily at 01:15 — expiry and status maintenance | SubscriptionServiceImpl | IMPLEMENTED |
| Email / SMS / push | None | — | NOT IMPLEMENTED |

## Scheduled jobs

| Job | Cadence | Purpose |
|---|---|---|
| `cancelStaleCarts` | every 30 min | Cancels abandoned POS carts |
| `pushLowStockAlerts` | every 6 hours | Notifies on stock below reorder level |
| `pushExpiryAlerts` | daily 08:00 | Notifies on approaching expiry |
| Subscription sweep | daily 01:15 | Expiry and tenancy status maintenance |
