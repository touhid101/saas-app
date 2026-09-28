/**
 * Password administration — platform operator only.
 *
 * <b>This screen does not show passwords, and no screen can.</b> They are stored
 * as BCrypt hashes: a one-way function with no key, chosen precisely so that a
 * copy of the database is not a copy of everyone's password. What it shows
 * instead is the <i>state</i> of each credential, which is what a support call
 * is really asking about — whether the login works, whether a temporary one has
 * already been issued, and whether it needs attention.
 *
 * <b>Layout.</b> A table on a desktop, because the job is scanning a column of
 * password states for the one that is wrong; stacked cards below 860px, where
 * seven columns would push that state and the button off the right edge. Both
 * come from one set of markup — the responsive rules live in styles.css beside
 * Branch RBAC's, which solves the same problem the same way.
 *
 * <b>Minimal clicks.</b> Reset is on the row, not behind a menu or a detail
 * page: find the person, press the button, read the password out. The one
 * interruption is the confirmation, which is there because the action signs
 * somebody out of a system they may be standing at a counter using.
 */
import React, { useCallback, useEffect, useMemo, useState } from "react";
import {
  Search, X, Building2, ShieldCheck, ShieldAlert, Clock, KeyRound, RotateCcw,
  Copy, Check, History, Users, Info, TriangleAlert, CircleSlash,
  SlidersHorizontal, LogIn,
} from "lucide-react";
import svc from "../services/passwordAdminService.js";
import SearchSelect from "../components/SearchSelect.jsx";
import { Pill } from "../components/UI.jsx";
import { useToast } from "../hooks/useToast.js";

/* ── how each password state is presented ────────────────────────────────────
   The word comes from the server; the icon, tone and sentence are the screen's
   own. Kept as one table so a state cannot be styled one way in the row and
   another in the filter. */
const STATE = {
  ACTIVE:    { label: "Active",     tone: "sage",  Icon: ShieldCheck,
               hint: "Set by the user and stored as a one-way hash." },
  TEMPORARY: { label: "Temporary",  tone: "amber", Icon: Clock,
               hint: "Holding a password an administrator issued. The user must replace it at next sign-in." },
  EXPIRED:   { label: "Expired",    tone: "clay",  Icon: TriangleAlert,
               hint: "The temporary password has run out and no longer works. Issue a new one." },
  WEAK:      { label: "Not hashed", tone: "clay",  Icon: ShieldAlert,
               hint: "Stored in the clear from before the migration. Resetting it fixes this immediately." },
  UNSET:     { label: "No password", tone: "grey", Icon: CircleSlash,
               hint: "Nothing is stored. This account cannot sign in at all." },
};
const stateOf = (w) => STATE[w] ?? { label: w || "—", tone: "grey", Icon: KeyRound, hint: "" };

const when = (iso) => {
  if (!iso) return "—";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  return d.toLocaleString("en-GB", {
    day: "numeric", month: "short", year: "numeric", hour: "2-digit", minute: "2-digit",
  });
};

/** "3 days ago" — the form the eye reads faster than a date when scanning. */
const ago = (iso) => {
  if (!iso) return null;
  const then = new Date(iso).getTime();
  if (Number.isNaN(then)) return null;
  const mins = Math.floor((Date.now() - then) / 60000);
  if (mins < 1) return "just now";
  if (mins < 60) return `${mins} min ago`;
  const hrs = Math.floor(mins / 60);
  if (hrs < 24) return `${hrs} hour${hrs === 1 ? "" : "s"} ago`;
  const days = Math.floor(hrs / 24);
  if (days < 31) return `${days} day${days === 1 ? "" : "s"} ago`;
  const months = Math.floor(days / 30);
  if (months < 12) return `${months} month${months === 1 ? "" : "s"} ago`;
  return `${Math.floor(months / 12)} year${months < 24 ? "" : "s"} ago`;
};

const initials = (name, fallback) => {
  const src = (name || fallback || "?").trim();
  const parts = src.split(/\s+/).filter(Boolean);
  return ((parts[0]?.[0] || "") + (parts[1]?.[0] || "")).toUpperCase() || src[0].toUpperCase();
};

/* ── a filter built on the app's searchable select ────────────────────────────
   SearchSelect rather than a native dropdown: type to narrow, arrows to move,
   Enter to take. Branch and role are open-ended — an installation with forty
   pharmacies turns a native list into a scroll — and reusing the control the
   rest of the app already uses means there is one of these to learn, not two.

   "ALL" is a real option rather than a separate clear button, so returning to
   everything is the same gesture as choosing anything else. */
function Filter({ value, onChange, options, allLabel, title }) {
  const list = [{ id: "ALL", name: allLabel }, ...options];
  return (
    <div className={`pwd-filter ${value !== "ALL" ? "on" : ""}`} title={title}>
      <SearchSelect
        value={value}
        onChange={onChange}
        options={list}
        ariaLabel={title}
        placeholder={allLabel}
        emptyLabel="Nothing matches that."
      />
    </div>
  );
}

/* ── loading: the shape of the answer, not a spinner ──────────────────────────
   A skeleton keeps the toolbar and headers in place, so the eye is already
   where the data will land. A spinner replaces the layout with a dot and then
   throws it away. */
function SkeletonRows({ rows = 7 }) {
  return Array.from({ length: rows }).map((_, i) => (
    <tr key={i} aria-hidden="true">
      {[54, 92, 62, 46, 58, 66, 40].map((w, j) => (
        <td key={j}><div className="pwd-skel" style={{ width: `${w}%` }} /></td>
      ))}
    </tr>
  ));
}

/* ── the handover dialog — the one moment the password exists outside a hash ── */
function Handover({ result, onClose }) {
  const [copied, setCopied] = useState(false);

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(result.temporaryPassword);
      setCopied(true);
      setTimeout(() => setCopied(false), 2400);
    } catch {
      /* Refused on an insecure origin, by a browser policy, or without focus.
         The password is on screen and selectable, so this costs a drag of the
         mouse rather than the handover. */
      setCopied(false);
    }
  };

  return (
    <div className="modal-backdrop"
         onMouseDown={(e) => { if (e.target === e.currentTarget) onClose(); }}>
      <div className="modal" style={{ maxWidth: 460 }}>
        <div className="modal-head">
          <div className="card-title" style={{ display: "flex", alignItems: "center", gap: 8 }}>
            <KeyRound size={16} /> Temporary password issued
          </div>
          <button className="icon-btn" onClick={onClose} aria-label="Close"><X size={16} /></button>
        </div>

        <div className="modal-body">
          <div className="pwd-target">
            <div className="pwd-avatar">{initials(result.fullName, result.userName)}</div>
            <div className="pwd-target-text">
              <b>{result.fullName || result.userName}</b>
              <span>
                {result.userName}
                {result.branchName ? ` · ${result.branchName}` : ""}
              </span>
            </div>
          </div>

          <div className="pwd-secret">
            <div className="pwd-secret-label">Temporary password</div>
            <div className="pwd-secret-value">{result.temporaryPassword}</div>
            <div className="pwd-secret-exp">Valid until {when(result.expiresAt)}</div>
          </div>

          {/* Said plainly, because the alternative is an operator who closes the
              dialog assuming they can come back for it. */}
          <div className="pwd-once">
            <Info size={15} />
            <span>
              <b>This is the only time it is shown.</b>
              Only a one-way hash was stored, so it cannot be looked up again. If it is
              lost before {result.userName} has it, issue another.
            </span>
          </div>

          <p style={{ fontSize: 12, color: "var(--ink-soft)", margin: "12px 0 0", lineHeight: 1.55 }}>
            Give it to them over a channel you trust. They will have to set a password of
            their own before they can use anything else.
          </p>
        </div>

        <div className="modal-foot">
          <button className="btn btn-ghost" onClick={onClose}
                  title="Close — the password is not shown again">Done</button>
          <button className="btn btn-primary" onClick={copy}
                  title="Copy the temporary password to the clipboard">
            {copied ? <><Check size={14} /> Copied</> : <><Copy size={14} /> Copy password</>}
          </button>
        </div>
      </div>
    </div>
  );
}

/* ── the confirmation ─────────────────────────────────────────────────────────
   Purpose-built rather than the generic confirm, because the thing that makes
   this decision safe is seeing exactly whose account is about to change — the
   row was clicked in a list of twenty-five similar ones. */
function ConfirmReset({ account, busy, onCancel, onConfirm }) {
  return (
    <div className="modal-backdrop"
         onMouseDown={(e) => { if (e.target === e.currentTarget && !busy) onCancel(); }}>
      <div className="modal" style={{ maxWidth: 440 }}>
        <div className="modal-head">
          <div className="card-title" style={{ display: "flex", alignItems: "center", gap: 8 }}>
            <RotateCcw size={16} /> Reset this password?
          </div>
          <button className="icon-btn" onClick={onCancel} disabled={busy} aria-label="Close">
            <X size={16} />
          </button>
        </div>

        <div className="modal-body">
          <div className="pwd-target">
            <div className="pwd-avatar">{initials(account.fullName, account.userName)}</div>
            <div className="pwd-target-text">
              <b>{account.fullName || account.userName}</b>
              <span>
                {account.userName}
                {account.roleName ? ` · ${account.roleName}` : ""}
                {account.branchName ? ` · ${account.branchName}` : ""}
              </span>
            </div>
          </div>

          <ul className="pwd-consequences">
            <li><LogIn size={14} />
              Their current password stops working straight away — including on a till
              they are signed in at right now.</li>
            <li><KeyRound size={14} />
              You will be shown a temporary password once, to give to them.</li>
            <li><ShieldCheck size={14} />
              They must set a password of their own before they can use anything else.</li>
            <li><TriangleAlert size={14} />
              This cannot be undone. The old password is a one-way hash, so it cannot be
              put back.</li>
          </ul>
        </div>

        <div className="modal-foot">
          <button className="btn btn-ghost" onClick={onCancel} disabled={busy}
                  title="Leave this password alone">Cancel</button>
          <button className="btn btn-danger" onClick={onConfirm} disabled={busy}
                  title={`Issue a temporary password for ${account.userName}`}>
            {busy ? "Issuing…" : "Issue temporary password"}
          </button>
        </div>
      </div>
    </div>
  );
}

/* ═════════════════════════════════════════════════════════════════════════════
   The page
   ═════════════════════════════════════════════════════════════════════════════ */

export default function PasswordAdmin() {
  const addToast = useToast();

  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(true);
  const [denied, setDenied] = useState(null);
  const [busy, setBusy] = useState(null);
  const [confirming, setConfirming] = useState(null);
  const [handover, setHandover] = useState(null);

  const [q, setQ] = useState("");
  const [branch, setBranch] = useState("ALL");
  const [role, setRole] = useState("ALL");
  const [status, setStatus] = useState("ALL");
  const [tab, setTab] = useState("accounts");

  const [trail, setTrail] = useState([]);
  const [trailLoading, setTrailLoading] = useState(false);

  const load = useCallback(() => {
    setLoading(true);
    svc.accounts()
      .then((d) => { setData(d); setDenied(null); })
      .catch((e) => {
        /* A refusal is a fact about the account, not a failure to load, so it is
           shown as its own state rather than a red toast over an empty table
           the operator will keep trying to refresh. */
        if (e?.status === 403) setDenied(e?.message || "This screen is limited to the platform operator.");
        else addToast(e?.message || "Could not load the accounts.", "error");
      })
      .finally(() => setLoading(false));
  }, [addToast]);

  useEffect(() => { load(); }, [load]);

  const loadTrail = useCallback(() => {
    setTrailLoading(true);
    svc.audit(null, 200)
      .then((d) => setTrail(Array.isArray(d) ? d : []))
      .catch((e) => addToast(e?.message || "Could not load the reset history.", "error"))
      .finally(() => setTrailLoading(false));
  }, [addToast]);

  useEffect(() => { if (tab === "history") loadTrail(); }, [tab, loadTrail]);

  const accounts = data?.accounts ?? [];

  const branches = useMemo(
    () => [...new Set(accounts.map((a) => a.branchName).filter(Boolean))].sort((a, b) => a.localeCompare(b)),
    [accounts]);
  const roles = useMemo(
    () => [...new Set(accounts.map((a) => a.roleName).filter(Boolean))].sort((a, b) => a.localeCompare(b)),
    [accounts]);

  const rows = useMemo(() => {
    const needle = q.trim().toLowerCase();
    return accounts.filter((a) => {
      if (branch !== "ALL" && a.branchName !== branch) return false;
      if (role !== "ALL" && a.roleName !== role) return false;
      if (status !== "ALL" && a.passwordStatus !== status) return false;
      if (!needle) return true;
      /* Everything somebody might read off a support call: the username they
         were given, the name they answer to, the branch, the role, the phone
         number or the email on the account. */
      return [a.userName, a.fullName, a.branchName, a.roleName, a.email, a.mobileNo]
        .some((v) => v && String(v).toLowerCase().includes(needle));
    });
  }, [accounts, q, branch, role, status]);

  const filtering = q !== "" || branch !== "ALL" || role !== "ALL" || status !== "ALL";
  const clearFilters = () => { setQ(""); setBranch("ALL"); setRole("ALL"); setStatus("ALL"); };

  const doReset = async () => {
    const a = confirming;
    if (!a) return;
    setBusy(a.pidUser);
    try {
      const result = await svc.reset(a.pidUser);
      setConfirming(null);
      setHandover(result);
      addToast(`Temporary password issued for ${a.userName}.`, "success");
      load();
    } catch (e) {
      setConfirming(null);
      addToast(e?.message || `Could not reset the password for ${a.userName}.`, "error");
    } finally {
      setBusy(null);
    }
  };

  if (denied) {
    return (
      <div className="page">
        <div className="pwd-mast"><div><h1>Passwords</h1></div></div>
        <div className="pwd-card">
          <div className="pwd-empty">
            <ShieldAlert size={34} />
            <b>Not available on this account</b>
            <span>{denied}</span>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="page">
      <div className="pwd-mast">
        <div>
          <h1>Passwords</h1>
          <p>
            Every account on the platform. Reset one to hand its owner a temporary
            password they must then replace.
          </p>
        </div>
        {data && (
          <dl className="pwd-stats">
            <div className="pwd-stat">
              <dt>Accounts</dt><dd>{data.total}</dd>
            </div>
            <div className={`pwd-stat ${data.temporaryCount ? "pwd-stat-mid" : ""}`}>
              <dt>Mid-reset</dt><dd>{data.temporaryCount}</dd>
            </div>
            <div className={`pwd-stat ${data.weakCount ? "pwd-stat-warn" : ""}`}>
              <dt>Not hashed</dt><dd>{data.weakCount}</dd>
            </div>
          </dl>
        )}
      </div>

      {/* Said once, at the top, so nobody hunts for a column that cannot exist. */}
      <div className="pwd-note">
        <Info size={15} />
        <div>
          <b>Passwords cannot be displayed</b>
          <span>
            They are stored as one-way hashes, so there is nothing to decrypt — not on this
            screen, and not by anyone holding the database. To give someone access, issue a
            new temporary password.
          </span>
        </div>
      </div>

      {data?.weakCount > 0 && (
        <button className="pwd-note pwd-note-warn"
                onClick={() => { setStatus("WEAK"); setTab("accounts"); }}>
          <ShieldAlert size={15} />
          <div>
            <b>
              {data.weakCount} account{data.weakCount === 1 ? " is" : "s are"} still stored
              in the clear
            </b>
            <span>
              From before password hashing. Each is upgraded automatically the next time its
              owner signs in — so the ones left are the accounts nobody has signed into
              since. Resetting one fixes it now. Press to show them.
            </span>
          </div>
        </button>
      )}

      <div className="pwd-tabs" role="tablist">
        <button className={`pwd-tab ${tab === "accounts" ? "on" : ""}`}
                role="tab" aria-selected={tab === "accounts"}
                onClick={() => setTab("accounts")}>
          <Users size={15} /> Accounts
        </button>
        <button className={`pwd-tab ${tab === "history" ? "on" : ""}`}
                role="tab" aria-selected={tab === "history"}
                onClick={() => setTab("history")}>
          <History size={15} /> Reset history
        </button>
      </div>

      {tab === "accounts" && (
        <>
          <div className="pwd-tools">
            <div className="pwd-search">
              <Search size={15} />
              <input
                value={q}
                onChange={(e) => setQ(e.target.value)}
                placeholder="Search name, username, branch, role, phone…"
                aria-label="Search accounts"
              />
              {q && (
                <button className="pwd-clear" onClick={() => setQ("")} aria-label="Clear search">
                  <X size={14} />
                </button>
              )}
            </div>

            <Filter value={branch} onChange={setBranch} title="Filter by branch"
                    allLabel="Every branch"
                    options={branches.map((b) => ({ id: b, name: b }))} />
            <Filter value={role} onChange={setRole} title="Filter by role"
                    allLabel="Every role"
                    options={roles.map((r) => ({ id: r, name: r }))} />
            <Filter value={status} onChange={setStatus} title="Filter by password state"
                    allLabel="Any password state"
                    options={Object.entries(STATE).map(([id, st]) => ({ id, name: st.label }))} />

            {filtering && (
              <button className="pwd-btn pwd-reset-filters" onClick={clearFilters}
                      title="Clear the search and every filter">
                <SlidersHorizontal size={13} /> Clear filters
              </button>
            )}
          </div>

          <div className="pwd-card">
            <div className="pwd-wrap">
              <table className="pwd-table">
                <thead>
                  <tr>
                    <th>Branch</th><th>User</th><th>Role</th><th>Status</th>
                    <th>Last login</th><th>Password</th><th />
                  </tr>
                </thead>
                <tbody>
                  {loading && <SkeletonRows />}

                  {!loading && rows.map((a) => {
                    const st = stateOf(a.passwordStatus);
                    const StIcon = st.Icon;
                    const lastLogin = ago(a.lastLogin);
                    return (
                      <tr key={a.pidUser}>
                        <td data-label="Branch">
                          {a.branchName
                            ? <span className="pwd-branch"><Building2 size={13} />{a.branchName}</span>
                            : <span className="pwd-dim">—</span>}
                        </td>

                        <td data-label="User" className="pwd-td-user">
                          <div className="pwd-user">
                            <b>{a.userName}</b>
                            {a.fullName && <span>{a.fullName}</span>}
                          </div>
                        </td>

                        <td data-label="Role">
                          {a.roleName || <span className="pwd-dim">—</span>}
                        </td>

                        <td data-label="Status">
                          <Pill tone={a.status === "ACTIVE" ? "sage" : "grey"}>
                            {a.status === "ACTIVE" ? "Active" : "Inactive"}
                          </Pill>
                        </td>

                        <td data-label="Last login">
                          {lastLogin
                            ? <span className="pwd-when" title={when(a.lastLogin)}>{lastLogin}</span>
                            : <span className="pwd-dim">never</span>}
                        </td>

                        <td data-label="Password">
                          <span title={a.passwordStatusNote || st.hint}>
                            <Pill tone={st.tone}><StIcon size={12} />{st.label}</Pill>
                          </span>
                          {a.lastResetAt && (
                            <div className="pwd-sub">
                              reset {ago(a.lastResetAt)}{a.lastResetBy ? ` by ${a.lastResetBy}` : ""}
                            </div>
                          )}
                        </td>

                        <td className="pwd-act">
                          {a.resettable ? (
                            <button className="pwd-btn"
                                    disabled={busy === a.pidUser}
                                    onClick={() => setConfirming(a)}
                                    title={`Issue a temporary password for ${a.userName}`}>
                              <RotateCcw size={13} />
                              {busy === a.pidUser ? "Resetting…" : "Reset"}
                            </button>
                          ) : (
                            /* The reason is carried on the element rather than
                               left to a disabled button with no explanation —
                               "why can I not click this" is a support call of
                               its own. */
                            <span className="pwd-locked" title={a.notResettableReason}>
                              <CircleSlash size={12} /> Not from here
                            </span>
                          )}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>

              {!loading && rows.length === 0 && (
                <div className="pwd-empty">
                  <Search size={32} />
                  <b>No account matches</b>
                  <span>
                    {filtering
                      ? "Nothing here fits the current search and filters."
                      : "There are no accounts on this installation yet."}
                  </span>
                  {filtering && (
                    <button className="pwd-btn" onClick={clearFilters}
                            title="Clear the search and every filter">
                      <SlidersHorizontal size={13} /> Clear filters
                    </button>
                  )}
                </div>
              )}
            </div>
          </div>

          {!loading && rows.length > 0 && (
            <div style={{ fontSize: 11.5, color: "var(--ink-soft)", marginTop: 9 }}>
              Showing {rows.length} of {accounts.length} account{accounts.length === 1 ? "" : "s"}
            </div>
          )}
        </>
      )}

      {tab === "history" && (
        <div className="pwd-card">
          <div style={{ padding: "11px 14px", borderBottom: "1px solid var(--line)",
                        fontSize: 12, color: "var(--ink-soft)", background: "var(--paper)" }}>
            Every reset and every refusal, newest first. Refusals are kept as well as
            successes — repeated refusals against one account are worth being able to see.
          </div>
          <div className="pwd-wrap">
            <table className="pwd-table">
              <thead>
                <tr>
                  <th>When</th><th>Account</th><th>Branch</th>
                  <th>Reset by</th><th>From</th><th>Outcome</th>
                </tr>
              </thead>
              <tbody>
                {trailLoading && <SkeletonRows rows={5} />}
                {!trailLoading && trail.map((e) => (
                  <tr key={e.pidAudit}>
                    <td data-label="When">
                      <span className="pwd-when" title={when(e.resetAt)}>{ago(e.resetAt)}</span>
                    </td>
                    <td data-label="Account" className="pwd-td-user">
                      <div className="pwd-user">
                        <b>{e.userName}</b>
                        {e.fullName && <span>{e.fullName}</span>}
                      </div>
                    </td>
                    <td data-label="Branch">
                      {e.branchName || <span className="pwd-dim">—</span>}
                    </td>
                    <td data-label="Reset by">
                      <span className="mono">{e.resetByName}</span>
                    </td>
                    <td data-label="From">
                      <span className="mono" style={{ fontSize: 11.5 }}>
                        {e.ipAddress || <span className="pwd-dim">—</span>}
                      </span>
                    </td>
                    <td data-label="Outcome">
                      <span title={e.reason || undefined}>
                        <Pill tone={e.outcome === "RESET" ? "sage" : "amber"}>
                          {e.outcome === "RESET"
                            ? <><Check size={12} />Reset</>
                            : <><CircleSlash size={12} />Refused</>}
                        </Pill>
                      </span>
                      {e.reason && <div className="pwd-sub">{e.reason}</div>}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>

            {!trailLoading && trail.length === 0 && (
              <div className="pwd-empty">
                <History size={32} />
                <b>Nothing reset yet</b>
                <span>Every password issued from this screen will be listed here, with who
                      issued it and when.</span>
              </div>
            )}
          </div>
        </div>
      )}

      {confirming && (
        <ConfirmReset account={confirming} busy={busy === confirming.pidUser}
                      onCancel={() => setConfirming(null)} onConfirm={doReset} />
      )}
      {handover && <Handover result={handover} onClose={() => setHandover(null)} />}
    </div>
  );
}
