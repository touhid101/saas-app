# Phase 6 — Business Data Model

> Friends Pharma ERP · SRS v2.0 · 9 September 2026

PostgreSQL, schema `pharma`: 64 base tables, 8 views, 63 mapped entities.

## Core entities

| Table | Business name | Business significance |
|---|---|---|
| POS_TRANSACTION_MST | Trading Document Header | Purchases (PU), sales (SL) and both returns (PR/SR) in one table, told apart by TRANSACTION_TYPE. PID_SUP_CUST points at a supplier or a customer depending on that type. STATUS: D draft, P pending, A approved, C cancelled. CASHBACK_AMOUNT, AMOUNT_TOBE_PAID, PID_TRAN_MST_RETURN. |
| POS_TRANSACTION_DTL | Document Line Item | QUANTITY, BONUS_QTY, LINE_KIND (PURCHASE or GIFT), MNP_PRICE, MRP_PRICE, IGTA, TP_PCT, DISCOUNT_PCT, FLAT_DISCOUNT, TOTAL_PRICE, NO_OF_PCS, SALES_TYPE, BATCH_NO, EXPIRY_DATE. |
| POS_STOCK_BATCH | Stock Lot | One lot of one product at one branch. QTY_PCS, RECEIVED_PCS, ISSUED_PCS, COST_PER_PCS, MRP_PRICE, EXPIRY_DATE, SOURCE_TRAN_MST. |
| POS_STOCK_MOVEMENT | Stock Ledger Entry | Every movement in or out, with the cost snapshot that fixes margin at the moment of sale. |
| POS_PRODUCT_INFO | Branch Product Master | Pricing (MRP, TP, IGTA, BUY, VAT, conversion rate), stock ladder, and the versioning chain PID_ROOT_PRODUCT / VERSION_NO / SUPERSEDED_BY. |
| POS_SUPPLIER_INFO | Supplier | POS_PID_COMPANY is the manufacturer they represent and governs what may be bought from them. PID_COMPANY is the tenant's own company — a different thing. |
| POS_SUPPLIER_PAYMENT | Supplier Payment / Advance | AMOUNT, ADVANCE_AMOUNT, PAYMENT_MODE, ENTRY_TYPE, optional PID_TRAN_MST. |
| POS_REQUISITION_MST/DTL | Inter-Branch Requisition | STAGE: DRAFT, SUBMITTED, APPROVED, REJECTED, CONVERTED, CANCELLED. |
| ACL_USER | User Account | PASSWORD_HASH, MUST_CHANGE_PASSWORD, TEMP_PASSWORD_EXPIRES, PID_ROLE, PID_BRANCH, PID_ORG. |
| ACL_PASSWORD_RESET_AUDIT | Password Reset Trail | Actor, target, branch, time, address and outcome — refusals recorded as well as resets. |
| SUB_PLAN / SUB_TERM / SUB_SUBSCRIPTION / SUB_INVOICE / SUB_PAYMENT | Tenancy Commercials | Plan, term, expiry, grace, invoice and payment. |

## Views

Reporting and settlement read views rather than recomputing, so a screen and its report cannot disagree.

| View | Answers |
|---|---|
| V_PURCHASE_PAYABLE | Per approved purchase: BILLED, PAID_AT_INVOICE, PAID_LATER, RETURN_CREDIT, DUE, AGE_DAYS |
| V_SUPPLIER_PAYMENT_LINE | Every payment line, INVOICE or MANUAL source, with APPLIED and ADVANCE_AMOUNT |
| V_SUPPLIER_ACCOUNT | Per supplier per organisation: outstanding, advance, net position |
| V_SUPPLIER_ACCOUNT_BRANCH | The same grained per branch — the one every screen must use |
| V_PRODUCT_LAST_PURCHASE | Most recent purchase price per product |
| V_SUPPLIER_PRODUCT | Which products a supplier may sell, from their manufacturer |
| V_INVOICE_PAYMENT_MISMATCH | Reconciliation: invoices whose payments do not agree |
| V_INVOICE_UNLOCK_LOG | Who unlocked an approved invoice, and when |

## The single-table transaction model

`POS_TRANSACTION_MST` holds purchases (PU), sales (SL), purchase returns (PR) and sales returns (SR), distinguished by `TRANSACTION_TYPE`, with `PID_SUP_CUST` pointing at either a supplier or a customer depending on that type.

**Consequence for query authors:** every query against this table must filter on `TRANSACTION_TYPE`. Omitting it silently mixes purchases with sales, and both with their returns.
