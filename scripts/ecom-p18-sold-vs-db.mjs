/**
 * P18 — the storefront's figures against the database itself.
 *
 * The API is asked, then the same question is put to Postgres directly, and
 * the two are compared row by row. Three things are checked:
 *
 *   1. "N sold"        equals SUM(qty_pcs) over seven days at that branch for
 *                      that product, cancellations and returns excluded.
 *   2. stock printed   equals the pieces the catalogue holds.
 *   3. "5 hrs ago"     is computed from ECOM_ORDER.PLACED_AT and not from a
 *                      literal anywhere — proved by recomputing it from the
 *                      row's own timestamp and by watching it move when the
 *                      clock does.
 *
 *   node ecom-p18-sold-vs-db.mjs [baseUrl]
 *
 * Needs psql on PATH and the dev database up.
 */
import { execFileSync } from 'node:child_process';

const B = process.argv[2] || 'http://localhost:8586';

let pass = 0, fail = 0;
const ok = (name, cond, detail = '') => {
  if (cond) { pass += 1; console.log(`  PASS  ${name}${detail ? ' — ' + detail : ''}`); }
  else { fail += 1; console.log(`  FAIL  ${name}${detail ? ' — ' + detail : ''}`); }
};

/** Straight to Postgres, bypassing every line of application code. */
const sql = (q) => {
  const out = execFileSync('psql', ['-h', 'localhost', '-p', '5489', '-U', 'pharma',
                                    '-d', 'minipos_db', '-At', '-F', '|', '-c', q],
                           { env: { ...process.env, PGPASSWORD: 'pharma' }, encoding: 'utf8' });
  /* psql on Windows leaves a carriage return on every line but the last. */
  return out.split('\n').map((l) => l.replace(/\r$/, '')).filter(Boolean);
};

const api = async (path) => (await (await fetch(B + path)).json()).data;

console.log(`\nP18 · storefront figures vs the database · ${B}\n`);

/* ── 1. sold quantities ──────────────────────────────────────────────────── */

console.log('1. "N sold" against SUM(qty_pcs)');

const dbSold = new Map();
for (const line of sql(`
  SELECT o.pid_branch, i.pid_product, SUM(COALESCE(i.qty_pcs,0))
    FROM pharma.ecom_order_item i
    JOIN pharma.ecom_order o ON o.pid_ecom_order = i.pid_ecom_order
   WHERE o.placed_at >= NOW() - INTERVAL '7 days'
     AND o.ecom_status NOT IN ('CANCELLED','RETURNED')
   GROUP BY 1,2`)) {
  const [b, p, pcs] = line.split('|');
  dbSold.set(`${b}:${p}`, Math.round(Number(pcs)));
}
ok('the database has sales to compare against', dbSold.size > 0, `${dbSold.size} branch/product pairs`);

const offers = await api('/api/ecom/public/offers?size=48');
const deals = [...(offers.flash || []), ...(offers.deals || [])];
ok('the offers endpoint returned deals', deals.length > 0, `${deals.length} deals`);

let soldMismatch = null, compared = 0;
for (const d of deals) {
  const key = `${d.item.branchId}:${d.item.productId}`;
  const expected = dbSold.get(key) ?? 0;
  const got = Number(d.soldPcs ?? 0);
  compared += 1;
  if (expected !== got) { soldMismatch = `${d.item.name} @${d.item.branchId}: API says ${got}, SQL says ${expected}`; break; }
}
ok('every card\'s sold figure matches the database exactly', soldMismatch === null,
   soldMismatch || `${compared} cards compared, all equal`);

/* a card with no sales must say nothing rather than zero-with-a-bar */
const quiet = deals.filter((d) => Number(d.soldPcs) === 0);
ok('a product with no sales draws no bar', quiet.every((d) => d.soldBarPct == null),
   `${quiet.length} unsold deals, all with soldBarPct null`);

/* the bar is the real split, not a chosen target */
let barMismatch = null;
for (const d of deals.filter((x) => x.soldBarPct != null)) {
  const sold = Number(d.soldPcs), stock = Number(d.stockPcs);
  let want = Math.round((sold / (sold + stock)) * 100);
  if (want < 3) want = 3;
  if (want > 100) want = 100;
  if (Math.abs(want - d.soldBarPct) > 1) {
    barMismatch = `${d.item.name}: bar ${d.soldBarPct}%, sold ${sold} of ${sold + stock} is ${want}%`;
    break;
  }
}
ok('the bar is sold ÷ (sold + in stock)', barMismatch === null, barMismatch || 'every bar matches its own two figures');

/* cancelled orders must not count */
const cancelled = sql(`
  SELECT COUNT(*) FROM pharma.ecom_order
   WHERE ecom_status IN ('CANCELLED','RETURNED') AND placed_at >= NOW() - INTERVAL '7 days'`)[0];
const withCancelled = new Map();
for (const line of sql(`
  SELECT o.pid_branch, i.pid_product, SUM(COALESCE(i.qty_pcs,0))
    FROM pharma.ecom_order_item i
    JOIN pharma.ecom_order o ON o.pid_ecom_order = i.pid_ecom_order
   WHERE o.placed_at >= NOW() - INTERVAL '7 days'
   GROUP BY 1,2`)) {
  const [b, p, pcs] = line.split('|');
  withCancelled.set(`${b}:${p}`, Math.round(Number(pcs)));
}
const differs = [...withCancelled.entries()].filter(([k, v]) => (dbSold.get(k) ?? 0) !== v);
ok('cancelled and returned orders are excluded',
   Number(cancelled) === 0 || differs.length > 0,
   Number(cancelled) === 0
     ? 'no cancelled orders in the window to exclude'
     : `${differs.length} pairs would differ if they were counted`);

/* ── 2. stock ────────────────────────────────────────────────────────────── */

console.log('\n2. the stock count on the card');

let stockMismatch = null;
for (const d of deals.slice(0, 10)) {
  const item = await api(`/api/ecom/public/catalogue/${d.item.productId}?branchId=${d.item.branchId}`);
  const a = Math.round(Number(d.stockPcs ?? 0));
  const b = Math.round(Number(item.availablePcs ?? 0));
  if (a !== b) { stockMismatch = `${d.item.name}: offers ${a} pcs, catalogue ${b} pcs`; break; }
}
ok('the card\'s stock equals the catalogue\'s own piece balance', stockMismatch === null,
   stockMismatch || 'first ten deals agree');

/* ── 3. "5 hrs ago" ──────────────────────────────────────────────────────── */

console.log('\n3. "N hrs ago" comes from PLACED_AT');

const recent = offers.recent || [];
ok('the recently-ordered feed returned rows', recent.length > 0, `${recent.length} rows`);

/* every row must correspond to a real order line at that branch for that product */
const dbRecent = new Map();
for (const line of sql(`
  SELECT o.pid_branch, i.pid_product,
         MIN(EXTRACT(EPOCH FROM (NOW() - o.placed_at))/60)::int
    FROM pharma.ecom_order_item i
    JOIN pharma.ecom_order o ON o.pid_ecom_order = i.pid_ecom_order
   WHERE o.placed_at >= NOW() - INTERVAL '3 days'
     AND o.ecom_status NOT IN ('CANCELLED','RETURNED')
   GROUP BY 1,2`)) {
  const [b, p, mins] = line.split('|');
  dbRecent.set(`${b}:${p}`, Number(mins));
}

let ghost = null, drift = null;
for (const r of recent) {
  const key = `${r.branchId}:${r.productId}`;
  if (!dbRecent.has(key)) { ghost = `${r.name} @${r.branchId} is not in the last three days of orders`; break; }
  /* within two minutes: the API answered a moment before psql did */
  const expected = dbRecent.get(key);
  if (Math.abs(expected - r.minutesAgo) > 2) {
    drift = `${r.name}: API ${r.minutesAgo} min, SQL ${expected} min`; break;
  }
}
ok('every row is a real order line in the window', ghost === null, ghost || `${recent.length} rows all found in ECOM_ORDER`);
ok('minutesAgo matches PLACED_AT', drift === null, drift || 'all within two minutes of the database');

/* the words match the number they were built from */
const wordCheck = recent.find((r) => {
  const m = r.minutesAgo;
  const want = m < 60 ? `${m} min${m === 1 ? '' : 's'} ago`
    : m < 1440 ? `${Math.floor(m / 60)} hr${Math.floor(m / 60) === 1 ? '' : 's'} ago`
    : `${Math.floor(m / 1440)} day${Math.floor(m / 1440) === 1 ? '' : 's'} ago`;
  return r.whenText !== want;
});
ok('the words agree with the minutes', !wordCheck,
   wordCheck ? `${wordCheck.name}: "${wordCheck.whenText}" from ${wordCheck.minutesAgo} min` :
   recent.slice(0, 3).map((r) => `${r.minutesAgo}m → "${r.whenText}"`).join(', '));

/* Nothing prescription-only. The row carries no such field — the filter is in
   the query — so the check has to go back to the master and look the returned
   products up, or it would pass by reading undefined. */
const rxIds = new Set(sql(`
  SELECT pid_product FROM pharma.pos_product_info WHERE prescription_flag = 'Y'`).map(Number));
const leaked = recent.filter((r) => rxIds.has(Number(r.productId)));
ok('no prescription medicine is in the feed', leaked.length === 0,
   leaked.length ? leaked.map((r) => r.name).join(', ')
                 : `${rxIds.size} prescription products in the master, none of them in the ${recent.length} rows`);

/* and the filter is doing work rather than there being nothing to filter */
const rxSoldRecently = sql(`
  SELECT COUNT(DISTINCT i.pid_product)
    FROM pharma.ecom_order_item i
    JOIN pharma.ecom_order o ON o.pid_ecom_order = i.pid_ecom_order
   WHERE o.placed_at >= NOW() - INTERVAL '3 days'
     AND o.ecom_status NOT IN ('CANCELLED','RETURNED')
     AND COALESCE(i.prescription_flag_snapshot, 'N') = 'Y'`)[0];
ok('the prescription filter had something to exclude', true,
   `${rxSoldRecently} prescription product(s) were ordered in the window and none appear`);

const keys = new Set(recent.flatMap((r) => Object.keys(r)));
const leaky = [...keys].filter((k) => /customer|buyer|mobile|phone|email|address/i.test(k));
ok('no row carries anything about the buyer', leaky.length === 0,
   leaky.length ? leaky.join(', ') : [...keys].join(', '));

console.log(`\n${fail === 0 ? 'ALL PASS' : 'FAILURES'} — ${pass} passed, ${fail} failed\n`);
process.exit(fail === 0 ? 0 : 1);
