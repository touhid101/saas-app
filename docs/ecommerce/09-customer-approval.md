# 09 — Customer registration & approval

Phase 0 · existing state + the precedent to copy.

## 1. How customers are created today

| Aspect | Existing behaviour |
|---|---|
| Who creates | branch staff (`ROLE01`/`ROLE02`) via `POST /api/customers` (`MasterDataController` → `MasterDataServiceImpl`) or inline from `CustomerPicker.jsx` on the Sales / Quick Sales screens |
| Approval | **none** — the row is active (`STATUS='Y'`) on creation; `POST /api/customers/{id}/toggle-status` deactivates |
| Identity | no login, no password, no e-mail verification; `MOBILE_NO` optional (V50) and unique per branch (V35) |
| Type | `CUSTOMER_TYPE` CHECK (Retailer, Wholesaler, Hospital, Pharmacy, Clinic, Special), free choice by staff, no audit of changes, no permission distinct from "edit customer" |
| Scope | `PID_ORG`, `PID_COMPANY`, `PID_BRANCH` **NOT NULL** — the customer belongs to one branch of one tenant |
| Credit | `CREDIT_LIMIT`, `OPENING_BAL` (ledger via `ACC_*` parties) |

**Conclusion:** there is no customer approval workflow to reuse. What exists is a
*branch-owned trade customer master*, exactly what RULE 1 says the e-commerce customer must not be.

## 2. The precedent that does exist: tenant registration

`TENANT_REGISTRATION` (`REG_STATUS` PENDING / APPROVED / REJECTED, `REJECT_REASON`,
`REVIEWED_BY`, `REVIEWED_AT`, password hashed on arrival, no side effects until approval) with
`POST /api/public/register` (public) and `AdminOnboardingController` (approve / reject / activate
in one transaction). This is the pattern the customer flow should mirror: a **public registration
writes one pending row and nothing else**; approval is an explicit, audited staff action; only an
approved identity can log in and order.

## 3. Required behaviour (from the brief, mapped to the ERP)

| Step | Design (Phase 1) | ERP touchpoint |
|---|---|---|
| Register | `POST /api/ecom/auth/register` → `ECOM_CUSTOMER` (`APPROVAL_STATUS='PENDING'`) + `ECOM_CUSTOMER_AUTH` (`{bcrypt}` via the existing `PasswordEncoder` bean) + optional first `ECOM_CUSTOMER_ADDRESS` + registration location | none |
| Verify | phone/e-mail OTP — **no SMS/mail provider exists in the codebase**; Phase 1 ships `PHONE_VERIFIED='N'` and a pluggable verifier interface; provider choice is a **BUSINESS/INFRA CONFIRMATION** |  |
| Login before approval | allowed to see "pending" status page only; API refuses browsing prices as an approved tier and refuses cart/checkout (`CUSTOMER_NOT_APPROVED`) | |
| Approve / reject / suspend | staff console (new module MOD5x "E-Commerce Customers") — **platform-level** (the customer is global, so approval is a platform operator action, ROLE01 in org 1; optionally delegated) | new `POS_PERMISSIONS` module row; `SUB_AUDIT`/`ECOM_AUDIT_LOG` event |
| Set / change customer type | separate right (`canApprove` on the module, or a dedicated MOD) — RULE: not any branch staff; every change → `ECOM_CUSTOMER_TYPE_HISTORY`, propagated to shadow rows (`04` §5) | |
| Suspension | `APPROVAL_STATUS='SUSPENDED'` → login allowed, ordering refused; existing orders unaffected | |

## 4. Open business decisions (marked, not invented)

* Which document(s) justify WHOLESALER / SPECIAL (drug licence? trade licence?) and who verifies.
* Default type on registration (RETAILER assumed, pending approval to upgrade).
* Whether guests may browse retail prices before registering (§50).
* Whether a branch may see the global customer's profile beyond what its own orders show
  (recommendation: name, phone, delivery address of *its* orders only).

## 5. A defect the Phase 13 work walked into

The console list (`GET /api/ecom/admin/customers`) answered **500** whenever no search term was
typed — which is every time the screen opens. The query guarded the term with `:q IS NULL OR …`
and then used it inside `CONCAT('%', :q, '%')`; a null parameter reaches PostgreSQL with no type,
the concatenation resolves to `bytea`, and `lower(bytea)` does not exist. Typing anything into
the search box made the screen work again, which is why it survived.

The guard is now `:q = ''` and the service sends an empty string rather than null. Recorded here
rather than quietly fixed because the pattern is elsewhere too: any JPQL that tests a parameter
for null and then concatenates it has the same trap.
