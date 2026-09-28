import React, { useState } from "react";
import {
  IconCart, IconBox, IconReceipt, IconWallet, IconFlask, IconAlert, IconTruck, IconUsers, IconLayers, IconClock, IconX,
} from "./Icons.jsx";

const ICONS = { cart: IconCart, box: IconBox, receipt: IconReceipt, wallet: IconWallet, flask: IconFlask, alert: IconAlert, truck: IconTruck, users: IconUsers, layers: IconLayers, clock: IconClock };
const ICON_TONE = {
  cart: ["#F3E3C8", "#8F5A1E"], box: ["#DCEAE5", "#2E6A62"], receipt: ["#E1EBE1", "#4C7A5E"],
  wallet: ["#F3E3C8", "#8F5A1E"], flask: ["#DCEAE5", "#2E6A62"], alert: ["#F3DCD4", "#A8452E"],
  truck: ["#E1EBE1", "#4C7A5E"], users: ["#DCEAE5", "#2E6A62"], layers: ["#F3E3C8", "#8F5A1E"], clock: ["#F3DCD4", "#A8452E"],
};

export function KpiCard({ label, value, delta, up, icon }) {
  const Ico = ICONS[icon] || IconBox;
  const [bg, fg] = ICON_TONE[icon] || ["#eee", "#333"];
  return (
    <div className="kpi-card">
      <div className="kpi-top">
        <div className="kpi-icon" style={{ background: bg, color: fg }}><Ico size={17} /></div>
        <span className={`kpi-delta ${up ? "up" : "down"}`}>{delta}</span>
      </div>
      <div className="kpi-value">{value}</div>
      <div className="kpi-label">{label}</div>
    </div>
  );
}

/* className passes through so a page can key layout on a card without wrapping
   it in another div — the Purchase panes need it, and a wrapper would break the
   grid gap the parent sets. Defaults to nothing, so every existing call is
   unchanged. */
export function Card({ title, eyebrow, right, children, pad = true, className = "" }) {
  return (
    <div className={`card ${className}`.trim()}>
      <div className={pad ? "card-pad" : ""}>
        {(title || right) && (
          <div className="card-head">
            <div>
              {eyebrow && <div className="card-eyebrow">{eyebrow}</div>}
              {title && <div className="card-title">{title}</div>}
            </div>
            {right}
          </div>
        )}
        {children}
      </div>
    </div>
  );
}

export function Pill({ children, tone = "grey" }) {
  return <span className={`pill pill-${tone}`}>{children}</span>;
}

export function statusTone(status) {
  if (["Y", "Approved", "Active", "Delivered", "Collected", "Paid", "Converted"].includes(status)) return "sage";
  if (["N", "Pending", "Pending Approval", "Submitted", "Draft", "Unpaid"].includes(status)) return "amber";
  if (["Rejected", "Inactive", "Cancelled", "Merged"].includes(status)) return "clay";
  return "teal";
}

export function Table({ head, children, empty }) {
  return (
    <div className="table-wrap">
      <table className="data-table">
        <thead><tr>{head.map((h, i) => <th key={i}>{h}</th>)}</tr></thead>
        <tbody>{children}</tbody>
      </table>
      {empty && <div className="empty-state">{empty}</div>}
    </div>
  );
}

/**
 * A quantity field with − / + steppers and mouse-wheel stepping.
 *
 * Used for every quantity in the app so they all behave the same way. Counter
 * staff adjust quantities constantly, and reaching for the keyboard to change a
 * 2 to a 3 is the slow path.
 *
 * <p><b>The wheel steps only while the field is focused.</b> A focused number
 * input steps on wheel natively, but an *unfocused* one does too in most
 * browsers — so scrolling a long invoice with the pointer passing over a
 * quantity would silently rewrite it, and someone ships the wrong amount of
 * stock. Requiring focus makes it a deliberate act: you clicked into the field,
 * then scrolled it.
 *
 * <p>The handler is attached manually with `{ passive: false }` because React
 * registers `onWheel` passively at the root, where `preventDefault()` — which
 * stops the page scrolling at the same time — is ignored.
 *
 * @param step   how much one press or notch moves it (default 1)
 * @param min    floor; the − button disables at it rather than going below
 * @param onChange receives the new value as a string, like a plain input
 */
/**
 * @param integer  Whole numbers only. The arrows, the wheel and anything typed
 *   are all forced to an integer — a count of things cannot be 2.5 of them, and
 *   a field that accepts it has to be defended against everywhere downstream
 *   instead of once here. Off by default: some callers count in fractions.
 */
export function QtyInput({
  value, onChange, min = 1, max, step = 1, integer = false,
  disabled, className = "", ariaLabel = "Quantity", ...rest
}) {
  const ref = React.useRef(null);

  const clamp = (n) => {
    if (Number.isNaN(n)) return min;
    let v = n;
    if (min !== undefined && min !== null && v < Number(min)) v = Number(min);
    if (max !== undefined && max !== null && v > Number(max)) v = Number(max);
    if (integer) return Math.round(v);
    // Round to the step's precision so 0.1 + 0.2 does not surface as
    // 0.30000000000000004 in a field someone has to read.
    const dp = String(step).includes(".") ? String(step).split(".")[1].length : 0;
    return Number(v.toFixed(dp));
  };

  /**
   * What a typed character is allowed to become.
   *
   * <p>An empty box stays empty while it is being retyped — replacing it with a
   * 1 mid-keystroke fights the person doing the typing. Everything else is
   * clamped, so "2.5" settles as 3 and "abc" as the minimum.
   */
  const typed = (raw) => {
    if (!integer) return raw;
    if (raw === "") return "";
    const n = Number(raw);
    return Number.isNaN(n) ? "" : String(clamp(n));
  };

  const bump = (dir) => {
    if (disabled) return;
    const current = Number(value);
    const base = Number.isNaN(current) ? Number(min) - Number(step) : current;
    onChange(String(clamp(base + dir * Number(step))));
  };

  React.useEffect(() => {
    const el = ref.current;
    if (!el || disabled) return;
    const onWheel = (e) => {
      if (document.activeElement !== el) return;   // only when deliberately in it
      e.preventDefault();                          // and do not scroll the page too
      bump(e.deltaY < 0 ? 1 : -1);
    };
    el.addEventListener("wheel", onWheel, { passive: false });
    return () => el.removeEventListener("wheel", onWheel);
  });

  const atMin = min !== undefined && min !== null && Number(value) <= Number(min);
  const atMax = max !== undefined && max !== null && Number(value) >= Number(max);

  return (
    <div className={`qty-input${disabled ? " qty-input-off" : ""}`}>
      <button type="button" className="qty-step" tabIndex={-1}
              onClick={() => bump(-1)} disabled={disabled || atMin}
              aria-label={`Decrease ${ariaLabel.toLowerCase()}`}>−</button>

      <input
        {...rest}
        ref={ref}
        type="number"
        className={`input qty-input-field ${className}`}
        value={value}
        min={min}
        max={max}
        step={step}
        disabled={disabled}
        aria-label={ariaLabel}
        onChange={(e) => onChange(typed(e.target.value))}
        /* The browser's own arrows on a number input step by the `step`
           attribute, which is already 1 — but they do not go through clamp(),
           so a min of 0.0001 let Down from 1 land on 0.0001. Handled here so
           the two arrows and the two buttons behave identically. */
        onKeyDown={(e) => {
          if (!integer || disabled) return;
          if (e.key === "ArrowUp")   { e.preventDefault(); bump(1); }
          if (e.key === "ArrowDown") { e.preventDefault(); bump(-1); }
        }}
      />

      <button type="button" className="qty-step" tabIndex={-1}
              onClick={() => bump(1)} disabled={disabled || atMax}
              aria-label={`Increase ${ariaLabel.toLowerCase()}`}>+</button>
    </div>
  );
}

export function Field({ label, error, children, hint }) {
  /* The label has to point at its control, or it is decoration: a screen
     reader announces "edit text, blank" and clicking the word does nothing.
     Done here, once, rather than asking thirty forms to remember an id — and
     only when there is a single element to point at, so a Field wrapping two
     controls or a fragment renders exactly as it did before. */
  const generated = React.useId();
  const only = React.Children.count(children) === 1 ? React.Children.only(children) : null;
  const linkable = only && React.isValidElement(only);
  const id = linkable ? (only.props.id || generated) : undefined;
  const control = linkable && !only.props.id ? React.cloneElement(only, { id }) : children;

  return (
    <div className="field">
      <label htmlFor={id}>{label}</label>
      {control}
      {/* An error replaces the hint rather than stacking under it. Two lines of
          small grey-and-red text below one input is how a form starts looking
          like it is arguing with itself. */}
      {error ? <span className="err-msg">{error}</span>
             : hint ? <span className="field-hint">{hint}</span> : null}
    </div>
  );
}

export function Modal({ title, subtitle, onClose, children, footer, width }) {
  return (
    <div className="modal-backdrop" onMouseDown={(e) => { if (e.target === e.currentTarget) onClose(); }}>
      <div className="modal" style={width ? { maxWidth: width } : undefined}>
        <div className="modal-head">
          <div>
            <div className="card-title">{title}</div>
            {subtitle && <div className="modal-subtitle">{subtitle}</div>}
          </div>
          <button className="icon-btn" onClick={onClose}><IconX size={16} /></button>
        </div>
        <div className="modal-body">{children}</div>
        {footer && <div className="modal-foot">{footer}</div>}
      </div>
    </div>
  );
}

export function Tabs({ tabs, active, onChange }) {
  return (
    <div className="tabs">
      {tabs.map(t => (
        <button key={t} className={`tab-btn ${active === t ? "active" : ""}`} onClick={() => onChange(t)}>{t}</button>
      ))}
    </div>
  );
}

export function Toast({ message, onDone }) {
  React.useEffect(() => { const t = setTimeout(onDone, 2600); return () => clearTimeout(t); }, [onDone]);
  return <div className="toast">{message}</div>;
}

export function useToast() {
  const [msg, setMsg] = useState(null);
  const fire = (m) => setMsg(m);
  const node = msg ? <Toast message={msg} onDone={() => setMsg(null)} /> : null;
  return [fire, node];
}
