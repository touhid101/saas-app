# 00 — Existing HR System Analysis

Read from the repository and the live database on 2026-09-09.

The general system audit — architecture, Flyway state, authentication, the
permission matrix, the tenant model — is in
[`docs/accounts-hr/00-current-system-audit.md`](../accounts-hr/00-current-system-audit.md)
and is not repeated. This records the areas that audit did not cover, which are
the ones this HR brief depends on.

---

## 1. Existing HR-related tables

| Table | Rows | Notes |
|---|---|---|
| `hr_employees` | 6 (4 at branch 1) | referenced by `pos_transaction_mst.delivery_man` and `acl_user.pid_employee` |
| `hr_designation` | 3 | |
| `all_departments` | 3 | |
| `hr_*` (13 more) | — | created by this work, V73–V79 |

`hr_employees` already carried `emp_image` (a large object), `file_name` and
`file_mime_type`. **Nothing in the application writes them** — every other image
goes to disk through `ImageStorageService`.

## 2. Existing HR-related APIs

`MasterDataController` — `/api/employees` (paged, searchable, filterable by
type), `/api/employees/types`, `/api/employees/{id}`, `/api/departments`,
`/api/designations`. Employee create and edit have lived here since before HR
existed, and still do.

## 3. Existing HR-related frontend

`MasterData.jsx`, under a group literally labelled **"HR"**, with entities
`department`, `designation`, `employee`.

## 4. Upload mechanism — the important find

`ImageStorageService` is a single, well-built implementation replacing three
that disagreed. It has a `Bucket` enum, config-driven directories anchored to
`UPLOAD_ROOT`, an extension whitelist, a MIME-vs-extension agreement check,
magic-byte inspection, a size limit, a path re-check against the resolved root,
and a best-effort delete. SVG is deliberately excluded.

```
app.upload.root ← UPLOAD_ROOT (default: uploads, relative ⇒ user home)
  ├─ /avatars        avatar-dir        → /uploads/avatars/
  ├─ /logo           logo-dir          → /uploads/logo/
  └─ /product_image  product-image-dir → /uploads/product_image/
```

`WebMvcConfig` creates each directory at startup, registers a static resource
handler per bucket, logs every resolved path, and warns if a URL prefix is moved
outside `/uploads/` (which `SecurityConfig` permits and `TenantStatusFilter`
exempts).

**Conclusion: extend, do not create.** Employee images became a fourth bucket.

## 5. Reporting mechanism

`ReportGridServiceImpl` holds a catalogue (`put(key, label)`) and dispatches by
key to a repository query; `ReportGridController` serves `/api/reports/catalogue`
and `/api/reports/{id}`; `Reports.jsx` renders whatever the catalogue lists,
with filters, pagination and CSV export via `src/utils/csv.js`.

18 reports registered today, across sales, purchase, supplier and P&L.

**Conclusion: HR reports register into this.** No new report engine.

## 6. Approval mechanism

**There is no generic one.** Purchase approval and requisition approval are each
bespoke, and each is gated by the `APPROVE` right on its own module through
`PermissionGuard`. The pattern — a right on a module, plus `decided_by` and
`decided_at` on the row — is the house convention, and HR follows it.

A generic multi-level engine would be a new subsystem, and the brief says not to
create one "unless absolutely necessary".

## 7. Calendar, expense, holiday, shift

Searched the whole tree and the live schema. **None of them exist.** Every hit
was a false positive: a comment, a dashboard label, or this work's own
`HOLIDAY`/`WEEKEND` attendance statuses.

## 8. Other reusable services

| Service | Use here |
|---|---|
| `DocumentNumberService` | `PR…` payroll numbers, `SP…` vouchers, under a row lock in `REQUIRES_NEW` |
| `NotificationService` + `pos_notification` | in-app alerts |
| `PasswordResetAuditRecorder` | the `REQUIRES_NEW` audit pattern — an audit row must not roll back with the refusal it records |
| `BranchScopeService` | branch resolution that refuses rather than widening |
| `PermissionGuard` | the only authorisation check |
| `InvoiceDocumentService`, `PrintDocument.jsx` | printing, for the payslip |

## 9. Flyway state at the time of reading

Highest applied: **V79** (was V72 before this work). Baseline 63,
`out-of-order: false`, schema `pharma`.

The two traps that govern every migration here — baseline 63 meaning no pre-V64
seed row can be relied on, and sequences that must be advanced with
`setval(GREATEST(…))` before any insert — are documented in
`docs/accounts-hr/16-flyway-production-migration-plan.md`. A third was found
during this work: **Flyway substitutes `${…}` placeholders across the whole
migration file, comments included**, and an unresolvable one stops startup.

## 10. Design system

`UI.jsx` (`Card, Pill, Table, Modal, Field, KpiCard, Tabs, QtyInput, useToast`),
`Icons.jsx`, CSS custom properties with a `[data-theme="dark"]` override, and the
QuickSales responsive pattern — breakpoints 767.98 / 1199.98, a fixed bottom tab
bar with 48px targets and `env(safe-area-inset-bottom)`, tables becoming cards
below 768. `usePermission(routeKey)` reads the server-filtered menu.

**Conclusion: reuse.** `hr.css` borrows the breakpoints and the bar; `HrKit.jsx`
wraps the existing components rather than replacing them.
