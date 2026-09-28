import { useState, useMemo } from "react";

/**
 * Generic client-side search over an array.
 * @param {Array} items - data array
 * @param {string[]} keys - object keys to search within
 * @returns {{ q, setQ, filtered }}
 */
export function useSearch(items, keys) {
  const [q, setQ] = useState("");
  const filtered = useMemo(() => {
    if (!q.trim()) return items;
    const s = q.toLowerCase();
    return items.filter(item => keys.some(k => String(item[k] ?? "").toLowerCase().includes(s)));
  }, [items, q, keys]);
  return { q, setQ, filtered };
}
