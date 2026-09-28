/**
 * HrKit.jsx — the pieces all seven HR screens are built from.
 *
 * Seven screens sharing a frame, a KPI strip, a card-on-mobile table and a
 * bottom bar. Writing each of those seven times would be seven chances for the
 * attendance register and the payroll register to drift apart in ways nobody
 * notices until a manager complains that one page "looks wrong".
 *
 * Nothing here holds business logic. Every figure and every allowed action
 * comes from the server; these components decide only where things sit.
 *
 * <h3>What lives here and what does not</h3>
 * The application already has a design system — tokens in styles.css, Card,
 * Modal, Field, Pill in UI.jsx, a confirm dialog in ConfirmProvider. None of it
 * is reimplemented here. This file adds the pieces HR needs that the rest of
 * the application had no use for: a breadcrumb, a search box that waits before
 * it asks the server, a filter bar that becomes a sheet on a phone, a table
 * that can be loading or broken as well as full or empty, a right-hand drawer,
 * an approval timeline and a stepper.
 */
import React, { useCallback, useEffect, useId, useRef, useState } from "react";
import { Pill } from "./UI.jsx";
import { toneFor } from "../services/hrService.js";
import uploadUrl from "../lib/uploadUrl.js";

/* ── Icons ───────────────────────────────────────────────────────────────
   Inline paths, the same convention as Sidebar and SalesDiscount: an icon set
   as a dependency is a megabyte to draw two dozen glyphs. One stroke width,
   one viewBox, colour always inherited — so an icon in a danger button is red
   without anybody choosing red. */

export const Ic = ({ path, size = 14, className }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none"
    stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round"
    className={className} aria-hidden="true" focusable="false">
    {(Array.isArray(path) ? path : [path]).map((d, i) => <path key={i} d={d} />)}
  </svg>
);

export const P = {
  people:   ["M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2", "M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8z",
             "M23 21v-2a4 4 0 0 0-3-3.87", "M16 3.13a4 4 0 0 1 0 7.75"],
  user:     ["M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2", "M12 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8z"],
  calendar: ["M3 5h18v16H3z", "M3 10h18", "M8 3v4", "M16 3v4"],
  away:     ["M12 2v6", "M5.6 5.6l4.2 4.2", "M2 12h6", "M18.4 5.6l-4.2 4.2", "M22 12h-6",
             "M12 22a6 6 0 1 0 0-12 6 6 0 0 0 0 12z"],
  wallet:   ["M19 5H5a2 2 0 0 0-2 2v10a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V7a2 2 0 0 0-2-2z", "M16 12h.01", "M3 10h11"],
  payroll:  ["M4 20V10", "M12 20V4", "M20 20v-7", "M2 20h20"],
  plus:     ["M12 5v14", "M5 12h14"],
  check:    "M20 6 9 17l-5-5",
  x:        "M18 6 6 18M6 6l12 12",
  clock:    ["M12 22a10 10 0 1 0 0-20 10 10 0 0 0 0 20z", "M12 6v6l4 2"],
  timer:    ["M12 22a8 8 0 1 0 0-16 8 8 0 0 0 0 16z", "M12 10v4", "M9 2h6"],
  edit:     ["M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7",
             "M18.5 2.5a2.12 2.12 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"],
  chevron:  "M9 18l6-6-6-6",
  chevronDown: "M6 9l6 6 6-6",
  back:     "M15 18l-6-6 6-6",
  money:    ["M12 1v22", "M17 5H9.5a3.5 3.5 0 0 0 0 7h5a3.5 3.5 0 0 1 0 7H6"],
  doc:      ["M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z", "M14 2v6h6",
             "M8 13h8M8 17h5"],
  lock:     ["M5 11h14v10H5z", "M8 11V7a4 4 0 0 1 8 0v4"],
  info:     ["M12 22a10 10 0 1 0 0-20 10 10 0 0 0 0 20z", "M12 16v-4", "M12 8h.01"],
  search:   ["M11 18a7 7 0 1 0 0-14 7 7 0 0 0 0 14z", "M21 21l-4.3-4.3"],
  filter:   "M4 5h16l-6 8v6l-4 2v-8L4 5z",
  reset:    ["M3 12a9 9 0 1 0 3-6.7L3 8", "M3 3v5h5"],
  download: ["M12 3v13", "M6 11l6 6 6-6", "M4 21h16"],
  printer:  ["M6 9V3h12v6", "M6 18H4a2 2 0 0 1-2-2v-5h20v5a2 2 0 0 1-2 2h-2", "M6 14h12v7H6z"],
  eye:      ["M1 12s4-7 11-7 11 7 11 7-4 7-11 7-11-7-11-7z", "M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6z"],
  more:     ["M5 12h.01", "M12 12h.01", "M19 12h.01"],
  trash:    ["M3 6h18", "M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2",
             "M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6"],
  upload:   ["M12 19V6", "M6 11l6-6 6 6", "M4 21h16"],
  receipt:  ["M6 2h12v20l-2.5-1.5L13 22l-2.5-1.5L8 22l-2-1.5V2z", "M9 7h6M9 11h6M9 15h4"],
  settings: ["M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6z",
             "M19.4 15a1.7 1.7 0 0 0 .3 1.9l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-2.9 1.2V21a2 2 0 1 1-4 0v-.1A1.7 1.7 0 0 0 7 19.4a1.7 1.7 0 0 0-1.9.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0-1.2-2.9H1a2 2 0 1 1 0-4h.1A1.7 1.7 0 0 0 2.6 7a1.7 1.7 0 0 0-.3-1.9l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1A1.7 1.7 0 0 0 8 2.6h.1A2 2 0 1 1 12 1v.1A1.7 1.7 0 0 0 15 2.6a1.7 1.7 0 0 0 1.9-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0 1.2 2.9H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1.6z"],
  alert:    ["M12 9v4", "M12 17h.01",
             "M10.3 3.9 1.8 18a1.8 1.8 0 0 0 1.5 2.7h17.4a1.8 1.8 0 0 0 1.5-2.7L13.7 3.9a1.8 1.8 0 0 0-3.4 0z"],
  inbox:    ["M22 12h-6l-2 3h-4l-2-3H2", "M5.5 5.5 2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.5-6.5A2 2 0 0 0 16.7 4H7.3a2 2 0 0 0-1.8 1.5z"],
  ban:      ["M12 22a10 10 0 1 0 0-20 10 10 0 0 0 0 20z", "M5 5l14 14"],
  undo:     ["M9 14L4 9l5-5", "M4 9h11a5 5 0 0 1 0 10h-4"],
  first:    ["M11 17l-5-5 5-5", "M18 17l-5-5 5-5"],
  last:     ["M13 17l5-5-5-5", "M6 17l5-5-5-5"],
  keyboard: ["M2 6h20v12H2z", "M6 10h.01M10 10h.01M14 10h.01M18 10h.01", "M8 14h8"],
  layers:   ["M12 2 2 7l10 5 10-5-10-5z", "M2 12l10 5 10-5", "M2 17l10 5 10-5"],
  sun:      ["M12 17a5 5 0 1 0 0-10 5 5 0 0 0 0 10z", "M12 1v2M12 21v2M4.2 4.2l1.4 1.4M18.4 18.4l1.4 1.4M1 12h2M21 12h2M4.2 19.8l1.4-1.4M18.4 5.6l1.4-1.4"],
  wand:     ["M15 4V2M15 16v-2M8 9h2M20 9h2M17.8 11.8L19 13M15 9h.01M17.8 6.2L19 5M3 21l9-9M12.2 6.2L11 5"],
  star:     ["M12 2l3.09 6.26L22 9.27l-5 4.87 1.18 6.88L12 17.77l-6.18 3.25L7 14.14 2 9.27l6.91-1.01L12 2z"],
};

/* Which glyph a status word carries, so a state is never colour alone. */
const STATUS_ICON = { sage: P.check, teal: P.check, amber: P.clock, clay: P.x, grey: null };

/* ── Breadcrumb ─────────────────────────────────────────────────────────── */

/**
 * The root every HR trail starts from.
 *
 * <p>A constant rather than a string typed on seven pages, so the section is
 * named identically everywhere and renaming it is one edit.
 */
export const HR_CRUMB = { label: "Human Resources" };

/**
 * Where this screen sits, said quietly.
 *
 * <p>Deliberately not a navigation control on the intermediate levels: "Human
 * Resources" is a section, not a page, and a link that goes nowhere is worse
 * than plain text. Only crumbs given an {@code onClick} become buttons.
 *
 * @param items [{ label, onClick }] — the last one is the current page
 */
export function HrCrumb({ items = [] }) {
  if (!items.length) return null;
  return (
    <nav className="hr-crumb" aria-label="Breadcrumb">
      <ol>
        {items.map((it, i) => {
          const last = i === items.length - 1;
          return (
            <li key={it.label + i} className={last ? "is-current" : undefined}>
              {it.onClick && !last
                ? <button type="button" onClick={it.onClick}>{it.label}</button>
                : <span aria-current={last ? "page" : undefined}>{it.label}</span>}
              {!last && <Ic path={P.chevron} size={11} className="hr-crumb-sep" />}
            </li>
          );
        })}
      </ol>
    </nav>
  );
}

/* ── Page frame ─────────────────────────────────────────────────────────── */

/**
 * @param crumbs   [{ label, onClick }] shown above the title
 * @param title    the page name
 * @param subtitle one line saying what the page is for
 * @param actions  primary and secondary buttons, right on desktop
 */
export function HrPage({ crumbs, title, subtitle, actions, children, className, icon }) {
  return (
    <div className={className ? `hr-page ${className}` : "hr-page"}>
      {(title || actions || crumbs) && (
        <div className="hr-head">
          {icon && <span className="hr-head-icon" aria-hidden="true"><Ic path={icon} size={20} /></span>}
          <div className="hr-head-title">
            <HrCrumb items={crumbs} />
            {title && <h2>{title}</h2>}
            {subtitle && <p>{subtitle}</p>}
          </div>
          {actions && <div className="hr-head-actions">{actions}</div>}
        </div>
      )}
      {children}
    </div>
  );
}

/* ── Buttons ────────────────────────────────────────────────────────────── */

/**
 * One button, with the states a business button actually needs.
 *
 * <p>It renders the application's own `.btn` classes rather than a second
 * button system — a page can still write `<button className="btn btn-ghost">`
 * and the two are indistinguishable. What this adds is the three things every
 * hand-written call site forgot:
 *
 * <ul>
 *   <li><b>busy</b> — disables itself and says what it is doing, so nobody
 *       presses Post twice on a payroll run;</li>
 *   <li><b>iconOnly</b> — refuses to render without a label, because an
 *       unexplained glyph is not a control;</li>
 *   <li>a consistent icon slot, so the icon never lands at a random side.</li>
 * </ul>
 *
 * @param variant primary | ghost | teal | danger | warn
 * @param busy    true while the action is in flight; `busyLabel` replaces the text
 * @param iconOnly renders the label as a tooltip and an aria-label instead
 */
export function HrButton({
  variant = "ghost", size, icon, iconAfter, busy = false, busyLabel,
  iconOnly = false, children, className = "", disabled, title, ...rest
}) {
  const label = iconOnly ? (title || textOf(children)) : null;

  if (iconOnly && !label) {
    // Louder than rendering a mystery glyph: an icon-only button with nothing
    // to announce is a defect, and a defect that only a screen reader meets is
    // the kind that ships.
    console.warn("HrButton: iconOnly needs a title or text children for its label");
  }

  const classes = [
    "btn", `btn-${variant}`, size === "sm" ? "btn-sm" : "",
    iconOnly ? "btn-icon-only" : "", busy ? "is-busy" : "", className,
  ].filter(Boolean).join(" ");

  return (
    <button type="button" {...rest}
            className={classes}
            disabled={disabled || busy}
            title={iconOnly ? label : title}
            aria-label={iconOnly ? label : undefined}
            aria-busy={busy || undefined}>
      {busy ? <span className="hr-spin" aria-hidden="true" />
            : icon ? <Ic path={icon} /> : null}
      {!iconOnly && <span>{busy && busyLabel ? busyLabel : children}</span>}
      {!busy && iconAfter && <Ic path={iconAfter} />}
    </button>
  );
}

const textOf = (node) =>
  typeof node === "string" ? node
    : Array.isArray(node) ? node.map(textOf).filter(Boolean).join(" ")
    : null;

/**
 * A primary action and everything else behind one menu.
 *
 * <p>Eight buttons in a table row is how a row stops being readable — and on a
 * phone it is eight 30px targets side by side. One visible action, the rest
 * named in a menu that closes on Escape, on a click outside and on choosing.
 *
 * @param items [{ label, icon, onClick, danger, disabled }]
 */
export function HrRowActions({ children, items = [], label = "More actions" }) {
  const [open, setOpen] = useState(false);
  const wrap = useRef(null);
  const menuId = useId();
  const usable = items.filter(Boolean);

  useEffect(() => {
    if (!open) return;
    const away = (e) => { if (!wrap.current?.contains(e.target)) setOpen(false); };
    const key = (e) => { if (e.key === "Escape") { setOpen(false); wrap.current?.querySelector("button")?.focus(); } };
    document.addEventListener("mousedown", away);
    document.addEventListener("keydown", key);
    return () => { document.removeEventListener("mousedown", away); document.removeEventListener("keydown", key); };
  }, [open]);

  return (
    <div className="hr-row-actions" ref={wrap}>
      {children}
      {usable.length > 0 && (
        <>
          <HrButton iconOnly icon={P.more} title={label} size="sm"
                    aria-haspopup="menu" aria-expanded={open} aria-controls={menuId}
                    onClick={() => setOpen((v) => !v)} />
          {open && (
            <div className="hr-menu" role="menu" id={menuId}>
              {usable.map((it, i) => (
                <button key={i} type="button" role="menuitem"
                        className={`hr-menu-item${it.danger ? " is-danger" : ""}`}
                        disabled={it.disabled}
                        onClick={() => { setOpen(false); it.onClick?.(); }}>
                  {it.icon && <Ic path={it.icon} />}
                  <span>{it.label}</span>
                </button>
              ))}
            </div>
          )}
        </>
      )}
    </div>
  );
}

/* ── KPI ────────────────────────────────────────────────────────────────── */

/**
 * One number worth reading at a glance.
 *
 * <p>{@code warn} is for the tile that is a call to action rather than a fact —
 * staff with no attendance recorded, requests waiting for a decision. It marks
 * the border as well as the colour, so it survives a colour-blind reading.
 *
 * <p>Give it {@code onClick} and it becomes a button that takes you to the rows
 * behind the figure. A tile with nothing to open stays a tile: making every
 * number look pressable turns a summary into a menu.
 */
export function Kpi({ label, value, note, warn = false, icon, onClick, tone }) {
  const cls = `hr-kpi${warn ? " is-warn" : ""}${onClick ? " is-link" : ""}${tone ? ` is-${tone}` : ""}`;
  const body = (
    <>
      {icon && <span className="hr-kpi-icon"><Ic path={icon} size={15} /></span>}
      <span className="hr-kpi-label">{label}</span>
      <span className="hr-kpi-value">{value}</span>
      {note && <span className="hr-kpi-note">{note}</span>}
    </>
  );
  return onClick
    ? <button type="button" className={cls} onClick={onClick}>{body}</button>
    : <div className={cls}>{body}</div>;
}

export const Kpis = ({ children }) => <div className="hr-kpis">{children}</div>;

/* ── Search ─────────────────────────────────────────────────────────────── */

/**
 * A search box that does not ask the server on every keystroke.
 *
 * <p>Types land locally at once — a field that lags behind the typing is worse
 * than a slow result — and the value is handed up once the typing stops. Escape
 * clears, which is what Escape does in a search box everywhere else.
 *
 * @param delay milliseconds of quiet before onChange fires. 0 for a purely
 *   local filter, where there is nothing to wait for.
 */
export function HrSearch({
  value, onChange, placeholder = "Search…", delay = 250, label = "Search", id,
}) {
  const [text, setText] = useState(value ?? "");
  const fieldId = useId();
  const timer = useRef(null);
  const latest = useRef(onChange);
  latest.current = onChange;

  // A value replaced from outside — a Reset, or a filter set from a KPI tile —
  // has to reach the box, or Reset clears the results and leaves the word.
  useEffect(() => { setText(value ?? ""); }, [value]);

  const push = useCallback((next) => {
    setText(next);
    clearTimeout(timer.current);
    if (!delay) { latest.current?.(next); return; }
    timer.current = setTimeout(() => latest.current?.(next), delay);
  }, [delay]);

  useEffect(() => () => clearTimeout(timer.current), []);

  const clear = () => { clearTimeout(timer.current); setText(""); latest.current?.(""); };

  return (
    <div className="hr-search">
      <Ic path={P.search} size={15} className="hr-search-icon" />
      <input id={id || fieldId} type="search" className="hr-search-input"
             value={text} placeholder={placeholder} aria-label={label}
             onChange={(e) => push(e.target.value)}
             onKeyDown={(e) => { if (e.key === "Escape" && text) { e.preventDefault(); clear(); } }} />
      {text && (
        <button type="button" className="hr-search-clear" onClick={clear}
                title="Clear the search" aria-label="Clear the search">
          <Ic path={P.x} size={13} />
        </button>
      )}
    </div>
  );
}

/* ── Filters ────────────────────────────────────────────────────────────── */

export const Filters = ({ children }) => <div className="hr-filters">{children}</div>;

export function Filter({ label, grow = false, children, hint }) {
  const id = useId();
  return (
    <div className={`hr-filter${grow ? " hr-filter-grow" : ""}`}>
      <label htmlFor={id}>{label}</label>
      {React.isValidElement(children) && !children.props.id
        ? React.cloneElement(children, { id })
        : children}
      {hint && <span className="hr-filter-hint">{hint}</span>}
    </div>
  );
}

/**
 * The filter row, and on a phone the sheet it becomes.
 *
 * <p>Six filters laid flat is most of a phone screen before a single row of
 * data appears. Below 768px this collapses to one button that says how many
 * filters are set, and opens them in a bottom sheet with Apply and Reset —
 * the same fields, somewhere they fit.
 *
 * @param active how many filters are currently set, shown on the button
 * @param onReset clears them; omit and no Reset is offered
 */
export function HrFilterBar({ children, advanced, active = 0, onReset, onApply }) {
  const [openSheet, setOpenSheet] = useState(false);
  const [openAdv, setOpenAdv] = useState(false);
  const advId = useId();

  useEffect(() => {
    if (!openSheet) return;
    const key = (e) => { if (e.key === "Escape") setOpenSheet(false); };
    document.addEventListener("keydown", key);
    return () => document.removeEventListener("keydown", key);
  }, [openSheet]);

  const fields = (
    <>
      <Filters>{children}</Filters>
      {advanced && openAdv && <div className="hr-filters-adv" id={advId}><Filters>{advanced}</Filters></div>}
    </>
  );

  return (
    <>
      <div className="hr-filterbar">
        <div className="hr-filterbar-fields">{fields}</div>

        <div className="hr-filterbar-tools">
          {advanced && (
            <HrButton size="sm" icon={P.filter} aria-expanded={openAdv} aria-controls={advId}
                      onClick={() => setOpenAdv((v) => !v)}>
              {openAdv ? "Fewer filters" : "More filters"}
            </HrButton>
          )}
          {onApply && <HrButton size="sm" variant="teal" icon={P.check} onClick={onApply}>Apply</HrButton>}
          {onReset && active > 0 && (
            <HrButton size="sm" icon={P.reset} onClick={onReset}>Reset</HrButton>
          )}
        </div>

        {/* The phone control. Hidden from 768px up, where the fields are shown. */}
        <button type="button" className="hr-filterbar-open" onClick={() => setOpenSheet(true)}>
          <Ic path={P.filter} size={15} />
          <span>Filters</span>
          {active > 0 && <em className="hr-filterbar-count">{active}</em>}
        </button>
      </div>

      {openSheet && (
        <div className="hr-sheet-backdrop"
             onMouseDown={(e) => { if (e.target === e.currentTarget) setOpenSheet(false); }}>
          <div className="hr-sheet" role="dialog" aria-modal="true" aria-label="Filters">
            <div className="hr-sheet-head">
              <strong>Filters</strong>
              <HrButton iconOnly icon={P.x} title="Close" size="sm"
                        onClick={() => setOpenSheet(false)} />
            </div>
            <div className="hr-sheet-body">{fields}</div>
            <div className="hr-sheet-foot">
              {onReset && (
                <HrButton icon={P.reset} onClick={() => { onReset(); }}>Reset</HrButton>
              )}
              <HrButton variant="primary" icon={P.check}
                        onClick={() => { onApply?.(); setOpenSheet(false); }}>
                Show results
              </HrButton>
            </div>
          </div>
        </div>
      )}
    </>
  );
}

/* ── Loading, empty, broken ─────────────────────────────────────────────── */

/**
 * A failure, in words the reader can act on.
 *
 * <p>The HR services write their refusals as sentences meant for people —
 * "Abdul Malek is now on 21,777.00, not the 21,000.00 this increment was
 * approved against" — so those pass straight through. What does not is the
 * handful of strings the browser and the fetch layer produce when the request
 * never reached the server: "Failed to fetch", "NetworkError when attempting to
 * fetch resource", "Load failed". Those name a mechanism, not a problem, and
 * the reader can do nothing with them.
 *
 * @param fallback what to say instead when the message is one of those
 */
export function humanError(err, fallback = "The server could not be reached.") {
  const raw = (typeof err === "string" ? err : err?.message || "").trim();
  if (!raw) return fallback;
  const technical = [
    "failed to fetch", "networkerror", "load failed", "network request failed",
    "the network connection was lost", "err_network", "err_connection_refused",
    "typeerror", "aborterror", "the operation was aborted",
  ];
  const lower = raw.toLowerCase();
  if (technical.some((t) => lower.includes(t))) return fallback;
  // A bare status line is no better than a stack trace.
  if (/^\d{3}(\s|$)/.test(raw) || /^(internal server error|bad gateway)$/i.test(raw)) {
    return fallback;
  }
  return raw;
}

/** A bar the width of the value that will replace it. */
export const HrSkeleton = ({ w = "100%", h = 11, r = 5, style }) => (
  <span className="hr-skel" style={{ width: w, height: h, borderRadius: r, ...style }} />
);

/**
 * The shape of the answer while the answer is being fetched.
 *
 * <p>A spinner throws the layout away and puts it back a moment later, which
 * reads as the page reloading. A skeleton keeps the columns where they will be,
 * so the eye is already in the right place.
 */
export function HrTableSkeleton({ cols = 5, rows = 6 }) {
  const widths = [70, 48, 58, 40, 62, 52, 44];
  return (
    <tbody aria-hidden="true">
      {Array.from({ length: rows }).map((_, r) => (
        <tr key={r} className="hr-skel-row">
          {Array.from({ length: cols }).map((_, c) => (
            <td key={c} data-label=""><HrSkeleton w={`${widths[(r + c) % widths.length]}%`} /></td>
          ))}
        </tr>
      ))}
    </tbody>
  );
}

/** A whole page's worth, for the first paint before anything has arrived. */
export function HrPageSkeleton({ label = "Loading…", kpis = 4, rows = 6 }) {
  return (
    <div className="hr-page" aria-busy="true">
      <span className="sr-only" role="status">{label}</span>
      <div className="hr-head" aria-hidden="true">
        <div className="hr-head-title">
          <HrSkeleton w={180} h={9} />
          <HrSkeleton w={260} h={18} style={{ marginTop: 8 }} />
          <HrSkeleton w={340} h={10} style={{ marginTop: 8 }} />
        </div>
      </div>
      <div className="hr-kpis" aria-hidden="true">
        {Array.from({ length: kpis }).map((_, i) => (
          <div className="hr-kpi" key={i}>
            <HrSkeleton w="55%" h={9} />
            <HrSkeleton w="45%" h={22} r={7} />
            <HrSkeleton w="70%" h={9} />
          </div>
        ))}
      </div>
      <div className="card" aria-hidden="true">
        <div className="card-pad">
          <HrSkeleton w={160} h={13} style={{ marginBottom: 14 }} />
          <table className="hr-table"><HrTableSkeleton rows={rows} /></table>
        </div>
      </div>
    </div>
  );
}

/** Kept for the pages that already call it; now a skeleton rather than a word. */
export const Spinner = ({ label = "Loading…" }) => <HrPageSkeleton label={label} />;

/**
 * Nothing here — and what to do about it.
 *
 * <p>"No records" is a dead end. Every empty state in HR says which of the two
 * empties it is: nothing has been created yet, or nothing matches the filter.
 * They need different next steps, and offering "Add" to somebody who has just
 * over-filtered is the wrong one.
 */
export function HrEmptyState({ icon = P.inbox, title = "Nothing here yet", hint, action }) {
  return (
    <div className="hr-empty">
      <span className="hr-empty-icon"><Ic path={icon} size={26} /></span>
      <b>{title}</b>
      {hint && <span>{hint}</span>}
      {action}
    </div>
  );
}

/**
 * The request failed, said in words and with a way out.
 *
 * <p>Never the exception. A stack trace on screen tells the reader nothing they
 * can act on and tells anybody else more than they should see; the detail is on
 * the server where it belongs.
 */
export function HrErrorState({
  title = "Something went wrong", hint = "The information could not be loaded.", onRetry, retrying,
}) {
  return (
    <div className="hr-empty is-error" role="alert">
      <span className="hr-empty-icon"><Ic path={P.alert} size={26} /></span>
      <b>{title}</b>
      <span>{hint}</span>
      {onRetry && (
        <HrButton variant="ghost" icon={P.reset} onClick={onRetry}
                  busy={retrying} busyLabel="Retrying…">Try again</HrButton>
      )}
    </div>
  );
}

/* ── Table ──────────────────────────────────────────────────────────────── */

/**
 * A table that becomes one card per row below 768px.
 *
 * <p>Column definitions carry their own label, so the mobile card labels and
 * the desktop headers cannot disagree — the label travels into `data-label` and
 * the CSS draws it.
 *
 * <p>Four states, not two: loading, broken, empty and full. A table that only
 * knows the last two shows "Nothing here yet" while it is still asking, and
 * again when the request failed — two lies with one string.
 *
 * @param cols  [{ key, label, render, num, lead, advisory, actions, sortable, sortValue }]
 *   lead      this cell is the card's heading on mobile
 *   num       right-aligned, tabular figures
 *   advisory  dropped between 768 and 1199, where the table is tight
 *   actions   becomes a full-width button row at the foot of the card
 *   sortable  the header becomes a sort control
 * @param empty  a string, or { icon, title, hint, action }
 * @param loading draws skeleton rows in place of the body
 * @param error   draws the error state with onRetry
 * @param rowClass (row, i) => extra class names for that <tr> — a cursor, an unsaved mark
 * @param rowAttrs (row, i) => extra attributes for that <tr> — a data-id to scroll to
 */
export function HrTable({
  cols, rows, rowKey, empty = "Nothing here yet.",
  loading = false, error = null, onRetry, onRowClick, caption,
  rowClass, rowAttrs,
}) {
  const [sort, setSort] = useState(null);   // { key, dir }

  const sorted = React.useMemo(() => {
    if (!sort) return rows;
    const col = cols.find((c) => c.key === sort.key);
    if (!col) return rows;
    const pick = col.sortValue || ((r) => r[col.key]);
    const dir = sort.dir === "asc" ? 1 : -1;
    return [...rows].sort((a, b) => {
      const x = pick(a), y = pick(b);
      if (x == null && y == null) return 0;
      if (x == null) return 1;                       // blanks last, either way
      if (y == null) return -1;
      if (typeof x === "number" && typeof y === "number") return (x - y) * dir;
      return String(x).localeCompare(String(y), undefined, { numeric: true }) * dir;
    });
  }, [rows, sort, cols]);

  const toggle = (key) =>
    setSort((s) => (s?.key === key
      ? (s.dir === "asc" ? { key, dir: "desc" } : null)
      : { key, dir: "asc" }));

  const emptyNode = typeof empty === "string"
    ? (empty ? <HrEmptyState title={empty} /> : null)
    : <HrEmptyState {...empty} />;

  return (
    <div className="hr-table-wrap">
      <table className="hr-table">
        {caption && <caption className="sr-only">{caption}</caption>}
        <thead>
          <tr>
            {cols.map((c) => (
              <th key={c.key} className={klass(c)} scope="col"
                  aria-sort={sort?.key === c.key
                    ? (sort.dir === "asc" ? "ascending" : "descending") : undefined}>
                {c.sortable
                  ? <button type="button" className="hr-th-sort" onClick={() => toggle(c.key)}>
                      {c.label}
                      <Ic path={P.chevronDown} size={11}
                          className={`hr-sort-caret${sort?.key === c.key ? " is-on" : ""}${
                            sort?.key === c.key && sort.dir === "desc" ? " is-desc" : ""}`} />
                    </button>
                  : c.label}
              </th>
            ))}
          </tr>
        </thead>

        {loading
          ? <HrTableSkeleton cols={cols.length} />
          : (
            <tbody>
              {sorted.map((r, i) => (
                <tr key={rowKey ? rowKey(r, i) : i}
                    {...(rowAttrs ? rowAttrs(r, i) : {})}
                    className={[onRowClick ? "is-clickable" : "", rowClass ? rowClass(r, i) : ""]
                      .filter(Boolean).join(" ") || undefined}
                    onClick={onRowClick ? (e) => {
                      // A click on a control inside the row belongs to that
                      // control, not to the row behind it.
                      if (e.target.closest("button, a, input, select, label")) return;
                      onRowClick(r);
                    } : undefined}>
                  {cols.map((c) => (
                    <td key={c.key} data-label={c.label} className={klass(c)}>
                      {c.render ? c.render(r, i) : r[c.key]}
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          )}
      </table>

      {!loading && error && (
        <HrErrorState hint={typeof error === "string" ? error : undefined} onRetry={onRetry} />
      )}
      {!loading && !error && rows.length === 0 && emptyNode}
    </div>
  );
}

const klass = (c) =>
  [c.num ? "hr-num" : "", c.lead ? "hr-td-lead" : "", c.actions ? "hr-td-actions" : "",
   c.advisory ? "hr-col-advisory" : ""].filter(Boolean).join(" ") || undefined;

/* ── Status ─────────────────────────────────────────────────────────────── */

/**
 * A status word in the colour its meaning carries, with a glyph beside it.
 *
 * <p>The label comes from the server — {@code requestStatusLabel},
 * {@code payrollStatusLabel} — so the screen holds no second copy of the
 * vocabulary and a value added on the server reads correctly here without a
 * frontend change.
 *
 * <p>The glyph is not decoration. Roughly one man in twelve cannot separate the
 * sage from the clay, and "Approved" against "Rejected" is exactly the pair
 * that must not depend on it.
 */
export const Status = ({ code, label }) => {
  if (!code) return <span className="hr-dash">—</span>;
  const tone = toneFor(code);
  const glyph = STATUS_ICON[tone];
  return (
    <Pill tone={tone}>
      <span className="hr-status">
        {glyph && <Ic path={glyph} size={11} />}
        {label || code}
      </span>
    </Pill>
  );
};

/* ── Steppers ───────────────────────────────────────────────────────────── */

const PAYROLL_STEPS = ["DRAFT", "CALCULATED", "REVIEWED", "APPROVED", "POSTED", "PAID"];

/**
 * Where a run has got to, and what comes after.
 *
 * <p>A status word alone says where it is; the rail says what is left. A
 * cancelled run gets no rail — it went nowhere, and drawing five greyed steps
 * beside it suggests it might still move.
 */
export function PayrollSteps({ status, exceptions = 0 }) {
  if (!status || status === "CANCELLED") return null;
  const at = PAYROLL_STEPS.indexOf(status);
  return (
    <ol className="hr-steps" aria-label="Payroll progress">
      {PAYROLL_STEPS.map((s, i) => {
        // An exception is raised against the calculation, so it marks the step
        // that produced it rather than the one the run is waiting on.
        const bad = exceptions > 0 && s === "CALCULATED" && at >= 1;
        return (
          <React.Fragment key={s}>
            {i > 0 && <span className="hr-step-sep" aria-hidden="true" />}
            <li className={`hr-step${i < at ? " is-done" : ""}${i === at ? " is-now" : ""}${bad ? " is-bad" : ""}`}
                aria-current={i === at ? "step" : undefined}>
              <span className="hr-step-dot" aria-hidden="true" />
              {s.charAt(0) + s.slice(1).toLowerCase()}
              {bad && <span className="hr-step-flag">{exceptions}</span>}
            </li>
          </React.Fragment>
        );
      })}
    </ol>
  );
}

/**
 * A numbered stepper for a form that runs in stages.
 *
 * @param steps [{ id, label, hint }]
 * @param at    index of the current step
 * @param done  ids already completed (defaults to everything before `at`)
 * @param errors ids that need fixing
 */
export function HrStepper({ steps, at = 0, done, errors = [], onGo }) {
  const isDone = (s, i) => (done ? done.includes(s.id) : i < at);
  return (
    <ol className="hr-stepper" aria-label="Progress">
      {steps.map((s, i) => {
        const state = errors.includes(s.id) ? "is-bad"
          : i === at ? "is-now" : isDone(s, i) ? "is-done" : "";
        const inner = (
          <>
            <span className="hr-stepper-no" aria-hidden="true">
              {state === "is-done" ? <Ic path={P.check} size={12} />
                : state === "is-bad" ? <Ic path={P.alert} size={12} /> : i + 1}
            </span>
            <span className="hr-stepper-text">
              <b>{s.label}</b>
              {s.hint && <em>{s.hint}</em>}
            </span>
          </>
        );
        return (
          <li key={s.id} className={`hr-stepper-step ${state}`}
              aria-current={i === at ? "step" : undefined}>
            {onGo && (isDone(s, i) || i === at)
              ? <button type="button" onClick={() => onGo(i)}>{inner}</button>
              : <span>{inner}</span>}
          </li>
        );
      })}
    </ol>
  );
}

/* ── Timeline ───────────────────────────────────────────────────────────── */

/**
 * What happened to this object, in order.
 *
 * <p>Built for approval history, where the sequence is the point: "approved by
 * the manager, then refused by the administrator" is a different story from
 * "refused", and a status word can only tell you the second one.
 *
 * @param items [{ title, when, who, note, tone, icon }]
 *   tone  sage | teal | amber | clay | grey — the dot's colour
 */
export function HrTimeline({ items = [], empty = "Nothing recorded yet." }) {
  if (!items.length) return <p className="hr-timeline-empty">{empty}</p>;
  return (
    <ol className="hr-timeline">
      {items.map((it, i) => (
        <li key={i} className={`hr-tl-item is-${it.tone || "grey"}`}>
          <span className="hr-tl-dot" aria-hidden="true">
            {it.icon && <Ic path={it.icon} size={10} />}
          </span>
          <div className="hr-tl-body">
            <div className="hr-tl-head">
              <b>{it.title}</b>
              {it.when && <time className="hr-tl-when">{it.when}</time>}
            </div>
            {it.who && <span className="hr-tl-who">{it.who}</span>}
            {it.note && <p className="hr-tl-note">{it.note}</p>}
          </div>
        </li>
      ))}
    </ol>
  );
}

/* ── Request lifecycle ──────────────────────────────────────────────────── */

/**
 * Where a request is, and what is left: Submitted → the signatures → what the
 * module does after approval → done.
 *
 * <p>Each stage is DONE, CURRENT, PENDING, REJECTED or CANCELLED and may carry
 * who, when and a remark. The state is drawn as a glyph and a word, never as a
 * colour alone; the current stage is the one the eye lands on. Horizontal
 * where there is room, a vertical list on a phone — the same markup.
 */
export function HrRequestTimeline({ stages = [], compact = false }) {
  const glyph = (st) => (st === "DONE" ? P.check : st === "REJECTED" ? P.x : st === "CANCELLED" ? P.ban : st === "CURRENT" ? P.clock : null);
  const word = (st) => ({ DONE: "Done", CURRENT: "Now", PENDING: "Next", REJECTED: "Rejected", CANCELLED: "Cancelled" }[st] || "");
  return (
    <ol className={`hr-rail${compact ? " is-compact" : ""}`} aria-label="Request progress">
      {stages.map((s, i) => (
        <li key={s.key || i} className={`hr-rail-step is-${(s.state || "PENDING").toLowerCase()}`}
            aria-current={s.state === "CURRENT" ? "step" : undefined}>
          <span className="hr-rail-dot" aria-hidden="true">
            {glyph(s.state) ? <Ic path={glyph(s.state)} size={11} /> : <i>{i + 1}</i>}
          </span>
          <span className="hr-rail-text">
            <b>{s.label}</b>
            <em>
              <span className="sr-only">{word(s.state)}. </span>
              {s.at ? fmtWhen(s.at) : s.by ? s.by : (s.state === "CURRENT" ? "In progress" : s.state === "PENDING" ? "Not yet" : word(s.state))}
            </em>
            {!compact && s.by && s.at && <small>{s.by}</small>}
            {!compact && s.remarks && <q>{s.remarks}</q>}
          </span>
        </li>
      ))}
    </ol>
  );
}

/** "12 Sep, 03:40 PM" from an ISO stamp; the date alone when there is no time. */
export function fmtWhen(iso) {
  if (!iso) return "";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return String(iso);
  const day = d.toLocaleDateString(undefined, { day: "numeric", month: "short" });
  if (/T00:00(:00)?$/.test(String(iso)) || (d.getHours() === 0 && d.getMinutes() === 0)) return day;
  return `${day}, ${d.toLocaleTimeString(undefined, { hour: "2-digit", minute: "2-digit" })}`;
}

/* ── Drawer ─────────────────────────────────────────────────────────────── */

/**
 * A right-hand panel for reading one row without leaving the list.
 *
 * <p>Used where a modal would be wrong: a modal says "answer me", a drawer says
 * "here is the detail, the list is still behind you". Approval review and claim
 * detail are both the second thing.
 *
 * <p>Full width on a phone, because 420px of panel on a 375px screen is a modal
 * with extra steps.
 */
export function HrDrawer({ title, subtitle, onClose, children, footer, width = 460 }) {
  const panel = useRef(null);
  const returnTo = useRef(null);
  /* onClose is nearly always an inline arrow, so it is a new function on every
     render of the page behind. Reading it through a ref keeps the effect below
     to one run per drawer: with onClose as a dependency it re-ran on every
     keystroke typed into a modal opened from the drawer — re-focusing the
     drawer's first button and pulling the cursor out of the field. */
  const close = useRef(onClose);
  useEffect(() => { close.current = onClose; }, [onClose]);

  useEffect(() => {
    returnTo.current = document.activeElement;
    // The first control inside, so a keyboard user is in the drawer rather than
    // still on the row behind it.
    const first = panel.current?.querySelector(FOCUSABLE);
    // preventScroll: focusing must not move the page behind the drawer.
    (first || panel.current)?.focus({ preventScroll: true });

    const key = (e) => {
      // A modal asked from the drawer (Approve, Reject, a confirmation) sits on
      // top; while it is there Escape and Tab belong to it, not to the drawer.
      if (document.querySelector(".modal-backdrop")) return;
      if (e.key === "Escape") { e.preventDefault(); close.current?.(); return; }
      if (e.key !== "Tab") return;
      const items = [...(panel.current?.querySelectorAll(FOCUSABLE) || [])]
        .filter((n) => n.offsetParent !== null);
      if (!items.length) return;
      const first = items[0], last = items[items.length - 1];
      if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
      else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
    };
    document.addEventListener("keydown", key);

    /* The page behind must not scroll under the drawer. The lock goes on
       <html>, not <body>: styles.css clips <html> sideways, so an overflow set
       on <body> made <body> a scroll container of its own and the sticky
       sidebar slid up the page with the content, leaving a blank strip below.
       Overflow on <html> propagates to the viewport and sticky keeps working. */
    const root = document.documentElement;
    const prev = root.style.overflow;
    root.style.overflow = "hidden";
    return () => {
      document.removeEventListener("keydown", key);
      root.style.overflow = prev;
      returnTo.current?.focus?.();
    };
  }, []);

  return (
    <div className="hr-drawer-backdrop"
         onMouseDown={(e) => { if (e.target === e.currentTarget) onClose(); }}>
      <aside className="hr-drawer" style={{ maxWidth: width }} ref={panel} tabIndex={-1}
             role="dialog" aria-modal="true" aria-label={title || "Details"}>
        <header className="hr-drawer-head">
          <div>
            <h3>{title}</h3>
            {subtitle && <p>{subtitle}</p>}
          </div>
          <HrButton iconOnly icon={P.x} title="Close" onClick={onClose} />
        </header>
        <div className="hr-drawer-body">{children}</div>
        {footer && <footer className="hr-drawer-foot">{footer}</footer>}
      </aside>
    </div>
  );
}

const FOCUSABLE =
  'button:not([disabled]), [href], input:not([disabled]):not([type="hidden"]), ' +
  'select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

/* ── The bottom bar ─────────────────────────────────────────────────────── */

/**
 * The phone navigation, borrowed wholesale from QuickSales.
 *
 * <p>Fixed, icons over labels, 48px targets, hidden from 768px up. The panes it
 * switches between are sections of one page on anything wider — see hr.css.
 *
 * @param tabs [{ id, label, icon, badge }]
 */
export function HrTabs({ tabs, active, onChange }) {
  return (
    <nav className={`hr-tabs hr-tabs-${tabs.length}`} aria-label="Sections">
      {tabs.map((t) => (
        <button key={t.id} type="button"
                className={`hr-tab${active === t.id ? " is-on" : ""}`}
                aria-current={active === t.id ? "page" : undefined}
                onClick={() => onChange(t.id)}>
          <Ic path={t.icon} size={17} />
          <span>{t.label}</span>
          {t.badge > 0 && (
            <em className="hr-tab-badge" aria-label={`${t.badge} waiting`}>
              {t.badge > 9 ? "9+" : t.badge}
            </em>
          )}
        </button>
      ))}
    </nav>
  );
}

/** One section. Shown alone on a phone, always shown from 768px up. */
export const Pane = ({ id, active, children }) => (
  <section className={`hr-pane${active === id ? " is-on" : ""}`}>{children}</section>
);

/* ── Forms ──────────────────────────────────────────────────────────────── */

/**
 * A named part of a long form.
 *
 * <p>An employee record is upward of thirty fields. Thirty inputs in a column
 * is a form nobody finishes; six sections of five is one somebody can put down
 * halfway and come back to.
 */
/**
 * A small editable grid inside a form — the expenses on a claim, the
 * signatures on a chain, the components of a structure.
 *
 * <p>A real table, because the rows are records and the columns are fields,
 * and a reader checks a column the way they check a column on any list: the
 * heading names it once, the cells line up under it. Each cell is one control
 * handed in by the caller; the grid styles it, numbers the row, and puts the
 * remove control where every row has it. On a phone each row becomes a card
 * and every cell wears its heading, because a headed column is no use once
 * the columns have stacked.
 *
 * @param cols    [{ key, label, width, num }] — the headings, in order
 * @param rows    the records
 * @param cell    (row, i, key) → the control for that cell
 * @param onRemove(i), onAdd(), addLabel
 * @param foot    a summary row: [{ label, value }] drawn under the last line
 */
export function HrLines({ cols, rows, cell, onRemove, onAdd, addLabel = "Add a line", numbered = true, foot, empty }) {
  const span = cols.length + (numbered ? 1 : 0) + (onRemove ? 1 : 0);
  return (
    <div className="hr-lines">
      <table className="hr-lines-table">
        <thead>
          <tr>
            {numbered && <th className="hr-lines-n">#</th>}
            {cols.map((c) => (
              <th key={c.key} className={c.num ? "hr-lines-num" : ""} style={c.width ? { width: c.width } : undefined}>{c.label}</th>
            ))}
            {onRemove && <th className="hr-lines-x"><span className="sr-only">Remove</span></th>}
          </tr>
        </thead>
        <tbody>
          {rows.length === 0 && empty && (
            <tr className="hr-lines-empty"><td colSpan={span}>{empty}</td></tr>
          )}
          {rows.map((r, i) => (
            <tr key={r.id ?? r._key ?? i}>
              {numbered && <td className="hr-lines-n" data-label="#">{i + 1}</td>}
              {cols.map((c) => (
                <td key={c.key} className={c.num ? "hr-lines-num" : ""} data-label={c.label}>{cell(r, i, c.key)}</td>
              ))}
              {onRemove && (
                <td className="hr-lines-x">
                  <HrButton variant="ghost" size="sm" iconOnly icon={P.trash} title={`Remove line ${i + 1}`}
                            onClick={() => onRemove(i)} />
                </td>
              )}
            </tr>
          ))}
        </tbody>
        {foot && foot.length > 0 && (
          <tfoot>
            {foot.map((f, i) => (
              <tr key={i}>
                <td colSpan={span - 1 - (onRemove ? 1 : 0)} className="hr-lines-foot-label">{f.label}</td>
                <td className="hr-lines-num hr-lines-foot-value">{f.value}</td>
                {onRemove && <td className="hr-lines-x" />}
              </tr>
            ))}
          </tfoot>
        )}
      </table>
      {onAdd && (
        <div className="hr-lines-add">
          <HrButton variant="ghost" size="sm" icon={P.plus} onClick={onAdd}>{addLabel}</HrButton>
        </div>
      )}
    </div>
  );
}

export function HrFormSection({ icon, title, hint, children, cols }) {
  return (
    <section className="hr-fsec">
      <header className="hr-fsec-head">
        {icon && <span className="hr-fsec-icon"><Ic path={icon} size={15} /></span>}
        <div>
          <h4>{title}</h4>
          {hint && <p>{hint}</p>}
        </div>
      </header>
      <div className={`hr-form${cols ? ` hr-form-${cols}` : ""}`}>{children}</div>
    </section>
  );
}

/* ── Desktop tabs ───────────────────────────────────────────────────────── */

/**
 * The sections of a page, one at a time, on a screen wide enough for a tab
 * strip.
 *
 * <p>`HrTabs` is the phone's bottom bar. On a desktop the same sections used
 * to be stacked down one very long page — six panes of a 2,500-person branch,
 * one after another. This is the top-of-page counterpart: the same `tabs`
 * array, the same `active`, drawn as a strip, and the page renders only the
 * chosen pane. Bind both to one state and the two never disagree.
 *
 * @param tabs [{ id, label, icon, badge }]
 */
export function HrTabBar({ tabs, active, onChange }) {
  return (
    <div className="hr-tabbar" role="tablist" aria-label="Sections">
      {tabs.map((t) => (
        <button key={t.id} type="button" role="tab"
                aria-selected={active === t.id}
                className={`hr-tabbar-tab${active === t.id ? " is-on" : ""}`}
                onClick={() => onChange(t.id)}>
          {t.icon && <Ic path={t.icon} size={14} />}
          <span>{t.label}</span>
          {t.badge > 0 && <em className="hr-tabbar-badge">{t.badge > 99 ? "99+" : t.badge}</em>}
        </button>
      ))}
    </div>
  );
}

/* ── Pagination ─────────────────────────────────────────────────────────── */

/**
 * Where you are in a long list, and how to move.
 *
 * <p>"1–50 of 2,500" rather than "page 1 of 50": people reason in rows, and a
 * count of rows is what tells you whether it is worth paging on. First and last
 * are offered because on fifty pages the arrows alone are a chore.
 *
 * @param page  zero-based
 * @param total how many rows match, in all
 */
export function HrPagination({ page, size, total, totalPages, onPage, onSize, sizes = [25, 50, 100] }) {
  const pages = Math.max(1, totalPages ?? Math.ceil((total || 0) / (size || 1)));
  const from = total === 0 ? 0 : page * size + 1;
  const to = Math.min(total, (page + 1) * size);
  const fmt = (n) => Number(n).toLocaleString("en-US");
  return (
    <div className="hr-pager" role="navigation" aria-label="Pages">
      <span className="hr-pager-range">
        {total === 0 ? "Nothing to show" : `${fmt(from)}–${fmt(to)} of ${fmt(total)}`}
      </span>
      <div className="hr-pager-controls">
        {onSize && (
          <label className="hr-pager-size">
            <span className="sr-only">Rows per page</span>
            <select value={size} onChange={(e) => onSize(Number(e.target.value))}>
              {sizes.map((s) => <option key={s} value={s}>{s} / page</option>)}
            </select>
          </label>
        )}
        <HrButton iconOnly icon={P.first} title="First page" size="sm"
                  disabled={page <= 0} onClick={() => onPage(0)} />
        <HrButton iconOnly icon={P.back} title="Previous page" size="sm"
                  disabled={page <= 0} onClick={() => onPage(page - 1)} />
        <span className="hr-pager-page">{fmt(page + 1)} / {fmt(pages)}</span>
        <HrButton iconOnly icon={P.chevron} title="Next page" size="sm"
                  disabled={page >= pages - 1} onClick={() => onPage(page + 1)} />
        <HrButton iconOnly icon={P.last} title="Last page" size="sm"
                  disabled={page >= pages - 1} onClick={() => onPage(pages - 1)} />
      </div>
    </div>
  );
}

/* ── Progress ───────────────────────────────────────────────────────────── */

/**
 * How far through something is — a register for the day, a run being paid.
 *
 * <p>The figure is in words as well as in the bar, because a bar alone says
 * "most" and the person filling a register wants to know it is 2,377 of 2,500.
 */
export function HrProgress({ value, max, label, tone = "teal" }) {
  const pct = max > 0 ? Math.min(100, Math.round((value / max) * 100)) : 0;
  const fmt = (n) => Number(n || 0).toLocaleString("en-US");
  return (
    <div className={`hr-progress is-${tone}`}>
      <div className="hr-progress-head">
        <span>{label}</span>
        <b>{fmt(value)} of {fmt(max)} · {pct}%</b>
      </div>
      <div className="hr-progress-track" role="progressbar"
           aria-valuemin={0} aria-valuemax={max || 0} aria-valuenow={value || 0}
           aria-label={label}>
        <div className="hr-progress-fill" style={{ width: `${pct}%` }} />
      </div>
    </div>
  );
}

/* ── Employee picker ────────────────────────────────────────────────────── */

/**
 * One person, found by typing, from however many there are.
 *
 * <p>A `<select>` of 2,500 names is not a control. This asks the server for
 * the twenty that match what has been typed so far, waits for the typing to
 * stop before asking, and shows each with a photograph and code so two
 * people with the same name can be told apart.
 *
 * <p>The value is the id as a string, like a select's `e.target.value`, so a
 * caller that used one changes nothing but the tag.
 *
 * @param value      the chosen employee's id, or ""
 * @param onChange   (id, employee) — the id as a string, and the row it came from
 * @param search     async (q) => [{ id, empName, empId, departmentName, imagePath, initials }]
 * @param allowEmpty offer "Everybody" (or `emptyLabel`) as the first choice
 */
export function HrEmployeePicker({
  value, onChange, search, placeholder = "Type a name or code…",
  allowEmpty = false, emptyLabel = "Everybody", label = "Employee", disabled = false, id,
  /* The name behind a preset value, when the caller already knows it — an
     existing manager on a record, say — so the box never has to say "#123". */
  valueLabel,
}) {
  const fieldId = useId();
  const [open, setOpen] = useState(false);
  const [q, setQ] = useState("");
  const [rows, setRows] = useState([]);
  const [busy, setBusy] = useState(false);
  const [hi, setHi] = useState(0);
  const [chosen, setChosen] = useState(null);      // the row behind `value`
  const wrap = useRef(null);
  const timer = useRef(null);
  const seq = useRef(0);

  /* A value set from outside — a KPI tile, a reset — with no row to show for
     it is looked up once, so the box can say a name rather than an id. */
  useEffect(() => {
    if (!value) { setChosen(null); return; }
    if (chosen && String(chosen.id) === String(value)) return;
    let live = true;
    search(String(value)).then((list) => {
      if (!live) return;
      const hit = (list || []).find((r) => String(r.id) === String(value));
      if (hit) setChosen(hit);
    }).catch(() => {});
    return () => { live = false; };
  }, [value]); // eslint-disable-line react-hooks/exhaustive-deps

  const ask = useCallback((text) => {
    clearTimeout(timer.current);
    const mine = ++seq.current;
    setBusy(true);
    timer.current = setTimeout(async () => {
      try {
        const list = await search(text);
        if (mine === seq.current) { setRows(list || []); setHi(0); }
      } catch {
        if (mine === seq.current) setRows([]);
      } finally {
        if (mine === seq.current) setBusy(false);
      }
    }, text ? 220 : 0);
  }, [search]);

  useEffect(() => () => clearTimeout(timer.current), []);

  useEffect(() => {
    if (!open) return;
    const away = (e) => { if (!wrap.current?.contains(e.target)) setOpen(false); };
    document.addEventListener("mousedown", away);
    return () => document.removeEventListener("mousedown", away);
  }, [open]);

  const pick = (row) => {
    setChosen(row);
    setOpen(false);
    setQ("");
    onChange?.(row ? String(row.id) : "", row);
  };

  const openList = () => { if (disabled) return; setOpen(true); ask(q); };

  const onKey = (e) => {
    if (!open && (e.key === "ArrowDown" || e.key === "Enter")) { e.preventDefault(); openList(); return; }
    if (!open) return;
    const n = rows.length + (allowEmpty ? 1 : 0);
    if (e.key === "ArrowDown") { e.preventDefault(); setHi((h) => Math.min(n - 1, h + 1)); }
    else if (e.key === "ArrowUp") { e.preventDefault(); setHi((h) => Math.max(0, h - 1)); }
    else if (e.key === "Enter") {
      e.preventDefault();
      if (allowEmpty && hi === 0) pick(null);
      else pick(rows[hi - (allowEmpty ? 1 : 0)]);
    }
    else if (e.key === "Escape") { e.preventDefault(); setOpen(false); setQ(""); }
  };

  const shown = open ? q : (chosen ? `${chosen.empName}` : (value ? (valueLabel || `#${value}`) : ""));

  return (
    <div className={`hr-picker${open ? " is-open" : ""}${disabled ? " is-off" : ""}`} ref={wrap}>
      {chosen && !open && (
        <span className="hr-picker-avatar" aria-hidden="true">
          {chosen.imagePath
            ? <img src={uploadUrl(chosen.imagePath)} alt="" />
            : (chosen.initials || chosen.empName?.slice(0, 2) || "?").toUpperCase()}
        </span>
      )}
      <input id={id || fieldId} className="hr-picker-input" type="text"
             role="combobox" aria-expanded={open} aria-autocomplete="list" aria-label={label}
             value={shown} disabled={disabled}
             placeholder={chosen ? chosen.empName : (allowEmpty ? emptyLabel : placeholder)}
             onFocus={openList}
             onClick={openList}
             onChange={(e) => { setQ(e.target.value); if (!open) setOpen(true); ask(e.target.value); }}
             onKeyDown={onKey} />
      {(chosen || q) && !disabled && (
        <button type="button" className="hr-picker-clear" title="Clear" aria-label="Clear"
                onClick={() => { if (open) { setQ(""); ask(""); } else pick(null); }}>
          <Ic path={P.x} size={12} />
        </button>
      )}
      {open && (
        <div className="hr-picker-list" role="listbox">
          {allowEmpty && (
            <button type="button" role="option" aria-selected={hi === 0}
                    className={`hr-picker-row is-all${hi === 0 ? " is-hi" : ""}`}
                    onMouseEnter={() => setHi(0)} onClick={() => pick(null)}>
              <span className="hr-picker-name">{emptyLabel}</span>
            </button>
          )}
          {busy && rows.length === 0 && <div className="hr-picker-note">Searching…</div>}
          {!busy && rows.length === 0 && (
            <div className="hr-picker-note">{q ? `Nobody matches “${q}”.` : "Type a name or a code."}</div>
          )}
          {rows.map((r, i) => {
            const idx = i + (allowEmpty ? 1 : 0);
            return (
              <button key={r.id} type="button" role="option" aria-selected={hi === idx}
                      className={`hr-picker-row${hi === idx ? " is-hi" : ""}`}
                      onMouseEnter={() => setHi(idx)} onClick={() => pick(r)}>
                <span className="hr-picker-avatar" aria-hidden="true">
                  {r.imagePath ? <img src={uploadUrl(r.imagePath)} alt="" />
                               : (r.initials || r.empName?.slice(0, 2) || "?").toUpperCase()}
                </span>
                <span className="hr-picker-text">
                  <span className="hr-picker-name">{r.empName}</span>
                  <span className="hr-picker-meta">
                    {r.empId}{r.departmentName ? ` · ${r.departmentName}` : ""}
                  </span>
                </span>
              </button>
            );
          })}
        </div>
      )}
    </div>
  );
}

/* ── Paging hooks ───────────────────────────────────────────────────────── */

/**
 * A list the server pages.
 *
 * <p>Every HR list endpoint already takes `page` and `size` and answers with
 * a total; the screens used to ask for fifty and show whatever came back. This
 * keeps the page, the size, the rows and the count together, reloads when a
 * filter changes, and goes back to page one when it does — page forty of a
 * filter with three pages is nowhere.
 *
 * @param fetcher ({ page, size }) => Promise<{ items, total, totalPages }>
 * @param deps    what a change of should refetch (filters, a reload token)
 */
export function usePagedList(fetcher, deps = [], { size: initialSize = 25 } = {}) {
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(initialSize);
  const [data, setData] = useState({ items: [], total: 0, totalPages: 1 });
  const [busy, setBusy] = useState(true);
  const [err, setErr] = useState(null);
  const latest = useRef(fetcher);
  latest.current = fetcher;

  const load = useCallback(async () => {
    setBusy(true);
    try {
      const d = await latest.current({ page, size });
      setData({ items: d?.items || [], total: d?.total || 0, totalPages: d?.totalPages || 1 });
      setErr(null);
      // The filter moved past this page: go to the last one that exists.
      if ((d?.items || []).length === 0 && page > 0 && (d?.totalPages || 0) > 0) {
        setPage(Math.max(0, d.totalPages - 1));
      }
    } catch (e) {
      setErr(humanError(e, "The list could not be read."));
    } finally {
      setBusy(false);
    }
  }, [page, size, ...deps]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => { load(); }, [load]);
  useEffect(() => { setPage(0); }, deps); // eslint-disable-line react-hooks/exhaustive-deps

  return {
    rows: data.items, total: data.total, totalPages: data.totalPages,
    page, size, setPage, setSize: (s) => { setSize(s); setPage(0); },
    busy, err, reload: load,
  };
}

/**
 * A list already in memory, searched and paged here.
 *
 * <p>For the queues — what is waiting for a decision — which the server hands
 * over whole because they are the work, not a report. At 2,500 people a queue
 * can still be a few hundred rows, which is more than one screen but not
 * more than one request.
 *
 * @param match (row, lowerCaseQuery) => boolean
 */
export function useLocalPage(rows, { size: initialSize = 25, q = "", match } = {}) {
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(initialSize);
  const t = (q || "").trim().toLowerCase();
  const filtered = React.useMemo(
    () => (t && match ? (rows || []).filter((r) => match(r, t)) : (rows || [])),
    [rows, t, match]);
  useEffect(() => { setPage(0); }, [t, rows]);
  const totalPages = Math.max(1, Math.ceil(filtered.length / size));
  const safePage = Math.min(page, totalPages - 1);
  return {
    rows: filtered.slice(safePage * size, safePage * size + size),
    total: filtered.length, totalPages, page: safePage, size,
    setPage, setSize: (s) => { setSize(s); setPage(0); },
  };
}

/* ── Notes ──────────────────────────────────────────────────────────────── */

/** Why something cannot happen — said plainly rather than hinted at. */
export const Blocked = ({ children }) => (
  <div className="hr-blocked" role="status">
    <Ic path={P.info} size={15} />
    <span>{children}</span>
  </div>
);

/**
 * Where the application is deliberately not deciding something.
 *
 * <p>Used for the questions this module leaves to the pharmacy: what overtime
 * is worth, what a leave entitlement is, what a gratuity formula says. A blank
 * field with no explanation reads as a bug; this says it is a choice.
 */
export const PolicyNote = ({ children }) => (
  <p className="hr-policy-note">{children}</p>
);

/* ── Small helpers the screens share ────────────────────────────────────── */

export const Facts = ({ children }) => <div className="hr-facts">{children}</div>;

export const Fact = ({ label, value, num = false }) => (
  <div className="hr-fact">
    <span className="hr-fact-label">{label}</span>
    <span className={`hr-fact-value${num ? " hr-num-inline" : ""}`}>{value ?? "—"}</span>
  </div>
);
