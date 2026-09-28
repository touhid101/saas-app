# 07 — Safety and Compliance

> Friends Pharma Platform · Platform SRS v1.0 · 9 September 2026

**Status vocabulary** — EXISTS · PARTIALLY EXISTS · MISSING · NEEDS MODIFICATION · NEW PLATFORM CAPABILITY · PLANNED · RECOMMENDED · BUSINESS DECISION REQUIRED · BUSINESS / LEGAL CONFIRMATION REQUIRED. These are never mixed.

> This section names where the platform must be ABLE to enforce a control. It does not state what the law requires. Bangladesh pharmacy regulation — which schedules need a prescription, what a dispensing record must contain, whether a licensed pharmacist must approve a remote sale, and whether medicines may be delivered by a non-pharmacist courier at all — must be confirmed with a qualified adviser before any of this is designed. The recommendation is that every control be configurable rather than hard-coded, so the answer can change without a redeployment.

| Control | Status | Evidence |
|---|---|---|
| Prescription flag on a product | EXISTS | GLOBAL_PRODUCT.PRESCRIPTION_FLAG; 9 of 29 global products carry it. POS_PRODUCT_INFO carries PRESCRIPTION_FLAG too. |
| Any workflow that reads the flag | MISSING | Nothing blocks, warns or requires anything on the strength of it. |
| Prescription upload | MISSING | File storage is proven for images; no prescription document type. |
| Pharmacist verification step | MISSING | No dispensing check, no pharmacist sign-off. |
| Restricted / controlled substance handling | MISSING | No schedule, no register, no quantity ceiling. |
| Customer identity capture for restricted sale | MISSING | — |
| Batch and expiry traceability | EXISTS | Every movement carries batch and expiry; FEFO is enforced. A recall can be traced. |
| Sale of expired stock | PARTIALLY EXISTS | Expiry is tracked and alerted; no rule refuses issuing an expired batch. |
| Audit trail | PARTIALLY EXISTS | Credential, subscription and invoice-unlock changes are attributed. Purchase approval records no actor. |

## Recommendation

Make every control configurable per platform and per pharmacy rather than hard-coded: whether a prescription is required for a given schedule, whether a pharmacist must approve before dispatch, what quantity ceiling applies, and what must be recorded about the customer. The regulatory answer may differ by product, by district, and over time; a coded rule would need a release each time it changed.
