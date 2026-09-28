/**
 * E-Commerce Customers — the platform's console for online customers.
 *
 * docs/ecommerce Phase 1. An online customer is global: not a row in any
 * pharmacy's customer list, but one identity that may buy from every
 * ECOM-enabled pharmacy. This screen is where the platform decides on them:
 *
 *   approve  — grants a pricing tier (what they asked for, or what you choose)
 *   reject   — a pending registration, with a reason
 *   suspend  — an approved customer, with a reason; signs them out everywhere
 *   type     — change the tier later; journaled and pushed to every pharmacy
 *              row that mirrors this customer
 *   unlock   — clear a sign-in lockout
 *
 * The server requires a platform operator (ROLE01 in the platform org) with
 * MOD55 rights for every call; this page only decides what to offer.
 */
import React, { useCallback, useEffect, useState } from "react";
import { Modal, Field } from "../components/UI.jsx";
import { useToast } from "../hooks/useToast.js";
import { usePermission } from "../lib/usePermission.js";
import {
  HrPage, Kpis, Kpi, HrTable, Status, Filter, HrFilterBar, HrSearch, HrButton,
  HrPagination, HrErrorState, HrDrawer, HrTabs, Pane, Facts, Fact, HrTimeline, humanError, fmtWhen,
  usePagedList, P,
} from "../components/HrKit.jsx";
import svc, { STATUSES, TYPES, STATUS_LABEL, TYPE_LABEL } from "../services/ecomAdminService.js";
import points from "../services/ecomPointsService.js";
import { getRegions } from "../services/masterDataService.js";

const CRUMBS = [{ label: "E-Commerce" }, { label: "Customers" }];

/* A phone number as people read it: 8801712345678 → 01712-345678 */
const phone = (m) => {
  if (!m) return "—";
  const local = m.length === 13 && m.startsWith("880") ? m.slice(2) : m;
  return local.length === 11 ? `${local.slice(0, 5)}-${local.slice(5)}` : local;
};

export default function EcomCustomers() {
  const toast = useToast();
  const perm = usePermission("ecomcustomers");

  const [status, setStatus] = useState("PENDING");
  const [type, setType] = useState("");
  const [q, setQ] = useState("");
  const [counts, setCounts] = useState(null);
  const [open, setOpen] = useState(null);       // customer id in the drawer

  const list = usePagedList(
    ({ page, size }) => svc.listCustomers({ status, type, q, page, size }),
    [status, type, q],
  );

  const loadCounts = useCallback(async () => {
    try { setCounts(await svc.getStatusCounts()); } catch { setCounts(null); }
  }, []);
  useEffect(() => { loadCounts(); }, [loadCounts]);

  const refresh = () => { list.reload(); loadCounts(); };

  const cols = [
    { key: "fullName", label: "Customer", lead: true, sortable: true,
      render: (r) => (
        <div>
          <div style={{ fontWeight: 600 }}>{r.fullName}</div>
          <div className="ec-sub">{r.customerCode}{r.businessName ? ` · ${r.businessName}` : ""}</div>
        </div>
      ) },
    { key: "mobileNo", label: "Mobile", render: (r) => <span className="num">{phone(r.mobileNo)}</span> },
    { key: "email", label: "E-mail", advisory: true, render: (r) => r.email || <span className="hr-dash">—</span> },
    { key: "customerType", label: "Type", sortable: true,
      render: (r) => (
        <div>
          <div>{TYPE_LABEL[r.customerType] || r.customerType}</div>
          {r.requestedType && r.requestedType !== r.customerType && r.approvalStatus === "PENDING" && (
            <div className="ec-sub">asked for {TYPE_LABEL[r.requestedType] || r.requestedType}</div>
          )}
        </div>
      ) },
    { key: "regCity", label: "City", advisory: true, render: (r) => r.regCity || <span className="hr-dash">—</span> },
    { key: "registeredAt", label: "Registered", sortable: true, sortValue: (r) => r.registeredAt || "",
      render: (r) => fmtWhen(r.registeredAt) },
    { key: "approvalStatus", label: "Status", render: (r) => <Status code={r.approvalStatus} label={STATUS_LABEL[r.approvalStatus]} /> },
    { key: "act", label: "", actions: true,
      render: (r) => (
        <HrButton size="sm" icon={P.eye} onClick={(e) => { e.stopPropagation(); setOpen(r.id); }}>Open</HrButton>
      ) },
  ];

  const active = (status ? 1 : 0) + (type ? 1 : 0) + (q ? 1 : 0);

  return (
    <HrPage crumbs={CRUMBS} title="E-Commerce Customers"
            subtitle="Online registrations across every pharmacy: approve, set the pricing tier, suspend"
            icon={P.people}
            actions={<HrButton icon={P.reset} onClick={refresh}>Refresh</HrButton>}>

      <Kpis>
        {STATUSES.map((s) => (
          <Kpi key={s} label={STATUS_LABEL[s]} value={counts ? (counts[s] ?? 0) : "…"}
               warn={s === "PENDING" && counts && counts.PENDING > 0}
               onClick={() => setStatus(status === s ? "" : s)}
               tone={status === s ? "teal" : undefined} />
        ))}
      </Kpis>

      <HrFilterBar active={active} onReset={() => { setStatus("PENDING"); setType(""); setQ(""); }}>
        <Filter label="Status">
          <select value={status} onChange={(e) => setStatus(e.target.value)}>
            <option value="">All</option>
            {STATUSES.map((s) => <option key={s} value={s}>{STATUS_LABEL[s]}</option>)}
          </select>
        </Filter>
        <Filter label="Type">
          <select value={type} onChange={(e) => setType(e.target.value)}>
            <option value="">All</option>
            {TYPES.map((t) => <option key={t} value={t}>{TYPE_LABEL[t]}</option>)}
          </select>
        </Filter>
        <Filter label="Search" grow>
          <HrSearch value={q} onChange={setQ} placeholder="Name, mobile, e-mail, business or code" />
        </Filter>
      </HrFilterBar>

      {list.err
        ? <HrErrorState title="The customer list could not be read" detail={list.err} onRetry={list.reload} />
        : <HrTable cols={cols} rows={list.rows} rowKey={(r) => r.id} loading={list.busy}
                   onRowClick={(r) => setOpen(r.id)}
                   empty={status === "PENDING" ? "Nothing is waiting for approval." : "No customers match."} />}

      <HrPagination page={list.page} size={list.size} total={list.total} totalPages={list.totalPages}
                    onPage={list.setPage} onSize={list.setSize} />

      {open != null && (
        <CustomerDrawer id={open} perm={perm} toast={toast}
                        onClose={() => setOpen(null)}
                        onChanged={() => { refresh(); }} />
      )}
    </HrPage>
  );
}

/* ── One customer ─────────────────────────────────────────────────────── */

function CustomerDrawer({ id, perm, toast, onClose, onChanged }) {
  const [d, setD] = useState(null);
  const [err, setErr] = useState(null);
  const [tab, setTab] = useState("profile");
  const [modal, setModal] = useState(null);   // approve | reject | suspend | type

  const load = useCallback(async () => {
    try { setD(await svc.getCustomer(id)); setErr(null); }
    catch (e) { setErr(humanError(e, "The customer could not be read.")); }
  }, [id]);
  useEffect(() => { load(); }, [load]);

  const c = d?.customer;
  const canDecide = perm.canApprove || perm.isSuperAdmin;
  const canEdit = perm.canEdit || perm.isSuperAdmin;

  const done = (msg) => { toast(msg); load(); onChanged(); setModal(null); };

  const unlock = async () => {
    try { await svc.unlock(id); done("Sign-in lockout cleared."); }
    catch (e) { toast(humanError(e), "error"); }
  };

  const footer = c && (
    <div className="ec-row" style={{ justifyContent: "flex-end", gap: 8, flexWrap: "wrap" }}>
      {canDecide && c.approvalStatus !== "APPROVED" && (
        <HrButton variant="primary" icon={P.check} onClick={() => setModal("approve")}>
          {c.approvalStatus === "SUSPENDED" ? "Reinstate" : "Approve"}
        </HrButton>
      )}
      {canDecide && c.approvalStatus === "PENDING" && (
        <HrButton variant="danger" icon={P.x} onClick={() => setModal("reject")}>Reject</HrButton>
      )}
      {canDecide && c.approvalStatus === "APPROVED" && (
        <>
          <HrButton icon={P.edit} onClick={() => setModal("type")}>Change type</HrButton>
          {c.customerType !== "RETAILER" && (
            <HrButton icon={P.pin} onClick={() => setModal("region")}>
              {c.approvedRegionId ? "Change region" : "Approve region"}
            </HrButton>
          )}
          <HrButton variant="danger" icon={P.ban} onClick={() => setModal("suspend")}>Suspend</HrButton>
        </>
      )}
      {canEdit && d?.authStatus === "LOCKED" && (
        <HrButton icon={P.lock} onClick={unlock}>Unlock sign-in</HrButton>
      )}
    </div>
  );

  return (
    <HrDrawer title={c ? c.fullName : "Customer"}
              subtitle={c ? `${c.customerCode} · ${phone(c.mobileNo)}` : ""}
              onClose={onClose} footer={footer} width={560}>
      {err && <HrErrorState title="Could not load" detail={err} onRetry={load} />}
      {!err && !d && <div className="ec-fine">Loading…</div>}
      {d && c && (
        <>
          <div className="ec-row" style={{ gap: 8, marginBottom: 14 }}>
            <Status code={c.approvalStatus} label={STATUS_LABEL[c.approvalStatus]} />
            <span className="ec-tag">{TYPE_LABEL[c.customerType] || c.customerType} pricing</span>
            {d.authStatus === "LOCKED" && <Status code="LOCKED" label="Sign-in locked" />}
          </div>
          {c.approvalStatus === "PENDING" && c.requestedType && c.requestedType !== c.customerType && (
            <div className="ec-note" style={{ marginBottom: 12 }}>
              Asked to be approved as <b>{TYPE_LABEL[c.requestedType]}</b>. Approving grants that tier unless you choose another.
            </div>
          )}
          {c.rejectReason && c.approvalStatus === "REJECTED" && (
            <div className="ec-note" style={{ marginBottom: 12 }}>Rejected: {c.rejectReason}</div>
          )}
          {c.suspendReason && c.approvalStatus === "SUSPENDED" && (
            <div className="ec-note" style={{ marginBottom: 12 }}>Suspended: {c.suspendReason}</div>
          )}

          <HrTabs active={tab} onChange={setTab} tabs={[
            { id: "profile", label: "Profile" },
            { id: "addresses", label: `Addresses (${d.addresses?.length || 0})` },
            { id: "pharmacies", label: `Pharmacies (${d.branchLinks?.length || 0})` },
            { id: "points", label: "Points" },
            { id: "history", label: "History" },
          ]} />

          <Pane id="profile" active={tab}>
            <Facts>
              <Fact label="Mobile" value={phone(c.mobileNo)} />
              <Fact label="E-mail" value={c.email || "—"} />
              <Fact label="Business" value={c.businessName || "—"} />
              <Fact label="Trade licence" value={c.tradeLicenseNo || "—"} />
              <Fact label="Drug licence" value={c.drugLicenseNo || "—"} />
              <Fact label="Registered from" value={[c.regAddress, c.regArea, c.regCity].filter(Boolean).join(", ") || "—"} />
              <Fact label="Location" value={c.regLatitude != null ? `${c.regLatitude}, ${c.regLongitude}` : "not shared"} />
              <Fact label="Registered" value={fmtWhen(c.registeredAt)} />
              <Fact label="Last sign-in" value={d.lastLoginAt ? fmtWhen(d.lastLoginAt) : "never"} />
              <Fact label="Approved" value={c.approvedAt ? fmtWhen(c.approvedAt) : "—"} />
              <Fact label="Points" value={c.pointsBalance ?? 0} num />
              {/* [V112] Only a wholesale account has one — showing it on a
                  retailer would imply a restriction that does not apply. */}
              {c.customerType !== "RETAILER" && (
                <Fact label="Wholesale region"
                      value={d.approvedRegionName
                        || (c.approvedRegionId ? `#${c.approvedRegionId}` : "not approved yet")} />
              )}
            </Facts>
          </Pane>

          <Pane id="addresses" active={tab}>
            {(d.addresses || []).length === 0
              ? <p className="ec-fine">No delivery address yet.</p>
              : (d.addresses || []).map((a) => (
                <div key={a.id} className="ec-line">
                  <div>
                    <b>{a.label || "Address"}{a.isDefault ? " · default" : ""}</b>
                    <div>{[a.line1, a.line2, a.area, a.city, a.postcode].filter(Boolean).join(", ")}</div>
                    {(a.recipientName || a.phone) && <div className="ec-sub">{[a.recipientName, phone(a.phone)].filter(Boolean).join(" · ")}</div>}
                  </div>
                </div>
              ))}
          </Pane>

          <Pane id="pharmacies" active={tab}>
            {(d.branchLinks || []).length === 0
              ? <p className="ec-fine">Not linked to any pharmacy yet. A pharmacy customer row is made the first time they order there.</p>
              : (d.branchLinks || []).map((l) => (
                <div key={l.branchId} className="ec-line">
                  <div>
                    <b>{l.branchName}</b>
                    <div className="ec-sub">Pharmacy customer #{l.posCustomerId} · {l.linkMode === "LINKED" ? "adopted an existing row" : "created"} · {fmtWhen(l.linkedAt)}</div>
                  </div>
                </div>
              ))}
            {canDecide && c.approvalStatus === "APPROVED" && (
              <LinkPharmacy customerId={id} linked={(d.branchLinks || []).map((l) => l.branchId)}
                            onLinked={(l) => done(`Linked to ${l.branchName}.`)} toast={toast} />
            )}
          </Pane>

          <Pane id="points" active={tab}>
            <CustomerPoints customerId={id} perm={perm} toast={toast} onChanged={load} />
          </Pane>

          <Pane id="history" active={tab}>
            {(d.typeHistory || []).length > 0 && (
              <>
                <h4 style={{ marginBottom: 8 }}>Type changes</h4>
                <HrTimeline items={(d.typeHistory || []).map((h) => ({
                  title: `${TYPE_LABEL[h.oldType] || h.oldType || "—"} → ${TYPE_LABEL[h.newType] || h.newType}`,
                  when: fmtWhen(h.changedAt), who: h.changedBy, note: h.reason, tone: "teal",
                }))} />
              </>
            )}
            <h4 style={{ margin: "14px 0 8px" }}>Recent activity</h4>
            <HrTimeline items={(d.recentAudit || []).map((a) => ({
              title: a.eventType.replace(/_/g, " ").toLowerCase().replace(/^./, (ch) => ch.toUpperCase()),
              when: fmtWhen(a.actedAt),
              who: a.actorKind === "STAFF" ? a.actorId : a.actorKind === "CUSTOMER" ? "customer" : "system",
              note: a.detail,
              tone: /FAILED|LOCKED|REJECTED|SUSPENDED/.test(a.eventType) ? "clay" : /APPROVED|REINSTATED|LOGIN_OK/.test(a.eventType) ? "sage" : "grey",
            }))} empty="Nothing recorded yet." />
          </Pane>

          {modal === "approve" && (
            <DecisionModal title={c.approvalStatus === "SUSPENDED" ? "Reinstate customer" : "Approve customer"}
                           confirmLabel={c.approvalStatus === "SUSPENDED" ? "Reinstate" : "Approve"}
                           defaultType={c.requestedType || c.customerType} withType reasonOptional
                           onClose={() => setModal(null)}
                           onSubmit={async ({ reason, customerType }) => {
                             await svc.approve(id, { reason, customerType });
                             done(`Approved as ${TYPE_LABEL[customerType] || customerType}.`);
                           }} />
          )}
          {modal === "reject" && (
            <DecisionModal title="Reject registration" confirmLabel="Reject" danger
                           onClose={() => setModal(null)}
                           onSubmit={async ({ reason }) => { await svc.reject(id, reason); done("Registration rejected."); }} />
          )}
          {modal === "suspend" && (
            <DecisionModal title="Suspend customer" confirmLabel="Suspend" danger
                           hint="They stay signed in to see the notice but cannot order. Every session is signed out."
                           onClose={() => setModal(null)}
                           onSubmit={async ({ reason }) => { await svc.suspend(id, reason); done("Customer suspended."); }} />
          )}
          {modal === "region" && (
            <RegionModal current={c.approvedRegionId}
                         onClose={() => setModal(null)}
                         onSubmit={async ({ regionId, reason }) => {
                           await svc.setRegion(id, regionId, reason);
                           done(regionId ? "Wholesale region approved." : "Wholesale region cleared.");
                         }} />
          )}
          {modal === "type" && (
            <DecisionModal title="Change customer type" confirmLabel="Change type" withType
                           defaultType={c.customerType}
                           hint="Applies to every pharmacy the customer has ordered from. Past orders keep their prices."
                           onClose={() => setModal(null)}
                           onSubmit={async ({ reason, customerType }) => {
                             await svc.setType(id, customerType, reason);
                             done(`Type changed to ${TYPE_LABEL[customerType] || customerType}.`);
                           }} />
          )}
        </>
      )}
    </HrDrawer>
  );
}

function DecisionModal({ title, confirmLabel, danger = false, withType = false, defaultType = "RETAILER",
                         reasonOptional = false, hint, onClose, onSubmit }) {
  const [reason, setReason] = useState("");
  const [customerType, setCustomerType] = useState(defaultType || "RETAILER");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const toast = useToast();

  const submit = async () => {
    if (!reasonOptional && !reason.trim()) { setError("A reason is required."); return; }
    setBusy(true);
    try { await onSubmit({ reason: reason.trim() || undefined, customerType }); }
    catch (e) { setError(humanError(e)); toast(humanError(e), "error"); }
    finally { setBusy(false); }
  };

  return (
    <Modal title={title} onClose={onClose} width={440}
           footer={
             <>
               <HrButton onClick={onClose}>Cancel</HrButton>
               <HrButton variant={danger ? "danger" : "primary"} busy={busy} onClick={submit}>{confirmLabel}</HrButton>
             </>
           }>
      {hint && <p className="ec-fine" style={{ marginBottom: 12 }}>{hint}</p>}
      {withType && (
        <Field label="Customer type">
          <select value={customerType} onChange={(e) => setCustomerType(e.target.value)} autoFocus>
            {TYPES.map((t) => <option key={t} value={t}>{TYPE_LABEL[t]}</option>)}
          </select>
          <p className="ec-fine" style={{ marginTop: 6 }}>
            Retailer buys pieces only. Wholesaler and Special buy box, strip and piece — Special at the special price list.
          </p>
        </Field>
      )}
      <Field label={reasonOptional ? "Note (optional)" : "Reason"} error={error}>
        <textarea rows={3} value={reason} onChange={(e) => { setReason(e.target.value); setError(null); }}
                  placeholder={reasonOptional ? "Anything worth recording" : "Written to the customer's history"} />
      </Field>
    </Modal>
  );
}

/* Pair the customer with a pharmacy ahead of their first order there — the
   same bridge checkout will use, made visible so it can be tried and seen. */
function LinkPharmacy({ customerId, linked, onLinked, toast }) {
  const [pharmacies, setPharmacies] = useState(null);
  const [pick, setPick] = useState("");
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    svc.listPharmacies().then(setPharmacies).catch(() => setPharmacies([]));
  }, []);
  const options = (pharmacies || []).filter((p) => !linked.includes(p.branchId));
  const link = async () => {
    if (!pick) return;
    setBusy(true);
    try { onLinked(await svc.linkBranch(customerId, Number(pick))); setPick(""); }
    catch (e) { toast(humanError(e), "error"); }
    finally { setBusy(false); }
  };
  return (
    <div className="ec-row" style={{ marginTop: 12 }}>
      <select value={pick} onChange={(e) => setPick(e.target.value)} disabled={!pharmacies || busy} style={{ flex: 1, minWidth: 200 }}>
        <option value="">{pharmacies ? "Link to a pharmacy…" : "Loading pharmacies…"}</option>
        {options.map((p) => <option key={p.branchId} value={p.branchId}>{p.branchName}</option>)}
      </select>
      <HrButton size="sm" variant="primary" busy={busy} disabled={!pick} onClick={link}>Link now</HrButton>
    </div>
  );
}

/* ── A customer's loyalty points ──────────────────────────────────────── */

/**
 * The balance and every movement behind it (docs/ecommerce Phase 13).
 *
 * <p>A balance on its own is a figure a customer cannot argue with, so staff
 * are shown the same statement the customer sees. An adjustment by hand is a
 * ledger row like any other — there is no way to type a balance, and the
 * reason is not optional because somebody will have to explain it later.
 */
function CustomerPoints({ customerId, perm, toast, onChanged }) {
  const [d, setD] = useState(null);
  const [err, setErr] = useState(null);
  const [amount, setAmount] = useState("");
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);

  const canAdjust = perm.canApprove || perm.isSuperAdmin;

  const load = useCallback(async () => {
    try { setD(await points.customerPoints(customerId, 25)); setErr(null); }
    catch (e) { setErr(humanError(e, "The points could not be read.")); }
  }, [customerId]);
  useEffect(() => { load(); }, [load]);

  const apply = async () => {
    setBusy(true);
    try {
      const res = await points.adjustCustomer(customerId, amount, reason);
      toast(`Balance is now ${res.balance}`);
      setAmount(""); setReason("");
      load(); onChanged?.();
    } catch (e) { toast(humanError(e), "error"); }
    finally { setBusy(false); }
  };

  const TONE = { EARN: "sage", REDEEM: "teal", EXPIRE: "grey", ADJUST: "amber" };

  if (err) return <HrErrorState title="Could not load" detail={err} onRetry={load} />;
  if (!d) return <p className="ec-fine">Loading…</p>;

  return (
    <>
      <div className="ec-row" style={{ alignItems: "baseline", gap: 10, marginBottom: 4 }}>
        <span className="hr-num" style={{ fontSize: 26, fontWeight: 700 }}>{Number(d.balance ?? 0)}</span>
        <span className="ec-sub">points</span>
      </div>
      <p className="ec-fine" style={{ marginBottom: 12 }}>{d.note}</p>

      {canAdjust && (
        <div className="ec-note" style={{ marginBottom: 14 }}>
          <div style={{ marginBottom: 8, fontWeight: 600 }}>Move the balance by hand</div>
          <div style={{ display: "grid", gridTemplateColumns: "110px 1fr", gap: 10 }}>
            <Field label="Points">
              <input inputMode="numeric" value={amount} onChange={(e) => setAmount(e.target.value)}
                     placeholder="25 or -25" />
            </Field>
            <Field label="Reason — it goes on the ledger row">
              <input value={reason} onChange={(e) => setReason(e.target.value)}
                     placeholder="Goodwill after a late delivery" />
            </Field>
          </div>
          <div style={{ marginTop: 10 }}>
            <HrButton size="sm" variant="primary" icon={P.check} busy={busy}
                      disabled={!amount || !reason.trim()} onClick={apply}>Apply</HrButton>
          </div>
        </div>
      )}

      <HrTimeline items={(d.entries || []).map((e) => ({
        title: `${Number(e.points) > 0 ? "+" : ""}${Number(e.points)} points → ${Number(e.balanceAfter)}`,
        when: fmtWhen(e.at),
        who: e.orderNo || undefined,
        note: e.note,
        tone: TONE[e.type] || "grey",
      }))} empty="Nothing yet. Points are paid when an order arrives." />
    </>
  );
}

/* ── The region a wholesale account may buy in ────────────────────────── */

/**
 * Approving, correcting or clearing it.
 *
 * <p>Clearing is offered deliberately. "We need to check this again" is a real
 * answer, and it has to be sayable without suspending the account — a cleared
 * region stops wholesale ordering and tells the customer what to complete,
 * which is kinder than a suspension and easier to undo.
 */
function RegionModal({ current, onClose, onSubmit }) {
  const [regions, setRegions] = useState([]);
  const [regionId, setRegionId] = useState(current ? String(current) : "");
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const toast = useToast();

  useEffect(() => { getRegions().then((r) => setRegions(r || [])).catch(() => setRegions([])); }, []);

  const submit = async () => {
    setBusy(true);
    try { await onSubmit({ regionId: regionId ? Number(regionId) : null, reason: reason.trim() }); }
    catch (e) { toast(humanError(e), "error"); }
    finally { setBusy(false); }
  };

  return (
    <Modal title="Wholesale region" width={460} onClose={onClose}
           footer={<>
             <HrButton onClick={onClose} disabled={busy}>Cancel</HrButton>
             <HrButton variant="primary" busy={busy} onClick={submit}>
               {regionId ? "Approve this region" : "Clear the region"}
             </HrButton>
           </>}>
      <div style={{ display: "grid", gap: 12 }}>
        <Field label="Region">
          <select value={regionId} onChange={(e) => setRegionId(e.target.value)}>
            <option value="">— not approved —</option>
            {regions.map((r) => (
              <option key={r.id ?? r.pidRegion} value={r.id ?? r.pidRegion}>{r.name ?? r.regionName}</option>
            ))}
          </select>
        </Field>
        <Field label="Reason (recorded with the change)">
          <input value={reason} onChange={(e) => setReason(e.target.value)}
                 placeholder="Trade licence checked, address verified…" />
        </Field>
        <p className="ec-fine">
          This customer will only be offered wholesale pharmacies in the region chosen here.
          Their cart is not emptied if the region changes — checkout will say plainly if the
          pharmacy they picked is no longer one they may buy from.
        </p>
      </div>
    </Modal>
  );
}

