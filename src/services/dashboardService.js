/**
 * The dashboard's data.
 *
 * One call returns both halves. `finance` is absent — not zeroed — for a user
 * who may not see money, and the server never ran those queries. The screen
 * decides what to draw from `scope.canSeeFinancials` rather than from the role,
 * so there is exactly one place that knows the rule and it is the server.
 */
import apiClient from '../lib/apiClient.js';

const BASE = '/api/dashboard';

const num = (v) => (v == null ? 0 : Number(v));
/** Null is meaningful: a margin on no revenue is undefined, not zero. */
const opt = (v) => (v == null ? null : Number(v));

export async function getDashboard({ days = 30 } = {}) {
  return shape(await apiClient.get(BASE, { days }));
}

function shape(d) {
  const o = d?.operations ?? {};
  return {
    scope: {
      branchId: d?.scope?.branchId ?? null,
      branchName: d?.scope?.branchName ?? '—',
      days: num(d?.scope?.days),
      canSeeFinancials: !!d?.scope?.canSeeFinancials,
      asOf: d?.scope?.asOf,
    },
    operations: {
      today: {
        ordersRaised: num(o.today?.ordersRaised),
        ordersDelivered: num(o.today?.ordersDelivered),
        ordersPending: num(o.today?.ordersPending),
        piecesSold: num(o.today?.piecesSold),
      },
      stock: {
        productsTracked: num(o.stock?.productsTracked),
        outOfStock: num(o.stock?.outOfStock),
        lowStock: num(o.stock?.lowStock),
        expiring30: num(o.stock?.expiring30),
        expiring90: num(o.stock?.expiring90),
        alreadyExpired: num(o.stock?.alreadyExpired),
        deadStock: num(o.stock?.deadStock),
        piecesOnHand: num(o.stock?.piecesOnHand),
        piecesExpiring90: num(o.stock?.piecesExpiring90),
      },
      delivery: {
        confirmed: num(o.delivery?.confirmed),
        assigned: num(o.delivery?.assigned),
        onTheWay: num(o.delivery?.onTheWay),
        delivered: num(o.delivery?.delivered),
        collected: num(o.delivery?.collected),
        unassigned: num(o.delivery?.unassigned),
      },
      movers: (o.movers ?? []).map((m) => ({
        ...m, pcs: num(m.pcs), onHand: num(m.onHand),
        daysOfCover: opt(m.daysOfCover), trendPct: opt(m.trendPct),
      })),
      expiring: (o.expiring ?? []).map((e) => ({ ...e, pcs: num(e.pcs), daysLeft: num(e.daysLeft) })),
      trend: (o.trend ?? []).map((t) => ({ day: t.day, orders: num(t.orders), pcs: num(t.pcs) })),
      alerts: o.alerts ?? [],
      coverBuckets: slices(o.coverBuckets),
      expiryRunway: slices(o.expiryRunway),
      pipeline: slices(o.pipeline),
    },
    // Absent, not blank. The screen tests for it rather than rendering zeros.
    finance: d?.finance ? shapeFinance(d.finance) : null,
  };
}

/** Every categorical chart arrives in the same shape. */
const slices = (rows) => (rows ?? []).map((r) => ({
  label: r.label, value: num(r.value),
  amount: r.amount == null ? null : Number(r.amount),
  count: num(r.count), tone: r.tone,
}));

const period = (p) => ({
  revenue: num(p?.revenue), cost: num(p?.cost), margin: num(p?.margin),
  marginPct: opt(p?.marginPct), invoices: num(p?.invoices), pcs: num(p?.pcs),
});

function shapeFinance(f) {
  return {
    today: period(f.today), month: period(f.month), previousMonth: period(f.previousMonth),
    revenueTrendPct: opt(f.revenueTrendPct),
    purchasesToday: num(f.purchasesToday), purchasesMonth: num(f.purchasesMonth),
    receivable: { total: num(f.receivable?.total), overdue: num(f.receivable?.overdue),
                  count: num(f.receivable?.count) },
    payable: { total: num(f.payable?.total), overdue: num(f.payable?.overdue),
               count: num(f.payable?.count) },
    stockValue: num(f.stockValue), expiringValue: num(f.expiringValue),
    expiredValue: num(f.expiredValue),
    trend: (f.trend ?? []).map((t) => ({ day: t.day, revenue: num(t.revenue), margin: num(t.margin) })),
    topCustomers: (f.topCustomers ?? []).map((c) => ({
      ...c, revenue: num(c.revenue), due: num(c.due), invoices: num(c.invoices) })),
    topProducts: (f.topProducts ?? []).map((p) => ({
      ...p, revenue: num(p.revenue), margin: num(p.margin),
      marginPct: opt(p.marginPct), pcs: num(p.pcs) })),
    monthly: (f.monthly ?? []).map((m) => ({
      month: m.month, revenue: num(m.revenue), cost: num(m.cost),
      margin: num(m.margin), marginPct: opt(m.marginPct), invoices: num(m.invoices) })),
    receivableAgeing: slices(f.receivableAgeing),
    paymentMix: slices(f.paymentMix),
    revenueByMaker: slices(f.revenueByMaker),
  };
}

export default { getDashboard };
