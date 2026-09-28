# 04 — Database gap analysis

Phase 0 · analysis only · **no migration has been written**. Flyway head is `V99`; the next
e-commerce migration would be `V100__…` (naming `V<n>__snake_case.sql`, schema `pharma`,
`baseline-on-migrate: true`; memory rules: no `${…}` in comments, no apostrophes in `--` comments
of `@Query`, no `--` inside JPQL).

## 1. Existing tables — REUSE AS-IS (read and/or write through existing services)

| Table | Role in ECOM | Written by ECOM? |
|---|---|---|
| `POS_TRANSACTION_MST` / `POS_TRANSACTION_DTL` | the order's financial and stock document | only via `SalesServiceImpl.createSale` / `advanceStage` / `collect` |
| `POS_STOCK_BATCH` / `POS_STOCK_MOVEMENT` | live stock, FEFO issue | only via `StockService` (indirectly) |
| `POS_PRODUCT_INFO`, `POS_BOX_INFO`, `POS_LEAF_INFO`, `POS_PRODUCT_UNIT` | catalogue, packing, MRP, six percentages, image | no |
| `POS_SALES_DISCOUNT` | discount% rules by unit | no |
| `GLOBAL_PRODUCT` (+ global masters) | cross-branch product identity, catalogue image | no |
| `ALL_BRANCH`, `ALL_COMPANY`, `ALL_ORGANIZATION` | pharmacy identity, tenant status | no (columns added, see §2) |
| `HR_EMPLOYEE` | delivery men | no |
| `POS_DELIVERY_COLLECTION` | COD cash-in | via `CollectionService` |
| `POS_NOTIFICATION` | staff alerts for new ECOM orders | via `NotificationService` |
| `ACC_*` | ledger | via `AccountingHooks` inside the sale |
| `ACL_MODULE_INFO`, `POS_PERMISSIONS`, `ACL_GROUP` | new ECOM staff modules (MOD53+) and rights | seed rows only |
| `SUB_AUDIT` | platform-level audit of ECOM admin actions (optional) | via existing recorder |

## 2. Existing tables — ALTER (additive, nullable first)

| Table | New column(s) | Why | Safety |
|---|---|---|---|
| `ALL_BRANCH` | `LATITUDE NUMERIC(9,6) NULL`, `LONGITUDE NUMERIC(9,6) NULL`, `ECOM_ENABLED CHAR(1) DEFAULT 'N'`, `ECOM_DELIVERY_RADIUS_KM NUMERIC(6,2) NULL`, `ECOM_MIN_ORDER_AMOUNT NUMERIC(14,2) NULL`, `ECOM_OPENING_HOURS VARCHAR(200) NULL`, `BRANCH_IMAGE_URL VARCHAR(500) NULL` | discovery, eligibility, delivery fee policy, pharmacy card | all nullable/defaulted; no existing row changes meaning (`ECOM_ENABLED='N'` = invisible) |
| `POS_TRANSACTION_MST` | **none required** | `FROM_SOURCE` (VARCHAR 15) already carries the origin; the ECOM order table holds the FK to the MST, not the reverse | an index `(FROM_SOURCE, PID_BRANCH, STAGE)` is a Phase 7 candidate only if the Sales Activity source filter proves slow |
| `POS_CUSTOMER_INFO` | **none** — see §5 on the bridge | keeping the ERP customer table untouched is the safest choice |
| `POS_PRODUCT_INFO` | none (`PRESCRIPTION_FLAG`, `PRODUCT_IMAGE`, `PID_GLOBAL_PRODUCT` already exist) | an `ECOM_VISIBLE CHAR(1) DEFAULT 'Y'` per branch product is optional (Phase 3) | |

Every `NOT NULL` or `CHECK` on new columns follows the four-step rule: add nullable → backfill →
validate (`SELECT count(*) WHERE … IS NULL`) → constrain in a *later* migration.

## 3. New tables actually required (by phase)

Naming follows the existing convention (`PHARMA.<AREA>_<NOUN>`, PK `PID_<NOUN>`, `BaseEntity`
columns `STATUS, INSERTED_BY, INSERTED_DATE, UPDATED_BY, UPDATED_DATE`). Prefix `ECOM_`.

### Phase 1 — global customer foundation

| Table | Purpose | Key columns | Constraints / indexes |
|---|---|---|---|
| `ECOM_CUSTOMER` | the **global** customer (platform-level, **no** `PID_ORG`, **no** `PID_BRANCH`) | `PID_ECOM_CUSTOMER` (seq), `CUSTOMER_CODE`, `FULL_NAME`, `MOBILE_NO`, `EMAIL`, `CUSTOMER_TYPE` (RETAILER / WHOLESALER / SPECIAL — the pricing tier, not the ERP category), `APPROVAL_STATUS` (PENDING / APPROVED / REJECTED / SUSPENDED), `APPROVED_BY`, `APPROVED_AT`, `REJECT_REASON`, `BUSINESS_NAME`, `TRADE_LICENSE_NO`, `DRUG_LICENSE_NO`, `REG_LATITUDE`, `REG_LONGITUDE`, `REG_ADDRESS`, `AVATAR_URL`, `POINTS_BALANCE NUMERIC(14,2) DEFAULT 0` (denormalised, ledger is truth) | `UNIQUE(MOBILE_NO)` (global, **not** per branch), `UNIQUE(EMAIL) WHERE EMAIL IS NOT NULL`, `CHECK(CUSTOMER_TYPE IN …)`, `CHECK(APPROVAL_STATUS IN …)`, index `(APPROVAL_STATUS)` |
| `ECOM_CUSTOMER_AUTH` | credentials, separate from `ACL_USER` | `PID_AUTH`, `PID_ECOM_CUSTOMER` FK, `LOGIN_IDENTIFIER` (normalised mobile or e-mail), `PASSWORD_HASH` (same `PasswordEncoder` bean → `{bcrypt}`), `STATUS`, `LAST_LOGIN_AT`, `FAILED_LOGIN_COUNT`, `LOCKED_UNTIL`, `EMAIL_VERIFIED`, `PHONE_VERIFIED`, `PASSWORD_CHANGED_AT` | `UNIQUE(LOGIN_IDENTIFIER)`, one row per customer (`UNIQUE(PID_ECOM_CUSTOMER)`) |
| `ECOM_CUSTOMER_REFRESH_TOKEN` | refresh tokens for customer sessions | mirrors `POS_REFRESH_TOKENS` (`TOKEN_VALUE` unique, `EXPIRES_AT`, `REVOKED`) but keyed by `PID_ECOM_CUSTOMER` | reusing `POS_REFRESH_TOKENS` is rejected: its `PID_USER` semantically means `ACL_USER` |
| `ECOM_CUSTOMER_ADDRESS` | address book (global) | `PID_ADDRESS`, `PID_ECOM_CUSTOMER`, `LABEL`, `RECIPIENT_NAME`, `PHONE`, `LINE1`, `LINE2`, `AREA`, `CITY`, `POSTCODE`, `LATITUDE`, `LONGITUDE`, `IS_DEFAULT` | index `(PID_ECOM_CUSTOMER)`; at most one default per customer (partial unique) |
| `ECOM_CUSTOMER_TYPE_HISTORY` | audit of tier changes (RULE: type change is a controlled action) | `PID_ECOM_CUSTOMER`, `OLD_TYPE`, `NEW_TYPE`, `CHANGED_BY` (staff user), `REASON`, `CHANGED_AT` | append-only |
| `ECOM_AUDIT_LOG` | customer-side and admin-side events (register, login, approve, reject, type change, address change, order placed/cancelled) | `EVENT_TYPE`, `ACTOR_KIND` (CUSTOMER / STAFF / SYSTEM), `ACTOR_ID`, `PID_ECOM_CUSTOMER`, `PID_BRANCH NULL`, `REF_TYPE`, `REF_ID`, `DETAIL`, `IP`, `AT` | written by a `REQUIRES_NEW` bean (memory: audit rows must survive refusals) |

### Phase 3–6 — catalogue, cart, checkout

| Table | Purpose | Notes |
|---|---|---|
| `ECOM_CART` **(built, V104)** | one open cart per customer **per fulfilment branch** | `PID_ECOM_CUSTOMER`, `PID_BRANCH`, `STATUS` (OPEN / CHECKED_OUT / ABANDONED), `COUPON_CODE`; `UNIQUE(PID_ECOM_CUSTOMER, PID_BRANCH) WHERE STATUS='OPEN'`. `POS_CART` is **not** reused: it is staff-keyed (`PID_USER NOT NULL`), branch-owned, feeds a dead finaliser |
| `ECOM_CART_ITEM` **(built, V104)** | requested lines | `PID_PRODUCT` (branch product), `SALES_UNIT`, `QTY`; **no price stored** — priced live on every read |
| `ECOM_ORDER` | the customer-facing order + snapshot | `ORDER_NO` (`ECOM-…`, unique), `PID_ECOM_CUSTOMER`, `PID_BRANCH`, `PID_ORG` (the branch's org, denormalised for scope queries), `PID_TRAN_MST` FK → `POS_TRANSACTION_MST` (**nullable until the POS document exists; unique when set**), `ECOM_STATUS` (NEW / CONFIRMED / PREPARING / READY / ON_THE_WAY / DELIVERED / CANCELLED / RETURNED), `CUSTOMER_TYPE_SNAPSHOT`, `CUSTOMER_NAME_SNAPSHOT`, `PHONE_SNAPSHOT`, delivery address snapshot columns + lat/long, `PAYMENT_METHOD` (COD first), `PAYMENT_REF`, `SUBTOTAL`, `DISCOUNT_AMOUNT`, `COUPON_CODE`, `COUPON_AMOUNT`, `OFFER_AMOUNT`, `POINTS_REDEEMED`, `POINTS_VALUE`, `DELIVERY_CHARGE`, `TAX_AMOUNT` (**TAX POLICY CONFIRMATION REQUIRED** — no VAT is charged on sales today), `GRAND_TOTAL`, `PLACED_AT`, `CONFIRMED_AT`, `DELIVERED_AT`, `CANCELLED_AT`, `CANCEL_REASON`, `CANCELLED_BY_KIND` | indexes `(PID_BRANCH, ECOM_STATUS, PLACED_AT)`, `(PID_ECOM_CUSTOMER, PLACED_AT)`, `(PID_TRAN_MST)` |
| `ECOM_ORDER_ITEM` | what the customer ordered, as ordered | `PID_PRODUCT`, `PID_GLOBAL_PRODUCT NULL`, `PRODUCT_NAME_SNAPSHOT`, `SALES_UNIT`, `QTY`, `PCS_PER_UNIT_SNAPSHOT`, `QTY_PCS`, `MRP_SNAPSHOT`, `UNIT_PRICE_SNAPSHOT`, `DISCOUNT_PCT_SNAPSHOT`, `LINE_TOTAL`, `PRESCRIPTION_FLAG_SNAPSHOT` | the batch-level split lives in `POS_TRANSACTION_DTL`; join via `PID_TRAN_MST` + `PID_PRODUCT` |
| `ECOM_ORDER_STATUS_HISTORY` | every transition with actor and reason | append-only |
| `ECOM_STOCK_RESERVATION` | **only if** the chosen checkout design is "reserve, then create the sale on confirmation" (option B in `07-live-stock.md`). Option A (create the POS sale at checkout inside one transaction) needs no reservation table. Decision deferred to Phase 6 with business input on when stock should leave the shelf | `PID_BRANCH`, `PID_PRODUCT`, `QTY_PCS`, `PID_ECOM_ORDER`, `EXPIRES_AT` |

### Phase 10–13 — tracking, promotions, loyalty

| Table | Purpose |
|---|---|
| `ECOM_DELIVERY_TRACKING` | `PID_ECOM_ORDER`, `PID_EMPLOYEE` (delivery man), `LATITUDE`, `LONGITUDE`, `RECORDED_AT`, `SOURCE` — append-only pings; retention policy required (privacy) |
| `ECOM_DELIVERY_PROOF` | photo / OTP / signature on delivery (optional) |
| `ECOM_SLIDER`, `ECOM_SLIDER_SLIDE` / `ECOM_SUB_SLIDER` | home hero slides and sub-slider items: image URL, title, link type (product / category / pharmacy / url), `PID_BRANCH NULL` (global) or scoped, sort, active window |
| `ECOM_COUPON` | as §23 of the brief: code, type (PERCENT / FIXED), value, max discount, min order, usage limit, per-customer limit, start/end, `FIRST_ORDER_ONLY`, `CUSTOMER_TYPE_SCOPE`, `BRANCH_SCOPE` (**ALL_BRANCHES / SELECTED_BRANCHES**, NOT NULL, no default) |
| `ECOM_COUPON_BRANCH` | `(PID_COUPON, PID_BRANCH)` PK — Coupon → Branch, never Customer → Branch |
| `ECOM_COUPON_PRODUCT` / `ECOM_COUPON_CATEGORY` | optional product/category scope |
| `ECOM_COUPON_USAGE` | `(PID_COUPON, PID_ECOM_CUSTOMER, PID_ECOM_ORDER, AMOUNT, USED_AT)`; unique per order |
| `ECOM_OFFER`, `ECOM_OFFER_BRANCH`, `ECOM_OFFER_PRODUCT` | automatic offers (no code) with the same branch-scope model |
| `ECOM_POINT_RULE` | earn rate by customer type / branch scope / date window |
| `ECOM_POINT_LEDGER` | append-only: `PID_ECOM_CUSTOMER`, `PID_ECOM_ORDER NULL`, `PID_BRANCH NULL`, `DIRECTION` (EARN / REDEEM / EXPIRE / ADJUST), `POINTS`, `BALANCE_AFTER`, `REASON` — **global** balance; branch only recorded as origin |

### Deliberately NOT created

| Candidate | Why not |
|---|---|
| `ECOM_PRODUCT`, `ECOM_PRICE`, `ECOM_STOCK` | duplicates `POS_PRODUCT_INFO`, the pricing engine and `POS_STOCK_BATCH` (RULE: no second ERP) |
| `ECOM_BRANCH` | `ALL_BRANCH` + additive columns is enough |
| `ECOM_DELIVERY_MAN` | `HR_EMPLOYEE.EMP_TYPE` already defines it |
| second refresh-token table for staff | exists (`POS_REFRESH_TOKENS`) |
| `ECOM_INVOICE` | the invoice is `POS_TRANSACTION_MST` |

## 4. Indexes required (new tables) — summary

* `ECOM_CUSTOMER(MOBILE_NO)` unique; `(EMAIL)` unique partial; `(APPROVAL_STATUS, INSERTED_DATE)`.
* `ECOM_CUSTOMER_AUTH(LOGIN_IDENTIFIER)` unique.
* `ECOM_CUSTOMER_ADDRESS(PID_ECOM_CUSTOMER)`.
* `ECOM_CART(PID_ECOM_CUSTOMER, PID_BRANCH) WHERE STATUS='OPEN'` unique; `ECOM_CART_ITEM(PID_CART)`.
* `ECOM_ORDER(ORDER_NO)` unique; `(PID_TRAN_MST)` unique partial; `(PID_BRANCH, ECOM_STATUS, PLACED_AT DESC)`; `(PID_ECOM_CUSTOMER, PLACED_AT DESC)`.
* `ECOM_COUPON(CODE)` unique (case-insensitive: `lower(CODE)`); `ECOM_COUPON_BRANCH` PK; `ECOM_COUPON_USAGE(PID_COUPON, PID_ECOM_CUSTOMER)`.
* `ECOM_POINT_LEDGER(PID_ECOM_CUSTOMER, INSERTED_DATE)`.
* `ECOM_DELIVERY_TRACKING(PID_ECOM_ORDER, RECORDED_AT)`.
* `ALL_BRANCH(ECOM_ENABLED) WHERE ECOM_ENABLED='Y'` partial (small table; optional).
* Geo: no PostGIS in the stack; nearby search on a few hundred branches is a bounding-box +
  Haversine in SQL over `(LATITUDE, LONGITUDE)`; a plain B-tree on the pair is sufficient.

## 5. The customer bridge — the one hard modelling decision

`POS_TRANSACTION_MST.PID_SUP_CUST` must reference a `POS_CUSTOMER_INFO` row for every existing
report, return, collection, ledger posting and the Sales Activity page to keep working unchanged.
A global customer has no such row. Three options were evaluated:

| Option | Description | Verdict |
|---|---|---|
| A. Make `PID_SUP_CUST` nullable / polymorphic | change every consumer | **rejected** — modifies ERP behaviour everywhere |
| B. Add global columns to `POS_CUSTOMER_INFO` and relax `PID_BRANCH` | breaks `uq_customer_branch_mobile`, `ix_customer_branch`, every branch-scoped customer query, and makes the ERP customer list show strangers | **rejected** — RULE 1 says never make the customer branch-owned, but it also says do not silently change ERP behaviour |
| **C. Shadow customer per branch (bridge)** | on the **first order at a branch**, create (inside the checkout transaction) a `POS_CUSTOMER_INFO` row in that branch with `CUSTOMER_TYPE` = the global tier label the ERP already understands (`Retailer` / `Wholesaler` / `Special`), `MOBILE_NO` = the global mobile, `CUSTOMER_CODE` = `EC-<globalId>`, and record the link in a new `ECOM_CUSTOMER_BRANCH_LINK(PID_ECOM_CUSTOMER, PID_BRANCH, PID_CUSTOMER)` unique on `(PID_ECOM_CUSTOMER, PID_BRANCH)` and on `(PID_CUSTOMER)` | **recommended** — the ERP sees a normal branch customer; the global identity, auth, addresses, points and type stay in `ECOM_*`; the branch never "owns" the customer (deleting the link does not delete the identity; the customer may hold links to any number of branches) |

Consequences of C the design must handle:

* The shadow row's `CUSTOMER_TYPE` is a **copy**. When the global type changes, every linked
  shadow row is updated in the same transaction (and the change is audited). Historical orders
  are unaffected because the tier is snapshotted on `ECOM_ORDER` and the prices are frozen in
  `POS_TRANSACTION_DTL`.
* `uq_customer_branch_mobile` still holds: a global mobile appears at most once per branch, which
  is exactly one shadow row. If a branch **already** has a staff-created customer with that mobile,
  the link must be made to the existing row after an explicit staff confirmation (Phase 1 design
  item; **BUSINESS CONFIRMATION REQUIRED**: auto-link or manual?).
* Staff editing the shadow row's type in Master Data would desynchronise it. Phase 1 marks
  shadow rows (`CUSTOMER_CODE LIKE 'EC-%'` or a nullable `ECOM_MANAGED CHAR(1)` column on
  `POS_CUSTOMER_INFO`, added nullable) and the customer form refuses type edits on them, pointing
  to the ECOM customer console.

## 6. Data-safety checklist for every ECOM migration

1. Additive only; `DROP`/`RENAME` of existing objects forbidden.
2. New FKs to ERP tables are `ON DELETE` **no action** (never cascade into POS documents).
3. Seed rows (`ACL_MODULE_INFO` MOD53+, role permissions) are idempotent (`ON CONFLICT DO NOTHING`)
   because production was baselined at 63 and has never received seed rows from earlier
   migrations (memory: `flyway-baseline-63-skips-seeds`).
4. Every migration is dry-run against a copy of `minipos_db` with `psql -f` (not `-c`, memory:
   `psql-c-mangles-non-ascii-on-windows`) and the app started once to catch Hibernate/JPQL
   validation failures.
5. `CHECK`/`NOT NULL` on new columns of existing tables only after a backfill migration and a
   validation query whose result is recorded in the migration's header comment.

## 7. Migrations as applied

| Version | What it adds | Phase |
|---|---|---|
| `V100` | `ECOM_CUSTOMER`, `ECOM_CUSTOMER_ADDRESS`, `ECOM_CUSTOMER_BRANCH_LINK`, `ECOM_REFRESH_TOKEN`, `ECOM_AUDIT_LOG` | 1 |
| `V101` | `GRP_ECOM` + `MOD55` (E-Commerce Customers) seed rows, idempotent | 1 |
| `V102` | `POS_CUSTOMER_INFO.ECOM_MANAGED` (nullable, no backfill of existing rows) | 1 |
| `V103` | `ALL_BRANCH` online-store columns: `ECOM_ENABLED`, `ECOM_MIN_ORDER_AMOUNT`, `ECOM_DELIVERY_RADIUS_KM`, `ECOM_OPENING_HOURS`, `ECOM_DESCRIPTION` | 2 |
| `V104` | `ECOM_CART`, `ECOM_CART_ITEM` (no price columns — carts are priced on read) | 5 |
| `V105` | `ECOM_ORDER` (+ `uq_ecom_order_tran`, a partial unique index on `PID_TRAN_MST`), `ECOM_ORDER_ITEM`, `ECOM_ORDER_STATUS_HISTORY`, `seq_ecom_order_no` | 6 |
| `V106` | `POS_TRANSACTION_MST.CANCELLED_BY` / `CANCELLED_DATE` / `CANCEL_REASON` + a partial index on cancelled documents | 7 |
| `V107` | `MOD53` (E-Commerce Orders) under `GRP_ECOM`, with matrix rows for ROLE01/02/09/04 — seed only, no table changes | 8 |
| `V108` | `ALL_BRANCH` delivery-charge policy: `ECOM_DELIVERY_FEE_TYPE` / `FLAT_FEE` / `FEE_PER_KM` / `FREE_ABOVE`, with CHECKs on the type and on negative amounts | 9 |
| `V109` | `ECOM_DELIVERY_TRACKING` (driver positions while an order is out) and `ECOM_DELIVERY_PROOF` (one per order, `PHOTO`/`NAME`/`NOTE`) | 10 |
| `V110` | `ECOM_COUPON`, `ECOM_COUPON_BRANCH`, `ECOM_COUPON_USAGE` + the `MOD54` promotions screen. `BRANCH_SCOPE` NOT NULL with no default; usage unique per order | 12 |
| `V111` | `ECOM_POINT_RULE`, `ECOM_POINT_RULE_BRANCH`, `ECOM_POINT_LEDGER`. No rule seeded and no default exchange rate; `POINT_VALUE` and `BRANCH_SCOPE` NOT NULL with no default; one EARN and one REDEEM row per order, by partial unique index | 13 |

All additive; no existing table was dropped, renamed or given a `NOT NULL` column.
| V113 | `ECOM_CUSTOMER_FAVOURITE` | New table. A customer + branch + product, unique per listing. No FK to the product, so a pharmacy can still retire one; the read resolves it and reports `available:false`. Additive. |
| V114 | `ALL_BRANCH` | New columns: `BRANCH_VERIFIED` (default N), `DRUG_LICENSE_NO`, `VERIFIED_BY`, `VERIFIED_AT`. No back-fill — marking an unchecked pharmacy verified would be the same claim the badge was removed for. Additive. |
