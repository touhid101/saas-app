# Phase 8 — Business Workflows

> Friends Pharma ERP · SRS v2.0 · 9 September 2026

## WF-01 — Purchase to Stock

**Actors** Purchase Manager, Branch Administrator (approval)

**Trigger** A supplier invoice arrives with the goods

**Preconditions** Supplier exists and has a manufacturer assigned; products exist on the branch list

### Main flow

1. Buyer selects the supplier. Their manufacturer decides which products may be added.
2. Lines are entered — quantity, bonus, unit, TP%, discounts. A gift line carries quantity 0 and a bonus.
3. The server ignores any client-supplied price and snapshots price from the product master.
4. Line totals are computed (CALC-02 / CALC-03), then the invoice roll-up (CALC-05).
5. Saved as Draft, submitted as Pending, or created directly Approved by a user holding Approve.
6. On approval: header allocations reach the lines (CALC-06), gifts take their share (CALC-07), cost per piece is derived (CALC-08) and checked against MRP (CALC-09).
7. A batch row and an inbound movement are written; the batch carries COST_PER_PCS.
8. The invoice becomes payable and appears in V_PURCHASE_PAYABLE.

### Failure

Validation refuses with a message naming the fix. Nothing is written; the transaction rolls back.

### Postconditions

Stock on hand rises; supplier payable rises; the invoice is locked against editing.

```mermaid
flowchart TD
  A[Select supplier] --> B[Add lines]
  B --> C{Line shape valid?}
  C -->|No| C1[Refuse: name the fix]
  C -->|Yes| D{Products from this maker?}
  D -->|No, and not a gift| D1[Refuse: PRODUCT_NOT_FROM_SUPPLIER]
  D -->|Yes| E[Compute line totals and invoice]
  E --> F{Status}
  F -->|Draft| G[Editable]
  F -->|Pending| H[Await approval]
  F -->|Approved| I[Receive stock]
  H --> I
  I --> J{Cost per piece <= MRP?}
  J -->|No| J1[Refuse at approval]
  J -->|Yes| K[Write batch + movement]
  K --> L[Payable]
```

---

## WF-02 — Sale to Margin

**Actors** Pharmacist, Sales Manager

**Trigger** A customer presents items at the counter

**Preconditions** Stock on hand exists in at least one batch

### Main flow

1. Items are added to a cart, by scan or search.
2. Unit price derives from MRP and the sales percentage (CALC-10).
3. On checkout, stock is issued FEFO — earliest expiry first.
4. Each outbound movement copies the batch's COST_PER_PCS, fixing the cost of that sale.
5. Revenue less that cost is gross profit (CALC-11).

### Failure

Insufficient stock, or no permission to sell, refuses the checkout. The cart is preserved.

### Postconditions

Stock falls; revenue and receivable rise; margin is fixed and cannot be changed by re-pricing.

```mermaid
flowchart TD
  A[Add to cart] --> B[Price from MRP and sales %]
  B --> C{Charge}
  C -->|No stock| C1[Refuse]
  C -->|OK| D[Issue FEFO across batches]
  D --> E[Copy batch cost onto each movement]
  E --> F[Invoice raised]
  F --> G[Margin = revenue - snapshot cost]
```

---

## WF-03 — Supplier Settlement

**Actors** Accounts

**Trigger** A payment is due or a supplier is paid

**Preconditions** An approved purchase exists

### Main flow

1. Payment is recorded against one or more invoices, or held as an unallocated advance.
2. V_SUPPLIER_PAYMENT_LINE splits it into INVOICE and MANUAL sources.
3. V_PURCHASE_PAYABLE nets billed against paid and return credit (CALC-13).
4. V_SUPPLIER_ACCOUNT_BRANCH aggregates per supplier per branch.

### Failure

Overpayment beyond tolerance is refused or held as an advance.

### Postconditions

Outstanding falls; advances are carried until recovered.

```mermaid
flowchart LR
  A[Payment] --> B{Allocated?}
  B -->|To invoices| C[APPLIED against V_PURCHASE_PAYABLE]
  B -->|Unallocated| D[ADVANCE_AMOUNT held]
  C --> E[Outstanding falls]
  D --> F[Recovered later]
```

---

## WF-04 — Inter-Branch Requisition

**Actors** Requesting branch, approving branch

**Trigger** A branch is short of stock another holds

**Preconditions** Both branches belong to the same organisation

### Main flow

1. A requisition is raised in DRAFT and lines are added.
2. It is SUBMITTED for approval.
3. A user holding Approve on Requisition approves or rejects it.
4. An approved requisition is CONVERTED into the movement that fulfils it.

### Failure

Rejection sets REJECTED with a reason; the stock never moves.

### Postconditions

CONVERTED requisitions are closed and cannot be edited.

```mermaid
flowchart TD
  A[DRAFT] --> B[SUBMITTED]
  B --> C{Approve right?}
  C -->|No| C1[403 APPROVE_NOT_PERMITTED]
  C -->|Reject| D[REJECTED]
  C -->|Approve| E[APPROVED]
  E --> F[CONVERTED]
  A --> G[CANCELLED]
```

---

## WF-05 — Password Reset

**Actors** Platform operator, account holder

**Trigger** A user cannot sign in

**Preconditions** The caller is ROLE01 and in the platform organisation

### Main flow

1. The operator finds the account. Credential state is shown; the password is not, and cannot be.
2. Reset is confirmed against the account's name, role and branch.
3. A 16-character temporary password is generated (CALC-15), hashed, and shown exactly once.
4. MUST_CHANGE_PASSWORD is set with an expiry; an audit row is written in its own transaction.
5. The account can then reach only self-read and self-change until it sets its own password.

### Failure

Self-reset and operator-to-operator reset are refused, and the refusal is recorded.

### Postconditions

The old password stops working immediately; the trail records who did what, when and from where.

```mermaid
flowchart TD
  A[Find account] --> B{Resettable?}
  B -->|Self or peer operator| B1[Refuse + record]
  B -->|Yes| C[Generate temporary password]
  C --> D[Store hash only; show once]
  D --> E[Set MUST_CHANGE + expiry]
  E --> F[Audit row, own transaction]
  F --> G[Account limited to self-change]
  G --> H[User sets own password]
  H --> I[Flag cleared]
```

---

## WF-06 — Tenant Onboarding

**Actors** Applicant, platform operator

**Trigger** A pharmacy applies to use the platform

**Preconditions** None — the sign-up endpoint is public

### Main flow

1. A pharmacy applies. Nothing is provisioned; one row enters a queue.
2. A platform operator reviews and approves with a plan, term and permission template.
3. Tenant, branch, roles and permissions are provisioned in one transaction.
4. The subscription sets an expiry; the tenancy filter admits or refuses on it thereafter.

### Failure

Rejection leaves the application closed and nothing provisioned.

### Postconditions

The pharmacy can trade until expiry plus grace.

```mermaid
flowchart TD
  A[Public sign-up] --> B[PENDING queue]
  B --> C{Operator decision}
  C -->|Reject| D[REJECTED]
  C -->|Approve| E[Provision tenant, branch, roles, permissions]
  E --> F[ACTIVE with expiry]
  F --> G{Past expiry?}
  G -->|Within grace| H[Trading, warned]
  G -->|Past grace| I[EXPIRED - refused]
```
