import {
  SALES, PURCHASES, PRODUCTS, REGIONS, BRANCHES, CUSTOMERS, SUPPLIERS, BATCHES,
  TODAY_STR, stockForProduct, SALES_RETURNS, PURCHASE_RETURNS, DELIVERY_ORDERS, COLLECTIONS,
  REQUISITIONS, PRODUCT_BY_ID, CATEGORY_BY_ID,
} from "./mockData.js";

export const money = (n) => "৳ " + Number(n || 0).toLocaleString("en-BD", { maximumFractionDigits: 0 });
export const moneyFull = (n) => "৳ " + Number(n || 0).toLocaleString("en-BD", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
export const num = (n) => Number(n || 0).toLocaleString("en-BD");

export function daysBetween(a, b) {
  return Math.round((new Date(b) - new Date(a)) / 86400000);
}

/* ===== LINE CALCULATIONS ===== */
export function calculateLineTotal(pcs, tp) { return Math.round(pcs * tp * 100) / 100; }
export function calculateDiscount(lineTotal, discPct) { return Math.round(lineTotal * (discPct / 100) * 100) / 100; }
export function calculateTax(lineTotal, vatPct) { return Math.round(lineTotal * (vatPct / 100) * 100) / 100; }
export function calculateInvoiceTotal(lines, invoiceDiscPct = 0, deliveryCharge = 0) {
  const subtotal = lines.reduce((s, l) => s + (l.lineTotal || 0), 0);
  const discount = subtotal * (invoiceDiscPct / 100);
  return Math.round((subtotal - discount + deliveryCharge) * 100) / 100;
}
export function calculateDue(total, paid) { return Math.round((total - paid) * 100) / 100; }
export function calculateChange(paid, total) { return Math.max(0, Math.round((paid - total) * 100) / 100); }
export function calculateStockBalance(prevStock, purchases, purchaseReturns, sales, salesReturns) {
  return prevStock + purchases - purchaseReturns - sales + salesReturns;
}

/* ===== FEFO ALLOCATION ===== */
export function allocateFEFO(productId, requestedPcs, branchId) {
  const batches = BATCHES
    .filter(b => b.productId === productId && b.qtyPcs > 0 && (!branchId || b.branchId === branchId))
    .filter(b => daysBetween(TODAY_STR, b.expiryDate) > 0) // no expired
    .sort((a, b) => a.expiryDate.localeCompare(b.expiryDate)); // FEFO
  let remaining = requestedPcs;
  const alloc = [];
  for (const b of batches) {
    if (remaining <= 0) break;
    const take = Math.min(b.qtyPcs, remaining);
    if (take > 0) { alloc.push({ batchNo: b.batchNo, expiryDate: b.expiryDate, pcs: take }); remaining -= take; }
  }
  return { alloc, shortfall: remaining };
}

/* ===== STOCK COVERAGE ===== */
export function calculateStockCoverage(currentStock, avgDailySales) {
  if (!avgDailySales || avgDailySales <= 0) return Infinity;
  return Math.round(currentStock / avgDailySales);
}
export function calculateRecommendedPurchaseQty(currentStock, safetyStock, avgDailySales, leadTimeDays) {
  const minRequired = safetyStock + (avgDailySales * leadTimeDays);
  const deficit = Math.max(0, minRequired - currentStock);
  return Math.ceil(deficit);
}

/* ===== INVOICE MERGE ===== */
export function mergeInvoices(invoices) {
  if (!invoices || invoices.length < 2) throw new Error("Select at least 2 invoices to merge");
  if (invoices.some(inv => inv.merged)) throw new Error("Cannot merge: one or more invoices are already merged");
  const allLines = invoices.flatMap(inv => inv.lines || []);
  const total = invoices.reduce((s, inv) => s + (inv.grandTotal || 0), 0);
  return { lines: allLines, grandTotal: Math.round(total * 100) / 100 };
}

/* ===== DASHBOARD KPIs ===== */
export function computeKpis(filters = {}) {
  const { branchId, dateFrom, dateTo } = filters;
  const todaySales = SALES.filter(s => s.date === TODAY_STR && (!branchId || s.branchId === branchId));
  const todayPurchases = PURCHASES.filter(p => p.date === TODAY_STR && (!branchId || p.branchId === branchId));
  const allSales = SALES.filter(s => (!branchId || s.branchId === branchId));
  const allPurchases = PURCHASES.filter(p => (!branchId || p.branchId === branchId));

  const todaySalesTotal = todaySales.reduce((s, x) => s + x.grandTotal, 0);
  const todayPurchaseTotal = todayPurchases.reduce((s, x) => s + x.total, 0);
  const totalDue = allSales.reduce((s, x) => s + x.due, 0);
  const payableDue = allPurchases.reduce((s, x) => s + x.due, 0);
  const grossProfit = allSales.reduce((s, x) => s + x.grandTotal * 0.18, 0);
  const netProfit = grossProfit * 0.65;

  const lowStock = PRODUCTS.filter(p => { const st = stockForProduct(p.id); return st > 0 && st < p.alertQty; }).length;
  const outOfStock = PRODUCTS.filter(p => stockForProduct(p.id) === 0).length;
  const pendingDeliveries = SALES.filter(s => ["Confirmed", "Delivery Assigned", "On The Way"].includes(s.stage)).length;
  const expiringSoon = BATCHES.filter(b => { const d = daysBetween(TODAY_STR, b.expiryDate); return d <= 30 && d >= 0 && b.qtyPcs > 0; }).length;
  const nearExpiry90 = BATCHES.filter(b => { const d = daysBetween(TODAY_STR, b.expiryDate); return d <= 90 && d >= 0 && b.qtyPcs > 0; }).length;
  const salesReturnTotal = SALES_RETURNS.reduce((s, r) => s + r.returnAmount, 0);
  const purchaseReturnTotal = PURCHASE_RETURNS.reduce((s, r) => s + r.returnAmount, 0);
  const totalCollected = COLLECTIONS.reduce((s, c) => s + c.collectedAmount, 0);

  return [
    { key: "sales_today",      label: "Today's Sales",         value: money(todaySalesTotal),    delta: "+8.2%",  up: true,  icon: "cart",    link: "reports", color: "#B8752B" },
    { key: "purchase_today",   label: "Today's Purchase",      value: money(todayPurchaseTotal), delta: "+2.1%",  up: true,  icon: "box",     link: "purchase", color: "#2E6A62" },
    { key: "gross_profit",     label: "Gross Profit",          value: money(grossProfit),        delta: "+5.4%",  up: true,  icon: "wallet",  link: "reports", color: "#4C7A5E" },
    { key: "net_profit",       label: "Net Profit",            value: money(netProfit),          delta: "+3.1%",  up: true,  icon: "wallet",  link: "reports", color: "#8F5A1E" },
    { key: "total_orders",     label: "Total Orders",          value: num(allSales.length),      delta: "+12",    up: true,  icon: "receipt", link: "sales",   color: "#B8752B" },
    { key: "receivable",       label: "Customer Due",          value: money(totalDue),           delta: "-3.4%",  up: false, icon: "wallet",  link: "reports", color: "#A8452E" },
    { key: "payable",          label: "Supplier Payable",      value: money(payableDue),         delta: "+1.2%",  up: false, icon: "wallet",  link: "reports", color: "#A8452E" },
    { key: "lowstock",         label: "Low Stock Products",    value: num(lowStock),             delta: "watch",  up: false, icon: "alert",   link: "inventory", color: "#A8452E" },
    { key: "nearexpiry",       label: "Near Expiry (30d)",     value: num(expiringSoon),         delta: "review", up: false, icon: "clock",   link: "inventory", color: "#A8452E" },
    { key: "outofstock",       label: "Out of Stock",          value: num(outOfStock),           delta: "action", up: false, icon: "alert",   link: "inventory", color: "#A8452E" },
    { key: "sales_return",     label: "Sales Return",          value: money(salesReturnTotal),   delta: "stable", up: true,  icon: "receipt", link: "sales",   color: "#5A6357" },
    { key: "purchase_return",  label: "Purchase Return",       value: money(purchaseReturnTotal),delta: "stable", up: true,  icon: "box",     link: "purchase", color: "#5A6357" },
    { key: "collection",       label: "Collection Today",      value: money(totalCollected),     delta: "+4.2%",  up: true,  icon: "wallet",  link: "collection", color: "#2E6A62" },
    { key: "pending_delivery", label: "Delivery Pending",      value: num(pendingDeliveries),    delta: "live",   up: true,  icon: "truck",   link: "delivery", color: "#4C7A5E" },
    { key: "customers",        label: "Total Customers",       value: num(CUSTOMERS.length),     delta: "+3",     up: true,  icon: "users",   link: "master",  color: "#2E6A62" },
    { key: "suppliers",        label: "Total Suppliers",       value: num(SUPPLIERS.length),     delta: "stable", up: true,  icon: "layers",  link: "master",  color: "#2E6A62" },
  ];
}

/* ===== CHARTS ===== */
export function regionBreakdown() {
  return REGIONS.map(r => {
    const branchIds = BRANCHES.filter(b => b.regionId === r.id).map(b => b.id);
    const regionSales = SALES.filter(s => branchIds.includes(s.branchId));
    const amount = regionSales.reduce((s, x) => s + x.grandTotal, 0);
    return { region: r.name, amount, orders: regionSales.length, branches: branchIds.length };
  }).sort((a, b) => b.amount - a.amount);
}

export function salesTrend(days = 14) {
  const out = [];
  for (let i = days - 1; i >= 0; i--) {
    const d = new Date(TODAY_STR); d.setDate(d.getDate() - i);
    const key = d.toISOString().slice(0, 10);
    const salesAmt = SALES.filter(s => s.date === key).reduce((s, x) => s + x.grandTotal, 0);
    const purchaseAmt = PURCHASES.filter(p => p.date === key).reduce((s, x) => s + x.total, 0);
    out.push({ date: key.slice(5), sales: Math.round(salesAmt), purchase: Math.round(purchaseAmt) });
  }
  return out;
}

export function categorySplit() {
  const map = {};
  SALES.forEach(s => s.lines.forEach(l => {
    const p = PRODUCTS.find(pp => pp.id === l.productId);
    if (!p) return;
    map[p.categoryId] = (map[p.categoryId] || 0) + l.lineTotal;
  }));
  return Object.entries(map)
    .sort((a, b) => b[1] - a[1])
    .slice(0, 6)
    .map(([id, val]) => ({ label: CATEGORY_BY_ID[id]?.name || id, value: Math.round(val) }));
}

export function paymentSplit() {
  let paid = 0, partial = 0, unpaid = 0;
  SALES.forEach(s => { if (s.paymentStatus === "Paid") paid++; else if (s.paymentStatus === "Partial") partial++; else unpaid++; });
  return [{ label: "Paid", value: paid }, { label: "Partial", value: partial }, { label: "Unpaid", value: unpaid }];
}

export function topProducts(limit = 10) {
  const map = {};
  SALES.forEach(s => s.lines.forEach(l => {
    if (!map[l.productId]) map[l.productId] = { pcs: 0, revenue: 0, orders: 0 };
    map[l.productId].pcs += (l.pcs || 0);
    map[l.productId].revenue += (l.lineTotal || 0);
    map[l.productId].orders += 1;
  }));
  return Object.entries(map)
    .map(([pid, v]) => ({ productId: pid, name: PRODUCT_BY_ID[pid]?.name || pid, ...v }))
    .sort((a, b) => b.revenue - a.revenue)
    .slice(0, limit);
}

export function topCustomers(limit = 10) {
  const map = {};
  SALES.forEach(s => {
    if (!map[s.customerId]) map[s.customerId] = { total: 0, orders: 0, name: s.customerName };
    map[s.customerId].total += s.grandTotal;
    map[s.customerId].orders += 1;
  });
  return Object.entries(map)
    .map(([id, v]) => ({ customerId: id, ...v }))
    .sort((a, b) => b.total - a.total)
    .slice(0, limit);
}

export function deliveryStatusSplit() {
  const map = {};
  SALES.forEach(s => { map[s.stage] = (map[s.stage] || 0) + 1; });
  return Object.entries(map).map(([label, value]) => ({ label, value }));
}

/* ===== AI RECOMMENDATIONS ===== */
export function aiReorderRecommendations({ leadTimeDays = 7, safetyFactor = 1.4, windowDays = 14 } = {}) {
  const since = new Date(TODAY_STR); since.setDate(since.getDate() - windowDays);
  const sinceKey = since.toISOString().slice(0, 10);

  return PRODUCTS.filter(p => p.status === "Active").map(p => {
    let pcsSold = 0;
    SALES.filter(s => s.date >= sinceKey).forEach(s => s.lines.forEach(l => {
      if (l.productId === p.id) pcsSold += (l.pcs || 0);
    }));
    const velocity = pcsSold / windowDays;
    const currentStock = stockForProduct(p.id);
    const safetyStock = Math.round(velocity * leadTimeDays * safetyFactor);
    const daysOfStock = velocity > 0 ? Math.round(currentStock / velocity) : Infinity;
    const deficit = Math.max(0, safetyStock - currentStock);
    const urgency = velocity === 0 ? 0 : deficit / (safetyStock || 1);
    const suggestedBoxQty = deficit > 0 ? Math.max(1, Math.ceil(deficit / p.pcsPerBox)) : 0;
    const supplier = SUPPLIERS.find(s => s.status === "Active");
    return {
      productId: p.id, name: p.name,
      velocity: Math.round(velocity * 10) / 10,
      currentStock, safetyStock, daysOfStock, deficit, urgency, suggestedBoxQty,
      estimatedCost: Math.round(suggestedBoxQty * p.pcsPerBox * p.tp),
      supplierId: supplier?.id, supplierName: supplier?.name,
      confidence: pcsSold >= 40 ? "high" : pcsSold >= 10 ? "med" : "low",
      priority: urgency > 0.7 ? "CRITICAL" : urgency > 0.4 ? "HIGH" : "MEDIUM",
      expectedStockoutDate: velocity > 0 && currentStock > 0
        ? (() => { const d = new Date(TODAY_STR); d.setDate(d.getDate() + Math.round(currentStock / velocity)); return d.toISOString().slice(0, 10); })()
        : null,
      last30DaysSales: pcsSold * (30 / windowDays),
      salesGrowth: Math.round((Math.random() * 40) - 5),
    };
  })
    .filter(r => r.deficit > 0 && r.velocity > 0)
    .sort((a, b) => b.urgency - a.urgency)
    .slice(0, 15);
}
