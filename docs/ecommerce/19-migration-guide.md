# 19 — Migration guide (Flyway rules + planned migration sequence)

## 1. Existing conventions (verified)

* Location `backend/src/main/resources/db/migration`, schema `pharma`, `baseline-on-migrate: true`,
  head **V99** (`V99__accounts_menu_seed_for_every_environment.sql`), 98 files, names
  `V<n>__snake_case.sql`. Versions are integers; no repeatable (`R__`) migrations in use.
* Style: long header comment explaining the *why*; `\set ON_ERROR_STOP on` and explicit
  `BEGIN/COMMIT` in some; `IF NOT EXISTS` / `ON CONFLICT DO NOTHING` for idempotent seeds; partial
  unique indexes; `COMMENT ON COLUMN`; validation `SELECT`s at the end.
* Production was **baselined at 63**: tables exist, but seed rows from `V1`–`V63` were never
  applied there; later seed migrations (`V88`, `V99`) were written "for every environment" and
  idempotent. New ECOM seeds must follow that pattern.
* Pitfalls recorded in memory: `${…}` in comments is a Flyway placeholder; apostrophes in `--`
  comments inside `@Query` strings break startup; `--` in JPQL breaks startup; `psql -c` mangles
  non-ASCII on Windows (use `-f`); Hibernate flush order on clear-and-reinsert.
* **`spring.jpa.hibernate.ddl-auto: update`** is set (`application.yml` line 40). Hibernate will
  therefore *create* a table or column for any new entity at startup **before** anyone writes a
  migration, with Hibernate's own names/types and no indexes or constraints. For ECOM this is a
  trap: an entity committed without its `V1xx` file silently "works" in dev and leaves production
  with an un-versioned schema. Rule for every ECOM phase: **migration first, entity second, and
  start the app against a database where the migration has already run**; switching the setting
  to `validate` is an ERP-wide change and a separate decision.

## 2. Planned sequence (numbers reserved, nothing written)

| Version | Content | Type |
|---|---|---|
| `V100__ecom_customer_foundation.sql` | `ECOM_CUSTOMER`, `ECOM_CUSTOMER_AUTH`, `ECOM_CUSTOMER_REFRESH_TOKEN`, `ECOM_CUSTOMER_ADDRESS`, `ECOM_CUSTOMER_TYPE_HISTORY`, `ECOM_AUDIT_LOG`, `ECOM_CUSTOMER_BRANCH_LINK`, sequences, indexes, `COMMENT ON` | new tables |
| `V101__ecom_modules_and_permissions.sql` | `ACL_MODULE_INFO` MOD53 E-Commerce Orders, MOD54 E-Commerce Promotions, MOD55 E-Commerce Customers (+ menu rows, default rights for ROLE01/02; idempotent) | seed |
| `V102__branch_ecom_columns.sql` | `ALL_BRANCH` + `LATITUDE`, `LONGITUDE`, `ECOM_ENABLED DEFAULT 'N'`, `ECOM_DELIVERY_RADIUS_KM`, `ECOM_MIN_ORDER_AMOUNT`, `ECOM_OPENING_HOURS`, `BRANCH_IMAGE_URL` — all nullable/defaulted | alter (additive) |
| `V103__pos_customer_ecom_managed_flag.sql` | `POS_CUSTOMER_INFO.ECOM_MANAGED CHAR(1) NULL` (+ comment) | alter (additive) |
| `V104__ecom_cart_and_order.sql` | `ECOM_CART`, `ECOM_CART_ITEM`, `ECOM_ORDER`, `ECOM_ORDER_ITEM`, `ECOM_ORDER_STATUS_HISTORY` (+ `ECOM_STOCK_RESERVATION` only if design B chosen) | new tables |
| `V105__ecom_order_indexes.sql` | composite indexes listed in `04` §4; optional `POS_TRANSACTION_MST(FROM_SOURCE, PID_BRANCH, STAGE)` after measuring | index |
| `V106__ecom_delivery_tracking.sql` | `ECOM_DELIVERY_TRACKING`, `ECOM_DELIVERY_PROOF` | new tables |
| `V107__ecom_slider.sql` | `ECOM_SLIDER`, `ECOM_SLIDER_SLIDE`, `ECOM_SUB_SLIDER_ITEM` | new tables |
| `V108__ecom_coupon_offer.sql` | `ECOM_COUPON`, `ECOM_COUPON_BRANCH`, `ECOM_COUPON_PRODUCT`, `ECOM_COUPON_CATEGORY`, `ECOM_COUPON_USAGE`, `ECOM_OFFER*` | new tables |
| `V109__ecom_loyalty.sql` | `ECOM_POINT_RULE`, `ECOM_POINT_LEDGER` | new tables |
| `V1xx__…_constraints.sql` | any `NOT NULL` / `CHECK` promoted **after** backfill + validation | constrain (separate, later) |

Each phase ships only its own migrations; numbers shift if the ERP adds migrations in between
(the next free number is taken at merge time).

## 3. Rollout rules

1. Dry-run every file with `psql -f` on a copy of the production-shaped DB; start the app once.
2. Never edit an applied migration; corrections are new versions.
3. No `DROP TABLE`, `DROP COLUMN`, `RENAME`, `TRUNCATE`, destructive `UPDATE` on ERP tables.
4. Data backfills are their own migration with a recorded pre/post count.
5. Rollback strategy = forward fix; ECOM tables can be emptied by a documented script if a
   phase is abandoned, ERP tables are never touched by rollback.
