# 22 — Troubleshooting (known failure modes, from the ERP and from this analysis)

| Symptom | Cause | Where to look / what to do |
|---|---|---|
| App does not start after adding a migration | `${…}` placeholder in a comment; `--` inside a JPQL `@Query`; apostrophe in a `--` comment of a native query | memory rules `flyway-placeholders-in-comments`, `jpql-no-sql-comments`, `query-comment-apostrophe`; fix the text, never the applied migration |
| New ECOM table appeared without a migration | `spring.jpa.hibernate.ddl-auto: update` created it from the entity | drop it **only in dev**, write the `V1xx` migration, restart against a migrated DB (`19` §1) |
| Sale refused `UNIT_NOT_SELLABLE` / `PRICE_NOT_CONFIGURED` | tier may not buy the unit, product has no box/strip packing, MRP null/0 | `CustomerPricing.unavailableReason` names the fix (Product Master packing, MRP, Sales Discount %) |
| Checkout says out of stock while the card said in stock | browse stock is advisory; another order consumed it, or the request is in BOX and only loose strips remain (whole-unit rule) | `GET /api/inventory/fefo-preview`; explain whole-unit counting (`06` §4) |
| Order shows in Sales Activity but not on the ECOM board (or vice versa) | `ECOM_ORDER.PID_TRAN_MST` link missing → checkout transaction did not complete atomically | must be impossible by design; if seen, the ECOM insert ran outside the `createSale` transaction — treat as a defect |
| `POST /api/sales-activity/{id}/cancel` returns 400 "'Cancelled' is not an order stage" | pre-existing ERP bug (`STAGES` lacks `Cancelled`) | ERP fix required before ECOM cancellation (`02` §3.4) |
| Sale written into the wrong branch | caller passed `branchId` in the body expecting it to be honoured; only ROLE01 may; acting-branch header in play | `BranchScopeService.resolve`; ECOM must use the service-level branch context (`11` §5) |
| **E-Commerce Customers** list returns 500 while a search term makes it work | a null parameter inside `CONCAT('%', :q, '%')` reaches PostgreSQL untyped, which resolves the concatenation to `bytea` and then fails on `lower(bytea)` | fixed in `EcomCustomerRepository.search` — the guard is `:q = ''` and the service sends `""`, never null. Any JPQL of the form `:x IS NULL OR … CONCAT(…, :x, …)` has the same trap |
| Customer type change does not change quoted prices | shadow `POS_CUSTOMER_INFO.CUSTOMER_TYPE` not propagated | `ECOM_CUSTOMER_TYPE_HISTORY` + link table; propagation runs in the same transaction (`04` §5) |
| Two customers with the same phone at one pharmacy | staff-created customer already holds the mobile; `uq_customer_branch_mobile` blocks the shadow row | link to the existing row after staff confirmation (business decision pending) |
| Coupon accepted in cart, rejected at checkout | branch changed, usage limit reached meanwhile, date passed | by design; checkout re-validates on the order branch |
| Images missing in the storefront | `PRODUCT_IMAGE` null (only 16/108 in dev) or `/uploads` not reachable from the public origin | fallback to `GLOBAL_PRODUCT.PRODUCT_IMAGE`, then pack art; check proxy path for `/uploads/**` |
| psql output has stray `\r` / non-ASCII mangled | Windows console codepage | memory `psql-output-carries-cr`, `psql-c-mangles-non-ascii-on-windows` — use `-f` files, strip `\r` |
| Playwright shows old values after a backend change | user's IntelliJ backend (8585) not restarted; test harness on 8586 | restart the right server; never kill 8585/5173 |
| 402 on every request | branch `TENANT_STATUS` suspended | `TenantStatusFilter`; platform console |
| Audit row missing after a refused action | record-then-throw rolled the row back | write audits through a `REQUIRES_NEW` bean (memory `audit-rows-roll-back-with-refusals`) |
