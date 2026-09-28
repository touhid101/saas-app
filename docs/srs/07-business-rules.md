# Phase 7 — Business Rules

> Friends Pharma ERP · SRS v2.0 · 9 September 2026

30 rules extracted from validation, service logic, database constraints and permission checks.

| ID | Business rule | Enforced at | Note | Status |
|---|---|---|---|---|
| BR-001 | A purchase line must have a quantity greater than zero, unless it is a free gift. | Service (PurchaseServiceImpl.requireLineShape) | Refusal names the fix: "mark the line as a gift" | IMPLEMENTED |
| BR-002 | A free-gift line carries no purchase quantity and must carry a bonus. | Service + DB check constraint CK_TRAN_DTL_QUANTITY (V70) | Both kinds enforced per LINE_KIND | IMPLEMENTED |
| BR-003 | A supplier represents one manufacturer; only that maker's products may be purchased from them. | Service (requireLinesFromSupplier) | Enforced server-side, not merely hidden in the picker | IMPLEMENTED |
| BR-004 | A free gift is the one exception to BR-003 — a supplier may give away another maker's product. | Service | Gift lines skip the manufacturer test | IMPLEMENTED |
| BR-005 | Cashback may not exceed the invoice value. | Service (CASHBACK_EXCEEDS_INVOICE) |  | IMPLEMENTED |
| BR-006 | An approved invoice cannot be edited; correction is by purchase return. | Service (PURCHASE_ALREADY_APPROVED) | Stock has already moved | IMPLEMENTED |
| BR-007 | Stock may not be received at a cost per piece above its MRP per piece. | Service (requireCostBelowMrp) | Refused at approval, stating the multiple | IMPLEMENTED |
| BR-008 | Stock is issued first-expiry-first-out. | Service (StockPostingServiceImpl) | Sales and sales returns | IMPLEMENTED |
| BR-009 | The cost of a sale is the batch's cost at the moment of sale, not the product's current cost. | Stock movement snapshot | Re-pricing cannot retrospectively change past profit | IMPLEMENTED |
| BR-010 | Editing a product creates a new version and retires the previous one. | Service (ProductServiceImpl) | Transactions keep pointing at what was actually traded | IMPLEMENTED |
| BR-011 | A superseded product version cannot be edited. | Service (PRODUCT_VERSION_SUPERSEDED) | Editing it would fork the version chain | IMPLEMENTED |
| BR-012 | A product name is unique within a branch. | Service | Shared (null-branch) rows form their own list | IMPLEMENTED |
| BR-013 | SKU is optional and NOT unique. | Service + DB (V72 dropped the unique index) | Business decision, 2026-09-05. Barcode remains unique | IMPLEMENTED |
| BR-014 | A barcode is unique per branch, among active products. | DB unique index UQ_PRODUCT_BRANCH_BARCODE |  | IMPLEMENTED |
| BR-015 | A user may never read or write another organisation's data. | Service + JWT | Cross-tenant ids return 404, not 403 | IMPLEMENTED |
| BR-016 | Acting as another branch requires platform-operator status (ROLE01 AND platform organisation). | ActingBranchFilter + BranchScopeService |  | IMPLEMENTED |
| BR-017 | A tenant past expiry and grace is refused, outside an administration allow list. | TenantStatusFilter | SUSPENDED is refused immediately regardless of dates | IMPLEMENTED |
| BR-018 | A stored password can never be displayed; only its state is reported. | PasswordAdminService | BCrypt is one-way by construction | IMPLEMENTED |
| BR-019 | A Super Admin may not reset their own password, nor another platform operator's. | PasswordAdminService | Both refusals are recorded | IMPLEMENTED |
| BR-020 | An account holding a temporary password may reach only self-read and self-change. | ForcedPasswordChangeFilter | Enforced server-side, not by the screen | IMPLEMENTED |
| BR-021 | A temporary password expires; an expired one is refused outright. | Filter + TEMP_PASSWORD_EXPIRES |  | IMPLEMENTED |
| BR-022 | Who may cancel a collection, or write a discount rule, is the permission matrix — not a role named in code. | CollectionServiceImpl, SalesDiscountServiceImpl | Corrected 2026-09-06 (GAP-01) | IMPLEMENTED |
| BR-023 | Approving or rejecting a requisition keys on the Approve tick for Requisition. | RequisitionServiceImpl | Screen and server read the same matrix | IMPLEMENTED |
| BR-024 | A requisition may be edited only in DRAFT or SUBMITTED. | RequisitionServiceImpl |  | IMPLEMENTED |
| BR-025 | Report totals cover the whole result set, never the page on screen. | ReportGridServiceImpl | A footer summing visible rows looks like an answer and is not | IMPLEMENTED |
| BR-026 | Supplier outstanding is a position as at today and is never date-filtered. | V_SUPPLIER_ACCOUNT_BRANCH | Narrowing it would drop older unpaid bills | IMPLEMENTED |
| BR-027 | Only approved documents reach a financial report. | Views filter STATUS = 'A' |  | IMPLEMENTED |
| BR-028 | A purchase return reduces supplier payable only when its payment type is ADJUSTMENT. | V_PURCHASE_PAYABLE | Business meaning unresolved — see GAP-04 | REQUIRES BUSINESS CONFIRMATION |
| BR-029 | A generic is the drug; the manufacturer belongs to the product, not the generic. | MasterData (field removed 2026-09-09) | 0 of 27 generics ever carried one | IMPLEMENTED |
| BR-030 | Money is BigDecimal at scale 2, HALF_UP. No floating point anywhere. | All four calculators |  | IMPLEMENTED |
