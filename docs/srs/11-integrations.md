# Phase 11 — Integrations

> Friends Pharma ERP · SRS v2.0 · 9 September 2026

**There is no outbound HTTP client anywhere in the backend.** No `RestTemplate`, `WebClient`, `HttpClient`, mail sender or SDK was found. The analytics assistant answers from the database, not an external model.

| System | Purpose | Direction | Authentication | Failure handling | Status |
|---|---|---|---|---|---|
| PostgreSQL | System of record | Inbound/outbound | Connection pool | Hard dependency — the application will not start without it | IMPLEMENTED |
| Local filesystem (UPLOAD_ROOT) | Product images, avatars, branch logos | Write and serve | None | Upload fails; the rest of the system continues | IMPLEMENTED |
| Analytics assistant (AI Copilot) | Natural-language questions over the ledger | Internal only | Session | Answered from AiAnalyticsRepository. There is no external model call and no HTTP client anywhere in the backend | IMPLEMENTED |
| Email / SMTP | Not integrated | — | — | Temporary passwords are handed over out of band today | NOT IMPLEMENTED |
| SMS | Not integrated | — | — | — | NOT IMPLEMENTED |
| Payment gateway | Not integrated | — | — | Payments are recorded, never captured | NOT IMPLEMENTED |
