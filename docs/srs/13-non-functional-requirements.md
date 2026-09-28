# Phase 13 — Non-Functional Requirements

> Friends Pharma ERP · SRS v2.0 · 9 September 2026

| ID | Requirement | Evidence / target | Status | Note |
|---|---|---|---|---|
| NFR-01 | Money precision | All money BigDecimal, scale 2, HALF_UP. No floating point. | IMPLEMENTED |  |
| NFR-02 | Tenant isolation | No endpoint returns another organisation's data; cross-tenant ids return 404. | IMPLEMENTED |  |
| NFR-03 | Auditability | Credential, subscription and invoice-unlock changes attributable to actor and time. | IMPLEMENTED |  |
| NFR-04 | Report responsiveness | Results truncate with a stated count rather than time out. | IMPLEMENTED |  |
| NFR-05 | POS speed | Line entry without the mouse; Enter walks Product → Qty → TP. | IMPLEMENTED |  |
| NFR-06 | Responsive UI | Breakpoints 767.98 / 1199.98 shared by the two entry screens. | IMPLEMENTED |  |
| NFR-07 | Password storage | One-way hash, BCrypt strength 12. | PARTIALLY IMPLEMENTED | 8 of 25 accounts still hold plaintext — GAP-03 |
| NFR-08 | Error handling | Consistent envelope: success, message, errorCode, timestamp, field map. | IMPLEMENTED |  |
| NFR-09 | Automated tests | 24 classes, 297 tests, all passing. | IMPLEMENTED |  |
| NFR-10 | Availability / SLA | Not defined. No health-based restart policy documented. | RECOMMENDED |  |
| NFR-11 | Backup and recovery | No documented RPO or RTO; no rehearsed restore. | RECOMMENDED |  |
| NFR-12 | Frontend bundle | A single ~1.08 MB chunk; no code splitting. | RECOMMENDED |  |
| NFR-13 | Accessibility | Focus rings, ARIA labels, 40–48px touch targets. No formal WCAG audit. | PARTIALLY IMPLEMENTED |  |
| NFR-14 | Concurrency | No optimistic lock on POS_TRANSACTION_MST. | RECOMMENDED |  |
