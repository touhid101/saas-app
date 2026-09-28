# 13 — Master Data Model

> Friends Pharma Platform · Platform SRS v1.0 · 9 September 2026

**Status vocabulary** — EXISTS · PARTIALLY EXISTS · MISSING · NEEDS MODIFICATION · NEW PLATFORM CAPABILITY · PLANNED · RECOMMENDED · BUSINESS DECISION REQUIRED · BUSINESS / LEGAL CONFIRMATION REQUIRED. These are never mixed.

| Level | Data | Status |
|---|---|---|
| Platform | Global product, generic, manufacturer, category, sub-category, unit, box, leaf | EXISTS |
| Platform | Prescription flag | EXISTS |
| Platform | Product description, usage, warnings | MISSING |
| Platform | Subscription plans and terms | EXISTS |
| Platform | Permission templates | EXISTS |
| Platform | Geography (division, district, thana, area) | PARTIALLY EXISTS — tables exist, one row each |
| Platform | Commission configuration | MISSING |
| Pharmacy | Which global products it sells | EXISTS |
| Pharmacy | Selling price and sales percentage | EXISTS |
| Pharmacy | Storefront profile: hours, coordinates, radius, fee, minimum | MISSING |
| Pharmacy | Marketplace visibility per product | MISSING |
| Pharmacy | Promotion | MISSING |
| Branch | Stock batches and movements | EXISTS |
| Branch | Sales, purchases, returns | EXISTS |
| Branch | Staff and permissions | EXISTS |
| Consumer | Identity, addresses, favourites, orders, reviews | MISSING |
