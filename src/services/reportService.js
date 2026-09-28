/**
 * Reports.
 *
 * Two sources, deliberately:
 *
 *  - Sales, purchase, finance and collection come from /api/reports, which
 *    returns each report as a grid describing its own columns. One table renders
 *    all of them and a new report needs no change here.
 *
 *  - Inventory reports come from /api/inventory, the same stock-ledger endpoints
 *    the Inventory screen uses. Reimplementing them here would mean two sets of
 *    stock numbers that have to agree and eventually would not.
 *
 * The old /api/v1/reports surface is not used: all 28 of its endpoints return
 * 500, having been written against a schema with INVOICE_DATE, PID_CUSTOMER and
 * GRAND_TOTAL columns this database does not have.
 */
import apiClient from '../lib/apiClient.js';
import inventoryService from './inventoryService.js';

const BASE = '/api/reports';

/* ── Which filters each report understands ──────────────────────────────────
   The server sends `supportedFilters` for its own reports; inventory reports
   are declared here because their endpoints predate that convention. Showing a
   control a report ignores invites someone to set it and wonder why nothing
   moved. */

export const REPORT_GROUPS = [
  {
    group: 'Finance',
    reports: [
      { id: 'daily-pnl',           label: 'Daily Profit & Loss' },
      { id: 'monthly-pnl',         label: 'Monthly Profit & Loss' },
      { id: 'product-pnl',         label: 'Product-wise Profit',    source: 'inventory' },
      { id: 'customer-profit',     label: 'Customer Profitability' },
    ],
  },
  {
    group: 'Sales',
    reports: [
      { id: 'sales-register',      label: 'Sales Register' },
      { id: 'sales-by-product',    label: 'Sales by Product' },
      { id: 'sales-by-customer',   label: 'Sales by Customer' },
      { id: 'sales-by-company',    label: 'Sales by Company' },
      { id: 'sales-by-category',   label: 'Sales by Category' },
      { id: 'customer-due',        label: 'Customer Due' },
      { id: 'sales-return',        label: 'Sales Return Register', source: 'salesReturn' },
    ],
  },
  {
    group: 'Purchase',
    reports: [
      { id: 'purchase-register',   label: 'Purchase Register' },
      { id: 'purchase-by-product', label: 'Purchase by Product' },
      { id: 'purchase-by-supplier',label: 'Purchase by Supplier' },
      { id: 'purchase-return',     label: 'Purchase Return Register' },
      { id: 'supplier-due',        label: 'Supplier Outstanding' },
      { id: 'supplier-benefit',    label: 'Supplier Benefit' },
      { id: 'supplier-payments',   label: 'Supplier Payment Register' },
      { id: 'supplier-advance',    label: 'Supplier Advance Register' },
    ],
  },
  {
    group: 'Inventory',
    reports: [
      { id: 'current-stock',       label: 'Current Stock',        source: 'inventory' },
      { id: 'batch-stock',         label: 'Batch Stock',          source: 'inventory' },
      { id: 'stock-valuation',     label: 'Stock Valuation',      source: 'inventory' },
      { id: 'stock-alert',         label: 'Low / Alert Stock',    source: 'inventory' },
      { id: 'out-of-stock',        label: 'Out of Stock',         source: 'inventory' },
      { id: 'expiry-30',           label: 'Near Expiry (30 days)',source: 'inventory' },
      { id: 'expiry-90',           label: 'Near Expiry (90 days)',source: 'inventory' },
      { id: 'expired',             label: 'Expired Stock',        source: 'inventory' },
      { id: 'stock-movement',      label: 'Stock Movement Ledger',source: 'inventory' },
      { id: 'slow-moving',         label: 'Slow-moving Stock',    source: 'inventory' },
    ],
  },
  {
    group: 'Delivery & Collection',
    reports: [
      { id: 'collection-summary',  label: 'Collection Summary' },
      { id: 'delivery-performance',label: 'Delivery Performance', source: 'delivery' },
    ],
  },

];

/* ── HR ──────────────────────────────────────────────────────────────────
   Every hr- report the server catalogues, on the HR Reports screen (MOD39)
   rather than beside sales and purchase — payroll figures are not sales
   figures, and the server checks View on HR Reports for any hr- id. Same
   grid, same /api/reports; only the door is different. Four groups rather
   than one of twenty-five, because that is how somebody looks for them —
   "who works here", "were they in", "what were they paid", "what is owed". */
export const HR_REPORT_GROUPS = [
  {
    group: 'People',
    reports: [
      { id: 'hr-employees',          label: 'Employee Register' },
      { id: 'hr-headcount',          label: 'Headcount' },
      { id: 'hr-separation',         label: 'Employee Separation' },
      { id: 'hr-increment',          label: 'Increment Register' },
    ],
  },
  {
    group: 'Attendance & Leave',
    reports: [
      { id: 'hr-attendance',         label: 'Attendance Register' },
      { id: 'hr-attendance-summary', label: 'Monthly Attendance Summary' },
      { id: 'hr-late',               label: 'Late Arrivals' },
      { id: 'hr-absent',             label: 'Absences' },
      { id: 'hr-overtime',           label: 'Overtime' },
      { id: 'hr-overtime-claims',    label: 'Overtime Claims' },
      { id: 'hr-leave-register',     label: 'Leave Register' },
      { id: 'hr-leave-balance',      label: 'Leave Balance' },
      { id: 'hr-leave-utilisation',  label: 'Leave Utilisation' },
      { id: 'hr-leave-pending',      label: 'Leave Awaiting Approval' },
    ],
  },
  {
    group: 'Payroll',
    reports: [
      { id: 'hr-salary-register',    label: 'Salary Register' },
      { id: 'hr-payroll-register',   label: 'Payroll Register' },
      { id: 'hr-payroll-summary',    label: 'Payroll Summary' },
      { id: 'hr-payroll-cost',       label: 'Payroll Cost by Department' },
      { id: 'hr-payroll-deduction',  label: 'Payroll Deductions' },
      { id: 'hr-payroll-exception',  label: 'Payroll Exceptions' },
    ],
  },
  {
    group: 'Money & Benefits',
    reports: [
      { id: 'hr-advance',            label: 'Advance Register' },
      { id: 'hr-loan',               label: 'Loan Register' },
      { id: 'hr-expense',            label: 'Expense & Conveyance' },
      { id: 'hr-pf',                 label: 'Provident Fund' },
      { id: 'hr-gratuity',           label: 'Gratuity Awards' },
    ],
  },
];

/* ── Accounts ─────────────────────────────────────────────────────────────
   Every acc- report the server catalogues, on the Accounts Reports screen
   (MOD51). The server checks View on Accounts Reports for any acc- id and
   reads every figure from the posted ledger — the same books the journals
   and the daily cash sheet show, never a second calculation. */
export const ACC_REPORT_GROUPS = [
  {
    group: 'Financial Statements',
    reports: [
      { id: 'acc-trial-balance',       label: 'Trial Balance', desc: 'Every account: opening, movement and closing — Dr equals Cr' },
      { id: 'acc-profit-loss',         label: 'Profit & Loss', desc: 'Revenue less cost of sales and expenses for the period' },
      { id: 'acc-balance-sheet',       label: 'Balance Sheet', desc: 'Assets, liabilities and equity as at a date' },
      { id: 'acc-cash-flow',           label: 'Cash Flow', desc: 'Where cash came from and went, by activity' },
      { id: 'acc-branch-pnl',          label: 'Branch-wise Profit & Loss', desc: 'Profit and loss side by side per branch' },
      { id: 'acc-revenue-analysis',    label: 'Revenue Analysis', desc: 'Revenue by account, with share of total' },
      { id: 'acc-expense-analysis',    label: 'Expense Analysis', desc: 'Expenses by account with share, previous period and variance' },
      { id: 'acc-ar-movement',         label: 'Receivable Movement', desc: 'Opening receivables + credit sales − collections − returns = closing, tied to open invoices' },
      { id: 'acc-ap-movement',         label: 'Payable Movement', desc: 'Opening payables + purchases − payments − returns = closing, tied to open bills' },
      { id: 'acc-inventory-movement',  label: 'Inventory Financial Movement', desc: 'Opening stock value + purchases − cost of sales ± adjustments = closing' },
    ],
  },
  {
    group: 'Ledgers & Journals',
    reports: [
      { id: 'acc-general-ledger',      label: 'General Ledger', desc: 'Every posting; search one account for its opening, running balance and closing' },
      { id: 'acc-journal-register',    label: 'Journal Register', desc: 'All journals in a range, with source and status' },
      { id: 'acc-daily-summary',       label: 'Daily Transaction Summary', desc: 'Revenue, expenses and cash movement per day' },
      { id: 'acc-daily-expense',       label: 'Daily Expense Report', desc: 'What was spent each day, by head and by cost centre' },
      { id: 'acc-events',              label: 'Accounting Event Register', desc: 'Every financial event and whether it posted' },
      { id: 'acc-tax',                 label: 'Tax / VAT Report', desc: 'Tax collected and payable by rate' },
    ],
  },
  {
    group: 'Receivables & Payables',
    reports: [
      { id: 'acc-ar-ageing',           label: 'Receivables Ageing', desc: 'What customers owe, aged 0–30 / 31–60 / 61–90 / 90+' },
      { id: 'acc-ap-ageing',           label: 'Payables Ageing', desc: 'What is owed to suppliers, by age' },
      { id: 'acc-customer-statement',  label: 'Customer Statement', desc: 'One customer’s account, line by line' },
      { id: 'acc-supplier-statement',  label: 'Supplier Statement', desc: 'One supplier’s account, line by line' },
    ],
  },
  {
    group: 'Cash & Bank',
    reports: [
      { id: 'acc-cash-book',           label: 'Cash Book', desc: 'Receipts and payments on the till' },
      { id: 'acc-bank-book',           label: 'Bank Book', desc: 'Receipts and payments on the bank accounts' },
      { id: 'acc-petty-cash-book',     label: 'Petty Cash Book', desc: 'Receipts and payments on petty cash' },
      { id: 'acc-daily-cash',          label: 'Daily Cash Report', desc: 'Each day’s opening, expected, counted and difference' },
      { id: 'acc-branch-cash-book',    label: 'Branch Cash Book', desc: 'The cash book per branch' },
      { id: 'acc-cash-closing',        label: 'Cash Closing Report', desc: 'Closed days with who closed them and when' },
      { id: 'acc-cash-difference',     label: 'Cash Short / Over Report', desc: 'Every short or over with its reason' },
      { id: 'acc-cash-reconciliation', label: 'Cash Reconciliation', desc: 'Ledger cash against counted cash by day' },
    ],
  },
  {
    group: 'Investors & Payroll',
    reports: [
      { id: 'acc-investor-statement',  label: 'Investor Statement', desc: 'One investor’s capital and profit account' },
      { id: 'acc-investor-capital',    label: 'Investor Capital Report', desc: 'Capital opening, in, out and closing per investor' },
      { id: 'acc-investor-profit',     label: 'Investor Profit Statement', desc: 'Profit allocated, paid and owed per investor' },
      { id: 'acc-profit-distribution', label: 'Profit Distribution Report', desc: 'Each distribution and its allocations' },
      { id: 'acc-payroll-summary',     label: 'Payroll Summary (ledger)', desc: 'Salary cost and payables from the ledger' },
      { id: 'acc-salary-expense',      label: 'Salary Expense', desc: 'Salary expense by period' },
    ],
  },
];

export const ALL_REPORTS = [...REPORT_GROUPS, ...HR_REPORT_GROUPS, ...ACC_REPORT_GROUPS].flatMap(g =>
  g.reports.map(r => ({ ...r, group: g.group })));

export const findReport = (id) => ALL_REPORTS.find(r => r.id === id);

export const emptyFilters = () => ({
  from: '', to: '', branchId: '', companyId: '', categoryId: '',
  customerId: '', supplierId: '', paymentStatus: 'All', q: '', costCenterId: '',
});

export const hasActiveFilter = (f) =>
  !!(f.from || f.to || f.branchId || f.companyId || f.categoryId
     || f.customerId || f.supplierId || f.costCenterId || f.q.trim()
     || (f.paymentStatus && f.paymentStatus !== 'All'));

/* ── Running a report ───────────────────────────────────────────────────── */

export async function runReport(id, filters) {
  const meta = findReport(id);
  if (!meta) throw new Error(`'${id}' is not a report.`);

  if (meta.source === 'inventory')   return inventoryReport(id, filters);
  if (meta.source === 'salesReturn') return salesReturnReport(filters);
  if (meta.source === 'delivery')    return deliveryReport(filters);

  const params = {};
  if (filters.from)       params.from       = filters.from;
  if (filters.to)         params.to         = filters.to;
  if (filters.branchId)   params.branchId   = Number(filters.branchId);
  if (filters.companyId)  params.companyId  = Number(filters.companyId);
  if (filters.categoryId) params.categoryId = Number(filters.categoryId);
  if (filters.customerId) params.customerId = Number(filters.customerId);
  if (filters.supplierId) params.supplierId = Number(filters.supplierId);
  if (filters.costCenterId) params.costCenterId = Number(filters.costCenterId);
  if (filters.q && filters.q.trim()) params.q = filters.q.trim();
  if (filters.paymentStatus && filters.paymentStatus !== 'All') {
    params.paymentStatus = filters.paymentStatus;
  }
  return apiClient.get(`${BASE}/${id}`, params);
}

/* ── Inventory reports, shaped into the same grid ───────────────────────── */

const col = (key, label, type, total) => ({ key, label, type, total });

/**
 * Inventory reports come back as their own row shapes, so they are mapped into
 * the grid contract here rather than the server growing a second format.
 */
async function inventoryReport(id, f) {
  const branchId = f.branchId ? Number(f.branchId) : null;
  const search = f.q?.trim() || null;
  const companyId = f.companyId ? Number(f.companyId) : null;

  switch (id) {
    case 'current-stock':
    case 'stock-valuation': {
      const page = await inventoryService.getStockLedger({
        branchId, companyId, search, inStockOnly: id === 'stock-valuation', size: 500 });
      const valuation = id === 'stock-valuation';
      const columns = [
        col('product', 'Product', 'text'), col('sku', 'SKU', 'text'),
        col('company', 'Company', 'text'), col('stock', 'Stock', 'text'),
        col('pcs', 'Pieces', 'number', true),
        ...(valuation
          ? [col('costPerPcs', 'Cost / pc', 'money'), col('value', 'Stock Value', 'money', true)]
          : [col('batches', 'Batches', 'number', true), col('status', 'Status', 'text')]),
      ];
      const rows = (page.items ?? []).map(r => ({
        product: r.productName, sku: r.sku, company: r.companyName,
        stock: r.stockDisplay, pcs: Number(r.stockPcs || 0),
        ...(valuation
          ? { costPerPcs: Number(r.cost?.perPcs ?? 0), value: Number(r.stockValue || 0) }
          : { batches: Number(r.batchCount || 0), status: r.stockStatus }),
      }));
      return grid(id, valuation ? 'Stock Valuation' : 'Current Stock', columns, rows,
        valuation ? 'Valued at the weighted average landed cost of the lots on hand.' : null,
        ['branch', 'company', 'search']);
    }

    case 'batch-stock': {
      const page = await inventoryService.getBatchStock({ branchId, companyId, search, size: 500 });
      const columns = [
        col('product', 'Product', 'text'), col('batch', 'Batch', 'text'),
        col('expiry', 'Expiry', 'date'), col('stock', 'Stock', 'text'),
        col('pcs', 'Pieces', 'number', true), col('cost', 'Cost / pc', 'money'),
        col('value', 'Value', 'money', true), col('status', 'Status', 'text')];
      const rows = (page.items ?? []).map(b => ({
        product: b.productName, batch: b.batchNo, expiry: b.expiryDate,
        stock: b.qtyDisplay, pcs: Number(b.qtyPcs || 0),
        cost: Number(b.costPerPcs || 0), value: Number(b.stockValue || 0), status: b.status,
      }));
      return grid(id, 'Batch Stock', columns, rows, null, ['branch', 'company', 'search']);
    }

    case 'slow-moving': {
      const rows = await inventoryService.getSlowMoving({ branchId, companyId, search, days: 90 });
      const columns = [
        col('product', 'Product', 'text'), col('company', 'Company', 'text'), col('batch', 'Batch', 'text'),
        col('stock', 'Stock', 'text'), col('pcs', 'Pieces', 'number', true),
        col('value', 'Value tied up', 'money', true), col('lastSold', 'Last sold', 'date'),
        col('daysIdle', 'Days idle', 'number'), col('expiry', 'Expiry', 'date'), col('daysLeft', 'Days to expiry', 'number')];
      return grid('slow-moving', 'Slow-moving Stock', columns,
        (rows || []).map(r => ({ product: r.productName, company: r.companyName, batch: r.batchNo, stock: r.qtyDisplay, pcs: r.qtyPcs,
          value: r.stockValue, lastSold: r.lastSold, daysIdle: r.daysIdle, expiry: r.expiryDate, daysLeft: r.daysToExpiry })),
        'Lots on the shelf whose product has not sold in this branch for 90 days, most value first. "Last sold" is blank where it has never sold here.',
        ['branch', 'company', 'search']);
    }
    case 'stock-alert':
    case 'out-of-stock': {
      const rowsRaw = id === 'stock-alert'
        ? await inventoryService.getLowStock({ branchId, companyId, search })
        : await inventoryService.getOutOfStock({ branchId, companyId, search });
      const columns = [
        col('product', 'Product', 'text'), col('company', 'Company', 'text'),
        col('stock', 'Stock', 'text'), col('pcs', 'Pieces', 'number', true),
        col('alertQty', 'Alert At', 'number')];
      const rows = (rowsRaw ?? []).map(r => ({
        product: r.productName, company: r.companyName, stock: r.stockDisplay,
        pcs: Number(r.stockPcs || 0), alertQty: r.alertQty ?? null,
      }));
      return grid(id, id === 'stock-alert' ? 'Low / Alert Stock' : 'Out of Stock',
        columns, rows,
        id === 'stock-alert' ? 'Products at or below their configured alert level.' : null,
        ['branch', 'company', 'search']);
    }

    case 'expiry-30':
    case 'expiry-90':
    case 'expired': {
      // One endpoint serves all three: /expiry returns lots expiring within the
      // window *and* those already past it, since expired stock is the more
      // urgent case. "Expired" is therefore that same result narrowed to lots
      // already gone — which is a property of each row, not a different query.
      const days = id === 'expiry-90' ? 90 : 30;
      const page = await inventoryService.getExpiringBatches({
        branchId, companyId, search, days, size: 500 });
      const rowsRaw = id === 'expired'
        ? (page.items ?? []).filter(b => Number(b.daysToExpiry) < 0)
        : (page.items ?? []);
      const columns = [
        col('product', 'Product', 'text'), col('batch', 'Batch', 'text'),
        col('expiry', 'Expiry', 'date'), col('daysLeft', 'Days Left', 'number'),
        col('stock', 'Stock', 'text'), col('pcs', 'Pieces', 'number', true),
        col('value', 'Value at Risk', 'money', true)];
      const rows = (rowsRaw ?? []).map(b => ({
        product: b.productName, batch: b.batchNo, expiry: b.expiryDate,
        daysLeft: b.daysToExpiry, stock: b.qtyDisplay,
        pcs: Number(b.qtyPcs || 0), value: Number(b.stockValue || 0),
      }));
      return grid(id,
        id === 'expired' ? 'Expired Stock' : `Near Expiry (${days} days)`, columns, rows,
        'Value at risk is what the stock cost, not what it would have sold for.',
        ['branch', 'company', 'search']);
    }

    case 'stock-movement': {
      const page = await inventoryService.getMovements({
        branchId, search, fromDate: f.from || null, toDate: f.to || null, size: 500 });
      const columns = [
        col('date', 'Date', 'date'), col('product', 'Product', 'text'),
        col('batch', 'Batch', 'text'), col('type', 'Movement', 'text'),
        col('direction', 'In/Out', 'text'), col('pcs', 'Pieces', 'number', true),
        col('balance', 'Balance After', 'number'), col('ref', 'Reference', 'text')];
      const rows = (page.items ?? []).map(m => ({
        date: m.movementDate, product: m.productName, batch: m.batchNo,
        type: m.movementLabel || m.movementType, direction: m.direction,
        pcs: Number(m.qtyPcs || 0), balance: Number(m.balanceAfter || 0), ref: m.refNo,
      }));
      return grid(id, 'Stock Movement Ledger', columns, rows,
        'Append-only. Every change to stock appears here exactly once.',
        ['dateRange', 'branch', 'search']);
    }

    case 'product-pnl': {
      const res = await inventoryService.getProfit({
        fromDate: f.from || null, toDate: f.to || null, branchId });
      const columns = [
        col('product', 'Product', 'text'), col('sku', 'SKU', 'text'),
        col('sold', 'Sold', 'text'), col('pcs', 'Net Pieces', 'number', true),
        col('returned', 'Returned', 'number', true),
        col('revenue', 'Revenue', 'money', true), col('cogs', 'COGS', 'money', true),
        col('profit', 'Gross Profit', 'money', true), col('margin', 'Margin', 'percent'),
        col('flag', '', 'text')];
      const rows = (res.rows ?? []).map(r => ({
        product: r.productName, sku: r.sku,
        sold: r.netDisplay, pcs: Number(r.netPcs || 0),
        returned: Number(r.returnedPcs || 0),
        revenue: Number(r.revenue || 0), cost: undefined,
        cogs: Number(r.cost || 0), profit: Number(r.grossProfit || 0),
        margin: r.marginPct ?? null,
        // Surfaced rather than buried inside a positive total: a product selling
        // under cost is the thing this report exists to find.
        flag: r.soldBelowCost ? 'BELOW COST' : '',
      }));
      return grid(id, 'Product-wise Profit', columns, rows,
        'Margin is against revenue, not cost. Sales returns net off both sides. '
        + 'Pieces are net of returns.',
        ['dateRange', 'branch']);
    }

    default:
      throw new Error(`'${id}' has no inventory mapping.`);
  }
}

async function salesReturnReport(f) {
  const params = { size: 500 };
  if (f.from) params.from = f.from;
  if (f.to)   params.to   = f.to;
  if (f.q?.trim()) params.q = f.q.trim();
  if (f.branchId) params.branchId = Number(f.branchId);
  const body = apiClient.unwrapPagination(await apiClient.get('/api/sales-returns', params));
  const columns = [
    col('returnNo', 'Return', 'text'), col('against', 'Against Invoice', 'text'),
    col('date', 'Date', 'date'), col('customer', 'Customer', 'text'),
    col('refund', 'Refund', 'money', true), col('paymentType', 'Refund By', 'text'),
    col('note', 'Note', 'text')];
  const rows = (body.items ?? []).map(r => ({
    returnNo: r.invoiceNo, against: r.challanNo, date: r.transactionDate,
    customer: r.customerName, refund: Number(r.amountTobePaid || 0),
    paymentType: r.paymentType, note: r.remarks,
  }));
  return grid('sales-return', 'Sales Return Register', columns, rows,
    'Refunds are net of any invoice discount — what the customer actually paid.',
    ['dateRange', 'branch', 'search']);
}

async function deliveryReport(f) {
  const params = {};
  if (f.from) params.from = f.from;
  if (f.to)   params.to   = f.to;
  if (f.branchId) params.branchId = Number(f.branchId);
  const rowsRaw = await apiClient.get('/api/sales-activity/delivery-performance', params);
  const columns = [
    col('name', 'Delivery Man', 'text'), col('vehicle', 'Vehicle', 'text'),
    col('assigned', 'Assigned', 'number', true), col('delivered', 'Delivered', 'number', true),
    col('inProgress', 'On The Road', 'number', true), col('completion', 'Completion', 'percent'),
    col('billed', 'Billed', 'money', true), col('collected', 'Collected', 'money', true),
    col('outstanding', 'Outstanding', 'money', true), col('collectionRate', 'Collected %', 'percent')];
  const rows = (rowsRaw ?? []).map(r => ({
    name: r.deliveryManName, vehicle: r.vehicleNo,
    assigned: r.assigned, delivered: r.delivered, inProgress: r.inProgress,
    completion: r.completionRate, billed: Number(r.billed || 0),
    collected: Number(r.collected || 0), outstanding: Number(r.outstanding || 0),
    collectionRate: r.collectionRate,
  }));
  return grid('delivery-performance', 'Delivery Performance', columns, rows,
    'Collected is capped at the amount billed — cash tendered above the invoice went back as change.',
    ['dateRange', 'branch']);
}

/** Totals computed the same way the server does, so both halves agree. */
function grid(id, title, columns, rows, note, supportedFilters) {
  const totals = {};
  columns.filter(c => c.total).forEach(c => {
    totals[c.key] = rows.reduce((s, r) => s + (Number(r[c.key]) || 0), 0);
  });
  return { id, title, columns, rows, rowCount: rows.length, totals, note, supportedFilters };
}

export default { REPORT_GROUPS, ALL_REPORTS, findReport, runReport, emptyFilters, hasActiveFilter };
