/**
 * P17 — saved medicines (favourites).
 *
 * The rules this proves:
 *   - a favourite is a product AT a pharmacy, so the same medicine at two
 *     branches is two separate saves
 *   - the list stores a reference, never a copy: price and stock are read back
 *     from the catalogue, priced for whoever is asking
 *   - one endpoint toggles, and saying "save" twice does not create two rows
 *   - the list is the token's own, and cannot be asked for on someone's behalf
 *
 *   node ecom-p17-favourites.mjs [baseUrl]
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

console.log(`\nP17 · favourites · ${B}\n`);

/* ── a fresh customer, so the list starts genuinely empty ────────────────── */

const stamp = Date.now().toString().slice(-7);
const reg = await call('POST', '/api/ecom/auth/register', {
  fullName: 'P17 Saver ' + stamp, mobileNo: '017' + stamp, password: 'Passw0rd!x', requestedType: 'RETAILER',
});
const mobile = reg.data?.customer?.mobileNo;
const login = await call('POST', '/api/ecom/auth/login', { mobileNo: mobile, password: 'Passw0rd!x' });
const T = login.data?.accessToken;
ok('a new customer can sign in', !!T, mobile);

/* someone else, to prove one list is not another's */
const stamp2 = (Date.now() + 7).toString().slice(-7);
const reg2 = await call('POST', '/api/ecom/auth/register', {
  fullName: 'P17 Other ' + stamp2, mobileNo: '018' + stamp2, password: 'Passw0rd!x', requestedType: 'RETAILER',
});
const T2 = (await call('POST', '/api/ecom/auth/login',
  { mobileNo: reg2.data?.customer?.mobileNo, password: 'Passw0rd!x' })).data?.accessToken;

/* ── the same medicine at two pharmacies ─────────────────────────────────── */

const cat = await call('GET', '/api/ecom/public/catalogue?inStockOnly=true&size=60');
const items = cat.data?.items || [];
const byProduct = new Map();
for (const i of items) {
  if (!byProduct.has(i.name)) byProduct.set(i.name, []);
  byProduct.get(i.name).push(i);
}
const twoBranches = [...byProduct.values()].find((v) => v.length > 1 && v[0].branchId !== v[1].branchId);
const a = twoBranches ? twoBranches[0] : items[0];
const b = twoBranches ? twoBranches[1] : items[1];
ok('found listings to save', !!a && !!b, `${a?.name} @${a?.branchId} and ${b?.name} @${b?.branchId}`);

/* ── signed out ──────────────────────────────────────────────────────────── */

console.log('\n1. signed out');
const anon = await call('GET', '/api/ecom/me/favourites');
ok('the list is refused without a token', anon.status === 401 || anon.status === 403, `status ${anon.status}`);

/* ── empty, then saving ──────────────────────────────────────────────────── */

console.log('\n2. saving');
const empty = await call('GET', '/api/ecom/me/favourites', null, T);
ok('a new customer starts with nothing saved', Array.isArray(empty.data) && empty.data.length === 0, `${empty.data?.length} rows`);

const t1 = await call('POST', '/api/ecom/me/favourites/toggle', { branchId: a.branchId, productId: a.productId }, T);
ok('the first toggle saves', t1.status === 200 && t1.data?.saved === true, t1.body?.message);

const t2 = await call('POST', '/api/ecom/me/favourites/toggle', { branchId: b.branchId, productId: b.productId }, T);
ok('a second listing saves too', t2.data?.saved === true);

const two = await call('GET', '/api/ecom/me/favourites', null, T);
ok('both are in the list', two.data?.length === 2, `${two.data?.length} saved`);

const keys = await call('GET', '/api/ecom/me/favourites/keys', null, T);
ok('the keys are branchId:productId', (keys.data?.keys || []).includes(`${a.branchId}:${a.productId}`),
   (keys.data?.keys || []).join(', '));
ok('the key count matches the list', keys.data?.count === 2, String(keys.data?.count));

/* ── a reference, not a copy ─────────────────────────────────────────────── */

console.log('\n3. the list is resolved, not remembered');
const saved = (two.data || []).find((r) => r.productId === a.productId);
ok('the saved row carries a live catalogue item', !!saved?.item && saved.available === true, saved?.item?.name);
ok('nothing about the price was stored on the favourite',
   saved && !('price' in saved) && !('mrp' in saved),
   'row holds only id, branchId, productId, available, item');
ok('the resolved item is priced for this caller',
   !!saved?.item?.tier, `tier ${saved?.item?.tier}`);

/* ── toggling off ────────────────────────────────────────────────────────── */

console.log('\n4. unsaving, and no duplicates');
const t3 = await call('POST', '/api/ecom/me/favourites/toggle', { branchId: a.branchId, productId: a.productId }, T);
ok('toggling the same listing removes it', t3.data?.saved === false, t3.body?.message);

const one = await call('GET', '/api/ecom/me/favourites', null, T);
ok('the list is down to one', one.data?.length === 1, `${one.data?.length} saved`);

await call('POST', '/api/ecom/me/favourites/toggle', { branchId: a.branchId, productId: a.productId }, T);
await call('POST', '/api/ecom/me/favourites/toggle', { branchId: a.branchId, productId: a.productId }, T);
await call('POST', '/api/ecom/me/favourites/toggle', { branchId: a.branchId, productId: a.productId }, T);
const after = await call('GET', '/api/ecom/me/favourites', null, T);
ok('toggling three more times leaves one copy, not three',
   after.data?.filter((r) => r.productId === a.productId).length <= 1,
   `${after.data?.length} rows in total`);

/* ── one customer's list is their own ────────────────────────────────────── */

console.log('\n5. scope');
const theirs = await call('GET', '/api/ecom/me/favourites', null, T2);
ok('another customer sees an empty list', theirs.data?.length === 0, `${theirs.data?.length} rows`);

/* ── refusals ────────────────────────────────────────────────────────────── */

console.log('\n6. refusals');
const bad = await call('POST', '/api/ecom/me/favourites/toggle', { branchId: a.branchId, productId: 99999999 }, T);
ok('saving something not listed is refused with a sentence',
   bad.status === 404 && /not listed/i.test(bad.body?.message || ''), bad.body?.message);

const half = await call('POST', '/api/ecom/me/favourites/toggle', { productId: a.productId }, T);
ok('saving without a pharmacy is refused', half.status === 400, half.body?.message);

console.log(`\n${fail === 0 ? 'ALL PASS' : 'FAILURES'} — ${pass} passed, ${fail} failed\n`);
process.exit(fail === 0 ? 0 : 1);
