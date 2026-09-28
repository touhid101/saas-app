/**
 * The Super Admin console: who has applied, and every pharmacy already running.
 *
 * <b>Activation is one decision, not eight.</b> The server does the whole
 * provisioning in a single transaction, so this screen collects the three things
 * that decision needs — plan, term, permission template — and then gets out of
 * the way. Anything it asked beyond that would be a field somebody has to fill in
 * correctly for a pharmacy to work, which is the manual step the whole feature
 * exists to remove.
 *
 * <b>Nothing here computes an expiry.</b> Days remaining, grace, whether a
 * pharmacy may still trade — all of it comes from the same code the API gate
 * uses, so this table and the gate can never disagree.
 */
import React, { useCallback, useEffect, useMemo, useState } from "react";
import apiClient from "../lib/apiClient.js";
import subscriptionService from "../services/subscriptionService.js";
import { Card, Pill } from "../components/UI.jsx";
import uploadUrl from "../lib/uploadUrl.js";
import { useToast } from "../hooks/useToast.js";
import { useConfirm } from "../lib/ConfirmProvider.jsx";
import TenantDetail from "./TenantDetail.jsx";

const bdt = (n) => "৳" + Number(n ?? 0).toLocaleString("en-IN", { maximumFractionDigits: 0 });
const day = (iso) => {
  if (!iso) return "—";
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? iso
    : d.toLocaleDateString("en-GB", { day: "numeric", month: "short", year: "numeric" });
};

const STATUS_TONE = { ACTIVE: "sage", PENDING: "amber", SUSPENDED: "clay", EXPIRED: "clay" };

/** What to say about a pharmacy's remaining time, in one line. */
function standing(t) {
  if (!t.allowed) return { tone: "clay", text: t.tenantStatus === "SUSPENDED" ? "Suspended" : "No access" };
  if (t.inGrace) return { tone: "clay", text: `In grace — ${t.daysRemaining}d left` };
  if (t.daysRemaining <= 14) return { tone: "amber", text: `${t.daysRemaining}d left` };
  return { tone: "sage", text: `${t.daysRemaining}d left` };
}

export default function Tenants() {
  const addToast = useToast();
  const confirm = useConfirm();
  const [tab, setTab] = useState("applications");
  const [queue, setQueue] = useState([]);
  const [tenants, setTenants] = useState([]);
  // The running list is the one that grows without limit — one row per
  // pharmacy on the platform — so it is the one that needs finding rather than
  // scrolling.
  const [tenantQ, setTenantQ] = useState("");
  const [plans, setPlans] = useState([]);
  const [templates, setTemplates] = useState([]);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(null);
  const [chosen, setChosen] = useState({});
  const [allPlans, setAllPlans] = useState([]);
  const [planDraft, setPlanDraft] = useState(null);
  // The lengths a pharmacy can be sold. These used to be four numbers written
  // into the JSX, which meant offering a two-year deal was a frontend release.
  const [terms, setTerms] = useState([]);
  const [termDraft, setTermDraft] = useState(null);
  // Every template including the retired ones — the activation dropdown wants
  // only the live ones, the screen that edits them has to show both.
  const [allTemplates, setAllTemplates] = useState([]);
  const [templateDraft, setTemplateDraft] = useState(null);
  // The open module editor: { templateId, templateName, modules: [...] }.
  const [templateModules, setTemplateModules] = useState(null);
  const [openTenant, setOpenTenant] = useState(null);
  const [newPharmacy, setNewPharmacy] = useState(null);
  // Each of these was a window.prompt. They are dialogs now because choosing a
  // plan by typing "2" from a numbered list is not a way to change what somebody
  // is charged.
  const [planMove, setPlanMove] = useState(null);
  const [expiryMove, setExpiryMove] = useState(null);
  const [declining, setDeclining] = useState(null);

  const load = useCallback(() => {
    setLoading(true);
    return Promise.all([
      apiClient.get("/api/admin/onboarding/registrations", { status: "ALL" }),
      subscriptionService.getTenants(),
      subscriptionService.getPlans(),
      apiClient.get("/api/admin/onboarding/templates"),
      // The admin list, not the sales list: this screen manages plans that are
      // off sale too, and the buyer-facing endpoint deliberately hides them.
      apiClient.get("/api/admin/subscriptions/plans"),
      apiClient.get("/api/admin/subscriptions/terms"),
      apiClient.get("/api/admin/onboarding/templates", { includeRetired: true }),
    ])
      .then(([q, t, p, tm, ap, tr, at]) => {
        setQueue(q ?? []); setTenants(t ?? []); setPlans(p ?? []);
        setTemplates(tm ?? []); setAllPlans(ap ?? []);
        setTerms(tr ?? []); setAllTemplates(at ?? []);
      })
      .catch((e) => addToast(e?.message || "Could not load the console.", "error"))
      .finally(() => setLoading(false));
  }, [addToast]);

  useEffect(() => { load(); }, [load]);

  // Every default here reads the first row of a list the operator controls. A
  // literal 1 would quietly survive the term being taken off the list.
  const sellableTerms = useMemo(() => terms.filter((t) => t.isActive === 1), [terms]);
  // An untouched row starts on whatever the applicant asked for on the sign-up
  // form. It is still only a default — the reviewer changes it like any other —
  // but starting at the top of the list would make them re-enter something the
  // applicant had already told us, which is the reason the preference is stored
  // at all. A preference naming a plan since withdrawn falls back rather than
  // selecting nothing.
  const preferenceOf = (id) => {
    const reg = queue.find((r) => r.registrationId === id);
    if (!reg) return {};
    const plan = plans.find((p) => p.planCode === reg.preferredPlanCode);
    const term = sellableTerms.find((t) => t.termMonths === reg.preferredTermMonths);
    return {
      ...(plan ? { planId: plan.planId } : {}),
      ...(term ? { termMonths: term.termMonths } : {}),
    };
  };

  const choiceFor = (id) => chosen[id] ?? {
    planId: plans[0]?.planId,
    templateCode: templates[0]?.templateCode,
    termMonths: sellableTerms[0]?.termMonths ?? 1,
    ...preferenceOf(id),
  };
  const choose = (id, patch) => setChosen((c) => ({ ...c, [id]: { ...choiceFor(id), ...patch } }));

  const activate = async (reg) => {
    const c = choiceFor(reg.registrationId);
    setBusy(reg.registrationId);
    try {
      const res = await apiClient.post(
        `/api/admin/onboarding/registrations/${reg.registrationId}/activate`, c);
      addToast(`${res.branchName} is live. Its administrator can sign in now.`, "success");
      await load();
    } catch (e) {
      addToast(e?.message || "Could not activate this pharmacy.", "error");
    } finally { setBusy(null); }
  };

  const reject = async () => {
    const { reg, reason } = declining;
    setBusy(reg.registrationId);
    try {
      await apiClient.post(`/api/admin/onboarding/registrations/${reg.registrationId}/reject`, { reason });
      addToast("Application declined.", "success");
      setDeclining(null);
      await load();
    } catch (e) {
      addToast(e?.message || "Could not decline the application.", "error");
    } finally { setBusy(null); }
  };

  const addPharmacy = async () => {
    const f = newPharmacy;
    if (!f.pharmacyName?.trim() || !f.contactPhone?.trim()
        || !f.adminUsername?.trim() || !f.adminPassword) {
      return addToast("Pharmacy name, phone, username and password are all needed.", "warning");
    }
    setBusy("new");
    try {
      const res = await apiClient.post("/api/admin/onboarding/pharmacies", {
        ...f,
        planId: Number(f.planId) || plans[0]?.planId,
        templateCode: f.templateCode || templates[0]?.templateCode,
        termMonths: Number(f.termMonths) || 1,
      });
      addToast(`${res.branchName} is live. ${f.adminUsername} can sign in now.`, "success");
      setNewPharmacy(null);
      await load();
    } catch (e) {
      addToast(e?.message || "Could not create the pharmacy.", "error");
    } finally { setBusy(null); }
  };

  const savePlan = async () => {
    setBusy("plan");
    try {
      const body = { ...planDraft, price: Number(planDraft.price) || 0 };
      if (planDraft.planId) await apiClient.put(`/api/admin/subscriptions/plans/${planDraft.planId}`, body);
      else await apiClient.post("/api/admin/subscriptions/plans", body);
      addToast(planDraft.planId ? "Plan updated." : "Plan added.", "success");
      setPlanDraft(null);
      await load();
    } catch (e) {
      addToast(e?.message || "Could not save the plan.", "error");
    } finally { setBusy(null); }
  };

  const retirePlan = async (p) => {
    const ok = await confirm({
      title: `Take "${p.planName}" off sale?`,
      body: "It stops being offered on new pharmacies. Anyone already on it keeps it, "
          + "and keeps being billed for it, until you move them to another plan.",
      confirmLabel: "Take off sale",
      tone: "danger",
    });
    if (!ok) return;
    setBusy(p.planId);
    try {
      await apiClient.delete(`/api/admin/subscriptions/plans/${p.planId}`);
      addToast("Plan taken off sale.", "success");
      await load();
    } catch (e) {
      addToast(e?.message || "Could not retire the plan.", "error");
    } finally { setBusy(null); }
  };

  const movePlan = async () => {
    const { tenant, planId } = planMove;
    const chosenPlan = allPlans.find((p) => String(p.planId) === String(planId));
    if (!chosenPlan) return addToast("Choose a plan.", "warning");
    setBusy(tenant.branchId);
    try {
      await apiClient.patch(`/api/admin/subscriptions/${tenant.branchId}/plan`, { planId: chosenPlan.planId });
      addToast(`${tenant.branchName} moves to ${chosenPlan.planName} at the next renewal.`, "success");
      setPlanMove(null);
      await load();
    } catch (e) {
      addToast(e?.message || "Could not change the plan.", "error");
    } finally { setBusy(null); }
  };

  const moveExpiry = async () => {
    const { tenant, expiresOn, reason } = expiryMove;
    if (!expiresOn) return addToast("Give the date it should run to.", "warning");
    if (!reason?.trim()) return addToast("Give a reason — it is recorded.", "warning");
    setBusy(tenant.branchId);
    try {
      await apiClient.patch(`/api/admin/subscriptions/${tenant.branchId}/expiry`,
                            { expiresOn, reason: reason.trim() });
      addToast(`${tenant.branchName} now runs to ${day(expiresOn)}.`, "success");
      setExpiryMove(null);
      await load();
    } catch (e) {
      addToast(e?.message || "Could not change the expiry.", "error");
    } finally { setBusy(null); }
  };

  const setStatus = async (t, status) => {
    setBusy(t.branchId);
    try {
      await subscriptionService.setTenantStatus(t.branchId, status, "changed from the console");
      addToast(`${t.branchName} is now ${status.toLowerCase()}.`, "success");
      await load();
    } catch (e) {
      addToast(e?.message || "Could not change the status.", "error");
    } finally { setBusy(null); }
  };

  /* ── terms ─────────────────────────────────────────────────────────────
     Nothing points at a term once a pharmacy is activated: the length is read
     once to work out an expiry and the date is what gets stored. That is why
     these can be deleted outright where a plan can only be withdrawn. */

  const saveTerm = async () => {
    setBusy("term");
    try {
      const body = {
        termMonths: Number(termDraft.termMonths) || null,
        termName: termDraft.termName,
        description: termDraft.description,
        discountPct: termDraft.discountPct === "" ? null : termDraft.discountPct,
      };
      if (termDraft.termId) await apiClient.put(`/api/admin/subscriptions/terms/${termDraft.termId}`, body);
      else await apiClient.post("/api/admin/subscriptions/terms", body);
      addToast(termDraft.termId ? "Term updated." : "Term added.", "success");
      setTermDraft(null);
      await load();
    } catch (e) {
      addToast(e?.message || "Could not save the term.", "error");
    } finally { setBusy(null); }
  };

  const toggleTerm = async (t) => {
    setBusy(`term-${t.termId}`);
    try {
      await apiClient.patch(`/api/admin/subscriptions/terms/${t.termId}/active`,
                            { active: t.isActive !== 1 });
      addToast(t.isActive === 1 ? "Term taken off the list." : "Term back on the list.", "success");
      await load();
    } catch (e) {
      addToast(e?.message || "Could not change the term.", "error");
    } finally { setBusy(null); }
  };

  const deleteTerm = async (t) => {
    const ok = await confirm({
      title: `Delete the ${t.termName} term?`,
      body: "Pharmacies already sold this length keep the expiry they were given — "
          + "the date was worked out at activation and stored. This only changes what "
          + "can be offered from now on.",
      confirmLabel: "Delete",
    });
    if (!ok) return;
    setBusy(`term-${t.termId}`);
    try {
      await apiClient.delete(`/api/admin/subscriptions/terms/${t.termId}`);
      addToast("Term deleted.", "success");
      await load();
    } catch (e) {
      addToast(e?.message || "Could not delete the term.", "error");
    } finally { setBusy(null); }
  };

  /* ── permission templates ──────────────────────────────────────────────── */

  const saveTemplate = async () => {
    setBusy("template");
    try {
      const body = {
        templateCode: templateDraft.templateCode,
        templateName: templateDraft.templateName,
        description: templateDraft.description,
        planCode: templateDraft.planCode,
      };
      if (templateDraft.templateId) {
        await apiClient.put(`/api/admin/onboarding/templates/${templateDraft.templateId}`, body);
      } else {
        await apiClient.post("/api/admin/onboarding/templates", body);
      }
      addToast(templateDraft.templateId ? "Template updated." : "Template added.", "success");
      setTemplateDraft(null);
      await load();
    } catch (e) {
      addToast(e?.message || "Could not save the template.", "error");
    } finally { setBusy(null); }
  };

  const toggleTemplate = async (t) => {
    setBusy(`tpl-${t.templateId}`);
    try {
      await apiClient.patch(`/api/admin/onboarding/templates/${t.templateId}/active`,
                            { active: t.isActive !== 1 });
      addToast(t.isActive === 1 ? "Template taken off the list." : "Template back on the list.", "success");
      await load();
    } catch (e) {
      addToast(e?.message || "Could not change the template.", "error");
    } finally { setBusy(null); }
  };

  const deleteTemplate = async (t) => {
    const ok = await confirm({
      title: `Delete "${t.templateName}"?`,
      body: "The modules it grants go with it. Pharmacies activated on this template "
          + "keep the permissions they were given — those were copied at activation, "
          + "not linked to this row.",
      confirmLabel: "Delete",
    });
    if (!ok) return;
    setBusy(`tpl-${t.templateId}`);
    try {
      await apiClient.delete(`/api/admin/onboarding/templates/${t.templateId}`);
      addToast("Template deleted.", "success");
      await load();
    } catch (e) {
      addToast(e?.message || "Could not delete the template.", "error");
    } finally { setBusy(null); }
  };

  const openModules = async (t) => {
    setBusy(`tpl-${t.templateId}`);
    try {
      const d = await apiClient.get(`/api/admin/onboarding/templates/${t.templateId}/modules`);
      setTemplateModules(d);
    } catch (e) {
      addToast(e?.message || "Could not load what this template grants.", "error");
    } finally { setBusy(null); }
  };

  const setModuleFlag = (moduleId, key, value) =>
    setTemplateModules((d) => ({
      ...d,
      modules: d.modules.map((m) => {
        if (m.moduleId !== moduleId) return m;
        const next = { ...m, [key]: value };
        // Ticking anything implies the module is granted; untick everything and
        // it is not. A module granted with no permission on it is a menu entry
        // that opens onto a permission error, so that state is not reachable.
        if (key !== "granted") {
          next.granted = ["canView", "canCreate", "canEdit", "canDelete",
                          "canApprove", "canExport", "canPrint"].some((k) => next[k]);
        } else if (value) {
          next.canView = true;          // the least a grant can mean
        } else {
          for (const k of ["canView", "canCreate", "canEdit", "canDelete",
                           "canApprove", "canExport", "canPrint"]) next[k] = false;
        }
        return next;
      }),
    }));

  const saveModules = async () => {
    setBusy("modules");
    try {
      const n = await apiClient.put(
        `/api/admin/onboarding/templates/${templateModules.templateId}/modules`,
        { modules: templateModules.modules.filter((m) => m.granted) });
      addToast(`"${templateModules.templateName}" now grants ${n} module${n === 1 ? "" : "s"}.`, "success");
      setTemplateModules(null);
      await load();
    } catch (e) {
      addToast(e?.message || "Could not save what this template grants.", "error");
    } finally { setBusy(null); }
  };

  const visibleTenants = useMemo(() => {
    const t = tenantQ.trim().toLowerCase();
    if (!t) return tenants;
    return tenants.filter((x) =>
      String(x.branchName ?? "").toLowerCase().includes(t) ||
      String(x.planName ?? "").toLowerCase().includes(t) ||
      String(x.tenantStatus ?? "").toLowerCase().includes(t));
  }, [tenants, tenantQ]);

  /* What the platform is owed across every pharmacy — the number this tab is
     usually opened to find, and one nobody should have to add up by eye. */
  const totalOwed = tenants.reduce((s, t) => s + Number(t.outstandingAmount ?? 0), 0);

  const pending = queue.filter((r) => r.status === "PENDING");
  const reviewed = queue.filter((r) => r.status !== "PENDING");

  return (
    <div className="page">
      <div className="page-head">
        <div>
          <h1>Pharmacies</h1>
          <div className="page-sub">
            {pending.length
              ? `${pending.length} application${pending.length === 1 ? "" : "s"} waiting`
              : "No applications waiting"} · {tenants.length} running
          </div>
        </div>
        <div className="flex-gap">
          <button className="btn btn-ghost btn-sm" onClick={load} disabled={loading}>
            {loading ? "Loading…" : "Refresh"}
          </button>
          <button className="btn btn-primary btn-sm"
                  onClick={() => setNewPharmacy({ pharmacyName: "", contactName: "", contactEmail: "",
                                                  contactPhone: "", address: "", adminUsername: "",
                                                  adminPassword: "", planId: plans[0]?.planId ?? "",
                                                  templateCode: templates[0]?.templateCode ?? "",
                                                  termMonths: 1, copyCatalogue: true })}>
            Add pharmacy
          </button>
        </div>
      </div>

      <div className="tabs">
        {[["applications", `Applications${pending.length ? ` (${pending.length})` : ""}`],
          ["running", "Running"],
          ["plans", "Plans"],
          ["terms", "Terms"],
          ["permissions", "Permissions"],
          ["history", "Reviewed"]].map(([k, label]) => (
          <button key={k} className={`tab-btn ${tab === k ? "active" : ""}`} onClick={() => setTab(k)}>
            {label}
          </button>
        ))}
      </div>

      {tab === "applications" && (
        pending.length === 0 ? (
          <div className="dash-allclear">Nothing waiting. Every application has been reviewed.</div>
        ) : (
          <div className="tenant-apps">
            {pending.map((r) => {
              const c = choiceFor(r.registrationId);
              return (
                <Card key={r.registrationId} eyebrow={`Applied ${day(r.appliedOn)}`} title={r.pharmacyName}>
                  <dl className="sub-facts">
                    <div><dt>Contact</dt><dd>{r.contactName} · {r.contactPhone}</dd></div>
                    {r.contactEmail && <div><dt>Email</dt><dd>{r.contactEmail}</dd></div>}
                    {r.address && <div><dt>Address</dt><dd>{r.address}</dd></div>}
                    <div><dt>Will sign in as</dt><dd className="mono">{r.adminUsername}</dd></div>
                  </dl>

                  <div className="tenant-choice">
                    <label>
                      <span>Plan</span>
                      <select value={c.planId} onChange={(e) => choose(r.registrationId, { planId: Number(e.target.value) })}>
                        {plans.map((p) => (
                          <option key={p.planId} value={p.planId}>{p.planName} — {bdt(p.price)}</option>
                        ))}
                      </select>
                    </label>
                    <label>
                      <span>Permissions</span>
                      <select value={c.templateCode} onChange={(e) => choose(r.registrationId, { templateCode: e.target.value })}>
                        {templates.map((t) => (
                          <option key={t.templateCode} value={t.templateCode}>{t.templateName}</option>
                        ))}
                      </select>
                    </label>
                    <label>
                      <span>Term</span>
                      <select value={c.termMonths} onChange={(e) => choose(r.registrationId, { termMonths: Number(e.target.value) })}>
                        {sellableTerms.map((t) => (
                          <option key={t.termId} value={t.termMonths}>{t.termName}</option>
                        ))}
                      </select>
                    </label>
                  </div>

                  <p className="tenant-note">
                    Activating creates the pharmacy, its subscription, the administrator's
                    account and its starting master data in one step.
                  </p>

                  <div className="tenant-actions">
                    <button className="btn btn-ghost btn-sm" disabled={busy === r.registrationId}
                            onClick={() => setDeclining({ reg: r, reason: "" })}>Decline</button>
                    <button className="btn btn-primary btn-sm" disabled={busy === r.registrationId}
                            onClick={() => activate(r)}>
                      {busy === r.registrationId ? "Activating…" : "Activate"}
                    </button>
                  </div>
                </Card>
              );
            })}
          </div>
        )
      )}

      {tab === "running" && (
        <Card eyebrow="Every pharmacy on the platform" title="Running">
          <div className="pg-toolbar">
            <input
              className="pg-search" type="search" value={tenantQ}
              onChange={(e) => setTenantQ(e.target.value)}
              placeholder={`Search ${tenants.length} pharmacies…`}
              aria-label="Search pharmacies"
            />
            <div className="td-summary">
              <Pill tone="teal">{tenants.length} running</Pill>
              {totalOwed > 0 && <Pill tone="amber">{bdt(totalOwed)} outstanding</Pill>}
            </div>
          </div>
          <div className="table-scroll">
            <table className="data-table">
              <thead>
                <tr>
                  <th>Pharmacy</th><th>Status</th><th>Plan</th>
                  <th>Ends</th><th>Standing</th><th className="num">Outstanding</th><th></th>
                </tr>
              </thead>
              <tbody>
                {visibleTenants.map((t) => {
                  const s = standing(t);
                  return (
                    <tr key={t.branchId}>
                      <td>
                        {/* The mark beside the name, as on the branch list and
                            in the pharmacy's own sidebar — the same pharmacy
                            should look the same wherever it appears. */}
                        <div className="md-branch-row">
                          <span className="td-logo md-logo-sm">
                            {t.logoUrl
                              ? <img src={uploadUrl(t.logoUrl)} alt="" />
                              : <span className="td-logo-initial" aria-hidden="true">
                                  {(t.branchName || "?").trim().charAt(0).toUpperCase() || "?"}
                                </span>}
                          </span>
                          <span style={{ minWidth: 0 }}>{t.branchName}</span>
                        </div>
                      </td>
                      <td><Pill tone={STATUS_TONE[t.tenantStatus] || "teal"}>{t.tenantStatus}</Pill></td>
                      <td>{t.planName ?? "—"}</td>
                      <td>{day(t.expiresOn)}</td>
                      <td><span className={`tenant-standing tenant-standing-${s.tone}`}>{s.text}</span></td>
                      <td className="num mono">
                        {t.outstandingInvoices ? bdt(t.outstandingAmount) : "—"}
                      </td>
                      <td className="num">
                        <button className="btn btn-ghost btn-sm"
                                onClick={() => setOpenTenant(t)}>Manage</button>
                        <button className="btn btn-ghost btn-sm" disabled={busy === t.branchId}
                                onClick={() => setPlanMove({ tenant: t, planId: allPlans.find((p) => p.planName === t.planName)?.planId ?? "" })}>Plan</button>
                        <button className="btn btn-ghost btn-sm" disabled={busy === t.branchId}
                                onClick={() => setExpiryMove({ tenant: t, expiresOn: t.expiresOn ?? "", reason: "" })}>Expiry</button>
                        {t.tenantStatus === "SUSPENDED" ? (
                          <button className="btn btn-ghost btn-sm" disabled={busy === t.branchId}
                                  onClick={() => setStatus(t, "ACTIVE")}>Reinstate</button>
                        ) : (
                          <button className="btn btn-ghost btn-sm" disabled={busy === t.branchId}
                                  onClick={() => setStatus(t, "SUSPENDED")}>Suspend</button>
                        )}
                      </td>
                    </tr>
                  );
                })}
                {tenants.length > 0 && visibleTenants.length === 0 && (
                  <tr><td colSpan={7} className="empty-state">
                    No pharmacy matches &ldquo;{tenantQ.trim()}&rdquo;.
                  </td></tr>
                )}
              </tbody>
            </table>
          </div>
        </Card>
      )}

      {tab === "plans" && (
        <Card eyebrow="What a pharmacy can buy" title="Plans"
              right={<button className="btn btn-primary btn-sm"
                             onClick={() => setPlanDraft({ planCode: "", planName: "", description: "",
                                                           price: "", billingTerm: "MONTHLY",
                                                           maxUsers: "", graceDays: 10 })}>
                       Add plan
                     </button>}>
          <p className="tenant-note">
            Editing a price changes what the next invoice costs. Pharmacies already running keep
            the expiry and grace they were sold — a price rise must not move the date somebody
            trading today gets locked out.
          </p>
          <div className="table-scroll">
            <table className="data-table">
              <thead>
                <tr><th>Plan</th><th>Code</th><th className="num">Price</th><th>Term</th>
                    <th className="num">Users</th><th className="num">Grace</th><th>On sale</th><th></th></tr>
              </thead>
              <tbody>
                {allPlans.map((p) => (
                  <tr key={p.planId} className={p.isActive === 1 ? "" : "row-picked"}>
                    <td>{p.planName}</td>
                    <td className="mono">{p.planCode}</td>
                    <td className="num mono">{bdt(p.price)}</td>
                    <td>{p.billingTerm === "YEARLY" ? "Yearly" : "Monthly"}</td>
                    <td className="num mono">{p.maxUsers ?? "—"}</td>
                    <td className="num mono">+{p.graceDays}</td>
                    <td><Pill tone={p.isActive === 1 ? "sage" : "clay"}>
                      {p.isActive === 1 ? "yes" : "withdrawn"}</Pill></td>
                    <td className="num">
                      <button className="btn btn-ghost btn-sm"
                              onClick={() => setPlanDraft({ ...p })}>Edit</button>
                      {p.isActive === 1 && (
                        <button className="btn btn-ghost btn-sm" disabled={busy === p.planId}
                                onClick={() => retirePlan(p)}>Withdraw</button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Card>
      )}

      {tab === "terms" && (
        <Card eyebrow="How long a pharmacy is sold for" title="Terms"
              right={<button className="btn btn-primary btn-sm"
                             onClick={() => setTermDraft({ termMonths: "", termName: "",
                                                           description: "", discountPct: "" })}>
                       Add term
                     </button>}>
          <p className="tenant-note">
            A term is not the plan's billing cycle. The cycle says what the price covers; this says
            how much is being bought at once, which is what lets a yearly deal exist on a monthly
            plan. Editing this list changes what can be offered from now on — a pharmacy already
            activated keeps the expiry it was given, because that date was worked out once and
            stored rather than recalculated from here.
          </p>
          <div className="table-scroll">
            <table className="data-table">
              <thead>
                <tr><th>Term</th><th className="num">Months</th><th>Description</th>
                    <th className="num">Discount</th><th>Offered</th><th></th></tr>
              </thead>
              <tbody>
                {terms.length === 0 && (
                  <tr><td colSpan={6} className="muted">
                    No terms yet. Add one — without a term nothing can be activated.
                  </td></tr>
                )}
                {terms.map((t) => (
                  <tr key={t.termId} className={t.isActive === 1 ? "" : "row-picked"}>
                    <td>{t.termName}</td>
                    <td className="num mono">{t.termMonths}</td>
                    <td className="muted">{t.description || "—"}</td>
                    <td className="num mono">{t.discountPct == null ? "—" : t.discountPct + "%"}</td>
                    <td><Pill tone={t.isActive === 1 ? "sage" : "clay"}>
                      {t.isActive === 1 ? "yes" : "withdrawn"}</Pill></td>
                    <td className="num">
                      <button className="btn btn-ghost btn-sm"
                              onClick={() => setTermDraft({ ...t })}>Edit</button>
                      <button className="btn btn-ghost btn-sm" disabled={busy === `term-${t.termId}`}
                              onClick={() => toggleTerm(t)}>
                        {t.isActive === 1 ? "Withdraw" : "Restore"}
                      </button>
                      <button className="btn btn-ghost btn-sm" disabled={busy === `term-${t.termId}`}
                              onClick={() => deleteTerm(t)}>Delete</button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Card>
      )}

      {tab === "permissions" && (
        <Card eyebrow="What a pharmacy gets on day one" title="Permission templates"
              right={<button className="btn btn-primary btn-sm"
                             onClick={() => setTemplateDraft({ templateCode: "", templateName: "",
                                                               description: "", planCode: "" })}>
                       Add template
                     </button>}>
          <p className="tenant-note">
            A template is copied into a pharmacy's permissions at activation, not linked to it.
            Editing one changes what the next activation grants and leaves every pharmacy already
            running exactly as it is — to re-permission one of those, open it from the Running tab
            and apply a template there.
          </p>
          <div className="table-scroll">
            <table className="data-table">
              <thead>
                <tr><th>Template</th><th>Code</th><th>Description</th><th>Plan</th>
                    <th className="num">Modules</th><th>Offered</th><th></th></tr>
              </thead>
              <tbody>
                {allTemplates.length === 0 && (
                  <tr><td colSpan={7} className="muted">
                    No templates yet. Add one — without a template nothing can be activated.
                  </td></tr>
                )}
                {allTemplates.map((t) => (
                  <tr key={t.templateId} className={t.isActive === 1 ? "" : "row-picked"}>
                    <td>{t.templateName}</td>
                    <td className="mono">{t.templateCode}</td>
                    <td className="muted">{t.description || "—"}</td>
                    <td className="mono">{t.planCode || "—"}</td>
                    <td className="num mono">{t.moduleCount}</td>
                    <td><Pill tone={t.isActive === 1 ? "sage" : "clay"}>
                      {t.isActive === 1 ? "yes" : "withdrawn"}</Pill></td>
                    <td className="num">
                      <button className="btn btn-ghost btn-sm" disabled={busy === `tpl-${t.templateId}`}
                              onClick={() => openModules(t)}>Modules</button>
                      <button className="btn btn-ghost btn-sm"
                              onClick={() => setTemplateDraft({ ...t })}>Edit</button>
                      <button className="btn btn-ghost btn-sm" disabled={busy === `tpl-${t.templateId}`}
                              onClick={() => toggleTemplate(t)}>
                        {t.isActive === 1 ? "Withdraw" : "Restore"}
                      </button>
                      <button className="btn btn-ghost btn-sm" disabled={busy === `tpl-${t.templateId}`}
                              onClick={() => deleteTemplate(t)}>Delete</button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Card>
      )}

      {termDraft && (
        <div className="modal-backdrop" onClick={() => setTermDraft(null)}>
          <div className="modal" onClick={(e) => e.stopPropagation()}>
            <h3>{termDraft.termId ? "Edit term" : "Add a term"}</h3>
            <p className="cat-modal-note">
              The length is what activation reads; the name is only what the operator picks from.
              Two terms cannot share a length — in a dropdown they would be indistinguishable, and
              whichever was chosen the pharmacy would get the same expiry.
            </p>
            <div className="reg-fields">
              <label><span>Months</span>
                <input type="number" min="1" value={termDraft.termMonths ?? ""}
                       onChange={(e) => setTermDraft({ ...termDraft, termMonths: e.target.value })} /></label>
              <label><span>Name — blank to use the length</span>
                <input value={termDraft.termName ?? ""}
                       onChange={(e) => setTermDraft({ ...termDraft, termName: e.target.value })} /></label>
              <label><span>Discount % — blank for none</span>
                <input type="number" min="0" max="100" value={termDraft.discountPct ?? ""}
                       onChange={(e) => setTermDraft({ ...termDraft, discountPct: e.target.value })} /></label>
              <label className="reg-wide"><span>Description</span>
                <input value={termDraft.description ?? ""}
                       onChange={(e) => setTermDraft({ ...termDraft, description: e.target.value })} /></label>
            </div>
            <div className="reg-actions">
              <button className="btn btn-ghost" onClick={() => setTermDraft(null)}>Cancel</button>
              <button className="btn btn-primary" onClick={saveTerm} disabled={busy === "term"}>
                {busy === "term" ? "Saving…" : "Save"}
              </button>
            </div>
          </div>
        </div>
      )}

      {templateDraft && (
        <div className="modal-backdrop" onClick={() => setTemplateDraft(null)}>
          <div className="modal" onClick={(e) => e.stopPropagation()}>
            <h3>{templateDraft.templateId ? "Edit template" : "Add a template"}</h3>
            <p className="cat-modal-note">
              {templateDraft.templateId
                ? "Applies to the next pharmacy activated on it. Nobody already running changes."
                : "A new template grants nothing until you set its modules."}
            </p>
            <div className="reg-fields">
              {!templateDraft.templateId && (
                <label><span>Code</span>
                  <input value={templateDraft.templateCode ?? ""}
                         onChange={(e) => setTemplateDraft({ ...templateDraft, templateCode: e.target.value })} /></label>
              )}
              <label><span>Name</span>
                <input value={templateDraft.templateName ?? ""}
                       onChange={(e) => setTemplateDraft({ ...templateDraft, templateName: e.target.value })} /></label>
              <label><span>Plan it suits — blank for any</span>
                <select value={templateDraft.planCode ?? ""}
                        onChange={(e) => setTemplateDraft({ ...templateDraft, planCode: e.target.value })}>
                  <option value="">Any plan</option>
                  {allPlans.map((pl) => (
                    <option key={pl.planId} value={pl.planCode}>{pl.planName}</option>
                  ))}
                </select></label>
              <label className="reg-wide"><span>Description</span>
                <input value={templateDraft.description ?? ""}
                       onChange={(e) => setTemplateDraft({ ...templateDraft, description: e.target.value })} /></label>
            </div>
            <div className="reg-actions">
              <button className="btn btn-ghost" onClick={() => setTemplateDraft(null)}>Cancel</button>
              <button className="btn btn-primary" onClick={saveTemplate} disabled={busy === "template"}>
                {busy === "template" ? "Saving…" : "Save"}
              </button>
            </div>
          </div>
        </div>
      )}

      {templateModules && (
        <div className="modal-backdrop" onClick={() => setTemplateModules(null)}>
          <div className="modal modal-wide" onClick={(e) => e.stopPropagation()}>
            <h3>{templateModules.templateName} — what it grants</h3>
            <p className="cat-modal-note">
              Every module is listed, not only the granted ones: choosing what a pharmacy gets
              means seeing what it is not getting. Untick every permission on a row and the module
              is not granted at all — a module with no permission on it is a menu entry that opens
              onto an error.
            </p>
            <div className="table-scroll tpl-modules">
              <table className="data-table">
                <thead>
                  <tr>
                    <th>Module</th>
                    <th className="num">View</th>
                    <th className="num">Create</th>
                    <th className="num">Edit</th>
                    <th className="num">Delete</th>
                    <th className="num">Approve</th>
                    <th className="num">Export</th>
                    <th className="num">Print</th>
                  </tr>
                </thead>
                <tbody>
                  {templateModules.modules.map((m) => (
                    <tr key={m.moduleId} className={m.granted ? "" : "row-picked"}>
                      <td>
                        <label className="tpl-module-name">
                          <input type="checkbox" checked={!!m.granted}
                                 onChange={(e) => setModuleFlag(m.moduleId, "granted", e.target.checked)} />
                          <span>{m.moduleName}</span>
                        </label>
                      </td>
                      <td className="num">
                        <input type="checkbox" checked={!!m.canView}
                               onChange={(e) => setModuleFlag(m.moduleId, "canView", e.target.checked)} />
                      </td>
                      <td className="num">
                        <input type="checkbox" checked={!!m.canCreate}
                               onChange={(e) => setModuleFlag(m.moduleId, "canCreate", e.target.checked)} />
                      </td>
                      <td className="num">
                        <input type="checkbox" checked={!!m.canEdit}
                               onChange={(e) => setModuleFlag(m.moduleId, "canEdit", e.target.checked)} />
                      </td>
                      <td className="num">
                        <input type="checkbox" checked={!!m.canDelete}
                               onChange={(e) => setModuleFlag(m.moduleId, "canDelete", e.target.checked)} />
                      </td>
                      <td className="num">
                        <input type="checkbox" checked={!!m.canApprove}
                               onChange={(e) => setModuleFlag(m.moduleId, "canApprove", e.target.checked)} />
                      </td>
                      <td className="num">
                        <input type="checkbox" checked={!!m.canExport}
                               onChange={(e) => setModuleFlag(m.moduleId, "canExport", e.target.checked)} />
                      </td>
                      <td className="num">
                        <input type="checkbox" checked={!!m.canPrint}
                               onChange={(e) => setModuleFlag(m.moduleId, "canPrint", e.target.checked)} />
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <div className="reg-actions">
              <button className="btn btn-ghost" onClick={() => setTemplateModules(null)}>Cancel</button>
              <button className="btn btn-primary" onClick={saveModules} disabled={busy === "modules"}>
                {busy === "modules"
                  ? "Saving…"
                  : `Save ${templateModules.modules.filter((m) => m.granted).length} module${templateModules.modules.filter((m) => m.granted).length === 1 ? "" : "s"}`}
              </button>
            </div>
          </div>
        </div>
      )}

      {planDraft && (
        <div className="modal-backdrop" onClick={() => setPlanDraft(null)}>
          <div className="modal" onClick={(e) => e.stopPropagation()}>
            <h3>{planDraft.planId ? "Edit plan" : "Add a plan"}</h3>
            <p className="cat-modal-note">
              {planDraft.planId
                ? "Applies to the next invoice raised on this plan. Nobody's current term moves."
                : "A new plan can be chosen when activating a pharmacy, or moved onto later."}
            </p>
            <div className="reg-fields">
              {!planDraft.planId && (
                <label><span>Code</span>
                  <input value={planDraft.planCode ?? ""}
                         onChange={(e) => setPlanDraft({ ...planDraft, planCode: e.target.value })} /></label>
              )}
              <label><span>Name</span>
                <input value={planDraft.planName ?? ""}
                       onChange={(e) => setPlanDraft({ ...planDraft, planName: e.target.value })} /></label>
              <label><span>Price per term</span>
                <input type="number" value={planDraft.price ?? ""}
                       onChange={(e) => setPlanDraft({ ...planDraft, price: e.target.value })} /></label>
              <label><span>Billing term</span>
                <select value={planDraft.billingTerm ?? "MONTHLY"}
                        onChange={(e) => setPlanDraft({ ...planDraft, billingTerm: e.target.value })}>
                  <option value="MONTHLY">Monthly</option>
                  <option value="YEARLY">Yearly</option>
                </select></label>
              <label><span>User cap — blank for none</span>
                <input type="number" value={planDraft.maxUsers ?? ""}
                       onChange={(e) => setPlanDraft({ ...planDraft, maxUsers: e.target.value })} /></label>
              <label><span>Grace days after expiry</span>
                <input type="number" value={planDraft.graceDays ?? 10}
                       onChange={(e) => setPlanDraft({ ...planDraft, graceDays: e.target.value })} /></label>
              <label className="reg-wide"><span>Description</span>
                <input value={planDraft.description ?? ""}
                       onChange={(e) => setPlanDraft({ ...planDraft, description: e.target.value })} /></label>
            </div>
            <div className="reg-actions">
              <button className="btn btn-ghost" onClick={() => setPlanDraft(null)}>Cancel</button>
              <button className="btn btn-primary" onClick={savePlan} disabled={busy === "plan"}>
                {busy === "plan" ? "Saving…" : "Save"}
              </button>
            </div>
          </div>
        </div>
      )}

      {planMove && (
        <div className="modal-backdrop" onClick={() => setPlanMove(null)}>
          <div className="modal modal-sm" onClick={(e) => e.stopPropagation()}>
            <h3>Move {planMove.tenant.branchName}</h3>
            <p className="cat-modal-note">
              The new price applies from the next renewal. They have paid for the term they
              are in, and it is not shortened or refunded by this.
            </p>
            <div className="reg-fields">
              <label className="reg-wide"><span>Plan</span>
                <select value={planMove.planId}
                        onChange={(e) => setPlanMove({ ...planMove, planId: e.target.value })}>
                  <option value="">Choose a plan…</option>
                  {allPlans.filter((p) => p.isActive === 1).map((p) => (
                    <option key={p.planId} value={p.planId}>
                      {p.planName} — {bdt(p.price)} / {p.billingTerm === "YEARLY" ? "year" : "month"}
                    </option>
                  ))}
                </select></label>
            </div>
            <div className="reg-actions">
              <button className="btn btn-ghost" onClick={() => setPlanMove(null)}>Cancel</button>
              <button className="btn btn-primary" onClick={movePlan}
                      disabled={busy === planMove.tenant.branchId}>Move</button>
            </div>
          </div>
        </div>
      )}

      {expiryMove && (
        <div className="modal-backdrop" onClick={() => setExpiryMove(null)}>
          <div className="modal modal-sm" onClick={(e) => e.stopPropagation()}>
            <h3>{expiryMove.tenant.branchName}</h3>
            <p className="cat-modal-note">
              Moving a date by hand is recorded with the reason you give, so that months
              later it can be told apart from a mistake.
            </p>
            <div className="reg-fields">
              <label><span>Runs until</span>
                <input type="date" value={expiryMove.expiresOn}
                       onChange={(e) => setExpiryMove({ ...expiryMove, expiresOn: e.target.value })} /></label>
              <label className="reg-wide"><span>Reason</span>
                <input placeholder="Goodwill after the delivery problem in August"
                       value={expiryMove.reason}
                       onChange={(e) => setExpiryMove({ ...expiryMove, reason: e.target.value })} /></label>
            </div>
            <div className="reg-actions">
              <button className="btn btn-ghost" onClick={() => setExpiryMove(null)}>Cancel</button>
              <button className="btn btn-primary" onClick={moveExpiry}
                      disabled={busy === expiryMove.tenant.branchId}>Save</button>
            </div>
          </div>
        </div>
      )}

      {declining && (
        <div className="modal-backdrop" onClick={() => setDeclining(null)}>
          <div className="modal modal-sm" onClick={(e) => e.stopPropagation()}>
            <h3>Decline {declining.reg.pharmacyName}</h3>
            <p className="cat-modal-note">
              Nothing is created and the username they chose is released. The reason is kept
              on the application.
            </p>
            <div className="reg-fields">
              <label className="reg-wide"><span>Reason</span>
                <input value={declining.reason} placeholder="Could not verify the trade licence"
                       onChange={(e) => setDeclining({ ...declining, reason: e.target.value })} /></label>
            </div>
            <div className="reg-actions">
              <button className="btn btn-ghost" onClick={() => setDeclining(null)}>Cancel</button>
              <button className="btn btn-danger" onClick={reject}
                      disabled={busy === declining.reg.registrationId}>Decline</button>
            </div>
          </div>
        </div>
      )}

      {openTenant && (
        <TenantDetail tenant={openTenant} onClose={() => setOpenTenant(null)} onChanged={load} />
      )}

      {newPharmacy && (
        <div className="modal-backdrop" onClick={() => setNewPharmacy(null)}>
          <div className="modal" onClick={(e) => e.stopPropagation()}>
            <h3>Add a pharmacy</h3>
            <p className="cat-modal-note">
              For a customer signed up over the phone. This does everything approving an
              application does — the pharmacy, its subscription, the administrator's account,
              its permissions and its starting data — and it is live immediately.
            </p>
            <div className="reg-fields">
              {[["pharmacyName", "Pharmacy name"], ["contactName", "Contact name"],
                ["contactPhone", "Mobile"], ["contactEmail", "Email"],
                ["adminUsername", "Administrator username"]].map(([k, label]) => (
                <label key={k}><span>{label}</span>
                  <input value={newPharmacy[k] ?? ""}
                         onChange={(e) => setNewPharmacy({ ...newPharmacy, [k]: e.target.value })} /></label>
              ))}
              <label><span>Administrator password</span>
                <input type="password" autoComplete="new-password" value={newPharmacy.adminPassword ?? ""}
                       onChange={(e) => setNewPharmacy({ ...newPharmacy, adminPassword: e.target.value })} /></label>
              <label className="reg-wide"><span>Address</span>
                <input value={newPharmacy.address ?? ""}
                       onChange={(e) => setNewPharmacy({ ...newPharmacy, address: e.target.value })} /></label>
              <label><span>Plan</span>
                <select value={newPharmacy.planId}
                        onChange={(e) => setNewPharmacy({ ...newPharmacy, planId: e.target.value })}>
                  {plans.map((p) => <option key={p.planId} value={p.planId}>{p.planName}</option>)}
                </select></label>
              <label><span>Permissions</span>
                <select value={newPharmacy.templateCode}
                        onChange={(e) => setNewPharmacy({ ...newPharmacy, templateCode: e.target.value })}>
                  {templates.map((t) => (
                    <option key={t.templateCode} value={t.templateCode}>{t.templateName}</option>
                  ))}
                </select></label>
              <label><span>Term</span>
                <select value={newPharmacy.termMonths}
                        onChange={(e) => setNewPharmacy({ ...newPharmacy, termMonths: e.target.value })}>
                  {sellableTerms.map((t) => (
                    <option key={t.termId} value={t.termMonths}>{t.termName}</option>
                  ))}
                </select></label>
              <label><span>Start with the catalogue</span>
                <select value={newPharmacy.copyCatalogue ? "yes" : "no"}
                        onChange={(e) => setNewPharmacy({ ...newPharmacy, copyCatalogue: e.target.value === "yes" })}>
                  <option value="yes">Yes — copy every medicine</option>
                  <option value="no">No — they will add their own</option>
                </select></label>
            </div>
            <div className="reg-actions">
              <button className="btn btn-ghost" onClick={() => setNewPharmacy(null)}>Cancel</button>
              <button className="btn btn-primary" onClick={addPharmacy} disabled={busy === "new"}>
                {busy === "new" ? "Creating…" : "Create and activate"}
              </button>
            </div>
          </div>
        </div>
      )}

      {tab === "history" && (
        <Card eyebrow="Already decided" title="Reviewed applications">
          {reviewed.length === 0 ? (
            <div className="sub-paid">Nothing has been reviewed yet.</div>
          ) : (
            <div className="table-scroll">
              <table className="data-table">
                <thead>
                  <tr><th>Pharmacy</th><th>Outcome</th><th>Reviewed</th><th>By</th><th>Reason</th></tr>
                </thead>
                <tbody>
                  {reviewed.map((r) => (
                    <tr key={r.registrationId}>
                      <td>{r.pharmacyName}</td>
                      <td><Pill tone={r.status === "APPROVED" ? "sage" : "clay"}>{r.status}</Pill></td>
                      <td>{day(r.reviewedAt)}</td>
                      <td>{r.reviewedBy ?? "—"}</td>
                      <td>{r.rejectReason ?? "—"}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </Card>
      )}
    </div>
  );
}
