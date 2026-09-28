// Small deterministic PRNG (mulberry32) so mock data is stable across reloads.
export function makeRng(seed = 42) {
  let a = seed >>> 0;
  return function rng() {
    a |= 0; a = (a + 0x6D2B79F5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

export function pick(rng, arr) { return arr[Math.floor(rng() * arr.length)]; }
export function pickN(rng, arr, n) {
  const pool = [...arr]; const out = [];
  while (out.length < n && pool.length) {
    const i = Math.floor(rng() * pool.length);
    out.push(pool.splice(i, 1)[0]);
  }
  return out;
}
export function int(rng, min, max) { return Math.floor(rng() * (max - min + 1)) + min; }
export function round2(n) { return Math.round(n * 100) / 100; }
export function addDays(base, days) { const d = new Date(base); d.setDate(d.getDate() + days); return d; }
export function fmtDate(d) { return d.toISOString().slice(0, 10); }
