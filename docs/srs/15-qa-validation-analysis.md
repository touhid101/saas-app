# Phase 15 — QA and Validation Analysis

> Friends Pharma ERP · SRS v2.0 · 9 September 2026

## Where each rule lives

| Layer | Enforces | Example |
|---|---|---|
| Bean validation | Field shape: required, range, digits | @DecimalMin on quantity |
| Request guard | Rejecting calculated fields from the client | UNKNOWN_FIELD on mrpPrice |
| Service | Rules needing context an annotation cannot see | Per-kind line shape; supplier scoping |
| Database | Invariants that must hold whatever the path | CK_TRAN_DTL_QUANTITY; NOT NULL; unique indexes |

> A rule that depends on another field cannot live in a bean annotation, because the annotation cannot see it. Quantity is the worked example: @NotNull refused the gift line it was meant to allow, because LINE_KIND is not visible from the annotation. Presence is now checked per kind in the service.

## Test scenarios

297 automated tests across 24 classes pass today.

| ID | Area | Scenario | Coverage |
|---|---|---|---|
| TS-01 | Purchase | A product from another manufacturer is refused, including by direct API call | Covered |
| TS-02 | Purchase | A gift line with an empty quantity and a bonus is accepted and stored as 0 | Covered |
| TS-03 | Purchase | 100 bought + 10 bonus at net 1,073.99 yields 110 pieces at 9.7636 | Covered |
| TS-04 | Purchase | Cost above MRP per piece refuses approval | CostAboveMrpTest |
| TS-05 | Inventory | Two batches: the earlier expiry is issued first | Covered |
| TS-06 | Inventory | Re-pricing a product does not change a recorded margin | LandedCostTest, ReturnCostTest |
| TS-07 | Product | An edit creates a version; the superseded one cannot be edited | ProductServiceImplTest |
| TS-08 | Product | Two products may share a SKU; a barcode may not repeat | Covered (V72) |
| TS-09 | Security | No password or hash appears in any payload | Covered |
| TS-10 | Security | An account with a temporary password is refused every other endpoint | Covered |
| TS-11 | Security | A non-platform ROLE01 is refused the account list | GrantedAuthorityTest, PermissionResolverTest |
| TS-12 | Reporting | A branch filter never leaks another branch's rows | ReportBranchScopeTest, SaleBranchScopeTest |
| TS-13 | Settlement | Billed 1,283.33 less 500 paid shows 783.33 outstanding | SettlementToleranceTest |
| TS-14 | Settlement | A non-ADJUSTMENT return does not reduce payable | FAILS by design — GAP-04 |
| TS-15 | Tenancy | A subscription past grace refuses every gated endpoint | Covered |
| TS-16 | Concurrency | One invoice cannot be approved twice concurrently | NOT COVERED — GAP / NFR-14 |
