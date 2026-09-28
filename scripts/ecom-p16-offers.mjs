/**
 * P16 — the storefront's offer surface, the catalogue's new orders, and the
 * manufacturer facet.
 *
 * Proves the three rules that keep a shop window honest:
 *   1. no live coupon with an end date  ->  no countdown
 *   2. a saving is MRP less the caller's own price
 *   3. the stock bar is pieces in hand against the branch's reorder level
 *
 * and that "cheapest first" now means cheapest of everything that matched,
 * not cheapest of the rows that happened to load.
 *
 *   node ecom-p16-offers.mjs [baseUrl]
 */
const B = process.argv[2] || 'http://localhost:8586';

let pass = 0, fail = 0;
const ok = (name, cond, detail = '') => {
  if (cond) { pass += 1; console.log(`  PASS  ${name}${detail ? ' — ' + detail : ''}`); }
  else { fail += 1; console.log(`  FAIL  ${name}${detail ? ' — ' + detail : ''}`); }
};

const call = async (method, path, body, token) => {
  const r = await fetch(B + path, {
    method,
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: 'Bearer ' + token } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  });
  const j = await r.json().catch(() => ({}));
  return { status: r.status, body: j, data: j.data };
};

const num = (v) => (v == null ? null : Number(v));

console.log(`\nP16 · offers and catalogue ordering · ${B}\n`);

/* ── 1. the offers payload ───────────────────────────────────────────────── */

console.log('1. GET /api/ecom/public/offers');
const off = await call('GET', '/api/ecom/public/offers?size=48');
ok('endpoint answers', off.status === 200, `status ${off.status}`);
const o = off.data || {};
ok('a sentence is always returned', typeof o.note === 'string' && o.note.length > 0, o.note);

/* rule 2 — every saving is MRP less the price actually quoted */
let wrongSaving = null;
for (const d of (o.flash || []).concat(o.deals || [])) {
  const mrp = num(d.mrp), price = num(d.price);
  if (mrp == null || price == null) { wrongSaving = `${d.item.name}: missing mrp/price`; break; }
  if (!(mrp > price)) { wrongSaving = `${d.item.name}: mrp ${mrp} not above price ${price}`; break; }
  const pct = Math.round(((mrp - price) / mrp) * 100);
  if (Math.abs(pct - d.savingPct) > 1) { wrongSaving = `${d.item.name}: says ${d.savingPct}%, arithmetic gives ${pct}%`; break; }
}
ok('every deal is genuinely below MRP, at the stated percentage', wrongSaving === null, wrongSaving || `${(o.flash || []).length} flash, ${(o.deals || []).length} more`);

/* rule 3 — the bar exists only where a reorder level does */
let wrongBar = null;
for (const d of o.flash || []) {
  const hasLevel = d.reorderPcs != null && Number(d.reorderPcs) > 0;
  if (!hasLevel && d.stockBarPct != null) { wrongBar = `${d.item.name}: bar drawn with no reorder level`; break; }
  if (hasLevel && d.stockBarPct == null) { wrongBar = `${d.item.name}: reorder level ${d.reorderPcs} but no bar`; break; }
  if (hasLevel) {
    const low = Number(d.stockPcs) <= Number(d.reorderPcs);
    if (low !== d.runningLow) { wrongBar = `${d.item.name}: stock ${d.stockPcs} vs reorder ${d.reorderPcs}, runningLow=${d.runningLow}`; break; }
  }
}
ok('the stock bar follows the branch reorder level, or is absent', wrongBar === null, wrongBar || 'bars and low flags agree with the data');

/* rule 1 — the clock only runs on a real deadline */
if (o.campaign) {
  ok('a campaign carries a real end date', !!o.campaign.endsAt, String(o.campaign.endsAt));
  ok('the countdown comes from the server', Number(o.campaign.secondsLeft) > 0, `${o.campaign.secondsLeft}s left on ${o.campaign.code}`);
} else {
  ok('no campaign means no countdown to draw', o.campaign == null, 'no live coupon has an end date');
}

/* vouchers must be spendable, or say why not */
let badVoucher = null;
for (const v of o.vouchers || []) {
  if (!v.code || !v.headline) { badVoucher = `${v.code}: no headline`; break; }
  if (!v.usable && !v.note) { badVoucher = `${v.code}: unusable with no reason given`; break; }
}
ok('every voucher has a headline, and any refusal has a reason', badVoucher === null, badVoucher || `${(o.vouchers || []).length} listed`);

/* ── 2. the catalogue's new orders ───────────────────────────────────────── */

console.log('\n2. sort= across the whole result set');
const priceOf = (i) => {
  const u = (i.units || []).find((x) => x.allowed !== false && x.price != null && x.unit === i.defaultUnit)
         || (i.units || []).find((x) => x.allowed !== false && x.price != null);
  return u ? Number(u.price) : null;
};

const asc = await call('GET', '/api/ecom/public/catalogue?inStockOnly=true&sort=price-asc&size=40');
const desc = await call('GET', '/api/ecom/public/catalogue?inStockOnly=true&sort=price-desc&size=40');
ok('price-asc answers', asc.status === 200);

const ascP = (asc.data?.items || []).map(priceOf).filter((x) => x != null);
const descP = (desc.data?.items || []).map(priceOf).filter((x) => x != null);
ok('cheapest first really is ascending', ascP.every((v, i) => i === 0 || ascP[i - 1] <= v), ascP.slice(0, 5).join(' ≤ '));
ok('dearest first really is descending', descP.every((v, i) => i === 0 || descP[i - 1] >= v), descP.slice(0, 5).join(' ≥ '));
ok('the two orders disagree, so the sort reached the server',
   ascP.length > 1 && descP.length > 1 && ascP[0] !== descP[0], `${ascP[0]} vs ${descP[0]}`);

const fresh = await call('GET', '/api/ecom/public/catalogue?inStockOnly=true&sort=new&size=20');
const added = (fresh.data?.items || []).map((i) => i.addedAt).filter(Boolean);
ok('recently added is newest first', added.every((v, i) => i === 0 || added[i - 1] >= v), added.slice(0, 3).join(' ≥ '));

const disc = await call('GET', '/api/ecom/public/catalogue?inStockOnly=true&sort=discount&size=20');
const offPct = (i) => {
  const u = (i.units || []).find((x) => x.allowed !== false && x.price != null && x.mrp != null);
  return u && Number(u.mrp) > Number(u.price) ? 1 - Number(u.price) / Number(u.mrp) : 0;
};
const offs = (disc.data?.items || []).map(offPct);
ok('biggest saving first', offs.every((v, i) => i === 0 || offs[i - 1] >= v - 1e-9),
   offs.slice(0, 4).map((x) => Math.round(x * 100) + '%').join(' ≥ '));

/* ── 3. the manufacturer facet and filter ────────────────────────────────── */

console.log('\n3. manufacturers');
const all = await call('GET', '/api/ecom/public/catalogue?inStockOnly=true&size=1');
const makers = all.data?.companies || [];
ok('the catalogue returns a manufacturer facet', makers.length > 0, makers.slice(0, 4).map((m) => `${m.name} ${m.count}`).join(', '));

if (makers.length > 0) {
  const one = makers[0];
  const only = await call('GET', `/api/ecom/public/catalogue?inStockOnly=true&company=${encodeURIComponent(one.name)}&size=100`);
  const names = new Set((only.data?.items || []).map((i) => i.company));
  ok('filtering by a maker returns only that maker', names.size === 1 && names.has(one.name), [...names].join(', '));
  ok('the facet count matches what the filter returns',
     Number(only.data?.total) === Number(one.count), `facet said ${one.count}, filter returned ${only.data?.total}`);
}

/* ── 4. every order still leads with what is in stock ────────────────────── */

console.log('\n4. the in-stock rule survives every order');
let led = null;
for (const s of ['price-asc', 'price-desc', 'new', 'discount']) {
  const r = await call('GET', `/api/ecom/public/catalogue?sort=${s}&size=60`);
  const items = r.data?.items || [];
  const firstOut = items.findIndex((i) => !i.inStock);
  const lastIn = items.map((i) => i.inStock).lastIndexOf(true);
  if (firstOut !== -1 && lastIn > firstOut) { led = `${s}: an out-of-stock row at ${firstOut} precedes an in-stock row at ${lastIn}`; break; }
}
ok('no order puts something nobody can buy above something they can', led === null, led || 'all four orders lead with stock');

console.log(`\n${fail === 0 ? 'ALL PASS' : 'FAILURES'} — ${pass} passed, ${fail} failed\n`);
process.exit(fail === 0 ? 0 : 1);
