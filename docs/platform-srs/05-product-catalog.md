# 05 — Product Catalogue

> Friends Pharma Platform · Platform SRS v1.0 · 9 September 2026

**Status vocabulary** — EXISTS · PARTIALLY EXISTS · MISSING · NEEDS MODIFICATION · NEW PLATFORM CAPABILITY · PLANNED · RECOMMENDED · BUSINESS DECISION REQUIRED · BUSINESS / LEGAL CONFIRMATION REQUIRED. These are never mixed.

The two-tier model the marketplace needs — a platform master plus a per-pharmacy selection — **already exists and is in use**.

## Platform level

| Element | Status | Detail |
|---|---|---|
| GLOBAL_PRODUCT | EXISTS | name, generic, company, category, sub-category, strength, unit, pcs/leaf, leaf/box, barcode, image, suggested MRP and TP, prescription flag, product type |
| GLOBAL_GENERIC / GLOBAL_COMPANY / GLOBAL_CATEGORY / GLOBAL_UNIT / GLOBAL_BOX / GLOBAL_LEAF | EXISTS | Platform reference data behind the product master |
| Product description / usage / warnings | MISSING | A consumer product page needs more than a name and a strength |
| Product images at consumer quality | PARTIALLY EXISTS | One image per global product; no gallery |

## Pharmacy level

| Element | Status | Detail |
|---|---|---|
| Which products this branch sells | EXISTS | POS_PRODUCT_INFO per branch, imported from the global catalogue |
| Selling price | EXISTS | MRP and sales percentage per product |
| Stock | EXISTS | Batch ledger with FEFO, expiry and cost per piece |
| Minimum / maximum / reorder / safety stock | EXISTS | Full stock ladder with an ordering constraint |
| Product versioning | EXISTS | Edits create a version; history is preserved |
| Marketplace visibility (sell online yes/no) | MISSING | A pharmacy must be able to stock something without listing it |
| Maximum order quantity per customer | MISSING | Needed for controlled or scarce items |
| Promotion / offer price | MISSING | Sales discount tiers exist in table form but the feature has never worked — 0 rows |

## The three additions that make it a marketplace catalogue

1. **Sell online (yes/no) per branch product.** A pharmacy must be able to stock something without listing it publicly. Default off.
2. **Maximum order quantity per customer.** Needed for scarce or controlled items.
3. **Consumer-grade product content.** A description, usage and warnings — a consumer page cannot be a name, a strength and a price.
