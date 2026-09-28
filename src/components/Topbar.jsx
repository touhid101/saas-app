import React, { useState, useRef, useEffect, useMemo } from "react";
import { useApp, CAP_NOTIFICATIONS, CAP_GLOBAL_SEARCH } from "../lib/AppContext.jsx";
import * as authService from "../services/authService.js";
import ProfileModal from "./ProfileModal.jsx";
import ChangePasswordModal from "./ChangePasswordModal.jsx";
import { fmtDateTimeFriendly, initials, moneyFull as money } from "../utils/format.js";

const Ic = ({ d, size = 16, color }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none"
    stroke={color || "currentColor"} strokeWidth="1.9"
    strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    {Array.isArray(d) ? d.map((p, i) => <path key={i} d={p} />) : <path d={d} />}
  </svg>
);

/* Fallback role accents, used only when the role has no colour set in the DB */
const ROLE_COLORS = {
  ROLE01: "#6B5EA8", ROLE02: "#2E6A62", ROLE03: "#B8752B",
  ROLE04: "#4C7A5E", ROLE05: "#A8452E", ROLE06: "#2E7D8A",
  ROLE07: "#5A9B3C", ROLE08: "#9FB0A5", ROLE09: "#D4925A",
};

const roleAccent = (user) =>
  user?.roleColor || ROLE_COLORS[user?.roleCode] || "var(--teal)";

const displayName = (user) => user?.fullName || user?.name || user?.username || "—";

/* ═════════════════════════════ NOTIFICATIONS ═══════════════════════════ */

const NOTIF_META = {
  LOW_STOCK:    { tone: "var(--amber)", icon: ["M12 9v4", "M12 17h.01", "M10.3 3.9 1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z"] },
  OUT_OF_STOCK: { tone: "var(--clay)",  icon: ["M21 16V8a2 2 0 0 0-1-1.7l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.7l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z", "M9 10l6 4", "M15 10l-6 4"] },
  EXPIRY:       { tone: "var(--amber)", icon: ["M12 8v4l3 2", "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z"] },
  REQUISITION:  { tone: "var(--teal)",  icon: ["M9 11l3 3L22 4", "M21 12v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11"] },
  DELIVERY:     { tone: "var(--sage)",  icon: ["M1 3h15v13H1z", "M16 8h4l3 3v5h-7z", "M5.5 19a2 2 0 1 0 0-4 2 2 0 0 0 0 4z", "M18.5 19a2 2 0 1 0 0-4 2 2 0 0 0 0 4z"] },
  EXPIRED:      { tone: "var(--clay)",  icon: ["M12 8v4l3 2", "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z", "M4.5 4.5l15 15"] },
  CUSTOMER_DUE: { tone: "var(--amber)", icon: ["M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2", "M12 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8z"] },
  SUPPLIER_DUE: { tone: "var(--clay)",  icon: ["M3 6h18v12H3z", "M3 10h18", "M7 15h4"] },
};
const DEFAULT_META = { tone: "var(--teal)", icon: ["M6 8a6 6 0 0 1 12 0c0 5 2 6 2 6H4s2-1 2-6z", "M10 21a2 2 0 0 0 4 0"] };

function NotificationPanel({ onClose, onNavigate }) {
  const { notifications, markNotificationRead, markAllRead } = useApp();
  const [filter, setFilter] = useState("all"); // "all" | "unread"

  const unread = notifications.filter(n => !n.read);
  const shown  = filter === "unread" ? unread : notifications;

  const open = (n) => {
    if (!n.read) markNotificationRead(n.id);
    if (n.link && onNavigate) { onNavigate(n.link); onClose(); }
  };

  return (
    <div
      role="dialog"
      aria-label="Notifications"
      className="topbar-popover notif-panel"
    >
      {/* Header */}
      <div style={{ padding: "15px 18px 12px", borderBottom: "1px solid var(--line)" }}>
        <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", gap: 12 }}>
          <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
            <span style={{ fontWeight: 700, fontSize: 14.5, color: "var(--ink)" }}>Notifications</span>
            {unread.length > 0 && (
              <span style={{
                background: "var(--clay)", color: "#fff", borderRadius: 999,
                padding: "1px 8px", fontSize: 11, fontWeight: 700,
              }}>{unread.length}</span>
            )}
          </div>
          <button
            onClick={markAllRead}
            disabled={unread.length === 0}
            style={{
              fontSize: 12, background: "none", border: "none", fontWeight: 600,
              padding: 0, color: unread.length ? "var(--amber-deep)" : "var(--ink-soft)",
              cursor: unread.length ? "pointer" : "default",
              opacity: unread.length ? 1 : 0.55,
            }}
          >
            Mark all read
          </button>
        </div>

        {/* Filter */}
        <div style={{ display: "flex", gap: 4, marginTop: 12, background: "var(--paper)", padding: 3, borderRadius: 8, width: "fit-content" }}>
          {[["all", `All ${notifications.length}`], ["unread", `Unread ${unread.length}`]].map(([key, label]) => (
            <button
              key={key}
              onClick={() => setFilter(key)}
              aria-pressed={filter === key}
              style={{
                fontSize: 12, fontWeight: 600, padding: "4px 12px", borderRadius: 6,
                border: "none", cursor: "pointer",
                background: filter === key ? "var(--card)" : "transparent",
                color: filter === key ? "var(--ink)" : "var(--ink-soft)",
                boxShadow: filter === key ? "var(--shadow)" : "none",
              }}
            >
              {label}
            </button>
          ))}
        </div>
      </div>

      {/* List */}
      <div style={{ maxHeight: 380, overflowY: "auto" }}>
        {shown.length === 0 ? (
          <div style={{ padding: "40px 20px", textAlign: "center", color: "var(--ink-soft)" }}>
            <div style={{ opacity: 0.5, marginBottom: 10 }}><Ic d={DEFAULT_META.icon} size={26} /></div>
            <div style={{ fontSize: 13, fontWeight: 600, color: "var(--ink)" }}>
              {filter === "unread" ? "You're all caught up" : "No notifications"}
            </div>
            <div style={{ fontSize: 12, marginTop: 3 }}>
              {filter === "unread" ? "Every notification has been read." : "New alerts will appear here."}
            </div>
          </div>
        ) : shown.map(n => {
          const meta = NOTIF_META[n.type] || DEFAULT_META;
          return (
            <button
              key={n.id}
              onClick={() => open(n)}
              style={{
                width: "100%", textAlign: "left", border: "none", cursor: "pointer",
                padding: "12px 18px 12px 15px",
                borderBottom: "1px solid var(--line)",
                borderLeft: `3px solid ${n.read ? "transparent" : meta.tone}`,
                background: n.read ? "transparent" : "var(--paper)",
                display: "flex", gap: 12, alignItems: "flex-start",
                transition: "background .12s",
              }}
              onMouseEnter={e => e.currentTarget.style.background = "var(--paper-deep)"}
              onMouseLeave={e => e.currentTarget.style.background = n.read ? "transparent" : "var(--paper)"}
            >
              <span style={{
                width: 32, height: 32, borderRadius: 9, flexShrink: 0,
                display: "flex", alignItems: "center", justifyContent: "center",
                background: "var(--card)", border: "1px solid var(--line)", color: meta.tone,
              }}>
                <Ic d={meta.icon} size={15} />
              </span>

              <span style={{ flex: 1, minWidth: 0 }}>
                <span style={{ display: "flex", alignItems: "baseline", gap: 8, justifyContent: "space-between" }}>
                  <span style={{
                    fontWeight: n.read ? 600 : 700, fontSize: 13, color: "var(--ink)",
                    overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap",
                  }}>{n.title}</span>
                  {/* A computed alert has no date to show — it is the position
                      now. Where one is about money, the amount is the figure
                      worth reading at a glance. */}
                  {n.amount != null && (
                    <span style={{ fontSize: 11, fontWeight: 700, color: meta.tone, flexShrink: 0, whiteSpace: "nowrap" }}>
                      {money(n.amount)}
                    </span>
                  )}
                </span>
                <span style={{ display: "block", fontSize: 12, color: "var(--ink-soft)", marginTop: 2, lineHeight: 1.45 }}>
                  {n.message}
                </span>
              </span>

              {!n.read && (
                <span
                  onClick={(e) => { e.stopPropagation(); markNotificationRead(n.id); }}
                  role="button"
                  aria-label="Mark as read"
                  title="Mark as read"
                  style={{
                    width: 8, height: 8, borderRadius: "50%", background: meta.tone,
                    marginTop: 12, flexShrink: 0,
                  }}
                />
              )}
            </button>
          );
        })}
      </div>
    </div>
  );
}

/* ═════════════════════════════ USER MENU ═══════════════════════════════ */

const THEMES = [
  { id: "light",  label: "Light",  swatch: "#F5F4EC", ring: "#B8752B" },
  { id: "dark",   label: "Dark",   swatch: "#1C2420", ring: "#D4925A" },
  { id: "ocean",  label: "Ocean",  swatch: "#EEF2FA", ring: "#3B72C2" },
  { id: "forest", label: "Forest", swatch: "#EDF4EB", ring: "#5A9B3C" },
];

/* Kept in step with ImageStorageService, which is the check that counts. WEBP
   is accepted there, so rejecting it here would refuse a file the server takes. */
const ACCEPTED_IMG = ["image/jpeg", "image/jpg", "image/png", "image/webp"];
const MAX_IMG_BYTES = 2 * 1024 * 1024;

function MenuItem({ icon, label, hint, onClick, tone }) {
  return (
    <button
      onClick={onClick}
      className="user-menu-item"
      style={{ color: tone || "var(--ink)" }}
    >
      <Ic d={icon} size={15} />
      <span style={{ flex: 1 }}>
        <span style={{ display: "block", fontSize: 13.5, fontWeight: 500 }}>{label}</span>
        {hint && <span style={{ display: "block", fontSize: 11, color: "var(--ink-soft)", marginTop: 1 }}>{hint}</span>}
      </span>
    </button>
  );
}

function UserMenu({ onClose, onOpenProfile, onOpenPassword }) {
  const {
    currentUser, theme, setTheme, currentBranch, currentAvatarUrl,
    patchCurrentUser, logout, branches, addToast,
  } = useApp();

  const [section, setSection]   = useState("main"); // "main" | "theme"
  const [uploading, setUpload]  = useState(false);
  const [uploadErr, setUpErr]   = useState("");
  const fileRef = useRef(null);

  const accent = roleAccent(currentUser);
  const ini    = initials(displayName(currentUser)) || "FP";

  const branchLabel = currentUser?.branchName
    || branches.find(b => b.id === (currentUser?.branchId ?? currentUser?.pidBranch))?.name
    || currentBranch?.name;

  const pickAvatar = async (e) => {
    const file = e.target.files?.[0];
    if (!file) return;
    setUpErr("");

    if (!ACCEPTED_IMG.includes(file.type)) {
      setUpErr("Use a JPG, PNG or WEBP image");
      if (fileRef.current) fileRef.current.value = "";
      return;
    }
    if (file.size > MAX_IMG_BYTES) {
      setUpErr("Image must be under 2 MB");
      if (fileRef.current) fileRef.current.value = "";
      return;
    }

    setUpload(true);
    try {
      // Self-service endpoint — works for every role, unlike the admin RBAC route
      const updated = await authService.uploadMyAvatar(file);
      if (updated?.avatarUrl) patchCurrentUser({ avatarUrl: updated.avatarUrl });
      addToast("Photo updated");
    } catch (err) {
      setUpErr(err?.message || "Upload failed");
    } finally {
      setUpload(false);
      if (fileRef.current) fileRef.current.value = "";
    }
  };

  return (
    <div
      role="menu"
      className="topbar-popover user-menu"
    >
      {/* ── Identity header ── */}
      <div style={{ padding: "16px 18px 14px", borderBottom: "1px solid var(--line)" }}>
        <div style={{ display: "flex", gap: 13, alignItems: "center" }}>
          <div style={{ position: "relative", flexShrink: 0 }}>
            <div style={{
              width: 50, height: 50, borderRadius: "50%", background: accent, color: "#fff",
              display: "flex", alignItems: "center", justifyContent: "center",
              fontWeight: 700, fontSize: 17, overflow: "hidden",
            }}>
              {currentAvatarUrl
                ? <img src={currentAvatarUrl} alt="" style={{ width: "100%", height: "100%", objectFit: "cover" }} />
                : ini}
            </div>
            <button
              type="button"
              onClick={() => !uploading && fileRef.current?.click()}
              aria-label="Change profile photo"
              title="Change profile photo"
              style={{
                position: "absolute", bottom: -3, right: -3, width: 22, height: 22, borderRadius: "50%",
                background: "var(--card)", border: "1.5px solid var(--line)",
                cursor: uploading ? "wait" : "pointer", padding: 0,
                display: "flex", alignItems: "center", justifyContent: "center",
              }}
            >
              <Ic d={uploading
                ? "M21 12a9 9 0 1 1-6.22-8.56"
                : ["M23 19a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4l2-3h6l2 3h4a2 2 0 0 1 2 2z", "M12 17a4 4 0 1 0 0-8 4 4 0 0 0 0 8z"]}
                size={11} />
            </button>
            <input
              ref={fileRef} type="file" accept=".jpg,.jpeg,.png,.webp,image/jpeg,image/png,image/webp"
              onChange={pickAvatar} style={{ display: "none" }}
            />
          </div>

          <div style={{ flex: 1, minWidth: 0 }}>
            <div style={{ fontWeight: 700, fontSize: 14, color: "var(--ink)", overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
              {displayName(currentUser)}
            </div>
            <div style={{ fontSize: 11.5, color: "var(--ink-soft)", overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap", marginTop: 1 }}>
              {currentUser?.email || currentUser?.username}
            </div>
            <span style={{
              display: "inline-block", marginTop: 5, background: accent, color: "#fff",
              fontSize: 10.5, fontWeight: 700, padding: "2px 8px", borderRadius: 999,
            }}>
              {currentUser?.roleName || "Staff"}
            </span>
          </div>
        </div>

        {/* Session facts */}
        <div style={{ marginTop: 12, display: "grid", gap: 6 }}>
          {branchLabel && (
            <div style={{ fontSize: 11.5, color: "var(--ink-soft)", display: "flex", alignItems: "center", gap: 7 }}>
              <Ic d={["M12 22s7-6.5 7-12a7 7 0 1 0-14 0c0 5.5 7 12 7 12z", "M12 10a2 2 0 1 0 0-4 2 2 0 0 0 0 4z"]} size={12} />
              <span style={{ overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>{branchLabel}</span>
            </div>
          )}
          <div style={{ fontSize: 11.5, color: "var(--ink-soft)", display: "flex", alignItems: "center", gap: 7 }}>
            <span style={{ width: 7, height: 7, borderRadius: "50%", background: "var(--sage)", flexShrink: 0, marginLeft: 2, marginRight: 3 }} />
            Last login: {fmtDateTimeFriendly(currentUser?.lastLogin)}
          </div>
          {uploadErr && (
            <div style={{ fontSize: 11.5, color: "var(--clay)" }}>{uploadErr}</div>
          )}
        </div>
      </div>

      {/* ── MAIN ── */}
      {section === "main" && (
        <>
          <div style={{ padding: "6px 0" }} role="none">
            <MenuItem
              icon={["M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2", "M12 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8z"]}
              label="My Profile"
              hint="View and edit your details"
              onClick={() => { onClose(); onOpenProfile(); }}
            />
            <MenuItem
              icon={["M19 11H5a2 2 0 0 0-2 2v7a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7a2 2 0 0 0-2-2z", "M7 11V7a5 5 0 0 1 10 0v4"]}
              label="Change Password"
              hint="Current password required"
              onClick={() => { onClose(); onOpenPassword(); }}
            />
          </div>

          <div style={{ padding: "4px 8px", borderTop: "1px solid var(--line)" }}>
            <button
              onClick={() => setSection("theme")}
              style={{
                width: "100%", padding: 10, background: "var(--paper)",
                border: "1px solid var(--line)", borderRadius: 10, cursor: "pointer",
                display: "flex", gap: 10, alignItems: "center", justifyContent: "space-between",
              }}
            >
              <span style={{ display: "flex", gap: 10, alignItems: "center" }}>
                <span style={{
                  width: 18, height: 18, borderRadius: "50%", flexShrink: 0,
                  background: THEMES.find(t => t.id === theme)?.swatch || "#F5F4EC",
                  border: `2px solid ${THEMES.find(t => t.id === theme)?.ring || "var(--amber)"}`,
                }} />
                <span style={{ textAlign: "left" }}>
                  <span style={{ display: "block", fontWeight: 600, fontSize: 13, color: "var(--ink)" }}>Appearance</span>
                  <span style={{ display: "block", fontSize: 11, color: "var(--ink-soft)" }}>
                    {THEMES.find(t => t.id === theme)?.label || "Light"}
                  </span>
                </span>
              </span>
              <Ic d="M9 18l6-6-6-6" size={14} color="var(--ink-soft)" />
            </button>
          </div>

          <div style={{ padding: "6px 8px 10px" }}>
            <button
              onClick={() => { logout(); onClose(); }}
              className="signout-btn"
            >
              <Ic d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4M16 17l5-5-5-5M21 12H9" size={15} color="var(--clay)" />
              Sign Out
            </button>
          </div>
        </>
      )}

      {/* ── THEME ── */}
      {section === "theme" && (
        <div style={{ padding: "10px 8px 12px" }}>
          <button
            onClick={() => setSection("main")}
            style={{
              background: "none", border: "none", cursor: "pointer", color: "var(--ink-soft)",
              display: "flex", alignItems: "center", gap: 6, fontSize: 13, padding: "4px 10px 8px",
            }}
          >
            <Ic d="M15 18l-6-6 6-6" size={14} /> Back
          </button>
          <div style={{
            fontWeight: 700, fontSize: 11, color: "var(--ink-soft)",
            textTransform: "uppercase", letterSpacing: ".08em", padding: "0 10px 8px",
          }}>
            Appearance
          </div>
          <div style={{ display: "grid", gap: 4 }}>
            {THEMES.map(t => {
              const active = theme === t.id;
              return (
                <button
                  key={t.id}
                  onClick={() => { setTheme(t.id); setSection("main"); }}
                  aria-pressed={active}
                  style={{
                    display: "flex", alignItems: "center", gap: 11, padding: "9px 10px",
                    borderRadius: 9, cursor: "pointer", textAlign: "left",
                    border: "1px solid " + (active ? "var(--amber)" : "transparent"),
                    background: active ? "var(--amber-soft)" : "transparent",
                  }}
                  onMouseEnter={e => { if (!active) e.currentTarget.style.background = "var(--paper)"; }}
                  onMouseLeave={e => { if (!active) e.currentTarget.style.background = "transparent"; }}
                >
                  <span style={{
                    width: 20, height: 20, borderRadius: "50%", flexShrink: 0,
                    background: t.swatch, border: `2px solid ${t.ring}`,
                  }} />
                  <span style={{ flex: 1, fontSize: 13, fontWeight: active ? 700 : 500, color: active ? "var(--amber-deep)" : "var(--ink)" }}>
                    {t.label}
                  </span>
                  {active && <Ic d="M20 6 9 17l-5-5" size={14} color="var(--amber-deep)" />}
                </button>
              );
            })}
          </div>
        </div>
      )}
    </div>
  );
}

/* ═════════════════════════════ TOPBAR ══════════════════════════════════ */

export default function Topbar({ title, subtitle, onNavigate, onMenu }) {
  const {
    currentUser, currentBranch, branches, unreadCount, can,
    setSearchOpen, currentAvatarUrl,
  } = useApp();

  const [showNotif, setShowNotif] = useState(false);
  const [showUser,  setShowUser]  = useState(false);
  const [showProfile,  setShowProfile]  = useState(false);
  const [showPassword, setShowPassword] = useState(false);

  const notifRef = useRef(null);
  const userRef  = useRef(null);

  useEffect(() => {
    const onDocMouseDown = (e) => {
      if (notifRef.current && !notifRef.current.contains(e.target)) setShowNotif(false);
      if (userRef.current  && !userRef.current.contains(e.target))  setShowUser(false);
    };
    const onKeyDown = (e) => {
      if (e.key === "Escape") { setShowNotif(false); setShowUser(false); }
    };
    document.addEventListener("mousedown", onDocMouseDown);
    document.addEventListener("keydown", onKeyDown);
    return () => {
      document.removeEventListener("mousedown", onDocMouseDown);
      document.removeEventListener("keydown", onKeyDown);
    };
  }, []);

  const accent = roleAccent(currentUser);
  const ini    = initials(displayName(currentUser)) || "FP";

  /* Branch comes from the signed-in session — it is not switchable here. */
  const branchLabel = useMemo(() => (
    currentUser?.branchName
    || branches.find(b => b.id === (currentUser?.branchId ?? currentUser?.pidBranch))?.name
    || currentBranch?.name
    || null
  ), [currentUser?.branchName, currentUser?.branchId, currentUser?.pidBranch, branches, currentBranch?.name]);

  return (
    <div className="topbar">
      {/* The way into the menu on a phone, where the sidebar is off-canvas.
          Hidden by the stylesheet above 768px, so on every other size the
          sidebar is already on screen and a control to reveal it would be a
          button that appears to do nothing. */}
      <button
        type="button"
        className="nav-toggle"
        onClick={onMenu}
        aria-label="Open menu"
      >
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none"
             stroke="currentColor" strokeWidth="2" strokeLinecap="round">
          <path d="M3 6h18M3 12h18M3 18h18" />
        </svg>
      </button>
      <div className="topbar-heading" style={{ display: "flex", flexDirection: "column", minWidth: 0 }}>
        <div className="title" style={{ overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>{title}</div>
        {subtitle && (
          <div className="topbar-subtitle" style={{ fontSize: 11.5, color: "var(--ink-soft)", marginTop: 1, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap", maxWidth: 400 }}>
            {subtitle}
          </div>
        )}
      </div>

      {/* Global Search — granted in RBAC, like the bell beside it. The box used
          to be shown to everybody and searched mockData in the browser, so it
          never returned a record that exists. */}
      {can(CAP_GLOBAL_SEARCH) && (
      <button className="search-box" onClick={() => setSearchOpen(true)} style={{ maxWidth: 320, cursor: "pointer" }}>
        <Ic d="M21 21l-4.3-4.3M11 19a8 8 0 1 0 0-16 8 8 0 0 0 0 16z" size={14} />
        <span style={{ fontSize: 13, color: "var(--ink-soft)", flex: 1, textAlign: "left" }}>Search… (Ctrl+K)</span>
        <kbd style={{ fontSize: 10.5, background: "var(--line)", borderRadius: 5, padding: "2px 6px", fontFamily: "monospace", color: "var(--ink-soft)" }}>⌘K</kbd>
      </button>
      )}

      {/* Pushes the actions to the right on a wide bar. Given the class the
          stylesheet already defines, so it can be removed on a phone where
          it competes with .topbar-right for the same free space. */}
      <div className="spacer" />

      <div className="topbar-right">
        {/* Branch — read-only, from the user's session */}
        {branchLabel && (
          <div
            className="branch-pill"
            title={`Signed in at ${branchLabel}`}
            style={{
              display: "flex", alignItems: "center", gap: 7,
              padding: "6px 12px", borderRadius: 999,
              background: "var(--paper)", border: "1px solid var(--line)",
              maxWidth: 210, cursor: "default",
            }}
          >
            <span style={{ color: "var(--teal)", display: "flex", flexShrink: 0 }}>
              <Ic d={["M12 22s7-6.5 7-12a7 7 0 1 0-14 0c0 5.5 7 12 7 12z", "M12 10a2 2 0 1 0 0-4 2 2 0 0 0 0 4z"]} size={13} />
            </span>
            <span style={{
              fontSize: 12.5, fontWeight: 600, color: "var(--ink)",
              overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap",
            }}>
              {branchLabel}
            </span>
          </div>
        )}

        {/* Notifications — granted in RBAC, not hard-coded here.

            The bell used to be shown to everybody. It is now the View right on
            the Notifications module, so a role gets one or does not by a tick on
            the Permissions screen. An entitled role with nothing outstanding
            still gets the bell, and the panel says it is all clear; that is a
            different thing from having no bell at all. */}
        {can(CAP_NOTIFICATIONS) && (
        <div ref={notifRef} style={{ position: "relative" }}>
          <button
            className="icon-btn"
            aria-label={`Notifications${unreadCount ? `, ${unreadCount} unread` : ""}`}
            aria-expanded={showNotif}
            onClick={() => { setShowNotif(v => !v); setShowUser(false); }}
          >
            <Ic d={["M6 8a6 6 0 0 1 12 0c0 5 2 6 2 6H4s2-1 2-6z", "M10 21a2 2 0 0 0 4 0"]} size={16} />
            {unreadCount > 0 && (
              <span style={{
                position: "absolute", top: 4, right: 4, background: "var(--clay)", color: "#fff",
                borderRadius: 999, minWidth: 16, height: 16, padding: "0 4px", fontSize: 10,
                display: "flex", alignItems: "center", justifyContent: "center", fontWeight: 700,
                border: "1.5px solid var(--card)",
              }}>
                {unreadCount > 9 ? "9+" : unreadCount}
              </span>
            )}
          </button>
          {showNotif && <NotificationPanel onClose={() => setShowNotif(false)} onNavigate={onNavigate} />}
        </div>
        )}

        {/* User chip */}
        <div ref={userRef} style={{ position: "relative" }}>
          <button
            onClick={() => { setShowUser(v => !v); setShowNotif(false); }}
            aria-label="Account menu"
            aria-expanded={showUser}
            className="user-chip"
          >
            <span style={{
              width: 32, height: 32, borderRadius: "50%", background: accent, color: "#fff",
              display: "flex", alignItems: "center", justifyContent: "center",
              fontWeight: 700, fontSize: 12.5, overflow: "hidden", flexShrink: 0,
            }}>
              {currentAvatarUrl
                ? <img src={currentAvatarUrl} alt="" style={{ width: "100%", height: "100%", objectFit: "cover" }} />
                : ini}
            </span>
            <span className="user-meta" style={{ textAlign: "left" }}>
              <span style={{ display: "block", fontSize: 13, fontWeight: 700, lineHeight: 1.2, color: "var(--ink)", whiteSpace: "nowrap" }}>
                {displayName(currentUser).split(" ").slice(0, 2).join(" ")}
              </span>
              <span style={{ display: "block", fontSize: 10.5, color: "var(--ink-soft)" }}>
                {currentUser?.roleName || "Staff"}
              </span>
            </span>
            <Ic d="m6 9 6 6 6-6" size={13} color="var(--ink-soft)" />
          </button>

          {showUser && (
            <UserMenu
              onClose={() => setShowUser(false)}
              onOpenProfile={() => setShowProfile(true)}
              onOpenPassword={() => setShowPassword(true)}
            />
          )}
        </div>
      </div>

      {showProfile && (
        <ProfileModal
          onClose={() => setShowProfile(false)}
          onOpenPassword={() => setShowPassword(true)}
        />
      )}
      {showPassword && <ChangePasswordModal onClose={() => setShowPassword(false)} />}
    </div>
  );
}
