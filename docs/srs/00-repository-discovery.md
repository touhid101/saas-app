# Phase 0 — Repository Discovery

> Friends Pharma ERP · SRS v2.0 · 9 September 2026

## Application

**Friends Pharma ERP** — Multi-Tenant Pharmacy POS, Inventory & Trading Platform

## Method

Reverse-engineered from the running system. 327 Java files, 98 React files, 63 JPA entities, 64 database tables, 8 views, 34 REST controllers, 68 repositories, 49 services, 71 Flyway migrations and 24 test classes were read. The live schema was queried and the running API probed. No application source, script or configuration was modified.

## Technology stack

| Layer | Technology | Notes |
|---|---|---|
| Frontend | React 18 + Vite | 36 pages, 16 shared components, 27 API service modules |
| Routing | None (no router library) | A single shell switches on a `page` state key |
| State | React Context (AppContext) | Session, permissions, menu, notifications, toasts |
| HTTP | apiClient wrapper | Bearer token, refresh-on-401, session/subscription expiry events |
| Backend | Spring Boot 3.3.2 (Java 21) | 34 controllers, 49 service implementations |
| Security | Spring Security 6.3.1 + JWT | Stateless; four servlet filters in a fixed order |
| Persistence | Spring Data JPA / Hibernate 6.5 | 63 entities; native SQL for all reporting |
| Database | PostgreSQL, schema `pharma` | 64 base tables, 8 views |
| Migration | Flyway | 71 migrations, baselined at V63 |
| Scheduling | Spring @Scheduled | 4 jobs |
| API docs | springdoc-openapi | Swagger UI |
| Tests | JUnit 5 + Mockito | 24 classes, 297 tests, all passing |

## Repository map

| Path | Holds |
|---|---|
| src/pages | 36 screen components — one per business screen |
| src/components | 16 shared components (Card, Pill, Field, Modal, SearchSelect, QtyInput …) |
| src/services | 27 API client modules, one per domain |
| src/lib | apiClient, AppContext, ConfirmProvider, usePermission |
| backend/…/controller | 34 REST controllers |
| backend/…/service/impl | 49 service implementations — all business logic |
| backend/…/repository | 68 repositories; reporting uses native queries |
| backend/…/entity | 63 JPA entities |
| backend/…/security | JWT, filters, PermissionGuard, UserPrincipal |
| backend/…/util | 4 stateless calculators — the money rules |
| backend/…/scheduler | Cart cleanup, low-stock and expiry alerts |
| backend/…/db/migration | 71 Flyway scripts, V64 upward are the ones production has run |
