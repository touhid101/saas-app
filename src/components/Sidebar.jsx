/**
 * Sidebar — Dynamic menu driven by RBAC permissions from backend.
 * Fetches user's permitted modules via /api/v1/rbac/menu
 */
import React, { useCallback, useEffect, useState } from "react";
import { useApp } from "../lib/AppContext.jsx";
import uploadUrl from "../lib/uploadUrl.js";

/* ── Icon helper ── */
const Ic = ({ d, size = 16 }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none"
    stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round">
    {Array.isArray(d) ? d.map((p, i) => <path key={i} d={p} />) : <path d={d} />}
  </svg>
);

/* ── Icon paths for each route key ── */
export const ICON_PATHS = {
  dashboard:        ["M3 3h7v9H3z","M14 3h7v5h-7z","M14 12h7v9h-7z","M3 16h7v5H3z"],
  // A person with a tick: my own HR, my own requests.
  hris:             ["M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2","M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8z","M16 11l2 2 4-4"],
  master:           ["M12 3 2 8l10 5 10-5-10-5Z","M2 13l10 5 10-5","M2 18l10 5 10-5"],
  // Org Structure — a building with a mast, for the organisation and where it trades.
  orgstructure:     ["M3 21h18","M5 21V7l7-4 7 4v14","M9 21v-6h6v6","M9 11h.01M15 11h.01"],
  // Capsule/pill — the medicine icon for Master Data › Product
  productmaster:    ["M10.5 20.5a7.78 7.78 0 0 1-11-11l9-9a7.78 7.78 0 0 1 11 11z","M8.5 8.5l7 7"],
  purchase:         ["M6 2h12v20l-2.5-1.5L13 22l-2.5-1.5L8 22l-2-1.5V2z","M9 7h6M9 11h6M9 15h4"],
  sales:            ["M6 2h12v20l-2.5-1.5L13 22l-2.5-1.5L8 22l-2-1.5V2z","M9 7h6M9 11h6M9 15h4"],
  quicksales:       ["M4 2h16a2 2 0 0 1 2 2v16a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2z","M11 18h2"],
  activity:         ["M3 8h18M3 12h18M3 16h18","M3 4h18","M3 20h18"],
  salesperformance: ["M22 12h-4l-3 9L9 3l-3 9H2"],
  salesdiscount:    ["M20.59 13.41l-7.17 7.17a2 2 0 0 1-2.83 0L2 12V2h10l8.59 8.59a2 2 0 0 1 0 2.82z","M7 7h.01"],
  salesreturn:      ["M3 7v6h6","M3 13a9 9 0 1 0 3-7.7L3 8"],
  // Carton with an arrow turning back out of it — goods leaving, not arriving.
  purchasereturn:   ["M21 8v13H3V8","M1 3h22v5H1z","M10 12h5a3 3 0 0 1 0 6h-3","M13 15l-3-3 3-3"],
  // Wallet with a card in the slot — money leaving, as against `collection`
  // (a plain card) which is money coming in.
  supplierpayment:  ["M19 5H5a2 2 0 0 0-2 2v10a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V7a2 2 0 0 0-2-2z","M16 12h.01","M3 10h11"],
  delivery:         ["M1 6h14v11H1z","M15 9h4l3 3v5h-7z","M5 20a2 2 0 1 0 0-4 2 2 0 0 0 0 4z","M18 20a2 2 0 1 0 0-4 2 2 0 0 0 0 4z"],
  collection:       ["M19 5H5a2 2 0 0 0-2 2v10a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V7a2 2 0 0 0-2-2z","M1 10h22"],
  requisition:      ["M9 2H5a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V9z","M13 2v7h7","M9 12h6M9 16h4"],
  merge:            ["M6 3v6a4 4 0 0 0 4 4h4","M18 3v6a4 4 0 0 1-4 4","M12 13v8","M9 18l3 3 3-3"],
  inventory:        ["M21 8 12 3 3 8l9 5 9-5z","M3 8v9l9 5 9-5V8","M12 13v9"],
  reports:          ["M4 20V10M12 20V4M20 20v-7","M2 20h20"],
  // A stack of discs: the lists everything else points at.
  database:         ["M12 8c4.97 0 9-1.34 9-3s-4.03-3-9-3-9 1.34-9 3 4.03 3 9 3z","M3 5v14c0 1.66 4.03 3 9 3s9-1.34 9-3V5","M3 12c0 1.66 4.03 3 9 3s9-1.34 9-3"],
  ai:               ["M12 2a6 6 0 0 1 6 6v2a6 6 0 0 1-6 6 6 6 0 0 1-6-6V8a6 6 0 0 1 6-6z","M8 14s1.5 2 4 2 4-2 4-2","M9 10h.01M15 10h.01","M12 0v2","M2 8h2M20 8h2"],
  rbac:             ["M12 2 4 5v6c0 5 3.4 8.4 8 11 4.6-2.6 8-6 8-11V5l-8-3z","M9 12l2 2 4-4"],
  // ── E-Commerce (V101) ──
  // A storefront: awning and a door. The group the online customers, orders and promotions hang from.
  ecom:             ["M3 9l1.5-5h15L21 9","M3 9a3 3 0 0 0 6 0 3 3 0 0 0 6 0 3 3 0 0 0 6 0","M5 12v9h14v-9","M10 21v-5h4v5"],
  // A parcel with a tick: an order the shop has to fill, not a product to sell.
  ecomorders:       ["M21 8 12 3 3 8l9 5 9-5z","M3 8v9l9 5 9-5V8","M9 12.5l2 2 4-4"],
  // A ticket with a torn stub: a code somebody is given and spends once.
  ecompromotions:   ["M4 8V6a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2v2a2 2 0 0 0 0 4v2a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2v-2a2 2 0 0 0 0-4z","M14 4v3M14 11v2M14 17v3"],
  // A person with a globe behind them: one identity, every pharmacy.
  ecomcustomers:    ["M16 21v-2a4 4 0 0 0-4-4H7a4 4 0 0 0-4 4v2","M9.5 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8z","M17 3a5 5 0 0 1 0 10","M17 3c-1.5 1.5-1.5 8.5 0 10","M14.5 8h5"],
  // ── Human Resources (V77) ──
  // Two figures rather than one: HR is about a workforce, and a single person
  // reads as a profile, which is what the topbar avatar already means.
  employees:        ["M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2","M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8z","M23 21v-2a4 4 0 0 0-3-3.87","M16 3.13a4 4 0 0 1 0 7.75"],
  // A calendar with a tick — the register, marked.
  attendance:       ["M3 5h18v16H3z","M3 10h18","M8 3v4","M16 3v4","M9 15l2 2 4-4"],
  // Sun over a horizon: away from the shop, which is what leave is.
  leave:            ["M12 3v3","M5.6 7.6l2.1 2.1","M18.4 7.6l-2.1 2.1","M3 18h18","M6 21h12","M12 10a5 5 0 0 1 5 5H7a5 5 0 0 1 5-5z"],
  // Hand under a coin — money going out to a person, not to a supplier.
  advance:          ["M12 3v6","M9.5 5.5 12 3l2.5 2.5","M3 14h4l2 2h4a2 2 0 0 1 0 4H8","M3 21h4","M14 20l6-2a2 2 0 0 0-2-3l-4 1"],
  // A payslip: a document with ruled lines and a currency mark.
  payroll:          ["M6 2h12v20l-2.5-1.5L13 22l-2.5-1.5L8 22l-2-1.5V2z","M9 7h6","M9 11h6","M12 14v4","M13.5 15h-2a1.5 1.5 0 0 0 0 3h1a1.5 1.5 0 0 1 0 3h-2"],
  // A till slip with a torn edge: money already spent, being handed back.
  expenses:         ["M6 2h12v20l-2-1.5-2 1.5-2-1.5-2 1.5-2-1.5V2z","M9 7h6","M9 11h6","M9 15h3"],
  // A tick inside a document: a decision recorded, not just a page read.
  approvals:        ["M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z","M14 2v6h6","M9 15l2 2 4-4"],
  // Accounts. Same 24-grid, same weight as the HR set.
  // A bound ledger with a summary bar: the dashboard.
  accdashboard:     ["M4 3h16v18H4z","M8 3v18","M11 8h6","M11 12h6","M11 16h3"],
  // Two facing pages — a journal: every entry has its opposite side.
  journal:          ["M2 4h9a2 2 0 0 1 2 2v14a1 1 0 0 0-1-1H2z","M22 4h-9a2 2 0 0 0-2 2v14a1 1 0 0 1 1-1h10z"],
  // Ruled columns with a running balance line.
  ledger:           ["M4 3h16v18H4z","M4 8h16","M12 8v13","M8 12h2","M8 16h2","M15 12h3","M15 16h3"],
  // A tree: the chart of accounts is a hierarchy.
  coa:              ["M6 3h6v4H6z","M4 12h6v4H4z","M14 12h6v4h-6z","M14 19h6v2h-6z","M9 7v3","M6 10h11","M7 10v2","M17 10v2","M17 16v3"],
  // Two people with an arrow between them: who owes whom.
  parties:          ["M7 7a3 3 0 1 0 0 .01","M17 7a3 3 0 1 0 0 .01","M2 20a5 5 0 0 1 10 0","M12 20a5 5 0 0 1 10 0","M9 13h6","M13 11l2 2-2 2"],
  // A bank front with a coin.
  cashbank:         ["M3 9l9-5 9 5H3z","M5 9v8","M10 9v8","M14 9v8","M19 9v8","M3 21h18","M12 3v2"],
  // A till drawer, open, with a coin stack: the day's cash count.
  dailycash:        ["M3 8h18v4H3z","M5 12v8h14v-8","M9 16h6","M12 4v4","M8 4h8"],
  // A person with a rising line: capital put in, share taken out.
  investors:        ["M9 8a3 3 0 1 0 0 .01","M3 21a6 6 0 0 1 12 0","M14 13l3-3 2 2 3-4","M22 8v3h-3"],
  // A pie with a slice out: profit shared.
  profit:           ["M21 12A9 9 0 1 1 12 3v9z","M14 2.5A9 9 0 0 1 21.5 10H14z"],
  // A calendar with a padlock: periods that close and lock.
  accsettings:      ["M3 5h18v16H3z","M3 10h18","M8 3v4","M16 3v4","M10 15h4v4h-4z","M11 15v-1a1 1 0 0 1 2 0v1"],
  // Stacked statements with a total rule.
  accreports:       ["M5 3h11l3 3v15H5z","M16 3v3h3","M8 9h8","M8 13h8","M8 17h5","M14 17h2"],
  // Two halves brought level: reconciled.
  reconcile:        ["M12 3v18","M4 8h16","M4 8l-2 6a4 4 0 0 0 8 0l-2-6","M20 8l-2 6a4 4 0 0 0 8 0l-2-6","M8 21h8"],
  // Fallback
  default:          ["M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20z"]
};

const NavIcon = ({ iconKey, size = 16 }) => {
  const paths = ICON_PATHS[iconKey] || ICON_PATHS.default;
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none"
      stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round">
      {(Array.isArray(paths) ? paths : [paths]).map((p, i) => <path key={i} d={p} />)}
    </svg>
  );
};

/**
 * Whether a click should be handled in-page, or left to the browser.
 *
 * <p>Ctrl/Cmd-click opens a new tab, Shift-click a new window, middle-click a
 * background tab — all of them the browser's own, and all of them lost the
 * moment the handler calls preventDefault. So it only intercepts the plain
 * left-click, and every other gesture falls through to the href.
 */
const isPlainLeftClick = (e) =>
  e.button === 0 && !e.metaKey && !e.ctrlKey && !e.shiftKey && !e.altKey;

/**
 * One page in the menu.
 *
 * <p>An anchor, not a button. A button has nothing for "Open link in new tab"
 * to open: the whole menu was unopenable that way, and no amount of handler
 * code fixes it, because the browser needs an href before the context menu even
 * offers the option.
 */
/**
 * The rail's label, drawn outside the rail.
 *
 * <p>It used to be a `::after` on the nav item, which worked only while the rail
 * had `overflow: visible` — and that is exactly what stopped the menu
 * scrolling, so on a short screen the rail spilled down the document and took
 * the layout with it. A scroll container clips its content on both axes, so no
 * amount of `overflow-clip-margin` lets a pseudo-element escape one.
 *
 * <p>So the label is a real element, a sibling of the sidebar rather than a
 * child, positioned to whichever item the pointer or the keyboard is on. Being
 * outside the rail, nothing clips it; being one element rather than thirty, it
 * costs nothing to keep.
 */
function RailTip({ tip }) {
  if (!tip) return null;
  return (
    <span className="nav-tip" role="presentation"
          style={{ top: tip.top, left: tip.left }}>
      {tip.label}
    </span>
  );
}

function NavLink({ item, active, collapsed, badges, onNavigate, onTip, nested = false }) {
  /* Keyed by the route, which is what `badges` is keyed by and what the menu
     actually carries. It used to read `item.badgeKey` — a field nothing ever
     set, on any item, so the whole badge mechanism was dead: the requisitions
     count it was written for had never appeared either. */
  const count = badges[item.id];
  const waiting = count > 0;

  return (
    <a
      href={`/${item.id}`}
      className={`nav-item ${active === item.id ? "active" : ""} ${nested ? "nav-item-child" : ""}`}
      onClick={(e) => {
        if (!isPlainLeftClick(e)) return;   // let the browser open its tab
        e.preventDefault();
        onNavigate(item.id);
      }}
      /* Collapsed, the label is the only name there is, so the count has to
         travel in it — a dot on a nameless icon says something is waiting
         without saying what or how many. `title` stays as well: it is the
         fallback if the pointer never triggers the drawn one, and it is what a
         screen reader announces. */
      title={collapsed
        ? (waiting ? `${item.label} — ${count} waiting` : item.label)
        : ""}
      style={collapsed ? { justifyContent: "center", padding: "10px" } : {}}
      onMouseEnter={collapsed ? (e) => onTip?.(e.currentTarget,
        waiting ? `${item.label} — ${count} waiting` : item.label) : undefined}
      onMouseLeave={collapsed ? () => onTip?.(null) : undefined}
      onFocus={collapsed ? (e) => onTip?.(e.currentTarget,
        waiting ? `${item.label} — ${count} waiting` : item.label) : undefined}
      onBlur={collapsed ? () => onTip?.(null) : undefined}
    >
      <NavIcon iconKey={item.iconKey || item.id} size={nested ? 14 : 15} />
      {!collapsed && <span style={{ flex: 1 }}>{item.label}</span>}

      {/* Two shapes for one fact. Open, the number itself; collapsed, a dot on
          the icon, because there is no room for a numeral beside a 56px rail
          and a shrunken one is unreadable rather than compact. */}
      {waiting && (collapsed
        ? <span className="nav-dot" aria-hidden="true" />
        : <span className="badge-count">{count > 99 ? "99+" : count}</span>)}

      {/* Said once, for a screen reader, whichever shape is drawn. */}
      {waiting && <span className="sr-only">{count} waiting for a decision</span>}
    </a>
  );
}

/**
 * A heading with pages under it.
 *
 * <p>Collapsed to icons, the children are shown flat rather than hidden behind a
 * heading with no room to expand — a rail of icons the user cannot reach is
 * worse than one that is slightly longer.
 */
function NavGroup({ group, active, collapsed, badges, open, onToggle, onNavigate, onTip }) {
  const holdsActive = group.children.some((c) => c.id === active);

  /* What is waiting inside, for when the heading is closed over it. Without
     this, collapsing "Human Resources" hides five badges behind a chevron and
     the whole point of them — being seen without looking — is lost. */
  const inside = group.children.reduce((n, c) => n + (badges[c.id] || 0), 0);

  if (collapsed) {
    return (
      <>
        {group.children.map((c) => (
          <NavLink key={c.id} item={c} active={active} collapsed badges={badges}
                   onNavigate={onNavigate} onTip={onTip} />
        ))}
      </>
    );
  }

  return (
    <div className={`nav-group ${open ? "nav-group-open" : ""}`}>
      <button className={`nav-item nav-group-head ${holdsActive && !open ? "active" : ""}`}
        onClick={onToggle} aria-expanded={open}>
        <NavIcon iconKey={group.iconKey} size={15} />
        <span style={{ flex: 1 }}>{group.label}</span>

        {/* Only while closed. Open, the pages carry their own counts, and a
            total sitting above them reads as a seventh thing waiting. */}
        {!open && inside > 0 && (
          <>
            <span className="badge-count">{inside > 99 ? "99+" : inside}</span>
            <span className="sr-only">{inside} waiting inside {group.label}</span>
          </>
        )}

        <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor"
          strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round"
          style={{ transform: open ? "rotate(90deg)" : "none", transition: "transform .15s", opacity: .6 }}>
          <path d="m9 18 6-6-6-6" />
        </svg>
      </button>
      {open && (
        <div className="nav-group-body">
          {group.children.map((c) => (
            <NavLink key={c.id} item={c} active={active} collapsed={false}
              badges={badges} onNavigate={onNavigate} nested />
          ))}
        </div>
      )}
    </div>
  );
}

/*
 * The three shapes this sidebar takes, and the widths that choose between them.
 * These strings are the single source of the breakpoints — styles.css repeats
 * the same numbers, and the two must be read as one decision.
 *
 *   below 768px    a drawer  — off-canvas, opened by the hamburger, full labels
 *   768 to 1023    a rail    — icons only, always visible, no room for labels
 *   1024 and up    a sidebar — full width, unless the user collapsed it
 *
 * A phone gets the drawer rather than the rail because a 56px rail on a 375px
 * screen costs 15% of the width to show icons nobody can identify. Hidden until
 * asked for, then full width with real labels, is the shape that fits.
 */
const DRAWER_BELOW = "(max-width: 767.98px)";
const RAIL_RANGE   = "(min-width: 768px) and (max-width: 1023.98px)";

/**
 * Whether the viewport is narrow enough that the sidebar is a rail.
 *
 * <p>The stylesheet already narrows the sidebar to 56px here. It cannot,
 * however, tell {@link NavGroup} to render its children flat — so the group
 * stayed a collapsible heading whose label the same stylesheet had just hidden:
 * a bare icon with a chevron, which had to be guessed at and tapped before its
 * pages appeared, and whose children were then indented off the middle of a
 * 56px rail.
 *
 * <p>Reading the same breakpoint here keeps the markup and the stylesheet
 * describing one sidebar rather than two.
 */
function useRailWidth() {
  const read = () => (typeof window === "undefined")
    ? { rail: false, mobile: false }
    : { rail: window.matchMedia(RAIL_RANGE).matches,
        mobile: window.matchMedia(DRAWER_BELOW).matches };
  const [rail, setRail] = useState(read);

  useEffect(() => {
    const mq = window.matchMedia(DRAWER_BELOW);
    const mqRail = window.matchMedia(RAIL_RANGE);
    // Read the query rather than trust the event: `change` is the right signal
    // but not a reliable one — it did not arrive at all when the viewport was
    // resized under this component, leaving the stylesheet drawing a 56px rail
    // while the markup still rendered the full menu inside it. `resize` fires
    // in every case that matters, including a phone being turned on its side,
    // and asking matchMedia each time costs nothing.
    const sync = () => setRail(read());
    mq.addEventListener("change", sync);
    mqRail.addEventListener("change", sync);
    window.addEventListener("resize", sync);
    sync();                       // catches a resize between render and effect
    return () => {
      mq.removeEventListener("change", sync);
      mqRail.removeEventListener("change", sync);
      window.removeEventListener("resize", sync);
    };
  }, []);

  return rail;
}

/**
 * The pharmacy's mark: its logo, or its initial when it has none.
 *
 * <p>A fallback rather than a broken image, because a tenant that has not
 * uploaded anything is the normal case, not an error — and because a logo file
 * that has gone missing should degrade to something recognisable rather than to
 * the browser's broken-image glyph on every page.
 */
function BrandMark({ name, logo, size = 30 }) {
  const src = uploadUrl(logo);

  /* Keyed by the URL, not a bare boolean. A single `failed` flag never cleared
     itself, so once one logo 404-ed the mark stayed a letter for the rest of
     the session — including after the branch was switched, or the logo
     re-uploaded and the URL corrected. Recording WHICH src failed means a new
     one is tried on its own merits. */
  const [failedSrc, setFailedSrc] = React.useState(null);
  const broken = !src || failedSrc === src;

  const initial = (name || "?").trim().charAt(0).toUpperCase() || "?";

  return (
    <span
      className="brand-mark"
      style={{ width: size, height: size, fontSize: Math.round(size * 0.42) }}
      /* The name is on the row already; repeating it here would have a screen
         reader announce the pharmacy twice before reading the menu. */
      aria-hidden="true"
    >
      {broken
        ? initial
        : <img
            src={src}
            alt=""
            loading="lazy"
            decoding="async"
            onError={() => setFailedSrc(src)}
          />}
    </span>
  );
}

export default function Sidebar({ active, onNavigate, navOpen = false, onClose }) {
  const { sidebarCollapsed, setSidebarCollapsed, currentUser, globalReqs,
          userMenu, menuLoading, hrCounts } = useApp();

  /**
   * Which groups the user has explicitly opened or closed.
   *
   * Declared before the early returns below: this component returns a
   * placeholder while the menu loads, and a hook called after that point would
   * run on some renders and not others — which React refuses with "rendered more
   * hooks than during the previous render" the moment loading finishes.
   */
  const [openGroups, setOpenGroups] = useState({});

  /* Whether the branch name is showing in full. A `title` tooltip answers this
     with a mouse and does nothing at all on a touch screen, where there is no
     hover to have — so the name is also tappable, and unfolds to as many lines
     as it needs. */
  const [nameOpen, setNameOpen] = useState(false);

  /* Whether the tablet rail has been opened out to show labels.
     Its own state, not sidebarCollapsed: the desktop preference is "I want the
     sidebar narrow", which persists; this is "show me what these icons are",
     which is a glance and should close again. */
  const [railExpanded, setRailExpanded] = useState(false);
  /**
   * Which item the rail is naming, and where to draw the name.
   *
   * <p>Read from the item's own box at the moment it is entered, so it follows
   * the menu as it scrolls rather than being computed once and drifting. Null
   * whenever nothing is hovered or focused, which is most of the time — the
   * element is not rendered at all then.
   *
   * <p>Up here with the other hooks, above the early returns, for the reason
   * spelled out on openGroups: a hook after them runs on some renders and not
   * others, and React refuses the next one.
   */
  const [tip, setTip] = useState(null);

  const showTip = useCallback((el, label) => {
    if (!el) { setTip(null); return; }
    const r = el.getBoundingClientRect();
    setTip({ label, top: Math.round(r.top + r.height / 2), left: Math.round(r.right + 10) });
  }, []);


  /* Narrow viewport counts as collapsed, so the icon rail the stylesheet draws
     and the flat child list this component renders are the same decision. */
  const { rail, mobile } = useRailWidth();

  /* A rail has no room for labels, so it is collapsed. A drawer has the whole
     screen once it is open, so it is NOT — showing icon-only navigation inside
     a panel the user deliberately opened would be the worst of both. */
  /* The rail defaults to icons, but is no longer STUCK there — `|| rail` meant
     no amount of clicking could open it, and the control that would have done
     so was hidden at the same widths. A tablet now opens the menu like anything
     else; it simply starts closed, because 244px of a 768px screen is a third
     of the width and these pages are built around wide tables. */
  const collapsed = mobile ? false : (rail ? !railExpanded : sidebarCollapsed);

  /* Opened on a tablet, the sidebar floats over the page instead of pushing it
     — 244px taken out of 768px reflows every table underneath, and the menu is
     a glance, not a new layout. */
  const railOverlay = rail && railExpanded;

  /* A rail that stops being a rail must not leave its label behind. Below
     `collapsed` because it reads it — above, it was a temporal-dead-zone
     reference and the whole sidebar failed to render. */
  useEffect(() => { if (!collapsed) setTip(null); }, [collapsed]);

  /* Kept for the collapse control below, which belongs to neither shape: the
     rail cannot expand, and the drawer expands by being opened. */
  const narrow = rail || mobile;

  /* Anything that covers the page closes on Escape and on a tap outside. */
  const overlayOpen = (mobile && navOpen) || railOverlay;
  const closeOverlay = () => { if (railOverlay) setRailExpanded(false); onClose?.(); };

  /* Choosing a page puts the overlay away. Without this the menu stays open
     over the screen it just opened, and every choice needs a second tap to see
     the result — which reads as the menu being stuck. (App.jsx already does
     this for the phone drawer; the tablet overlay is this component's own.) */
  const navigateAndClose = (id) => { if (railOverlay) setRailExpanded(false); onNavigate(id); };

  /* Off-canvas panels swallow the page behind them. Escape closes it, because a
     drawer with no visible way out is a trap for anyone not using touch. */
  useEffect(() => {
    if (!overlayOpen) return undefined;
    const onKey = (e) => { if (e.key === "Escape") closeOverlay(); };
    window.addEventListener("keydown", onKey);
    /* The page behind must not scroll under the drawer — on iOS especially,
       that reads as the drawer itself being broken. */
    const root = document.documentElement;   // on <html>, not <body> — see HrDrawer
    const prev = root.style.overflow;
    root.style.overflow = "hidden";
    return () => {
      window.removeEventListener("keydown", onKey);
      root.style.overflow = prev;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [overlayOpen]);

  const shellCls = [
    "sidebar",
    collapsed ? "sidebar-collapsed" : "",
    mobile ? "sidebar-drawer" : "",
    mobile && navOpen ? "is-open" : "",
    railOverlay ? "rail-open" : "",
  ].filter(Boolean).join(" ");

  /* Whose pharmacy this is. Both come from the session the user signed in with,
     so a platform operator supporting a customer still sees their own — the
     acting-branch header changes what the data is, not who is signed in. */
  const branchName = currentUser?.branchName || "Friends Pharma";
  const branchLogo = currentUser?.branchLogoUrl || null;

  /* ── If menu not loaded yet, show loading or fallback ── */
  if (menuLoading) {
    return (
      <aside className={shellCls}>
        <div className="sidebar-brand">
          <div style={{ padding: "20px", color: "var(--sidebar-soft)", textAlign: "center" }}>
            Loading menu...
          </div>
        </div>
      </aside>
    );
  }
  
  if (!userMenu) {
    console.log('[Sidebar] userMenu is null, showing placeholder');
    return (
      <aside className={shellCls}>
        <div className="sidebar-brand">
          <div style={{ padding: "20px", color: "var(--sidebar-soft)", textAlign: "center", fontSize: "12px" }}>
            No menu loaded yet...
            <br/>
            <small>Check console for errors</small>
          </div>
        </div>
      </aside>
    );
  }
  
  if (userMenu && userMenu.length === 0) {
    console.log('[Sidebar] userMenu is empty array');
    return (
      <aside className={shellCls}>
        <div className="sidebar-brand">
          <div style={{ padding: "20px", color: "var(--sidebar-soft)", textAlign: "center", fontSize: "12px" }}>
            No permissions assigned
            <br/>
            <small>Contact administrator</small>
          </div>
        </div>
      </aside>
    );
  }
  
  /* ── Convert backend menu to frontend format ── */
  /**
   * The menu, exactly as the server sends it.
   *
   * This used to sort modules into four headings by matching hard-coded module
   * codes — so the grouping lived here, in a list nobody updated, and any page
   * missing from it silently landed under "OPERATIONS". The structure now comes
   * from ACL_MODULE_INFO.PID_PARENT_MODULE, which is where the modules are.
   *
   * A node with children and no route is a container: a heading that expands.
   * A node with a route is a page.
   */
  const toNav = (items) => (items ?? []).map((m) => ({
    // The route is the page key the app navigates by. Containers have none, so
    // they fall back to their module code purely to have a stable React key.
    id: m.routePath ? m.routePath.replace(/^\//, "") : m.moduleCode.toLowerCase(),
    label: m.moduleName,
    moduleId: m.moduleCode,
    iconKey: m.iconKey || (m.routePath ? m.routePath.replace(/^\//, "") : "default"),
    isGroup: !m.routePath && (m.children?.length ?? 0) > 0,
    children: toNav(m.children),
    permissions: {
      canView: m.canView, canCreate: m.canCreate, canEdit: m.canEdit,
      canDelete: m.canDelete, canApprove: m.canApprove, canExport: m.canExport,
    },
  }));

  const fromServer = userMenu ? toNav(userMenu) : [];

  /* Self-service is not a module anybody is granted: it is the signed-in
     person's own HR, unlocked by their login being linked to an employee
     record. So it is added here, once, after the dashboard — and the server
     refuses every /hr/me call for a login with no employee behind it. */
  const nav = currentUser?.employeeId && !fromServer.some((n) => n.id === "hris")
    ? [...fromServer.slice(0, 1), {
        id: "hris", label: "My HR (HRIS)", moduleId: "HRIS", iconKey: "hris", isGroup: false, children: [],
        permissions: { canView: true, canCreate: true, canEdit: false, canDelete: false, canApprove: false, canExport: false },
      }, ...fromServer.slice(1)]
    : fromServer;


  /**
   * Explicit choices are remembered; anything untouched falls back to "open if
   * it holds the page you are on". Navigating into a group from elsewhere — a
   * dashboard link, a browser reload — must not leave the current page hidden
   * inside a collapsed heading with nothing indicating where it went.
   */
  const toggleGroup = (moduleId) =>
    setOpenGroups((s) => ({ ...s, [moduleId]: !isGroupOpenFor(moduleId) }));

  const isGroupOpenFor = (moduleId) => {
    if (moduleId in openGroups) return openGroups[moduleId];
    const g = nav.find((n) => n.moduleId === moduleId);
    return !!g?.children?.some((c) => c.id === active);
  };
  const isGroupOpen = (group) => isGroupOpenFor(group.moduleId);
  
  /* ══ badge counts ══════════════════════════════════════════════════════

     Only things that are **waiting on a person**. Not "how many exist", not
     "how much money" — a badge is a call to action, and one that never reaches
     zero is furniture the eye stops seeing within a week.

     That rules out two figures it would be easy to put here: attendance not yet
     recorded today, which is a full house every morning before anybody marks
     the register, and payroll, whose run is a five-step sequence rather than a
     queue of items. */
  const pendingReqs = globalReqs.filter(r => r.status === "Pending Approval").length;
  const hr = hrCounts || {};

  const badges = {
    requisitions: pendingReqs,
    leave:     hr.pendingLeaveRequests || 0,
    // One badge for one page: the page holds both, so the number has to as well.
    advances: (hr.pendingAdvances || 0) + (hr.pendingLoans || 0),
    expenses:  hr.pendingExpenseClaims || 0,
    attendance: hr.pendingAttendanceCorrections || 0,
    // Separations are decided on the Employees screen.
    hr:        hr.pendingSeparations || 0,
  };

  return (
    <>
      {/* The backdrop is what makes the drawer feel like a drawer: it dims the
          page, and tapping it closes — the gesture everyone tries first, and
          the only obvious one on a phone with no visible close button. It is
          rendered only on mobile so it can never intercept a desktop click. */}
      {(mobile || rail) && (
        <div
          className={`nav-backdrop ${overlayOpen ? "is-open" : ""}`}
          onClick={closeOverlay}
          aria-hidden="true"
        />
      )}
    <aside className={shellCls}>
      {/* Brand — the pharmacy's own name and mark.
          Both used to be literals here, so every tenant on the platform was
          shown one company's branding. They come from the session now: the
          same row that already supplied the branch name. */}
      <div className="sidebar-brand" style={{ position: "relative" }}>
        {!collapsed && (
          <div className="brand-row">
            <BrandMark name={branchName} logo={branchLogo} size={30} />
            <div className="brand-text">
              <div className="kicker">Pharma ERP</div>
              <button
                type="button"
                className={`name ${nameOpen ? "is-expanded" : ""}`}
                title={branchName}
                aria-label={branchName}
                aria-expanded={nameOpen}
                onClick={() => setNameOpen(v => !v)}
              >
                {branchName}
              </button>
            </div>
          </div>
        )}
        {/* The rail has no room for the name, so the mark carries it as a
            tooltip — otherwise the only clue to which pharmacy you are in is a
            26px image. */}
        {collapsed && (
          <div title={branchName}
               style={{ display: "flex", alignItems: "center", justifyContent: "center", padding: "4px 0" }}>
            <BrandMark name={branchName} logo={branchLogo} size={26} />
          </div>
        )}
        {/* Hidden on a narrow screen: there the rail is the only layout that
            fits, so a control offering to expand it would toggle a preference
            and change nothing on screen. */}
        {!mobile && (
          <button
            onClick={() => rail ? setRailExpanded(v => !v) : setSidebarCollapsed(c => !c)}
            aria-label={collapsed ? "Expand menu" : "Collapse menu"}
            style={{ position: "absolute", right: 10, top: collapsed ? 8 : 24, background: "rgba(255,255,255,0.08)", border: "none", borderRadius: 6, color: "var(--sidebar-soft)", width: 26, height: 26, display: "flex", alignItems: "center", justifyContent: "center", cursor: "pointer" }}
          >
            <Ic d={collapsed ? "M9 18l6-6-6-6" : "M15 18l-6-6 6-6"} size={13} />
          </button>
        )}
      </div>

      {/* Role badge */}
      {!collapsed && currentUser && (
        <div style={{ padding: "8px 12px 2px", display: "flex", alignItems: "center", gap: 8 }}>
          <div style={{ fontSize: 10.5, background: "rgba(184,117,43,0.25)", color: "var(--amber)", borderRadius: 6, padding: "3px 9px", fontFamily: "monospace", fontWeight: 700 }}>
            {currentUser.roleId === "ROLE01" ? "Super Admin"
             : currentUser.roleId === "ROLE02" ? "Branch Manager"
             : currentUser.roleId === "ROLE03" ? "Pharmacist"
             : currentUser.roleId === "ROLE04" ? "Accounts"
             : currentUser.roleId === "ROLE05" ? "Purchase"
             : currentUser.roleId === "ROLE06" ? "Delivery"
             : currentUser.roleId === "ROLE07" ? "Store"
             : currentUser.roleId === "ROLE09" ? "Sales Mgr"
             : "Viewer"}
          </div>
          <div style={{ fontSize: 11.5, color: "var(--sidebar-soft)", overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
            {(currentUser.fullName || currentUser.name || 'User').split(" ")[0]}
          </div>
        </div>
      )}

      {/* Navigation */}
      <nav className="sidebar-nav">
        {nav.map((item) => item.isGroup ? (
          <NavGroup key={item.moduleId} group={item} active={active}
            collapsed={collapsed} badges={badges}
            open={isGroupOpen(item)} onToggle={() => toggleGroup(item.moduleId)}
            onNavigate={navigateAndClose} onTip={showTip} />
        ) : (
          <NavLink key={item.id} item={item} active={active}
            collapsed={collapsed} badges={badges} onNavigate={navigateAndClose}
            onTip={showTip} />
        ))}
      </nav>

      {/* Footer */}
      {!collapsed && (
        <div className="sidebar-footer">
          <div>Friends Pharma ERP v2.0</div>
          <div style={{ opacity: 0.5, fontSize: 11 }}>Developed by www.amigoscloud.com</div>
        </div>
      )}
    </aside>

    {/* Outside the <aside> on purpose — see RailTip. */}
    <RailTip tip={tip} />
    </>
  );
}
