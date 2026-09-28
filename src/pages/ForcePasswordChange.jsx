/**
 * The screen an account sees while it holds a password somebody else chose.
 *
 * It stands in place of the whole application rather than appearing over it,
 * because that is the truth of the situation: the server refuses every other
 * endpoint until this is done, so a sidebar full of pages that would all return
 * 403 would be a lie told in menu form.
 *
 * There is no Skip and no Later. The temporary password was handed over out of
 * band — read down a phone, most likely — which makes it the weakest credential
 * the system will ever hold, and the only thing that retires it is this form.
 */
import React, { useState } from "react";
import { useApp } from "../lib/AppContext.jsx";
import apiClient from "../lib/apiClient.js";
import { useToast } from "../hooks/useToast.js";

/**
 * The rules the new password must satisfy, checked as it is typed.
 *
 * Shown as a live list rather than as an error after submitting: a rule you
 * discover by failing is a rule you meet by adding "1" to the end, which is the
 * password everybody already has.
 */
const RULES = [
  { id: "length", label: "At least 8 characters", test: (v) => v.length >= 8 },
  { id: "letter", label: "A letter",              test: (v) => /[A-Za-z]/.test(v) },
  { id: "digit",  label: "A number",              test: (v) => /\d/.test(v) },
];

export default function ForcePasswordChange() {
  const { currentUser, logout, setMustChangePassword } = useApp();
  const addToast = useToast();

  const [current, setCurrent] = useState("");
  const [next, setNext] = useState("");
  const [again, setAgain] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);

  const passed = RULES.filter((r) => r.test(next));
  const allPassed = passed.length === RULES.length;
  const matches = next.length > 0 && next === again;
  /* The temporary password is a real credential, so reusing it is a no-op that
     would leave the account exactly where it started. The server refuses it too;
     catching it here saves a round trip and says so in plainer words. */
  const reusing = next.length > 0 && next === current;
  const ready = allPassed && matches && !reusing && current.length > 0;

  const submit = async (e) => {
    e.preventDefault();
    if (!ready || busy) return;

    setBusy(true);
    setError(null);
    try {
      await apiClient.patch("/api/auth/me/password", {
        oldPassword: current,
        newPassword: next,
      });
      /* Clearing the flag locally is what returns the app to the user. The
         server has already cleared it on their row, so the next request would
         succeed regardless — this only saves them a sign-out and back in. */
      setMustChangePassword(false);
      addToast("Password set. Welcome back.", "success");
    } catch (err) {
      setError(err?.message || "Could not set the password. Please try again.");
    } finally {
      setBusy(false);
    }
  };

  return (
    <div style={{
      minHeight: "100vh", display: "flex", alignItems: "center", justifyContent: "center",
      background: "var(--surface-2)", padding: 20,
    }}>
      <div style={{
        width: "100%", maxWidth: 420, background: "var(--surface)",
        border: "1px solid var(--line)", borderRadius: 12, padding: "28px 26px",
        boxShadow: "0 10px 30px rgba(0,0,0,.08)",
      }}>
        <h1 style={{ margin: "0 0 6px", fontSize: 20 }}>Set your own password</h1>
        <p style={{ margin: "0 0 20px", fontSize: 13, color: "var(--ink-soft)", lineHeight: 1.5 }}>
          {currentUser?.fullName || currentUser?.userName}, the password you signed in with was
          issued by an administrator. Choose one only you know before continuing.
        </p>

        <form onSubmit={submit}>
          <label className="field">
            <span className="field-label">Temporary password</span>
            <input className="input" type="password" autoComplete="current-password"
                   value={current} onChange={(e) => setCurrent(e.target.value)}
                   autoFocus disabled={busy} />
          </label>

          <label className="field" style={{ marginTop: 12 }}>
            <span className="field-label">New password</span>
            <input className="input" type="password" autoComplete="new-password"
                   value={next} onChange={(e) => setNext(e.target.value)} disabled={busy} />
          </label>

          <ul style={{ listStyle: "none", padding: 0, margin: "8px 0 0", fontSize: 11.5 }}>
            {RULES.map((r) => {
              const ok = r.test(next);
              return (
                <li key={r.id} style={{
                  color: next.length === 0 ? "var(--ink-soft)" : ok ? "var(--sage)" : "var(--ink-soft)",
                  display: "flex", alignItems: "center", gap: 6, marginTop: 2,
                }}>
                  <span aria-hidden>{ok ? "✓" : "○"}</span>{r.label}
                </li>
              );
            })}
          </ul>

          <label className="field" style={{ marginTop: 12 }}>
            <span className="field-label">New password again</span>
            <input className="input" type="password" autoComplete="new-password"
                   value={again} onChange={(e) => setAgain(e.target.value)} disabled={busy} />
          </label>

          {again.length > 0 && !matches && (
            <div style={{ color: "var(--clay)", fontSize: 11.5, marginTop: 5 }}>
              The two do not match.
            </div>
          )}
          {reusing && (
            <div style={{ color: "var(--clay)", fontSize: 11.5, marginTop: 5 }}>
              That is the temporary password. Choose a different one — it is the one
              credential somebody else has seen.
            </div>
          )}
          {error && (
            <div style={{
              color: "var(--clay)", fontSize: 12, marginTop: 12, padding: "8px 10px",
              background: "var(--clay-wash, rgba(180,70,60,.08))", borderRadius: 6,
            }}>
              {error}
            </div>
          )}

          <button type="submit" className="btn btn-primary"
                  style={{ width: "100%", marginTop: 18 }}
                  disabled={!ready || busy}>
            {busy ? "Setting…" : "Set password and continue"}
          </button>
        </form>

        <button className="btn btn-ghost btn-sm"
                style={{ width: "100%", marginTop: 10 }}
                onClick={logout} disabled={busy}>
          Sign out instead
        </button>
      </div>
    </div>
  );
}
