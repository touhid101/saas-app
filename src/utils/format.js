/** Pure formatting utilities */
import { CURRENCY_SYMBOL } from "../constants/index.js";

export const money = (n) =>
  `${CURRENCY_SYMBOL} ${Number(n || 0).toLocaleString("en-BD", { maximumFractionDigits: 0 })}`;

export const moneyFull = (n) =>
  `${CURRENCY_SYMBOL} ${Number(n || 0).toLocaleString("en-BD", { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;

export const num = (n) => Number(n || 0).toLocaleString("en-BD");

export const pct = (n) => `${Number(n || 0).toFixed(1)}%`;

export const fmtDate = (d) => {
  if (!d) return "—";
  const dt = typeof d === "string" ? new Date(d) : d;
  return dt.toLocaleDateString("en-BD", { day: "2-digit", month: "2-digit", year: "numeric" });
};

export const fmtDateISO = (d) => {
  if (!d) return "";
  const dt = typeof d === "string" ? new Date(d) : d;
  return dt.toISOString().slice(0, 10);
};

export const daysBetween = (a, b) =>
  Math.round((new Date(b) - new Date(a)) / 86400000);

export const truncate = (s, len = 30) =>
  s && s.length > len ? `${s.slice(0, len)}…` : s || "";

export const initials = (name = "") =>
  name.split(" ").map(w => w[0]).slice(0, 2).join("").toUpperCase();

/* ── Date/time helpers ─────────────────────────────────────────────────── */

/** Parse backend values (ISO string, epoch, Date) into a Date, or null.
 *  Also accepts the app's own dd/mm/yyyy display format, which `new Date()`
 *  either misreads as mm/dd/yyyy or rejects outright. */
const toDate = (value) => {
  if (!value) return null;
  if (value instanceof Date) return Number.isNaN(value.getTime()) ? null : value;

  if (typeof value === "string") {
    const dmy = value.match(/^(\d{1,2})\/(\d{1,2})\/(\d{4})$/);
    if (dmy) {
      const [, d, m, y] = dmy;
      const dt = new Date(Number(y), Number(m) - 1, Number(d));
      return Number.isNaN(dt.getTime()) ? null : dt;
    }
  }

  const dt = new Date(value);
  return Number.isNaN(dt.getTime()) ? null : dt;
};

/** "12:00 PM" */
export const fmtTime = (value) => {
  const dt = toDate(value);
  if (!dt) return "";
  return dt.toLocaleTimeString("en-US", { hour: "numeric", minute: "2-digit", hour12: true });
};

const isSameDay = (a, b) =>
  a.getFullYear() === b.getFullYear() &&
  a.getMonth() === b.getMonth() &&
  a.getDate() === b.getDate();

/**
 * Human-friendly timestamp anchored on today.
 *   today      → "Today, 12:00 PM"
 *   yesterday  → "Yesterday, 4:30 PM"
 *   this year  → "12 Aug, 4:30 PM"
 *   older      → "12 Aug 2025, 4:30 PM"
 */
export const fmtDateTimeFriendly = (value, fallback = "Never") => {
  const dt = toDate(value);
  if (!dt) return fallback;

  const now = new Date();
  const yesterday = new Date(now);
  yesterday.setDate(now.getDate() - 1);

  if (isSameDay(dt, now))       return `Today, ${fmtTime(dt)}`;
  if (isSameDay(dt, yesterday)) return `Yesterday, ${fmtTime(dt)}`;

  const datePart = dt.toLocaleDateString("en-GB", {
    day: "numeric",
    month: "short",
    ...(dt.getFullYear() !== now.getFullYear() && { year: "numeric" }),
  });
  return `${datePart}, ${fmtTime(dt)}`;
};

/** Compact relative age: "just now", "5m ago", "3h ago", "2d ago", then a date. */
export const fmtRelative = (value, fallback = "") => {
  const dt = toDate(value);
  if (!dt) return fallback;

  const seconds = Math.floor((Date.now() - dt.getTime()) / 1000);
  if (seconds < 0)     return fmtDateTimeFriendly(dt, fallback);
  if (seconds < 45)    return "just now";
  if (seconds < 3600)  return `${Math.max(1, Math.floor(seconds / 60))}m ago`;
  if (seconds < 86400) return `${Math.floor(seconds / 3600)}h ago`;
  if (seconds < 604800) return `${Math.floor(seconds / 86400)}d ago`;

  return dt.toLocaleDateString("en-GB", {
    day: "numeric",
    month: "short",
    ...(dt.getFullYear() !== new Date().getFullYear() && { year: "numeric" }),
  });
};
