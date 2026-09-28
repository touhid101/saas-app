# 21 — Deployment (existing state + ECOM additions)

## 1. Existing state (verified)

* Backend: Spring Boot fat jar (`spring-boot-maven-plugin`), Java 17 target (built locally with
  JDK 21), Postgres 18 (`localhost:5489/minipos_db` in dev, `pharma` user), Flyway on startup.
  No Dockerfile, no compose, no CI config in the repo.
* Frontend: `vite build` → static `dist/`; SPA served with `try_files $uri $uri/ /index.html`
  (documented in `usePageRoute.js`).
* Uploads: local filesystem under `UPLOAD_ROOT` (`avatars`, `logo`, `product_image`,
  `employee_image`, `expense_receipt`), served by Spring at `/uploads/**`. Not object storage;
  a multi-instance deployment needs a shared volume.
* Config: `application.yml` with env overrides (`UPLOAD_ROOT`, JWT secret, DB, CORS origins,
  `app.platform.org-id/branch-id`). Ports in dev: backend 8585 (user's IntelliJ), Vite 5173.
* Scheduled jobs in-process (`scheduler/`), so only one backend instance may run them unless
  guarded.

## 2. ECOM additions to plan (Phase 16)

| Item | Requirement |
|---|---|
| Public surface | `/api/ecom/public/**` and `/shop/**` reachable without VPN; reverse proxy with TLS, gzip, and rate limiting on `/api/ecom/auth/**` |
| Separate secrets | `app.ecom.jwt.secret` (distinct from staff), OTP provider keys, map provider key — all env-injected |
| Static assets | product/branch/slider images: keep `/uploads` for v1; CDN/object storage is a later optimisation |
| Database | new `ECOM_*` tables in the same `pharma` schema (single DB, single transaction boundary with the ERP — required for checkout atomicity); connection pool sized for the extra public read load |
| Caching | none in stack; optional in-process cache for compare/nearby read models with short TTL; no Redis unless infra approves |
| Scheduled jobs | auto-cancel of stale `NEW` orders; tracking retention purge; points expiry — same in-process scheduler with a single-instance guard |
| Observability | request logging for `/api/ecom/**` with correlation id; `actuator/health` already public |
| Rollout | feature flag `app.ecom.enabled` (default false) so ERP deployments are unaffected until the storefront is switched on per environment |
| Backups | unchanged (same DB); ECOM tables covered automatically |
