/**
 * financial.js — the one place money is formatted for the Accounts screens.
 *
 * Every accounting page reads its figures through these helpers, so a
 * negative shows the same way on the trial balance, the ledger and a KPI
 * tile, and a change of currency symbol or precision is one edit.
 */
import { CURRENCY_SYMBOL } from "../constants/index.js";

const nf2 = new Intl.NumberFormat("en-BD", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
const nf0 = new Intl.NumberFormat("en-BD", { maximumFractionDigits: 0 });

/** "৳ 1,234.50" · null/blank → "—" · negatives as "−৳ 1,234.50" (accounting style with `paren`: "(৳ 1,234.50)"). */
export function amount(v, { paren = false, zero = "৳ 0.00", symbol = true, dash = "—" } = {}) {
  if (v === null || v === undefined || v === "") return dash;
  const n = Number(v);
  if (Number.isNaN(n)) return dash;
  if (n === 0) return symbol ? zero : "0.00";
  const body = `${symbol ? CURRENCY_SYMBOL + " " : ""}${nf2.format(Math.abs(n))}`;
  if (n < 0) return paren ? `(${body})` : `−${body}`;
  return body;
}

/** Whole-currency figure for tiles: "৳ 12,345". */
export function compact(v) {
  if (v === null || v === undefined || v === "") return "—";
  const n = Number(v);
  if (Number.isNaN(n)) return "—";
  const body = `${CURRENCY_SYMBOL} ${nf0.format(Math.abs(n))}`;
  return n < 0 ? `−${body}` : body;
}

/** A debit/credit cell: blank when zero, so a column of noughts does not shout. */
export function drcr(v) {
  const n = Number(v || 0);
  return n === 0 ? "" : nf2.format(n);
}

export function pct(v, digits = 2) {
  if (v === null || v === undefined || v === "") return "—";
  return `${Number(v).toFixed(digits)}%`;
}

export function num(v) {
  if (v === null || v === undefined || v === "") return "—";
  return nf0.format(Number(v));
}

/** Signed with a word, for differences: "SHORT ৳ 5.78", "OVER ৳ 2.00", "BALANCED". */
export function variance(v) {
  const n = Number(v || 0);
  if (n === 0) return "BALANCED";
  return `${n < 0 ? "SHORT" : "OVER"} ${amount(Math.abs(n))}`;
}

export const isoToday = () => new Date().toISOString().slice(0, 10);
export const isoMonthStart = () => isoToday().slice(0, 8) + "01";

export function fmtDate(d) {
  if (!d) return "—";
  const s = typeof d === "string" ? d.slice(0, 10) : new Date(d).toISOString().slice(0, 10);
  const [y, m, day] = s.split("-");
  return `${day}/${m}/${y}`;
}

export function fmtDateTime(d) {
  if (!d) return "—";
  const dt = new Date(d);
  if (Number.isNaN(dt.getTime())) return "—";
  return `${fmtDate(dt.toISOString())} ${dt.toLocaleTimeString("en-US", { hour: "numeric", minute: "2-digit", hour12: true })}`;
}
