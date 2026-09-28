# 02 — Actors and Roles

> Friends Pharma Platform · Platform SRS v1.0 · 9 September 2026

**Status vocabulary** — EXISTS · PARTIALLY EXISTS · MISSING · NEEDS MODIFICATION · NEW PLATFORM CAPABILITY · PLANNED · RECOMMENDED · BUSINESS DECISION REQUIRED · BUSINESS / LEGAL CONFIRMATION REQUIRED. These are never mixed.

| Actor | Status | What exists today | What the platform adds |
|---|---|---|---|
| Platform Administrator | EXISTS | Super Admin in the platform organisation. Runs the approval queue, plans, subscriptions, global catalogue and platform KPIs today. | Add: pharmacy verification, commission configuration, dispute handling, supplier and consumer administration. |
| Pharmacy Owner | PARTIALLY EXISTS | Administrator (Branch) — ROLE02 — has full control of one pharmacy: staff, products, inventory, purchase, sales, reports. | No storefront profile, no marketplace order queue, no settlement view. The role exists; the marketplace surface does not. |
| Pharmacy Manager | PARTIALLY EXISTS | Not a distinct role. The nine roles are functional (Pharmacist, Accounts, Purchase Manager, Store Keeper, Sales Manager) and the per-branch matrix can express a manager. | Either add a role or document that Administrator (Branch) is the manager. |
| Pharmacy Staff | EXISTS | Six operational roles gated by a per-branch View/Create/Edit/Delete/Approve/Export/Print matrix. | Reusable as-is; marketplace order handling becomes a new module in the same matrix. |
| Customer (consumer) | MISSING | Exists only as a record a pharmacy keeps. No login, no self-service, no app. | Entirely new: identity, addresses, orders, payments, reviews. |
| Supplier / Distributor | PARTIALLY EXISTS | POS_SUPPLIER_INFO is a pharmacy's own supplier record, tied to one manufacturer, used for purchasing and payables. | A supplier as a platform participant with its own login, wholesale catalogue and order queue does not exist. |
| Delivery Partner | MISSING | A delivery man's name is attached to a collection record. There is no courier identity, assignment or tracking. | New actor. |
| Finance / Admin | PARTIALLY EXISTS | Accounts (ROLE04) handles supplier payment and collection within a pharmacy. | Platform-level finance — commission, settlement, payouts — does not exist. |
| Support Team | MISSING | No support role, ticket, dispute or case object. | New actor. |

## Note on the customer

The distinction matters more than it looks. `ACL_USER` is a **staff** model: it belongs to one organisation and one branch, and every authorisation rule in the system reads that. A consumer belongs to the platform and orders from many pharmacies. Reusing the staff table for consumers would put the tenancy model — the part of this system that is genuinely sound — under strain it was not designed for. A separate consumer identity domain is the recommendation.
