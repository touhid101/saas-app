# Phase 9 — Functional Requirements

> Friends Pharma ERP · SRS v2.0 · 9 September 2026

## FR-001 — Record a supplier purchase

**Module** M-04 · **Actor** Purchase Manager · **Status** IMPLEMENTED

### Requirement
The system shall let a buyer record a supplier invoice, including free bonus units, free gift products and invoice-level cashback, and shall compute every derived money value on the server.

### Business purpose
The purchase invoice is where cost enters the business. Everything downstream — valuation, margin, payables — is derived from it.

### Preconditions
A supplier with a manufacturer assigned; products on the branch list.

### Inputs
Supplier, date, costing basis, optional challan; per line: product, quantity, bonus, unit, TP%, discounts. Optionally a gift line and a cashback amount.

### Business rules
BR-001 … BR-007

### Outputs
A saved invoice with line totals, invoice totals and payable.

### Exceptions
Client-supplied calculated fields are refused (UNKNOWN_FIELD). Products from another maker are refused unless the line is a gift.

### Postconditions
Draft or Pending: nothing moves. Approved: stock received, invoice locked, payable raised.

### Implementation evidence
`Purchase.jsx · POST /api/purchases · PurchaseServiceImpl · PurchaseCalculator · POS_TRANSACTION_MST/DTL`

---

## FR-002 — Receive stock at a correct cost per piece

**Module** M-07 · **Actor** System (on approval) · **Status** IMPLEMENTED

### Requirement
On approval the system shall derive a cost per piece for every received line, spreading invoice discount, cashback and gift value, and shall refuse a cost above MRP.

### Business purpose
Valuation and margin are only as good as this figure.

### Preconditions
An approved purchase.

### Inputs
The invoice and its lines.

### Business rules
BR-007, BR-009

### Outputs
One batch per line with COST_PER_PCS; one inbound movement.

### Exceptions
A cost above MRP per piece refuses the approval, stating the multiple.

### Postconditions
Stock on hand rises; the batch cost is fixed for everything issued from it.

### Implementation evidence
`StockPostingServiceImpl (netLineTotal, CostAllocation, unitCost) · POS_STOCK_BATCH · POS_STOCK_MOVEMENT`

---

## FR-003 — Sell stock and fix its margin

**Module** M-08 · **Actor** Pharmacist · **Status** IMPLEMENTED

### Requirement
The system shall issue stock first-expiry-first-out and record the issuing batch's cost on each movement.

### Business purpose
Margin must reflect what the goods actually cost, and must not change when the product is re-priced.

### Preconditions
Stock on hand.

### Inputs
Cart lines, customer, payment.

### Business rules
BR-008, BR-009

### Outputs
A sales invoice and outbound movements carrying the cost snapshot.

### Exceptions
Insufficient stock refuses the checkout.

### Postconditions
Stock falls; margin is fixed.

### Implementation evidence
`QuickSales.jsx · /api/v1/pos · PosServiceImpl · SalesServiceImpl · StockPostingServiceImpl`

---

## FR-004 — Keep product history when a product changes

**Module** M-03 · **Actor** Product administrator · **Status** PARTIALLY IMPLEMENTED — The bulk import path still updates in place — GAP-06.

### Requirement
An edit to a product shall create a new version and retire the previous one, leaving past transactions pointing at what was actually traded.

### Business purpose
A price or pack change must not rewrite the meaning of last month's invoices.

### Preconditions
A live (not superseded) product.

### Inputs
The changed product fields.

### Business rules
BR-010, BR-011, BR-012

### Outputs
A new version; the previous row retired and inactive.

### Exceptions
Editing a superseded version is refused.

### Postconditions
Pickers show only the live version; history is intact.

### Implementation evidence
`ProductMaster.jsx · /api/products · ProductServiceImpl · PID_ROOT_PRODUCT / VERSION_NO / SUPERSEDED_BY`

---

## FR-005 — Administer a forgotten password safely

**Module** M-02 · **Actor** Platform operator · **Status** IMPLEMENTED

### Requirement
The system shall let a platform operator issue a temporary password, shall never display a stored password, shall force the holder to replace it, and shall record every reset and refusal.

### Business purpose
Support must be able to restore access without anyone learning a password.

### Preconditions
Caller is ROLE01 and in the platform organisation.

### Inputs
The target account.

### Business rules
BR-018 … BR-021

### Outputs
A 16-character temporary password shown once; an audit row.

### Exceptions
Self-reset and peer-operator reset refused and recorded.

### Postconditions
The account can do nothing but set its own password until it does.

### Implementation evidence
`PasswordAdmin.jsx · /api/admin/passwords · PasswordAdminService · ForcedPasswordChangeFilter · ACL_PASSWORD_RESET_AUDIT`

---

## FR-006 — Report what suppliers gave beyond the price

**Module** M-11 · **Actor** Buyer, management · **Status** IMPLEMENTED

### Requirement
The system shall report bonus units, gift units, gift value and cashback per supplier for a period, counting quantities in pieces and never valuing a bonus.

### Business purpose
Two suppliers may quote the same rate; what they give back is otherwise invisible.

### Preconditions
Approved purchases in the period.

### Inputs
Date range, branch, search.

### Business rules
BR-025, BR-027

### Outputs
Supplier, invoices, bonus pieces, gift pieces, gift value, cashback, billed, benefit %.

### Exceptions
A period with nothing returns an empty grid, not an error.

### Postconditions
None — read only.

### Implementation evidence
`Reports.jsx · GET /api/reports/supplier-benefit · ReportGridServiceImpl · SalesPurchaseReportRepository`

---

## FR-007 — Restrict a pharmacy whose subscription has lapsed

**Module** M-12 · **Actor** System · **Status** PARTIALLY IMPLEMENTED — The exemption tests isSuperAdmin() alone, without the organisation half — GAP-05.

### Requirement
The system shall refuse a tenant past expiry and grace, while keeping an administration path open.

### Business purpose
A commercial control that must not lock the operator out of fixing it.

### Preconditions
A subscription with an expiry.

### Inputs
The tenant of the session.

### Business rules
BR-017

### Outputs
A refusal naming the expiry, or normal service.

### Exceptions
SUSPENDED is refused regardless of dates.

### Postconditions
None.

### Implementation evidence
`TenantStatusFilter · TenantStatusService · SUB_SUBSCRIPTION`
