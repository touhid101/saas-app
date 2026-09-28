# Phase 3 — Users, Roles and Permissions

> Friends Pharma ERP · SRS v2.0 · 9 September 2026

## Roles

| Code | Role | Principal responsibility |
|---|---|---|
| ROLE01 | Super Admin | Everything; across tenants only when in the platform organisation |
| ROLE02 | Administrator (Branch) | Full control of one pharmacy |
| ROLE03 | Pharmacist | Sales, POS, sales return |
| ROLE04 | Accounts | Supplier payment, collection, finance reporting |
| ROLE05 | Purchase Manager | Purchase, purchase return, product |
| ROLE06 | Delivery Staff | Delivery and collection |
| ROLE07 | Store Keeper | Inventory, purchase return |
| ROLE08 | Viewer | Read only |
| ROLE09 | Sales Manager | Sales, targets, performance |

## How authorities are granted

`UserPrincipal.getAuthorities()` grants a Super Admin (or any ROLE01 holder) the authorities `ROLE_ADMIN`, `ROLE_SUPER_ADMIN` and `ROLE_ROLE01..ROLE_ROLE09`. **Every other user receives exactly one authority: `ROLE_<their code>`.**

An annotation naming a role code that no user is ever granted is therefore unreachable, and fails closed rather than loudly. This was the mechanism behind GAP-01.

## Platform operator

A platform operator is `isAdmin() AND the user's real organisation equals app.platform.org-id`. **Both halves are required** — the platform runs its own pharmacy, so being in the platform organisation alone does not make somebody an operator.

## Two layers of authorisation

Coarse access is the role annotation on the controller. Fine access is a per-branch permission matrix (`ACL_USER_PERMISSION` / `POS_PERMISSIONS`) read through `PermissionGuard`, gating View / Create / Edit / Delete / Approve / Export / Print per module.

| Module | Who holds rights today | Note |
|---|---|---|
| MOD03 Purchase Invoice | ROLE01, ROLE02, ROLE05 | Approve receives stock and locks the invoice |
| MOD04 Sales Invoice | ROLE01, ROLE02, ROLE03, ROLE09 | Approve issues stock FEFO |
| MOD08 Sales Discount% | ROLE01, ROLE02 full; ROLE09 add/edit only | Delete withheld from Sales Manager |
| MOD10 Collection | ROLE01, ROLE02 full; ROLE04 add/edit; ROLE06 add | Cancel requires Delete |
| MOD11 Requisition | Per matrix | Approve and Reject both key on the Approve tick |
| MOD01 Dashboard | Approve tick gates money | Anyone who can open it holds View, so View cannot gate takings |
