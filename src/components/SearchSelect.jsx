/**
 * A select you can type into.
 *
 * <b>Why not the native one.</b> A native `<select>` is fine at ten options and
 * useless at four hundred: its type-ahead only matches from the first letter, so
 * finding "Sodium Bicarbonate" among the generics means either scrolling or
 * knowing it starts with S. This one matches anywhere in the name — "bicarb"
 * finds it — which is how somebody actually remembers a medicine.
 *
 * <b>Keyboard first, like the rest of entry.</b> Type to narrow, ↑/↓ to move,
 * Enter to take the highlighted one, Escape to close and leave the value alone.
 * Tab closes and moves on, so it still behaves like a field in a form rather
 * than a thing you have to escape from. It carries the same `id`, `disabled`
 * and `aria-invalid` a native select would, so the surrounding Field, its error
 * styling and the form's focus-the-first-bad-field all keep working.
 *
 * <b>The value is still the id.</b> `onChange` hands back a string exactly as
 * `e.target.value` did, so callers do not change and neither does what is sent
 * to the API.
 */
import React, { useEffect, useMemo, useRef, useState } from "react";

export default function SearchSelect({
  value,
  onChange,
  options = [],
  placeholder = "Select…",
  disabled = false,
  invalid = false,
  className = "",
  ariaLabel,
  emptyLabel = "Nothing matches that.",
  id,
}) {
  const [open, setOpen] = useState(false);
  const [q, setQ] = useState("");
  const [highlight, setHighlight] = useState(0);

  const wrapRef = useRef(null);
  const inputRef = useRef(null);
  const listRef = useRef(null);

  const selected = options.find((o) => String(o.id) === String(value)) || null;

  /* Matched anywhere in the name, not just at the start — the whole point of
     replacing the native control. Case-folded because nobody types a
     manufacturer's capitalisation from memory. */
  const shown = useMemo(() => {
    const term = q.trim().toLowerCase();
    if (!term) return options;
    return options.filter((o) => String(o.name ?? "").toLowerCase().includes(term));
  }, [q, options]);

  /* Opening lands on what is already chosen rather than at the top, so Enter
     twice is a no-op instead of a silent change to the first option. */
  useEffect(() => {
    if (!open) return;
    const at = shown.findIndex((o) => String(o.id) === String(value));
    setHighlight(at >= 0 ? at : 0);
  }, [open]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => { if (open) inputRef.current?.focus(); }, [open]);

  /* Keeps the highlighted row in view when it is moved by the keyboard. Without
     it, holding ↓ walks past the bottom of the box and the list stops appearing
     to respond. */
  useEffect(() => {
    if (!open) return;
    listRef.current?.children?.[highlight]?.scrollIntoView({ block: "nearest" });
  }, [highlight, open]);

  useEffect(() => {
    if (!open) return undefined;
    const away = (e) => { if (wrapRef.current && !wrapRef.current.contains(e.target)) close(); };
    document.addEventListener("mousedown", away);
    return () => document.removeEventListener("mousedown", away);
  }, [open]);

  const close = () => { setOpen(false); setQ(""); };

  const take = (option) => {
    if (!option) return;
    onChange(String(option.id));
    close();
    // Back to the control itself, so Tab carries on to the next field rather
    // than starting again from the top of the form.
    wrapRef.current?.querySelector("[data-ss-button]")?.focus();
  };

  const onKeyDown = (e) => {
    if (e.key === "ArrowDown") {
      e.preventDefault();
      if (!open) { setOpen(true); return; }
      setHighlight((h) => Math.min(h + 1, shown.length - 1));
    } else if (e.key === "ArrowUp") {
      e.preventDefault();
      if (!open) { setOpen(true); return; }
      setHighlight((h) => Math.max(h - 1, 0));
    } else if (e.key === "Enter") {
      // Only swallowed while the list is open. On a closed control Enter belongs
      // to the form, and stealing it would break Save from the keyboard.
      if (!open) return;
      e.preventDefault();
      take(shown[highlight]);
    } else if (e.key === "Escape") {
      if (!open) return;
      e.preventDefault();
      e.stopPropagation();   // the value stays as it was; the page keeps its own Escape
      close();
      wrapRef.current?.querySelector("[data-ss-button]")?.focus();
    } else if (e.key === "Tab") {
      close();               // leaves like any other field, no trap
    } else if (!open && e.key.length === 1) {
      // Typing on the closed control opens it and starts the search with that
      // letter, the way a native select's type-ahead begins.
      setOpen(true);
      setQ(e.key);
    }
  };

  return (
    <div className={`ss ${className}`} ref={wrapRef} onKeyDown={onKeyDown}>
      <button
        type="button"
        id={id}
        data-ss-button
        disabled={disabled}
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-invalid={invalid || undefined}
        aria-label={ariaLabel}
        className={`ss-control${invalid ? " field-error" : ""}${open ? " ss-open" : ""}`}
        onClick={() => (open ? close() : setOpen(true))}>
        <span className={selected ? "ss-value" : "ss-placeholder"}>
          {selected ? selected.name : placeholder}
        </span>
        <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor"
             strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
          <path d="M6 9l6 6 6-6" />
        </svg>
      </button>

      {open && (
        <div className="ss-pop" role="dialog">
          <input
            ref={inputRef}
            className="ss-search"
            value={q}
            placeholder="Type to search…"
            aria-label="Search the list"
            aria-autocomplete="list"
            onChange={(e) => { setQ(e.target.value); setHighlight(0); }} />

          <ul className="ss-list" role="listbox" ref={listRef}>
            {shown.length === 0 && <li className="ss-empty">{emptyLabel}</li>}
            {shown.map((o, i) => (
              <li
                key={o.id}
                role="option"
                aria-selected={String(o.id) === String(value)}
                className={`ss-option${i === highlight ? " on" : ""}`
                          + (String(o.id) === String(value) ? " ss-chosen" : "")}
                onMouseEnter={() => setHighlight(i)}
                /* mousedown, not click: the outside-click handler closes the
                   popup on mousedown, and a click listener would never fire. */
                onMouseDown={(e) => { e.preventDefault(); take(o); }}>
                {o.name}
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
}
