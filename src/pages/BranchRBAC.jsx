/**
 * RBAC for one pharmacy, run by the person who runs that pharmacy.
 *
 * <b>The same screens, a smaller world.</b> Users and New User are the very
 * components the platform's RBAC page uses — imported, not copied — so a branch
 * manager and a Super Admin see one screen with one behaviour. The list behind
 * them was branch-scoped on the server already; nothing here filters anything,
 * because a page that filters is a page that can forget to.
 *
 * <b>What is missing, and why.</b> No editing of modules or roles. Those are the
 * platform's catalogue: every pharmacy shares them, so one manager renaming a
 * role would rename it for the estate. They are shown, because working out what
 * a role grants is exactly what somebody assigning it needs — and are read-only.
 *
 * <b>The ceiling.</b> A branch works inside the module grant its plan carries.
 * The Plan tab states what that is, and the permission editor cannot exceed it —
 * the server refuses, not this screen. That boundary is commercial and is not a
 * manager's to move.
 *
 * <b>On the layout.</b> People is a list of rows, not a table, and that is the
 * reason it works on a phone: a table with five columns had Role, Status and the
 * only button on it clipped off the right edge, so the screen's whole purpose
 * was unreachable on the device a manager is most likely holding. The rows
 * reflow instead. Everything is driven by the theme's own tokens, so the page
 * follows light, dark and the branded palettes without knowing about them.
 */
import React, { useCallback, useEffect, useMemo, useState } from "react";
import apiClient from "../lib/apiClient.js";
import uploadUrl from "../lib/uploadUrl.js";
import { Card, Pill } from "../components/UI.jsx";
import { useApp } from "../lib/AppContext.jsx";
import { useToast } from "../hooks/useToast.js";
import { usePermission } from "../lib/usePermission.js";
import { UsersList, UserEntry } from "./RBAC.jsx";
import UserPermissions from "./UserPermissions.jsx";
import rbacService from "../services/rbacService.js";

/* No branch in the address. The server takes it from the session, which is what
   makes another pharmacy unreachable rather than merely refused. */
const permissionsPath = (userId) => `/api/v1/branch-rbac/users/${userId}/permissions`;

const Ic = ({ d, size = 15 }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor"
       strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    {(Array.isArray(d) ? d : [d]).map((p, i) => <path key={i} d={p} />)}
  </svg>
);

const P = {
  people:  ["M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2", "M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8z", "M23 21v-2a4 4 0 0 0-3-3.87", "M16 3.13a4 4 0 0 1 0 7.75"],
  user:    ["M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2", "M12 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8z"],
  plus:    "M12 5v14M5 12h14",
  shield:  ["M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"],
  layers:  ["M12 2 2 7l10 5 10-5-10-5Z", "M2 12l10 5 10-5", "M2 17l10 5 10-5"],
  search:  ["M21 21l-4.3-4.3", "M11 19a8 8 0 1 0 0-16 8 8 0 0 0 0 16z"],
  x:       "M18 6L6 18M6 6l12 12",
  key:     ["M21 2l-2 2", "M15.5 8.5a4.5 4.5 0 1 1-6.4 6.3L3 21l2-4 4-4", "M17 6l1 1"],
  check:   "M20 6 9 17l-5-5",
};

const TABS = [
  { key: "People",   icon: P.people, hint: "Who works here and what each of them may do" },
  { key: "Users",    icon: P.user,   hint: "Accounts, avatars and activation" },
  { key: "New User", icon: P.plus,   hint: "Add someone to this pharmacy", needsCreate: true },
  { key: "Roles",    icon: P.shield, hint: "What each role grants — set by the Super Admin" },
  { key: "Plan",     icon: P.layers, hint: "The modules this pharmacy is entitled to" },
];

/** Initials for someone with no picture. Two letters, never more. */
const initialsOf = (name = "") =>
  name.trim().split(/\s+/).slice(0, 2).map((w) => w[0] || "").join("").toUpperCase() || "?";

/** A stable tint per person, so the same face keeps the same colour. */
const TINTS = ["teal", "amber", "sage", "clay"];
const tintOf = (seed = "") => {
  let n = 0;
  for (let i = 0; i < seed.length; i++) n = (n * 31 + seed.charCodeAt(i)) % 997;
  return TINTS[n % TINTS.length];
};

function Avatar({ user, size = 38 }) {
  const url = uploadUrl(user?.avatarUrl);
  const tint = tintOf(user?.username || user?.fullName || "");
  /* A stored avatar can outlive its file — the upload directory moves, or the
     row is restored from a backup the folder was not. Without this the circle
     rendered empty, which reads as a person with no name at all. */
  const [broken, setBroken] = useState(false);
  const showImage = url && !broken;

  return (
    <span className="brb-avatar" style={{ width: size, height: size,
            background: `var(--${tint}-soft)`, color: `var(--${tint})`, fontSize: size * 0.34 }}>
      {showImage
        ? <img src={url} alt="" onError={() => setBroken(true)} />
        : initialsOf(user?.fullName || user?.username)}
    </span>
  );
}

/* ═══════════════════════════════════════════════════════════════════════════
   PEOPLE — pick one, then set what they may do
   ═══════════════════════════════════════════════════════════════════════════ */

function BranchPeople({ onCount }) {
  const addToast = useToast();
  const [users, setUsers] = useState([]);
  const [loading, setLoading] = useState(true);
  const [q, setQ] = useState("");
  const [role, setRole] = useState("");
  const [editing, setEditing] = useState(null);
  /* Which role is the platform's own. Resolved rather than assumed: a user row
     carries the role's NAME, and a name can be edited, so matching on "Super
     Admin" would stop working the moment somebody renamed it. ROLE01 is the
     code, and the code is fixed. */
  const [superAdminName, setSuperAdminName] = useState(null);

  const load = useCallback(() => {
    setLoading(true);
    return rbacService.getAllUsers()
      .then((u) => { setUsers(u ?? []); onCount?.(u ?? []); })
      .catch((e) => addToast(e?.message || "Could not load the people here.", "error"))
      .finally(() => setLoading(false));
  }, [addToast, onCount]);

  useEffect(() => { load(); }, [load]);

  useEffect(() => {
    let live = true;
    rbacService.getAllRoles(false)
      .then((rs) => {
        if (!live) return;
        setSuperAdminName((rs ?? []).find((r) => r.groupCode === "ROLE01")?.groupName ?? null);
      })
      // A failure here costs the branch nothing it can see: the chip row simply
      // offers one more choice than it should, and the list is unaffected.
      .catch(() => {});
    return () => { live = false; };
  }, []);

  /* The roles actually present, not every role that exists — a filter offering
     choices that match nobody is a filter that wastes a click to prove it.
     Super Admin is left out even when somebody here holds it: it is the
     platform's own role, not a job in a pharmacy, and this page neither grants
     it nor takes it away. */
  const roles = useMemo(
    () => [...new Set(users.map((u) => u.groupName).filter(Boolean))]
            .filter((n) => n !== superAdminName)
            .sort(),
    [users, superAdminName]);

  const visible = useMemo(() => {
    const term = q.trim().toLowerCase();
    return users.filter((u) => {
      if (role && u.groupName !== role) return false;
      if (!term) return true;
      return (u.fullName || "").toLowerCase().includes(term)
          || (u.username || "").toLowerCase().includes(term)
          || (u.groupName || "").toLowerCase().includes(term);
    });
  }, [users, q, role]);

  return (
    <>
      <div className="brb-toolbar">
        <div className="brb-search">
          <Ic d={P.search} size={14} />
          <input type="search" value={q} placeholder="Search by name, username or role…"
                 aria-label="Search people" onChange={(e) => setQ(e.target.value)} />
          {q && (
            <button className="brb-clear" aria-label="Clear search" onClick={() => setQ("")}>
              <Ic d={P.x} size={12} />
            </button>
          )}
        </div>

        {/* Only worth offering once there is more than one role in the room. */}
        {roles.length > 1 && (
          <div className="brb-chips" role="group" aria-label="Filter by role">
            <button className={`brb-chip ${role === "" ? "on" : ""}`} onClick={() => setRole("")}>
              Everyone <span className="brb-chip-n">{users.length}</span>
            </button>
            {roles.map((r) => (
              <button key={r} className={`brb-chip ${role === r ? "on" : ""}`}
                      onClick={() => setRole(role === r ? "" : r)}>
                {r} <span className="brb-chip-n">{users.filter((u) => u.groupName === r).length}</span>
              </button>
            ))}
          </div>
        )}
      </div>

      <Card pad={false}>
        {/* Column headings on wide screens only. On a phone each row carries its
            own labels, because a heading row scrolled out of sight explains
            nothing. */}
        <div className="brb-head" aria-hidden="true">
          <span>Person</span><span>Role</span><span>Status</span><span />
        </div>

        <ul className="brb-list">
          {visible.map((u) => {
            const active = u.isActive === 1;
            return (
              <li key={u.pidUser ?? u.id} className="brb-row">
                <div className="brb-person">
                  <Avatar user={u} />
                  <span className="brb-person-text">
                    <span className="brb-name">{u.fullName || u.username}</span>
                    <span className="brb-username mono">{u.username}</span>
                  </span>
                </div>

                <div className="brb-cell brb-cell-role" data-label="Role">
                  <span className="brb-role">{u.groupName || "No role"}</span>
                </div>

                <div className="brb-cell brb-cell-status" data-label="Status">
                  <span className={`brb-status ${active ? "on" : "off"}`}>
                    <i /> {active ? "Active" : "Inactive"}
                  </span>
                </div>

                <div className="brb-actions">
                  <button className="btn btn-ghost btn-sm" onClick={() => setEditing(u)}
                          title={`Set what ${u.fullName || u.username} may do`}>
                    <Ic d={P.key} size={13} /> Permissions
                  </button>
                </div>
              </li>
            );
          })}

          {loading && users.length === 0 && (
            <li className="brb-empty">Loading the people here…</li>
          )}
          {!loading && visible.length === 0 && (
            <li className="brb-empty">
              {users.length === 0
                ? "Nobody works here yet. Add someone from New User."
                : "Nobody matches that."}
            </li>
          )}
        </ul>
      </Card>

      {editing && (
        <UserPermissions
          user={editing}
          pathFor={permissionsPath}
          onClose={() => { setEditing(null); load(); }}
        />
      )}
    </>
  );
}

/* ═══════════════════════════════════════════════════════════════════════════
   ROLES — read-only, and without the platform's own

   Its own list rather than RBAC's. That one is the platform's editor: it shows
   every role there is, Super Admin included, and carries create, edit and
   delete behind permission flags. A pharmacy needs none of that — it cannot
   change a role, and Super Admin is not a job anybody here can hold. A role
   shown but unusable is a door with no handle, so it is not shown.
   ═══════════════════════════════════════════════════════════════════════════ */

/** Falls back to the page's own accent when a role carries no colour. */
const ROLE_TINT = "var(--teal)";

function BranchRoles() {
  const addToast = useToast();
  const [roles, setRoles] = useState([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let live = true;
    rbacService.getAllRoles(false)
      .then((d) => { if (live) setRoles(d ?? []); })
      .catch((e) => addToast(e?.message || "Could not load the roles.", "error"))
      .finally(() => { if (live) setLoading(false); });
    return () => { live = false; };
  }, [addToast]);

  const visible = useMemo(
    () => roles.filter((r) => r.groupCode !== "ROLE01"),
    [roles]);

  if (loading) return <Card><div className="brb-empty">Loading the roles…</div></Card>;

  return (
    <Card>
      <div className="card-head">
        <div>
          <div className="card-eyebrow">Branch · Roles</div>
          <div className="card-title">What each role grants</div>
        </div>
        <Pill tone="teal">{visible.length} roles</Pill>
      </div>

      <p className="brb-note">
        Roles are the same across every pharmacy and are set by the Super Admin.
        To give one person more or less than their role allows, open them on the
        People tab.
      </p>

      <div className="brb-roles">
        {visible.map((r) => {
          const rc = r.roleColor || ROLE_TINT;
          return (
            <div key={r.pidGroup} className="brb-role-card" style={{ borderColor: `${rc}33` }}>
              <span className="brb-role-mark" style={{ background: rc }}>
                {initialsOf(r.groupName)}
              </span>
              <div className="brb-role-text">
                <div className="brb-role-name">{r.groupName}</div>
                {r.description && <div className="brb-role-desc">{r.description}</div>}
                <div className="brb-role-meta">
                  <span className="brb-role-code mono" style={{ background: `${rc}15`, color: rc }}>
                    {r.groupCode}
                  </span>
                  <span className={`brb-status ${r.isActive ? "on" : "off"}`}>
                    <i /> {r.isActive ? "Active" : "Inactive"}
                  </span>
                </div>
              </div>
            </div>
          );
        })}

        {visible.length === 0 && (
          <div className="brb-empty">No roles are defined yet.</div>
        )}
      </div>
    </Card>
  );
}

/* ═══════════════════════════════════════════════════════════════════════════
   PLAN — the ceiling this pharmacy works inside
   ═══════════════════════════════════════════════════════════════════════════ */

function BranchPlan({ onPlan }) {
  const addToast = useToast();
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    apiClient.get("/api/v1/branch-rbac/modules")
      .then((d) => { setData(d); onPlan?.(d); })
      .catch((e) => addToast(e?.message || "Could not read the plan.", "error"))
      .finally(() => setLoading(false));
  }, [addToast, onPlan]);

  const modules = data?.modules ?? [];
  const granted = modules.filter((m) => m.granted);

  if (loading) return <Card><div className="brb-empty">Loading the plan…</div></Card>;

  return (
    <Card>
      <div className="card-head">
        <div>
          <div className="card-eyebrow">Branch · Plan</div>
          <div className="card-title">What this pharmacy is entitled to</div>
        </div>
        <Pill tone={data?.unrestricted ? "sage" : "teal"}>
          {data?.unrestricted ? "Unrestricted" : `${granted.length} of ${modules.length}`}
        </Pill>
      </div>

      {data?.unrestricted ? (
        /* An empty grant and an empty list mean opposite things, and saying so
           is the point of this panel: no grant is everything the roles allow,
           not nothing. */
        <div className="brb-plan-note brb-plan-open">
          <Ic d={P.check} size={16} />
          <div>
            <b>No module restriction is set.</b>
            <p>
              This pharmacy has everything its roles allow. Only the Super Admin can narrow it,
              and permissions here are then bounded by the roles alone.
            </p>
          </div>
        </div>
      ) : (
        <>
          <div className="brb-plan-note">
            <Ic d={P.layers} size={16} />
            <div>
              <b>{granted.length} module{granted.length === 1 ? "" : "s"} granted.</b>
              <p>
                Permissions on the People tab can go up to this and no further. To widen it,
                ask the Super Admin to change the plan.
              </p>
            </div>
          </div>
          <div className="brb-modules">
            {granted.map((m) => (
              <span key={m.moduleId} className="brb-module" title={m.moduleCode}>
                <Ic d={P.check} size={12} /> {m.moduleName}
              </span>
            ))}
          </div>
        </>
      )}
    </Card>
  );
}

/* ═══════════════════════════════════════════════════════════════════════════
   PAGE
   ═══════════════════════════════════════════════════════════════════════════ */

export default function BranchRBAC() {
  const { currentUser } = useApp();
  const perm = usePermission("branchrbac");
  const [tab, setTab] = useState("People");

  /* Summary figures, filled in by the panels that already fetch them rather
     than fetched twice. */
  const [people, setPeople] = useState(null);
  const [plan, setPlan] = useState(null);

  const onCount = useCallback((list) => setPeople(list), []);
  const onPlan = useCallback((d) => setPlan(d), []);

  const tabs = TABS.filter((t) => !t.needsCreate || perm.canCreate);
  const active = people?.filter((u) => u.isActive === 1).length ?? 0;

  return (
    <div className="page brb">
      <header className="brb-mast">
        <div className="brb-mast-text">
          <h1>Branch Access Control</h1>
          <p>
            The people at <b>{currentUser?.branchName || "this pharmacy"}</b>, what each of them
            may do, and the plan it works inside. Roles and modules are set by the Super Admin.
          </p>
        </div>

        {/* Facts, not decoration: how many people, how many can sign in, and
            whether the plan is holding anything back. */}
        <dl className="brb-stats">
          <div>
            <dt>People</dt>
            <dd>{people?.length ?? "—"}</dd>
          </div>
          <div>
            <dt>Active</dt>
            <dd>{people ? active : "—"}</dd>
          </div>
          <div>
            <dt>Plan</dt>
            <dd className={plan && !plan.unrestricted ? "brb-stat-limited" : ""}>
              {plan == null ? "—" : plan.unrestricted ? "Open" : `${plan.modules.filter((m) => m.granted).length} mods`}
            </dd>
          </div>
        </dl>
      </header>

      <nav className="brb-tabs" role="tablist" aria-label="Branch access control">
        {tabs.map((t) => (
          <button key={t.key} role="tab" aria-selected={tab === t.key} title={t.hint}
                  className={`brb-tab ${tab === t.key ? "on" : ""}`}
                  onClick={() => setTab(t.key)}>
            <Ic d={t.icon} size={14} />
            <span>{t.key}</span>
          </button>
        ))}
      </nav>

      {tab === "People"   && <BranchPeople onCount={onCount} />}
      {tab === "Users"    && <UsersList perm={perm} />}
      {tab === "New User" && <UserEntry perm={perm} />}
      {tab === "Roles"    && <BranchRoles />}
      {tab === "Plan"     && <BranchPlan onPlan={onPlan} />}

      {/* The plan figure belongs in the header whichever tab is open, so it is
          fetched once on mount rather than only when the Plan tab is chosen. */}
      {tab !== "Plan" && plan == null && <PlanPrefetch onPlan={onPlan} />}
    </div>
  );
}

/** Reads the plan for the header without rendering anything. */
function PlanPrefetch({ onPlan }) {
  useEffect(() => {
    let live = true;
    apiClient.get("/api/v1/branch-rbac/modules")
      .then((d) => { if (live) onPlan?.(d); })
      .catch(() => { /* the header simply shows a dash */ });
    return () => { live = false; };
  }, [onPlan]);
  return null;
}
