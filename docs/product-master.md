# Product Master — implementation notes

Pharmaceutical Product Master: database, backend, and frontend.
Built on the existing architecture rather than alongside it — the sections below
record what was reused, what was added, and the decisions that were not obvious.

---

## 1. Database changes

All changes target `PHARMA.POS_PRODUCT_INFO`. No new table was created.

| Migration | Purpose |
|---|---|
| `src/main/resources/db/migration/V4__product_master.sql` | PostgreSQL — authoritative (matches the live datasource) |
| `backend/db/oracle/V4__product_master_oracle.sql` | Oracle dialect equivalent |
| `backend/db/rollback/V4__product_master_rollback.sql` | Rollback |

### Columns added

| Column | Type | Why |
|---|---|---|
| `PRODUCT_CODE` | `VARCHAR(30)` | Did not exist. `getProductCode()` previously aliased `SKU`, conflating two different identifiers. |
| `REORDER_LEVEL` | `NUMERIC(14,4)` | Only `ALERT_QTY` existed. See the mirroring note below. |
| `SAFETY_STOCK` | `NUMERIC(14,4)` | New. |
| `MINIMUM_STOCK` | `NUMERIC(14,4)` | New. |
| `MAXIMUM_STOCK` | `NUMERIC(14,4)` | New. |
| `BATCH_TRACKING` | `CHAR(1)` Y/N | New. Follows the existing `PRESCRIPTION_FLAG` convention. |
| `EXPIRY_TRACKING` | `CHAR(1)` Y/N | New. |
| `PRODUCT_IMAGE` | `VARCHAR(300)` | Disk-stored filename. The legacy `IMAGE` BLOB is retained for pre-V4 rows. |
| `PID_UNIT_ID` | `BIGINT` | `PID_UNIT` is a `VARCHAR(20)` holding the unit *name*, not a key. |

### Columns changed

- **`IGTA`: `VARCHAR2(50)` → `NUMERIC(14,4)`.** It holds a calculated monetary
  amount but was imported as text. Verified unreferenced anywhere in application
  code before converting.
- **`PRODUCT_NAME`: `VARCHAR(50)` → `VARCHAR(150)`.** 50 truncates real names,
  e.g. *Amoxicillin + Clavulanic Acid 875mg/125mg Film-Coated Tablet*.

### Column dropped

- **`NO_OF_PCS`.** Pieces-per-box is derived
  (`POS_BOX_INFO.NO_OF_LEAF × POS_LEAF_INFO.NO_OF_PCS`) and the specification
  forbids storing it on the product. It was never read or written by application
  code, so the drop is safe. The rollback script recreates the column but cannot
  restore values — take a backup first.

### Columns reused (not duplicated)

`PRODUCT_NAME`, `PID_GENERIC`, `PID_COMPANY`, `PID_CATEGORY`, `PID_SUB_CATEGORY`,
`SKU`, `PID_BOX`, `MRP_PRICE`, `CONVERSION_RATE`, `TP_PRICE`, `VAT_PCT`,
`BUY_PRICE`, `BARCODE`, `PRESCRIPTION_FLAG`, `STATUS`, `STOCK_QTY`,
and the `BaseEntity` audit columns `INSERTED_BY` / `INSERTED_DATE` /
`UPDATED_BY` / `UPDATED_DATE`.

### Constraints and indexes

- Unique: `PRODUCT_CODE` (global); `(PID_ORG, UPPER(SKU))` and
  `(PID_ORG, UPPER(BARCODE))` scoped per organisation, as partial indexes so
  NULLs stay exempt.
- Check: prices and stock levels non-negative; VAT and conversion rate ≤ 100;
  the stock ladder; Y/N flag domains.
- FK: unit, box, generic, category, company — added `NOT VALID` so pre-existing
  orphan references do not fail the migration. Run `VALIDATE CONSTRAINT` after
  data cleanup.
- Search indexes on `(PID_ORG, STATUS)`, `LOWER(PRODUCT_NAME)`, and each FK.

---

## 2. Decisions worth knowing

**Product code is derived from the primary key.** `PRD` + zero-padded
`PID_PRODUCT`, assigned right after the first `save()` inside the same
transaction. `PID_PRODUCT` already comes from `SEQ_PID_PRODUCT`, so uniqueness
and concurrency safety are inherited from the sequence — no second sequence to
keep in step, and no dialect-specific `nextval` SQL. `PRODUCT_CODE` is
`updatable = false`, so an edit cannot change it.

**`REORDER_LEVEL` is mirrored into `ALERT_QTY` on every write.** `InventoryServiceImpl`
and `PosProductInfoRepository.findLowStockProducts()` still read `ALERT_QTY`.
Writing only the new column would have silently broken low-stock alerts.
Reads prefer `REORDER_LEVEL` and fall back to `ALERT_QTY` for pre-V4 rows.

**`MAXIMUM_STOCK = 0` means "no ceiling configured", not "a ceiling of zero".**
Without this exemption every product would have to declare a maximum before it
could declare a reorder level, and every existing row would fail the check
constraint. Encoded identically in the DB constraint, the validator, and the
frontend.

**Product name is not globally unique.** The same medicine is legitimately sold
by several manufacturers. The duplicate guard is the business key:
name + company + generic.

**Status stays `Y`/`N` in the database.** The rest of the schema and every other
module use `Y`/`N`. The API speaks `ACTIVE`/`INACTIVE`; translation lives in
`ProductMasterMapper` alone, so no page needs to know about `Y`/`N`.

---

## 3. Backend

Package layout follows the project's existing layer-first convention
(`controller/`, `service/impl/`, `dto/request/`, …) rather than the
feature-first `product/{controller,service}` in the specification, so the module
sits alongside its peers.

| File | Role |
|---|---|
| `controller/ProductController.java` | All `/api/products` routes |
| `service/interfaces/ProductService.java` | Use-case contract |
| `service/impl/ProductServiceImpl.java` | Orchestration, tenant scoping, delete guard |
| `service/impl/ProductImageStorageService.java` | Validated disk storage |
| `validation/ProductValidator.java` | Master-data, uniqueness, stock ladder |
| `util/ProductPricingCalculator.java` | Pure pricing rules |
| `specification/ProductSpecification.java` | Criteria-API filters + sort whitelist |
| `mapper/ProductMasterMapper.java` | Entity ↔ DTO |
| `dto/request/ProductMasterRequest.java` | Create/update payload |
| `dto/response/ProductMasterResponse.java` | Detail payload |
| `dto/response/ProductListItemResponse.java` | Grid row |
| `exception/custom/ProductInUseException.java` | 409 on delete-with-history |

### Removed

The old product endpoints on `MasterDataController` were **removed, not left in
place**. Two controllers mapping `/api/products` is an ambiguous-mapping failure
at startup. The old path also accepted a client-supplied `?orgId`, which let one
organisation read another's catalogue.

Removed with them: `MasterDataService`/`Impl` product methods, `ProductMapper`,
`MasterDataRequests.ProductRequest`, `MasterDataResponses.ProductResponse`, and
the frontend's unused, already-broken `services/productsService.js`.

### API

| Method | Path | Roles |
|---|---|---|
| `GET` | `/api/products` | any authenticated |
| `GET` | `/api/products/lookups` | any authenticated |
| `GET` | `/api/products/{id}` | any authenticated |
| `GET` | `/api/products/barcode/{barcode}` | any authenticated |
| `POST` | `/api/products` | ROLE01, ROLE02, ROLE05 |
| `PUT` | `/api/products/{id}` | ROLE01, ROLE02, ROLE05 |
| `PATCH` | `/api/products/{id}/status` | ROLE01, ROLE02, ROLE05 |
| `DELETE` | `/api/products/{id}` | ROLE01, ROLE02 |
| `POST` | `/api/products/{id}/image` | ROLE01, ROLE02, ROLE05 |
| `DELETE` | `/api/products/{id}/image` | ROLE01, ROLE02, ROLE05 |

List parameters: `search`, `sku`, `barcode`, `genericId`, `companyId`,
`categoryId`, `unitId`, `status`, `stockStatus`, `batchTracking`,
`expiryTracking`, `prescriptionRequired`, `page`, `size`, `sortBy`, `sortDir`.

**There is no `orgId` parameter anywhere.** The tenant comes from the JWT.

---

## 4. Security

- **Derived values cannot be forged.** `tpPrice`, `igta`, `buyPrice` and
  `productCode` are absent from the request DTO — Jackson has nowhere to bind a
  submitted value. A test asserts the DTO never grows those fields.
- **Tenant isolation.** `findByIdAndOrg` filters on the JWT org; a cross-tenant
  id returns 404, not 403, so the API does not confirm another organisation's
  product exists. Master-data references are checked for the same org.
- **No dynamic SQL.** All filtering goes through the Criteria API. The `sortBy`
  parameter is whitelisted — an unknown value falls back to product name rather
  than reaching `Sort.by()`.
- **Upload hardening.** Stored filenames are generated UUIDs, so path traversal
  has nothing to act on; MIME type, extension and magic bytes must all agree, so
  a renamed executable is rejected; the resolved path is re-checked against the
  configured root before any write.
- **Page size is clamped** to 200 so a client cannot request the whole catalogue.

### Image storage configuration

Never hardcoded. `application.yml`:

```yaml
app:
  upload:
    product-image-dir: ${PRODUCT_IMAGE_UPLOAD_DIR:uploads/product_image}
    product-image-url-prefix: /uploads/product_image/
```

A relative path is anchored to the user home — on this dev box that resolves to
`C:\Users\Touhid\uploads\product_image`, matching the requested location. In
production set `PRODUCT_IMAGE_UPLOAD_DIR` to an absolute server path.

---

## 5. Frontend

| File | Role |
|---|---|
| `src/pages/ProductMaster.jsx` | List, six-section form, detail view, import modal |
| `src/services/productMasterService.js` | API layer, pricing preview, client validation |
| `src/services/productExcel.js` | Template generation, workbook parsing, exports |

### Design system conformance

The page follows the house layout used by Sales Activity and the other
operational pages: `.page` → `.page-head` with `<h1>` + `.page-sub` → KPI strip
→ filter chips → filter bar → `.data-table` → pagination footer.

Every colour is a CSS custom property from `styles.css` with **no hardcoded
fallback**, so the page follows all four themes. The first draft invented three
tokens that do not exist — `--bg-soft`, `--line-soft`, `--clay-deep` — whose
fallbacks were cool greys (`#F4F5F7`, `#EDEFF2`) that clashed with the warm
paper palette and did not respond to theme switching at all. Verified in the
browser: all four themes resolve correctly and zero elements paint the old greys.

Typography follows the system rather than inline weights: Fraunces via `<h1>`
and `.card-title`, IBM Plex Mono via `.mono` for codes, SKUs and money, Inter
for body text.

`@keyframes spin` was added to `styles.css` — the inline spinners across several
pages referenced it but it was never defined, so every loading spinner sat
frozen. A `prefers-reduced-motion` block accompanies it.

### Excel import / export

| Action | Behaviour |
|---|---|
| **Template** | Three-sheet `.xlsx`: *Products* (headers + two worked examples built from your own master data), *Reference* (every valid Generic / Company / Category / Sub-Category / Unit / Box name), *Instructions* (rules, formulas, defaults, limits) |
| **Import** | Drag-drop or browse → parse → preview → per-row result table → downloadable result workbook |
| **Export** | Current filter selection as `.xlsx` (not just the visible page) |

The template deliberately has **no** TP Price, IGTA, Buy Price or Product Code
columns. If a user adds them by hand the parser reports them as ignored — the
server owns those values, and `POST /api/products/bulk` rejects them the same
way the single-product endpoint does.

Rows are validated and saved individually through the same `ProductService` the
form uses, each in its own transaction. A 500-row file with three bad rows lands
497 products and reports exactly which three failed and why; re-uploading the
fixed file is safe because SKU uniqueness stops the good rows duplicating.
`mode=CREATE` treats an existing SKU as an error, `mode=UPSERT` updates it.

SheetJS (~419 KB) is **lazy-loaded** via dynamic `import()`. Bundling it
statically pushed the main bundle from 595 KB to 1,043 KB for a feature used
occasionally on one page; it now ships as a separate chunk fetched only when
Import, Export or Template is clicked.

Menu integration follows the existing RBAC-driven sidebar: the V4 migration
inserts a `productmaster` row into `ACL_MODULE_INFO` and grants Super Admin full
permissions; `App.jsx` routes the key and `Sidebar.jsx` supplies a capsule icon.
Permissions are read through the existing `usePermission('productmaster')` hook.

The form computes TP Price, IGTA and Buy Price live as the user types, but
**never submits them** — the preview exists for feedback only, and the server
recalculates independently. If the two ever disagree, the server is right and
`calculatePrices()` is the bug.

`getProducts` deliberately does not use `apiClient.unwrapPagination`. That helper
expects `currentPage`/`pageSize`/`hasNext`/`hasPrevious`, which `PageResponse.java`
does not emit — it would silently return zeros. See the pre-existing issues below.

---

## 6. Tests

76 passing (`mvn test`).

| Suite | Count | Covers |
|---|---|---|
| `ProductPricingCalculatorTest` | 29 | Spec cases 1–5, rounding, null/NVL semantics, defaults |
| `ProductServiceImplTest` | 24 | Spec case 6 (tamper resistance), code generation + persistence guard, delete guard, tenant isolation, field mapping, image lifecycle |
| `ProductValidatorTest` | 23 | Master-data active/exists/tenant, uniqueness, stock ladder, pricing inputs |

Spec acceptance case 1 verified exactly: MRP 100, rate 25.037, VAT 17.40 →
TP 74.96, IGTA 13.04, Buy 88.00.

### End-to-end run (live PostgreSQL + running backend + browser)

~120 checks against the real stack. Verified in the database, not just the API
response: all 25 products satisfy
`tp_price = ROUND(mrp - mrp*rate/100, 2)`,
`igta = ROUND(tp*vat/100, 2)` and
`buy_price = ROUND(tp + tp*vat/100, 2)` exactly, and all 25 carry a product code.

Three defects were found and fixed during that run:

**1. `product_code` was never persisted.** The column was annotated
`updatable = false`. The code is derived from the generated primary key, so it
can only be written by the UPDATE that follows the INSERT — and
`updatable = false` excluded it from exactly that statement. The API response
looked correct because the in-memory entity had the value; the database column
was null. Unit tests could not catch this: a mocked `save()` has no JPA
semantics. Fixed by dropping `updatable = false` (immutability is enforced by
the DTO having no such field and `update()` never touching it) and adding
`productCodeColumnIsUpdatable()` as a regression guard.

**2. Unknown/malformed JSON returned HTTP 500.** `HttpMessageNotReadableException`
had no handler and fell through to the catch-all. Posting a server-owned field
such as `tpPrice` was correctly refused, but as a 500. Added a handler returning
400 with `UNKNOWN_FIELD` / `INVALID_FIELD_FORMAT` / `MALFORMED_REQUEST`. This
also fixes every other endpoint, where any malformed body previously read as a
server fault.

**3. The V4 menu seed used the wrong column names.** It was written against
`V3__seed_rbac.sql`'s Oracle-era shape (`MODULE_TITLE`, `ROUTE_KEY`, `PID_GROUP`,
`'Y'`/`'N'`), but the entities generate `MODULE_NAME`, `ROUTE_PATH`,
`MODULE_CODE`, `PID_ROLE` and integer flags. Rewritten against the entity shape,
plus a `SETVAL` step because the existing module rows were seeded without drawing
from `SQ_ACL_MODULE`, leaving the sequence behind `MAX(id)`.

### Applying V4 to an existing database

`spring.flyway.enabled` is `false` and `ddl-auto` is `update`, so Hibernate adds
the new **columns** on startup but cannot change `IGTA`'s type, drop `NO_OF_PCS`,
create the unique indexes and check constraints, or seed the menu row. Run the
migration by hand:

```bash
psql -h localhost -U pharma -d minipos_db -f backend/src/main/resources/db/migration/V4__product_master.sql
```

It is idempotent and safe to re-run.

---

## 7. Build note

`pom.xml` sets `<proc>full</proc>`, which emits `-proc:full` — a flag that only
exists on **JDK 21+**. Maven on this machine defaults to JDK 18, so
`mvn compile` fails with `invalid flag: -proc:full` before reaching any source.

```bash
JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.10.7-hotspot" mvn test
```

This predates Product Master. Either set `JAVA_HOME` permanently or drop the
`<proc>full</proc>` line — `annotationProcessorPaths` already handles Lombok.

---

## 8. Pre-existing issues this module did not fix

Out of scope, but they affect Product Master's neighbours:

1. **`apiClient.unwrapPagination` does not match `PageResponse`.** It reads
   `currentPage`/`pageSize`/`hasNext`/`hasPrevious`; the backend emits
   `page`/`size`/`first`/`last`. Every caller silently gets page 0, size 20.
2. **Oracle SQL against PostgreSQL.** `SYSDATE`, `NVL`, `FROM DUAL` in
   `ReportServiceImpl`, `DashboardServiceImpl`, `AiServiceImpl`, and
   `V1__baseline_schema.sql`.
3. **SQL injection** in `ReportServiceImpl.currentStock` — `category`, `from`
   and `to` are concatenated into native SQL.
4. **`NoOpPasswordEncoder`** in `SecurityConfig` — passwords stored in plaintext.
5. **62 endpoints accept `?orgId`** from the client across 11 controllers.
   Product Master takes none; the others still do.
