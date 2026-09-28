/**
 * Choose a customer by typing their name or their number.
 *
 * <b>Why not a dropdown.</b> Both selling screens used one, holding whatever the
 * first two hundred customers happened to be. A pharmacy with more than that
 * could not reach the rest at all, and even inside the two hundred the only way
 * to find somebody was to scroll — at a counter, with a queue.
 *
 * <b>Why it searches the server.</b> Filtering the loaded page would keep the
 * same ceiling, just better hidden. The number is the useful key at a till —
 * customers give it faster than they spell their name — and the server already
 * matches on either.
 *
 * <b>Shared by the till and the Sales page</b> because a second copy of a
 * lookup is a second place for it to drift, and the two screens are the same
 * question asked in two rooms. What differs is only whether "no customer" is a
 * legitimate answer: the till sells to a walk-in, an invoice needs a name. That
 * is `walkIn`.
 */
import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { getCustomers } from "../services/masterDataService.js";

export const custId     = (c) => c?.id ?? c?.pidCustomer;
export const custName   = (c) => c?.name ?? c?.customerName ?? "";
export const custCode   = (c) => String(c?.code ?? c?.customerCode ?? "").toUpperCase();
export const custType   = (c) => c?.type ?? c?.customerType ?? "Retailer";
export const custMobile = (c) => c?.mobileNo ?? c?.mobile ?? "";

/**
 * @param customers  the rows already loaded, searched instantly before the
 *                   server answers
 * @param selected   the chosen customer object, or null
 * @param onPick     called with the customer, or null for "no customer" —
 *                   which means walk-in where `walkIn` is given
 * @param walkIn     `{label, meta}` to offer a no-customer row, or null to
 *                   require a real one
 * @param hideCode   a customer code to keep out of the list, so the till's own
 *                   Walk-in record is not offered twice
 */
export default function CustomerPicker({
  customers = [], selected, onPick,
  walkIn = null, hideCode = null,
  id = "customer-picker", placeholder, className = "input",
  disabled = false,
}) {
  const [open, setOpen]     = useState(false);
  const [q, setQ]           = useState("");
  const [remote, setRemote] = useState(null);   // null until the server answers
  const [active, setActive] = useState(0);
  const boxRef = useRef(null);

  const empty = walkIn ? walkIn.label : "";
  const label = selected ? `${custName(selected)} · ${custType(selected)}` : empty;

  /* Local first so the list is never empty while the request is in flight, then
     replaced by the server's answer, which can see past the loaded page. */
  const local = useMemo(() => {
    const term = q.trim().toLowerCase();
    const pool = customers.filter((c) => !hideCode || custCode(c) !== hideCode);
    if (!term) return pool.slice(0, 20);
    return pool.filter((c) =>
      custName(c).toLowerCase().includes(term) ||
      String(custMobile(c)).includes(term)).slice(0, 20);
  }, [customers, q, hideCode]);

  useEffect(() => {
    const term = q.trim();
    if (!open || term.length < 2) { setRemote(null); return; }
    // Debounced: an operator types faster than a round trip, and one request per
    // keystroke would arrive out of order as well as be wasteful.
    let cancelled = false;
    const t = setTimeout(() => {
      getCustomers({ q: term, size: 20 })
        .then((r) => { if (!cancelled) setRemote(r.items ?? []); })
        .catch(() => { if (!cancelled) setRemote(null); });   // fall back to local
    }, 220);
    return () => { cancelled = true; clearTimeout(t); };
  }, [q, open]);

  const results = remote ?? local;
  const showWalkIn = walkIn
    && (!q.trim() || walkIn.label.toLowerCase().includes(q.trim().toLowerCase()));
  const options = showWalkIn ? [null, ...results] : results;

  useEffect(() => { setActive(0); }, [q, open]);

  // Clicking anywhere else closes it without changing the choice.
  useEffect(() => {
    if (!open) return;
    const away = (e) => { if (!boxRef.current?.contains(e.target)) { setOpen(false); setQ(""); } };
    document.addEventListener("mousedown", away);
    return () => document.removeEventListener("mousedown", away);
  }, [open]);

  const choose = useCallback((c) => { onPick(c); setOpen(false); setQ(""); }, [onPick]);

  const onKeyDown = (e) => {
    if (e.key === "ArrowDown" || e.key === "ArrowUp") {
      e.preventDefault();
      if (!open) { setOpen(true); return; }
      setActive((i) => {
        const n = options.length;
        if (n === 0) return 0;
        return e.key === "ArrowDown" ? (i + 1) % n : (i - 1 + n) % n;
      });
    } else if (e.key === "Enter") {
      if (open && options.length) { e.preventDefault(); choose(options[active]); }
    } else if (e.key === "Escape") {
      // Stopped here so a screen's own Escape — which clears the bill on the
      // till — does not also fire on the way out of a dropdown.
      e.stopPropagation();
      if (open) { setOpen(false); setQ(""); }
      else if (walkIn) choose(null);
    }
  };

  return (
    <div className="cust-picker" ref={boxRef}>
      <input
        id={id} className={className} role="combobox" disabled={disabled}
        aria-expanded={open} aria-autocomplete="list" aria-controls={`${id}-list`}
        value={open ? q : label}
        placeholder={placeholder ?? (walkIn ? walkIn.label : "Search name or mobile…")}
        onChange={(e) => { setQ(e.target.value); setOpen(true); }}
        onFocus={(e) => { setOpen(true); setQ(""); e.target.select(); }}
        onKeyDown={onKeyDown}
        title="Type a name or a mobile number"
      />
      {open && (
        <ul className="cust-list" id={`${id}-list`} role="listbox">
          {options.length === 0 && (
            <li className="cust-empty">
              {q.trim().length < 2 ? "Type a name or number…" : `No customer matches “${q.trim()}”`}
            </li>
          )}
          {options.map((c, i) => (
            <li key={c ? custId(c) : "__none"} role="option" aria-selected={i === active}
              className={`cust-opt${i === active ? " cust-on" : ""}`}
              onMouseEnter={() => setActive(i)}
              onMouseDown={(e) => { e.preventDefault(); choose(c); }}>
              {c === null ? (
                <><span className="cust-name">{walkIn.label}</span>
                  {walkIn.meta && <span className="cust-meta">{walkIn.meta}</span>}</>
              ) : (
                <><span className="cust-name">{custName(c)}</span>
                  <span className="cust-meta">
                    {custMobile(c) || "no number"} · {custType(c)}
                  </span></>
              )}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
