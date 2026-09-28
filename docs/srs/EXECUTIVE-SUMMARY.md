# Executive Summary

**Friends Pharma ERP** — Multi-Tenant Pharmacy POS, Inventory & Trading Platform

9 September 2026

## What the system does

One installation runs many independent pharmacies. Each pharmacy may run several branches, and each branch trades on its own: its own stock, its own invoices, its own staff and permissions. The platform covers the full trading cycle — buying from suppliers, holding stock by batch and expiry, selling at the counter, taking returns in both directions, delivering and collecting cash, and reporting on all of it.

## The business problem it solves

A pharmacy's margin lives in details that are easy to lose: a supplier's free goods, a cashback at quarter end, a batch bought cheaply and sold after a price rise. Recorded loosely, none of it reaches the accounts. This system makes every one of them reduce the recorded cost of the goods that actually arrived, so profit reported is profit earned.

## Business capabilities

- **Authentication & Session** — Sign in, token refresh, own profile and password, forced change
- **Access Control** — Roles, per-branch module rights, password administration
- **Product & Catalogue** — Branch product master, global catalogue, versioning, bulk import
- **Purchasing** — Supplier invoice, approval, bonus, gift, cashback, supplier scoping
- **Purchase Return & Credit** — Return goods to a supplier and raise credit against payable
- **Supplier Settlement** — Payment against invoices, advances, allocation, ageing
- **Inventory** — Batches, FEFO issue, movement ledger, valuation, expiry
- **Sales & POS** — Full sales screen, fast till, returns, invoice merge, discounts
- **Fulfilment** — Delivery assignment and cash collection
- **Requisition** — Inter-branch stock request, approval, conversion
- **Reporting & Analytics** — 18 grid reports, dashboard, internal analytics assistant
- **Tenancy & Subscription** — Public sign-up, approval queue, plans, terms, invoices, the access gate
- **Reference Data** — Suppliers, customers, categories, units, areas, companies, generics
- **Notifications** — In-app notifications and scheduled operational alerts

## What is working well

- **Costing is sound.** Discounts, cashback and free goods all reach the cost per piece. Margin is fixed at the moment of sale and cannot be rewritten by a later price change.
- **Tenants are isolated.** One pharmacy cannot read another's data; a cross-tenant request is answered as though the record does not exist.
- **The counter is fast.** Purchase and sales entry are keyboard-first; a line can be entered without touching the mouse.
- **Sensitive actions are recorded.** Password resets, subscription changes and invoice unlocks all carry an actor and a timestamp.
- **297 automated tests pass.**

## Risks and gaps needing attention

- High — **GAP-02 API surface.** An integrator will find them in Swagger and trust them.
- High — **GAP-03 Security.** A database copy is a copy of those passwords.
- High — **GAP-04 Payables.** Supplier outstanding is overstated wherever goods were returned.
- Medium — **GAP-05 Tenancy.** A customer's own administrator could pass the gate for their expired pharmacy.
- Medium — **GAP-06 Product history.** A price changed by import loses history the UI would have kept.
- Medium — **GAP-07 Money rules.** A purchase and a sale of identical shape can differ by a paisa.
- Medium — **GAP-08 Sales discounts.** A documented capability that has never worked.
- Medium — **GAP-09 Configuration.** On an installation whose platform organisation is not 1, every platform-operator test silently fails.

## Two decisions only the business can make

1. **Which purchase returns reduce what a supplier is owed** (GAP-04). Today almost none do, so supplier balances are overstated wherever goods went back.
2. **Whether a sales discount rule belongs to a branch or to the whole pharmacy** (GAP-08). The feature has never worked because the two answers were never reconciled.

## Recommended next steps

| Priority | Action | Addresses |
|---|---|---|
| 1 | Decide the purchase-return credit rule | GAP-04 |
| 2 | Reset the 8 accounts still holding plaintext passwords | GAP-03 |
| 3 | Remove the 31 dead report endpoints | GAP-02 |
| 4 | Decide the discount scope, then fix the one line | GAP-08 |
| 5 | Set the platform organisation explicitly per environment | GAP-09 |
| 6 | Define backup, recovery and availability targets | NFR-10, NFR-11 |
