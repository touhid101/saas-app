# Friends Pharma ERP — React Prototype v2.0

A complete pharmacy ERP prototype built with React, covering POS, Inventory, Purchase, Sales, Delivery, Collection, Requisition, Reporting, RBAC and AI Analytics.

## Quick Start

```bash
npm install
npm run dev
```

Then open http://localhost:5173

## Demo Users

| Username | Role | Access |
|----------|------|--------|
| admin | Super Admin | Full access |
| nusrat | Branch Manager | Branch-level ops |
| rezaul | Pharmacist | Sales + Inventory |
| farhana | Accounts Officer | Reports + Payments |
| shahriar | Purchase Officer | Purchase + Requisition |

## Demo Workflow

1. **Dashboard** — view 16 KPIs, low stock alerts, charts
2. **AI Copilot** — get purchase recommendations
3. **Requisition** → New → Auto-Generate → Submit → Approve → Convert to Purchase
4. **Purchase** — complete a purchase, stock increases
5. **Quick Sales (POS)** — F2 search, F8 hold, F12 charge
6. **Sales** — FEFO allocation, batch chips shown
7. **Sales Activity** — Kanban: advance order stages
8. **Delivery** — track deliveries, assign delivery men
9. **Collection** — record cash collection from delivery men
10. **Reports** — 28+ reports with CSV export

## Keyboard Shortcuts

- **Ctrl+K** — Global search
- **F2** — POS product search
- **F8** — POS hold bill
- **F9** — POS recall held bill
- **F12** — POS complete sale

## Project Structure

```
src/
├── lib/
│   ├── mockData.js      # All mock data (100 products, 500 sales, etc.)
│   ├── calc.js          # All business calculations (FEFO, KPIs, AI)
│   ├── rng.js           # Deterministic RNG for stable mock data
│   └── AppContext.jsx   # Global state (user, branch, notifications)
├── components/
│   ├── UI.jsx           # Reusable UI components (Card, Modal, Toast...)
│   ├── Charts.jsx       # BarChart, Donut, RegionBars, Sparkline
│   ├── Sidebar.jsx      # Collapsible navigation
│   ├── Topbar.jsx       # Branch selector, notifications, user
│   └── GlobalSearch.jsx # Ctrl+K search
├── pages/
│   ├── Dashboard.jsx    # 16 KPI cards + charts
│   ├── MasterData.jsx   # 19-entity CRUD
│   ├── Purchase.jsx     # Purchase + Return + History
│   ├── Sales.jsx        # Sales + FEFO + Return + History
│   ├── QuickSales.jsx   # POS terminal
│   ├── SalesActivity.jsx# Kanban order lifecycle
│   ├── Delivery.jsx     # Delivery management
│   ├── Collection.jsx   # Collection management
│   ├── Requisition.jsx  # Approval workflow
│   ├── InvoiceMerge.jsx # Invoice consolidation
│   ├── Inventory.jsx    # Stock + Batch + Expiry + Alerts
│   ├── Reports.jsx      # 28 reports with pagination + CSV
│   ├── AIAgent.jsx      # AI recommendations + chat
│   └── RBAC.jsx         # Roles + permissions + users
└── styles.css           # Design system

docs/
├── source-analysis.md   # DB + APEX reverse-engineering
├── business-rules.md    # All business rules
└── page-map.md          # Page routing map
```

## Mock Data Scale

- 2 organizations, 3 companies, 8 branches, 6 regions
- 100 products, 15 generics, 10 categories
- 50 customers, 20 suppliers, 20 employees
- 200 purchase invoices, 500 sales invoices
- ~200 batches, 80 delivery orders, 30+ collections
- ~12 requisitions, 9 roles, 10 demo users

## Replacing Mock API with Spring Boot

Each data source in `src/lib/mockData.js` maps to a REST endpoint:

```javascript
// Replace this:
export const PRODUCTS = [...];

// With this:
export async function getProducts() {
  return fetch('/api/products').then(r => r.json());
}
```

The calculation functions in `calc.js` are pure — they work with any data source.

## Currency & Locale

All amounts in BDT (৳). Date format: YYYY-MM-DD internally, displayed as DD/MM/YYYY where needed.
