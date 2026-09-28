/**
 * Login Page — Beautiful pharmacy-themed authentication
 * Features: username/password, show/hide password, demo user quick-select,
 *           role badge preview, animated logo, responsive layout
 */
import React, { useState, useRef, useEffect } from "react";
import { ROLES } from "../lib/mockData.js";
import { useApp } from "../lib/AppContext.jsx";

/* ── Role colors matching topbar ── */
const ROLE_COLOR = {
  ROLE01:"#6B5EA8", ROLE02:"#2E6A62", ROLE03:"#B8752B",
  ROLE04:"#4C7A5E", ROLE05:"#A8452E", ROLE06:"#2E7D8A",
  ROLE07:"#5A9B3C", ROLE08:"#9FB0A5", ROLE09:"#D4925A",
};

/* ── Demo user pill ── */
function DemoUser({ user, onSelect, selected }) {
  const rc = ROLE_COLOR[user.roleId] || "#888";
  const ini = user.name.split(" ").map(s => s[0]).slice(0, 2).join("");
  const isActive = selected === user.username;

  return (
    <button
      onClick={() => onSelect(user)}
      style={{
        display: "flex", alignItems: "center", gap: 10,
        padding: "9px 12px", borderRadius: 10,
        border: `1.5px solid ${isActive ? rc : "var(--line)"}`,
        background: isActive ? rc + "15" : "var(--paper)",
        cursor: "pointer", textAlign: "left", transition: "all .12s",
        width: "100%",
      }}
    >
      <div style={{ width: 34, height: 34, borderRadius: "50%", background: rc, color: "#fff", display: "flex", alignItems: "center", justifyContent: "center", fontWeight: 800, fontSize: 12, fontFamily: "monospace", flexShrink: 0 }}>
        {ini}
      </div>
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ fontWeight: 700, fontSize: 13, whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" }}>{user.name}</div>
        <div style={{ fontSize: 11, color: "var(--ink-soft)" }}>{user.role}</div>
      </div>
      <div style={{ fontSize: 10.5, fontFamily: "monospace", color: "var(--ink-soft)", background: "var(--line)", borderRadius: 5, padding: "2px 7px" }}>
        {user.username}
      </div>
    </button>
  );
}

/* ═══ MAIN LOGIN COMPONENT ═══════════════════════════════════════════════ */
import Register from "./Register.jsx";

export default function Login() {
  const { login } = useApp();
  // Somebody with no account is standing on this page. The sign-up route is
  // rendered from here rather than from the router because there is nothing
  // else on screen yet to route from.
  const [registering, setRegistering] = useState(false);
  const [username,    setUsername]    = useState("");
  const [password,    setPassword]    = useState("");
  const [showPass,    setShowPass]    = useState(false);
  const [error,       setError]       = useState("");
  const [loading,     setLoading]     = useState(false);
  const [selectedDemo,setSelectedDemo] = useState(null);
  const [activeTab,   setActiveTab]   = useState("login"); // "login" | "demo"
  const usernameRef = useRef(null);

  useEffect(() => { usernameRef.current?.focus(); }, []);

  const doLogin = async (e) => {
    e?.preventDefault();
    setError("");
    if (!username.trim()) { setError("Username is required"); return; }
    if (!password.trim()) { setError("Password is required"); return; }

    setLoading(true);
    try {
      await login(username.trim(), password);
      // Login successful - AppContext will handle navigation
    } catch (err) {
      // Show backend error message
      setError(err.message || "Login failed. Please try again.");
      setLoading(false);
    }
  };

  const selectDemo = (user) => {
    setSelectedDemo(user.username);
    setUsername(user.username);
    setPassword(user.password);
    setError("");
  };

  const loginAsDemo = async () => {
    if (username && password) {
      await doLogin();
    }
  };

  // Demo users for quick testing
  const demoUsers = [
    { username: "admin", password: "admin123", name: "System Admin", roleId: "ROLE01", role: "Admin" },
    { username: "manager", password: "manager123", name: "Branch Manager", roleId: "ROLE02", role: "Manager" },
    { username: "cashier", password: "cashier123", name: "POS Cashier", roleId: "ROLE03", role: "Cashier" },
  ];

  /* This guard was sitting inside DemoUser, which does not hold the state: it
     read `registering` from nothing and threw a ReferenceError every time the
     Demo Users tab rendered. It also meant Login never checked the flag at all,
     so "Open a pharmacy account" set it and nothing happened. */
  if (registering) return <Register onBack={() => setRegistering(false)} />;

  return (
    <div style={{
      minHeight: "100vh",
      background: "linear-gradient(135deg, #0F1510 0%, #182420 50%, #1E2C26 100%)",
      display: "flex", alignItems: "center", justifyContent: "center",
      padding: 20, position: "relative", overflow: "hidden",
    }}>
      {/* Background decorative circles */}
      {[
        { size: 400, top: -100, right: -100, opacity: 0.04 },
        { size: 250, bottom: -50, left: -80, opacity: 0.05 },
        { size: 150, top: "40%", left: "20%", opacity: 0.03 },
      ].map((c, i) => (
        <div key={i} style={{
          position: "absolute",
          width: c.size, height: c.size, borderRadius: "50%",
          border: "2px solid rgba(184,117,43,0.4)",
          top: c.top, right: c.right, bottom: c.bottom, left: c.left,
          opacity: c.opacity * 10,
          pointerEvents: "none",
        }} />
      ))}

      <div style={{
        display: "flex", gap: 0, width: "100%", maxWidth: 960,
        borderRadius: 20, overflow: "hidden",
        boxShadow: "0 32px 80px rgba(0,0,0,0.6)",
        minHeight: 580,
      }}>
        {/* ── LEFT PANEL — Branding ── */}
        <div style={{
          flex: 1, background: "linear-gradient(155deg, #B8752B 0%, #8F5A1E 60%, #5A3A10 100%)",
          padding: "48px 44px", display: "flex", flexDirection: "column",
          justifyContent: "space-between", color: "#fff",
          minWidth: 0,
        }}>
          {/* Logo */}
          <div>
            <div style={{ display: "flex", alignItems: "center", gap: 14, marginBottom: 40 }}>
              <div style={{ width: 52, height: 52, borderRadius: 14, background: "rgba(255,255,255,0.2)", display: "flex", alignItems: "center", justifyContent: "center" }}>
                <svg width={28} height={28} viewBox="0 0 24 24" fill="none" stroke="#fff" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
                  <path d="M9 3H5a2 2 0 0 0-2 2v4m6-6h10a2 2 0 0 1 2 2v4M9 3v18m0 0h10a2 2 0 0 0 2-2V9M9 21H5a2 2 0 0 1-2-2V9m0 0h18" />
                </svg>
              </div>
              <div>
                <div style={{ fontFamily: "Fraunces, serif", fontSize: 22, fontWeight: 700, lineHeight: 1.1 }}>Friends Pharma</div>
                <div style={{ fontSize: 11.5, opacity: 0.8, letterSpacing: ".1em", textTransform: "uppercase", marginTop: 2 }}>ERP System v2.0</div>
              </div>
            </div>

            <h2 style={{ fontSize: 28, fontFamily: "Fraunces, serif", fontWeight: 600, lineHeight: 1.3, marginBottom: 16, margin: "0 0 16px" }}>
              Integrated Pharmacy Management
            </h2>
            <p style={{ opacity: 0.8, lineHeight: 1.6, fontSize: 14.5, margin: "0 0 32px" }}>
              Complete POS, Inventory, Purchase, Sales, Delivery, Collection, Reporting and AI Analytics — all in one platform.
            </p>

            {/* Feature bullets */}
            {[
              "Batch-wise inventory with FEFO allocation",
              "AI-powered purchase recommendations",
              "Multi-branch operations with RBAC",
              "Real-time delivery tracking",
            ].map((f, i) => (
              <div key={i} style={{ display: "flex", alignItems: "center", gap: 10, marginBottom: 10, fontSize: 13.5 }}>
                <div style={{ width: 20, height: 20, borderRadius: "50%", background: "rgba(255,255,255,0.25)", display: "flex", alignItems: "center", justifyContent: "center", flexShrink: 0 }}>
                  <svg width={11} height={11} viewBox="0 0 24 24" fill="none" stroke="#fff" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round">
                    <path d="M20 6 9 17l-5-5" />
                  </svg>
                </div>
                <span style={{ opacity: 0.9 }}>{f}</span>
              </div>
            ))}
          </div>

          {/* Bottom badge */}
          <div style={{ fontSize: 12, opacity: 0.6, display: "flex", gap: 14 }}>
            <span>React · Spring Boot · Oracle</span>
            <span>Bangladesh 🇧🇩</span>
          </div>
        </div>

        {/* ── RIGHT PANEL — Login form ── */}
        <div style={{ width: 420, background: "var(--card)", padding: "40px 40px 36px", display: "flex", flexDirection: "column" }}>
          {/* Tabs */}
          <div style={{ display: "flex", gap: 0, marginBottom: 28, background: "var(--paper)", borderRadius: 10, padding: 4 }}>
            {["login", "demo"].map(t => (
              <button key={t} onClick={() => { setActiveTab(t); setError(""); }}
                style={{ flex: 1, padding: "8px 0", borderRadius: 8, border: "none", cursor: "pointer", fontWeight: 700, fontSize: 13, transition: "all .15s",
                  background: activeTab === t ? "var(--card)" : "transparent",
                  color: activeTab === t ? "var(--ink)" : "var(--ink-soft)",
                  boxShadow: activeTab === t ? "var(--shadow)" : "none",
                }}>
                {t === "login" ? "Sign In" : "Demo Users"}
              </button>
            ))}
          </div>

          {/* ── LOGIN TAB ── */}
          {activeTab === "login" && (
            <form onSubmit={doLogin} style={{ flex: 1 }}>
              <div style={{ marginBottom: 24 }}>
                <h3 style={{ margin: "0 0 6px", fontSize: 22, fontFamily: "Fraunces, serif", fontWeight: 700 }}>Welcome back</h3>
                <p style={{ margin: 0, fontSize: 13.5, color: "var(--ink-soft)" }}>Sign in to your account to continue</p>
              </div>

              {/* Username */}
              <div style={{ marginBottom: 16 }}>
                <label style={{ display: "block", fontSize: 12.5, fontWeight: 700, color: "var(--ink-soft)", marginBottom: 7, textTransform: "uppercase", letterSpacing: ".06em" }}>Username</label>
                <div style={{ position: "relative" }}>
                  <div style={{ position: "absolute", left: 12, top: "50%", transform: "translateY(-50%)", color: "var(--ink-soft)", pointerEvents: "none" }}>
                    <svg width={16} height={16} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round">
                      <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2M12 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8z" />
                    </svg>
                  </div>
                  <input ref={usernameRef} type="text" value={username}
                    onChange={e => { setUsername(e.target.value); setError(""); }}
                    placeholder="Enter your username"
                    style={{ width: "100%", padding: "11px 12px 11px 40px", borderRadius: 10, border: `1.5px solid ${error ? "var(--clay)" : "var(--line)"}`, fontSize: 14, background: "var(--paper)", color: "var(--ink)", outline: "none", boxSizing: "border-box", transition: "border-color .15s" }}
                    onFocus={e => { if (!error) e.target.style.borderColor = "var(--amber)"; }}
                    onBlur={e  => { if (!error) e.target.style.borderColor = "var(--line)"; }}
                  />
                </div>
              </div>

              {/* Password */}
              <div style={{ marginBottom: 20 }}>
                <label style={{ display: "block", fontSize: 12.5, fontWeight: 700, color: "var(--ink-soft)", marginBottom: 7, textTransform: "uppercase", letterSpacing: ".06em" }}>Password</label>
                <div style={{ position: "relative" }}>
                  <div style={{ position: "absolute", left: 12, top: "50%", transform: "translateY(-50%)", color: "var(--ink-soft)", pointerEvents: "none" }}>
                    <svg width={16} height={16} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round">
                      <rect x="3" y="11" width="18" height="11" rx="2" ry="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/>
                    </svg>
                  </div>
                  <input type={showPass ? "text" : "password"} value={password}
                    onChange={e => { setPassword(e.target.value); setError(""); }}
                    placeholder="Enter your password"
                    style={{ width: "100%", padding: "11px 44px 11px 40px", borderRadius: 10, border: `1.5px solid ${error ? "var(--clay)" : "var(--line)"}`, fontSize: 14, background: "var(--paper)", color: "var(--ink)", outline: "none", boxSizing: "border-box", transition: "border-color .15s" }}
                    onFocus={e => { if (!error) e.target.style.borderColor = "var(--amber)"; }}
                    onBlur={e  => { if (!error) e.target.style.borderColor = "var(--line)"; }}
                  />
                  <button type="button" onClick={() => setShowPass(v => !v)}
                    style={{ position: "absolute", right: 12, top: "50%", transform: "translateY(-50%)", background: "none", border: "none", cursor: "pointer", color: "var(--ink-soft)", padding: 2 }}>
                    {showPass ? (
                      <svg width={16} height={16} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.9"><path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19m-6.72-1.07a3 3 0 1 1-4.24-4.24M1 1l22 22" /></svg>
                    ) : (
                      <svg width={16} height={16} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.9"><path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/><circle cx="12" cy="12" r="3"/></svg>
                    )}
                  </button>
                </div>
              </div>

              {/* Error */}
              {error && (
                <div style={{ background: "var(--clay-soft)", border: "1px solid var(--clay)", borderRadius: 8, padding: "10px 14px", marginBottom: 16, fontSize: 13, color: "var(--clay)", display: "flex", alignItems: "center", gap: 8 }}>
                  <svg width={15} height={15} viewBox="0 0 24 24" fill="none" stroke="var(--clay)" strokeWidth="2"><path d="M10.3 3.9 1.8 18a1.8 1.8 0 0 0 1.5 2.7h17.4a1.8 1.8 0 0 0 1.5-2.7L13.7 3.9a1.8 1.8 0 0 0-3.4 0z"/><path d="M12 9v4M12 17h.01"/></svg>
                  {error}
                </div>
              )}

              {/* Submit */}
              <button type="submit" disabled={loading}
                style={{ width: "100%", padding: "13px", borderRadius: 10, background: loading ? "#C9CBC0" : "var(--amber)", color: "#fff", border: "none", fontSize: 15, fontWeight: 700, cursor: loading ? "not-allowed" : "pointer", transition: "background .15s", display: "flex", alignItems: "center", justifyContent: "center", gap: 10 }}>
                {loading ? (
                  <>
                    <svg width={18} height={18} viewBox="0 0 24 24" fill="none" stroke="#fff" strokeWidth="2.5" strokeLinecap="round" style={{ animation: "spin 1s linear infinite" }}>
                      <path d="M12 2v4M12 18v4M4.93 4.93l2.83 2.83M16.24 16.24l2.83 2.83M2 12h4M18 12h4M4.93 19.07l2.83-2.83M16.24 7.76l2.83-2.83" />
                    </svg>
                    Signing in…
                  </>
                ) : (
                  <>Sign In →</>
                )}
              </button>

              {/* Hint */}
              <div style={{ marginTop: 16, fontSize: 12.5, color: "var(--ink-soft)", textAlign: "center" }}>
                Try <strong>admin</strong> / <strong>admin123</strong> for full access
              </div>
              <p className="login-signup">
                No account yet?{" "}
                <button type="button" onClick={() => setRegistering(true)}>
                  Open a pharmacy account
                </button>
              </p>
            </form>
          )}

          {/* ── DEMO USERS TAB ── */}
          {activeTab === "demo" && (
            <div style={{ flex: 1, display: "flex", flexDirection: "column" }}>
              <div style={{ marginBottom: 14 }}>
                <div style={{ fontSize: 13.5, fontWeight: 700, marginBottom: 4 }}>Select a demo account</div>
                <div style={{ fontSize: 12.5, color: "var(--ink-soft)" }}>Each user has a different role and access level.</div>
              </div>
              <div style={{ flex: 1, overflowY: "auto", display: "flex", flexDirection: "column", gap: 7, maxHeight: 340 }}>
                {demoUsers.map((u, i) => (
                  <DemoUser key={i} user={u} onSelect={selectDemo} selected={selectedDemo} />
                ))}
              </div>
              <button onClick={loginAsDemo} disabled={!selectedDemo}
                style={{ marginTop: 16, width: "100%", padding: "12px", borderRadius: 10, background: selectedDemo ? "var(--teal)" : "#C9CBC0", color: "#fff", border: "none", fontSize: 14.5, fontWeight: 700, cursor: selectedDemo ? "pointer" : "not-allowed", transition: "background .15s" }}>
                {selectedDemo ? `Sign in as ${demoUsers.find(u=>u.username===selectedDemo)?.name.split(" ")[0]}` : "Select a user above"}
              </button>
            </div>
          )}

          {/* Footer */}
          <div style={{ marginTop: 24, paddingTop: 16, borderTop: "1px solid var(--line)", fontSize: 11.5, color: "var(--ink-soft)", textAlign: "center" }}>
            Friends Pharma ERP · Designed for Bangladesh Pharmacy Operations
          </div>
        </div>
      </div>

      <style>{`@keyframes spin { to { transform: rotate(360deg); } }`}</style>
    </div>
  );
}
