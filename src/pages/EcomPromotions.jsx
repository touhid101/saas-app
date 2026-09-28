/**
 * E-Commerce Promotions — the two things a pharmacy can give a customer that
 * the ERP never had: a coupon code, and loyalty points.
 *
 * docs/ecommerce Phase 12 and Phase 13. The price list, the standing trade
 * discount (Sales Discount%) and the manual invoice discount all exist already,
 * and none of them is something a customer can be given and choose to spend.
 *
 * Coupons are a code typed once. Points are a balance the customer keeps, and
 * the balance is the ledger's sum — never a figure anybody types.
 *
 * Every rule on this screen is enforced again on the server, including the ones
 * that cost money: a pharmacy may only promote at its own branch, only a
 * platform administrator may promote everywhere, and there is no default
 * exchange rate for a point.
 */
import React, { useCallback, useEffect, useState } from "react";
import { Field } from "../components/UI.jsx";
import { useToast } from "../hooks/useToast.js";
import { usePermission } from "../lib/usePermission.js";
import {
  HrPage, Kpis, Kpi, HrTable, Status, Filter, HrFilterBar, HrSearch, HrButton, HrTabBar,
  HrPagination, HrErrorState, HrDrawer, Facts, Fact, humanError, fmtWhen, usePagedList, P,
} from "../components/HrKit.jsx";
import svc, { DISCOUNT_TYPES, TIERS } from "../services/ecomCouponService.js";
import points from "../services/ecomPointsService.js";
import { getBranches } from "../services/masterDataService.js";

const CRUMBS = [{ label: "E-Commerce" }, { label: "Promotions" }];

const money = (v) => (v == null ? "—" : "৳ " + Number(v).toLocaleString("en-IN", { minimumFractionDigits: 2, maximumFractionDigits: 2 }));
const plain = (v) => (v == null ? "—" : Number(v).toLocaleString("en-IN", { maximumFractionDigits: 2 }));

export default function EcomPromotions() {
  const toast = useToast();
  const perm = usePermission("ecompromotions");

  const [tab, setTab] = useState("coupons");
  const [open, setOpen] = useState(null);        // coupon id, or "new"
  const [openRule, setOpenRule] = useState(null); // points rule id, or "new"

  const canCreate = perm.canCreate || perm.isSuperAdmin;

  return (
    <HrPage crumbs={CRUMBS} title="E-Commerce Promotions"
            subtitle="What a customer can be given: a code to type at checkout, or points that build up as they buy"
            icon={P.wallet}
            actions={canCreate && (
              tab === "coupons"
                ? <HrButton variant="primary" icon={P.plus} onClick={() => setOpen("new")}>New coupon</HrButton>
                : <HrButton variant="primary" icon={P.plus} onClick={() => setOpenRule("new")}>New points rule</HrButton>
            )}>

      <HrTabBar active={tab} onChange={setTab} tabs={[
        { id: "coupons", label: "Coupons", icon: P.receipt },
        { id: "points", label: "Loyalty points", icon: P.star },
      ]} />

      {tab === "coupons"
        ? <CouponsTab perm={perm} toast={toast} open={open} setOpen={setOpen} />
        : <PointsTab perm={perm} toast={toast} open={openRule} setOpen={setOpenRule} />}
    </HrPage>
  );
}

/* ══════════════════════════════════════════════════════════════════════════
   COUPONS
   ══════════════════════════════════════════════════════════════════════════ */

function CouponsTab({ perm, toast, open, setOpen }) {
  const [active, setActive] = useState("");
  const [q, setQ] = useState("");

  const list = usePagedList(({ page, size }) => svc.listCoupons({ active, q, page, size }), [active, q]);

  const rows = list.rows || [];
  const running = rows.filter((c) => c.state === "Running").length;
  const spent = rows.reduce((t, c) => t + Number(c.spent || 0), 0);
  const used = rows.reduce((t, c) => t + Number(c.timesUsed || 0), 0);

  const cols = [
    { key: "code", label: "Code", lead: true,
      render: (r) => (
        <div>
          <div className="mono" style={{ fontWeight: 700 }}>{r.code}</div>
          <div className="ec-sub">{r.name}</div>
        </div>
      ) },
    { key: "summary", label: "What it does", render: (r) => <span style={{ fontSize: 12.5 }}>{r.summary}</span> },
    { key: "window", label: "When", advisory: true,
      render: (r) => (
        <div style={{ fontSize: 12 }}>
          {r.startsAt ? fmtWhen(r.startsAt) : "any time"}
          <div className="ec-sub">{r.endsAt ? `until ${fmtWhen(r.endsAt)}` : "no end date"}</div>
        </div>
      ) },
    { key: "timesUsed", label: "Used",
      render: (r) => (
        <div>
          <span className="mono">{r.timesUsed}{r.usageLimitTotal ? ` / ${r.usageLimitTotal}` : ""}</span>
          <div className="ec-sub">{money(r.spent)} given away</div>
        </div>
      ) },
    { key: "state", label: "Status", render: (r) => <Status code={r.state} label={r.state} /> },
    { key: "act", label: "", actions: true,
      render: (r) => <HrButton size="sm" icon={P.eye} onClick={(e) => { e.stopPropagation(); setOpen(r.id); }}>Open</HrButton> },
  ];

  return (
    <>
      <Kpis>
        <Kpi label="Running now" value={running} tone={running ? "teal" : undefined} />
        <Kpi label="Coupons" value={list.total ?? rows.length} />
        <Kpi label="Times used" value={used} />
        <Kpi label="Given away" value={money(spent)} note="on this page" />
      </Kpis>

      <HrFilterBar active={(active ? 1 : 0) + (q ? 1 : 0)} onReset={() => { setActive(""); setQ(""); }}>
        <Filter label="Status">
          <select value={active} onChange={(e) => setActive(e.target.value)}>
            <option value="">All</option>
            <option value="Y">Switched on</option>
            <option value="N">Stopped</option>
          </select>
        </Filter>
        <Filter label="Search" grow>
          <HrSearch value={q} onChange={setQ} placeholder="Code or name" />
        </Filter>
      </HrFilterBar>

      {list.err
        ? <HrErrorState title="The coupons could not be read" detail={list.err} onRetry={list.reload} />
        : <HrTable cols={cols} rows={rows} rowKey={(r) => r.id} loading={list.busy}
                   onRowClick={(r) => setOpen(r.id)}
                   empty="No coupons yet. A coupon is a code a customer types at checkout — the price list and the standing trade discount are set elsewhere." />}

      <HrPagination page={list.page} size={list.size} total={list.total} totalPages={list.totalPages}
                    onPage={list.setPage} onSize={list.setSize} />

      {open && (
        <CouponDrawer id={open} perm={perm} toast={toast}
                      onClose={() => setOpen(null)}
                      onChanged={() => { list.reload(); }} />
      )}
    </>
  );
}

/* ── One coupon ───────────────────────────────────────────────────────── */

function CouponDrawer({ id, perm, toast, onClose, onChanged }) {
  const creating = id === "new";
  const [d, setD] = useState(null);
  const [form, setForm] = useState(creating ? svc.emptyCoupon() : null);
  const [branches, setBranches] = useState([]);
  const [err, setErr] = useState(null);
  const [busy, setBusy] = useState(false);
  const [editing, setEditing] = useState(creating);

  const canEdit = perm.canEdit || perm.isSuperAdmin;
  const canDecide = perm.canApprove || perm.isSuperAdmin;

  const load = useCallback(async () => {
    if (creating) return;
    try {
      const detail = await svc.getCoupon(id);
      setD(detail);
      const c = detail.coupon;
      setForm({
        ...svc.emptyCoupon(), ...c,
        discountValue: c.discountValue ?? "",
        maxDiscountAmount: c.maxDiscountAmount ?? "",
        minOrderAmount: c.minOrderAmount ?? "",
        usageLimitTotal: c.usageLimitTotal ?? "",
        usageLimitPerCustomer: c.usageLimitPerCustomer ?? "",
        startsAt: c.startsAt ? c.startsAt.slice(0, 10) : "",
        endsAt: c.endsAt ? c.endsAt.slice(0, 10) : "",
        firstOrderOnly: c.firstOrderOnly ? "Y" : "N",
        isActive: c.active ? "Y" : "N",
        branchIds: (detail.branches || []).map((b) => b.branchId),
      });
      setErr(null);
    } catch (e) { setErr(humanError(e, "The coupon could not be read.")); }
  }, [id, creating]);

  useEffect(() => { load(); }, [load]);
  useEffect(() => { getBranches().then((b) => setBranches(b || [])).catch(() => setBranches([])); }, []);

  const set = (k) => (e) => setForm((f) => ({ ...f, [k]: e.target.value }));
  const toggleBranch = (branchId) => setForm((f) => ({
    ...f,
    branchIds: f.branchIds.includes(branchId)
      ? f.branchIds.filter((b) => b !== branchId)
      : [...f.branchIds, branchId],
  }));

  const save = async () => {
    setBusy(true);
    try {
      const payload = svc.toForm(form);
      const saved = creating ? await svc.createCoupon(payload) : await svc.updateCoupon(id, payload);
      toast(creating ? `Coupon ${saved.code} created` : `Coupon ${saved.code} saved`);
      onChanged();
      if (creating) onClose(); else { setEditing(false); load(); }
    } catch (e) { toast(humanError(e), "error"); }
    finally { setBusy(false); }
  };

  const flip = async (on) => {
    setBusy(true);
    try {
      await svc.setActive(id, on);
      toast(on ? "Coupon started" : "Coupon stopped");
      onChanged(); load();
    } catch (e) { toast(humanError(e), "error"); }
    finally { setBusy(false); }
  };

  const c = d?.coupon;
  const footer = (
    <div className="ec-row" style={{ justifyContent: "flex-end", gap: 8, flexWrap: "wrap" }}>
      {!creating && canDecide && c && (
        <HrButton variant={c.active ? "danger" : undefined} icon={c.active ? P.ban : P.check} disabled={busy}
                  onClick={() => flip(!c.active)}>
          {c.active ? "Stop this coupon" : "Start it again"}
        </HrButton>
      )}
      {!creating && canEdit && !editing && <HrButton icon={P.edit} onClick={() => setEditing(true)}>Change the rules</HrButton>}
      {editing && <HrButton variant="primary" icon={P.check} disabled={busy} onClick={save}>
        {busy ? "Saving…" : creating ? "Create the coupon" : "Save"}
      </HrButton>}
    </div>
  );

  return (
    <HrDrawer title={creating ? "New coupon" : (c?.code || "Coupon")}
              subtitle={creating ? "A code customers type at checkout" : c?.summary}
              onClose={onClose} footer={footer} width={620}>
      {err && <HrErrorState title="Could not load" detail={err} onRetry={load} />}
      {!form && !err && <div className="ec-fine">Loading…</div>}

      {form && !editing && c && (
        <>
          <div className="ec-row" style={{ gap: 8, marginBottom: 14 }}>
            <Status code={c.state} label={c.state} />
            {c.firstOrderOnly && <span className="ec-tag">First order only</span>}
            {c.customerTypeScope !== "ALL" && <span className="ec-tag">{c.customerTypeScope.toLowerCase()} only</span>}
          </div>
          <Facts>
            <Fact label="Worth" value={c.discountType === "PERCENT" ? `${c.discountValue}%${c.maxDiscountAmount ? `, up to ${money(c.maxDiscountAmount)}` : ""}`
              : c.discountType === "FIXED" ? money(c.discountValue) : "Free delivery"} />
            <Fact label="Minimum order" value={c.minOrderAmount ? money(c.minOrderAmount) : "none"} />
            <Fact label="Where" value={c.branchScope === "ALL_BRANCHES" ? "Every pharmacy"
              : (d.branches || []).map((b) => b.branchName).join(", ") || "—"} />
            <Fact label="Opens" value={c.startsAt ? fmtWhen(c.startsAt) : "immediately"} />
            <Fact label="Closes" value={c.endsAt ? fmtWhen(c.endsAt) : "no end date"} />
            <Fact label="Limit" value={[c.usageLimitTotal ? `${c.usageLimitTotal} in total` : "no total limit",
              c.usageLimitPerCustomer ? `${c.usageLimitPerCustomer} per customer` : "no per-customer limit"].join(" · ")} />
            <Fact label="Used" value={`${c.timesUsed} time${c.timesUsed === 1 ? "" : "s"}`} num />
            <Fact label="Given away" value={money(c.spent)} num />
          </Facts>
          {c.description && <p className="ec-fine" style={{ marginTop: 12 }}>{c.description}</p>}
          <p className="ec-fine" style={{ marginTop: 14 }}>
            A cancelled order gives its use back, and the record of it stays. Stopping a coupon
            never deletes it: orders paid with it point at it.
          </p>
        </>
      )}

      {form && editing && (
        <div style={{ display: "grid", gap: 12 }}>
          {creating && (
            <Field label="Code — what the customer types">
              <input value={form.code} onChange={set("code")} placeholder="SAVE100" autoFocus
                     style={{ textTransform: "uppercase" }} />
            </Field>
          )}
          <Field label="Name — what it is called on the customer's order">
            <input value={form.name} onChange={set("name")} placeholder="100 off your first order" />
          </Field>

          <Field label="What it gives">
            <select value={form.discountType} onChange={set("discountType")}>
              {DISCOUNT_TYPES.map((t) => <option key={t.value} value={t.value}>{t.label}</option>)}
            </select>
          </Field>
          {form.discountType !== "FREE_DELIVERY" && (
            <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 12 }}>
              <Field label={form.discountType === "PERCENT" ? "Percentage" : "Amount (৳)"}>
                <input inputMode="decimal" value={form.discountValue} onChange={set("discountValue")} />
              </Field>
              {form.discountType === "PERCENT" && (
                <Field label="Never more than (৳)">
                  <input inputMode="decimal" value={form.maxDiscountAmount} onChange={set("maxDiscountAmount")}
                         placeholder="leave empty for no ceiling" />
                </Field>
              )}
            </div>
          )}

          <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 12 }}>
            <Field label="Minimum order (৳)">
              <input inputMode="decimal" value={form.minOrderAmount} onChange={set("minOrderAmount")} placeholder="none" />
            </Field>
            <Field label="Who may use it">
              <select value={form.customerTypeScope} onChange={set("customerTypeScope")}>
                {TIERS.map((t) => <option key={t.value} value={t.value}>{t.label}</option>)}
              </select>
            </Field>
          </div>

          <Field label="Where it may be used">
            <select value={form.branchScope} onChange={set("branchScope")}>
              <option value="">Choose…</option>
              <option value="SELECTED_BRANCHES">Only the pharmacies I choose</option>
              <option value="ALL_BRANCHES">Every pharmacy on the platform</option>
            </select>
          </Field>
          {form.branchScope === "SELECTED_BRANCHES" && (
            <div className="ec-note">
              <div style={{ marginBottom: 6, fontWeight: 600 }}>Pharmacies</div>
              <div style={{ display: "flex", flexWrap: "wrap", gap: 8 }}>
                {branches.map((b) => (
                  <label key={b.id} style={{ display: "inline-flex", alignItems: "center", gap: 6, fontSize: 12.5 }}>
                    <input type="checkbox" checked={form.branchIds.includes(b.id)} onChange={() => toggleBranch(b.id)} />
                    {b.name}
                  </label>
                ))}
              </div>
              <div className="ec-sub" style={{ marginTop: 6 }}>
                You can only offer a coupon at your own pharmacy; the server checks this again.
              </div>
            </div>
          )}

          <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 12 }}>
            <Field label="Opens on"><input type="date" value={form.startsAt} onChange={set("startsAt")} /></Field>
            <Field label="Closes after"><input type="date" value={form.endsAt} onChange={set("endsAt")} /></Field>
          </div>

          <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 12 }}>
            <Field label="Total uses"><input inputMode="numeric" value={form.usageLimitTotal} onChange={set("usageLimitTotal")} placeholder="unlimited" /></Field>
            <Field label="Uses per customer"><input inputMode="numeric" value={form.usageLimitPerCustomer} onChange={set("usageLimitPerCustomer")} placeholder="unlimited" /></Field>
          </div>

          <label className="ec-row" style={{ gap: 8, fontSize: 13 }}>
            <input type="checkbox" checked={form.firstOrderOnly === "Y"}
                   onChange={(e) => setForm((f) => ({ ...f, firstOrderOnly: e.target.checked ? "Y" : "N" }))} />
            Only on a customer's first order
          </label>

          {!creating && (
            <HrButton onClick={() => { setEditing(false); load(); }}>Cancel the changes</HrButton>
          )}
        </div>
      )}
    </HrDrawer>
  );
}

/* ══════════════════════════════════════════════════════════════════════════
   LOYALTY POINTS
   ══════════════════════════════════════════════════════════════════════════ */

function PointsTab({ perm, toast, open, setOpen }) {
  const [active, setActive] = useState("");
  const [q, setQ] = useState("");

  const list = usePagedList(({ page, size }) => points.listRules({ active, q, page, size }), [active, q]);

  const rows = list.rows || [];
  const live = rows.filter((r) => r.state === "Running");
  const rates = [...new Set(live.map((r) => Number(r.pointValue)))];

  const cols = [
    { key: "name", label: "Scheme", lead: true,
      render: (r) => (
        <div>
          <div style={{ fontWeight: 700 }}>{r.name}</div>
          {r.description && <div className="ec-sub">{r.description}</div>}
        </div>
      ) },
    { key: "earn", label: "Earning",
      render: (r) => (
        <div style={{ fontSize: 12.5 }}>
          {plain(r.earnPointsPer100)} per ৳100
          <div className="ec-sub">{r.minOrderToEarn ? `on orders over ${money(r.minOrderToEarn)}` : "on any order"}</div>
        </div>
      ) },
    { key: "spend", label: "Spending",
      render: (r) => (
        <div style={{ fontSize: 12.5 }}>
          1 point = {money(r.pointValue)}
          <div className="ec-sub">
            {r.maxRedeemPercent ? `up to ${plain(r.maxRedeemPercent)}% of a bill` : "no ceiling"}
            {r.minPointsToRedeem ? `, from ${r.minPointsToRedeem}` : ""}
          </div>
        </div>
      ) },
    { key: "where", label: "Where", advisory: true,
      render: (r) => (
        <span style={{ fontSize: 12 }}>
          {r.branchScope === "ALL_BRANCHES" ? "Every pharmacy" : (r.branchNames || []).join(", ") || "—"}
        </span>
      ) },
    { key: "state", label: "Status", render: (r) => <Status code={r.state} label={r.state} /> },
    { key: "act", label: "", actions: true,
      render: (r) => <HrButton size="sm" icon={P.eye} onClick={(e) => { e.stopPropagation(); setOpen(r.id); }}>Open</HrButton> },
  ];

  return (
    <>
      <Kpis>
        <Kpi label="Running now" value={live.length} tone={live.length ? "teal" : undefined} />
        <Kpi label="Schemes" value={list.total ?? rows.length} />
        <Kpi label="A point is worth"
             value={rates.length === 0 ? "—" : rates.length === 1 ? money(rates[0]) : "varies"}
             note={rates.length > 1 ? "different rates are running" : undefined} />
        <Kpi label="Points expire" value={live.some((r) => r.expiryMonths) ? "yes" : live.length ? "never" : "—"}
             note={live.find((r) => r.expiryMonths) ? `after ${live.find((r) => r.expiryMonths).expiryMonths} months` : undefined} />
      </Kpis>

      <HrFilterBar active={(active ? 1 : 0) + (q ? 1 : 0)} onReset={() => { setActive(""); setQ(""); }}>
        <Filter label="Status">
          <select value={active} onChange={(e) => setActive(e.target.value)}>
            <option value="">All</option>
            <option value="Y">Switched on</option>
            <option value="N">Stopped</option>
          </select>
        </Filter>
        <Filter label="Search" grow>
          <HrSearch value={q} onChange={setQ} placeholder="Scheme name" />
        </Filter>
      </HrFilterBar>

      {list.err
        ? <HrErrorState title="The points rules could not be read" detail={list.err} onRetry={list.reload} />
        : <HrTable cols={cols} rows={rows} rowKey={(r) => r.id} loading={list.busy}
                   onRowClick={(r) => setOpen(r.id)}
                   empty="No points scheme yet. Until one is written here nothing earns and nothing can be redeemed — what a point is worth is your decision, and there is no default." />}

      <HrPagination page={list.page} size={list.size} total={list.total} totalPages={list.totalPages}
                    onPage={list.setPage} onSize={list.setSize} />

      <p className="ec-fine" style={{ marginTop: 14 }}>
        Points are paid when an order arrives, not when it is placed, and a cancelled order
        gives back whatever it spent. A customer's own balance is on their record, under
        E-Commerce Customers.
      </p>

      {open && (
        <RuleDrawer id={open} perm={perm} toast={toast}
                    onClose={() => setOpen(null)}
                    onChanged={() => { list.reload(); }} />
      )}
    </>
  );
}

/* ── One points scheme ────────────────────────────────────────────────── */

function RuleDrawer({ id, perm, toast, onClose, onChanged }) {
  const creating = id === "new";
  const [r, setR] = useState(null);
  const [form, setForm] = useState(creating ? points.emptyRule() : null);
  const [branches, setBranches] = useState([]);
  const [err, setErr] = useState(null);
  const [busy, setBusy] = useState(false);
  const [editing, setEditing] = useState(creating);

  const canEdit = perm.canEdit || perm.isSuperAdmin;
  const canDecide = perm.canApprove || perm.isSuperAdmin;

  const load = useCallback(async () => {
    if (creating) return;
    try {
      const rule = await points.getRule(id);
      setR(rule);
      setForm({
        ...points.emptyRule(), ...rule,
        earnPointsPer100: rule.earnPointsPer100 ?? "",
        minOrderToEarn: rule.minOrderToEarn ?? "",
        pointValue: rule.pointValue ?? "",
        minPointsToRedeem: rule.minPointsToRedeem ?? "",
        maxRedeemPercent: rule.maxRedeemPercent ?? "",
        expiryMonths: rule.expiryMonths ?? "",
        startsAt: rule.startsAt ? rule.startsAt.slice(0, 10) : "",
        endsAt: rule.endsAt ? rule.endsAt.slice(0, 10) : "",
        isActive: rule.active ? "Y" : "N",
        branchIds: rule.branchIds || [],
      });
      setErr(null);
    } catch (e) { setErr(humanError(e, "The points rule could not be read.")); }
  }, [id, creating]);

  useEffect(() => { load(); }, [load]);
  useEffect(() => { getBranches().then((b) => setBranches(b || [])).catch(() => setBranches([])); }, []);

  const set = (k) => (e) => setForm((f) => ({ ...f, [k]: e.target.value }));
  const toggleBranch = (branchId) => setForm((f) => ({
    ...f,
    branchIds: f.branchIds.includes(branchId)
      ? f.branchIds.filter((b) => b !== branchId)
      : [...f.branchIds, branchId],
  }));

  const save = async () => {
    setBusy(true);
    try {
      const payload = points.toForm(form);
      const saved = creating ? await points.createRule(payload) : await points.updateRule(id, payload);
      toast(creating ? `${saved.name} created` : `${saved.name} saved`);
      onChanged();
      if (creating) onClose(); else { setEditing(false); load(); }
    } catch (e) { toast(humanError(e), "error"); }
    finally { setBusy(false); }
  };

  const flip = async (on) => {
    setBusy(true);
    try {
      await points.setRuleActive(id, on);
      toast(on ? "Scheme started" : "Scheme stopped");
      onChanged(); load();
    } catch (e) { toast(humanError(e), "error"); }
    finally { setBusy(false); }
  };

  const footer = (
    <div className="ec-row" style={{ justifyContent: "flex-end", gap: 8, flexWrap: "wrap" }}>
      {!creating && canDecide && r && (
        <HrButton variant={r.active ? "danger" : undefined} icon={r.active ? P.ban : P.check} disabled={busy}
                  onClick={() => flip(!r.active)}>
          {r.active ? "Stop this scheme" : "Start it again"}
        </HrButton>
      )}
      {!creating && canEdit && !editing && <HrButton icon={P.edit} onClick={() => setEditing(true)}>Change the rules</HrButton>}
      {editing && <HrButton variant="primary" icon={P.check} disabled={busy} onClick={save}>
        {busy ? "Saving…" : creating ? "Create the scheme" : "Save"}
      </HrButton>}
    </div>
  );

  return (
    <HrDrawer title={creating ? "New points scheme" : (r?.name || "Points scheme")}
              subtitle={creating ? "What a point is worth, and how one is earned" : r?.summary}
              onClose={onClose} footer={footer} width={620}>
      {err && <HrErrorState title="Could not load" detail={err} onRetry={load} />}
      {!form && !err && <div className="ec-fine">Loading…</div>}

      {form && !editing && r && (
        <>
          <div className="ec-row" style={{ gap: 8, marginBottom: 14 }}>
            <Status code={r.state} label={r.state} />
            {r.customerTypeScope !== "ALL" && <span className="ec-tag">{r.customerTypeScope.toLowerCase()} only</span>}
          </div>
          <Facts>
            <Fact label="Earned" value={`${plain(r.earnPointsPer100)} point${Number(r.earnPointsPer100) === 1 ? "" : "s"} per ৳100 spent`} />
            <Fact label="Minimum order to earn" value={r.minOrderToEarn ? money(r.minOrderToEarn) : "none"} />
            <Fact label="One point is worth" value={money(r.pointValue)} num />
            <Fact label="Points may pay for" value={r.maxRedeemPercent ? `${plain(r.maxRedeemPercent)}% of an order` : "the whole order"} />
            <Fact label="Fewest that can be spent" value={r.minPointsToRedeem ? `${r.minPointsToRedeem} points` : "any number"} />
            <Fact label="Unused points expire" value={r.expiryMonths ? `after ${r.expiryMonths} months` : "never"} />
            <Fact label="Where" value={r.branchScope === "ALL_BRANCHES" ? "Every pharmacy" : (r.branchNames || []).join(", ") || "—"} />
            <Fact label="Opens" value={r.startsAt ? fmtWhen(r.startsAt) : "immediately"} />
            <Fact label="Closes" value={r.endsAt ? fmtWhen(r.endsAt) : "no end date"} />
          </Facts>
          <p className="ec-fine" style={{ marginTop: 14 }}>
            Points are paid when the order arrives, so an order that never turns up never earns.
            Stopping this scheme leaves every balance exactly where it is — it only stops points
            being earned or spent at these pharmacies.
          </p>
        </>
      )}

      {form && editing && (
        <div style={{ display: "grid", gap: 12 }}>
          <Field label="Name — what the scheme is called">
            <input value={form.name} onChange={set("name")} placeholder="Friends Points" autoFocus={creating} />
          </Field>
          <Field label="Description — for your own staff">
            <input value={form.description} onChange={set("description")} placeholder="A point for every 100 spent" />
          </Field>

          <div className="ec-note">
            <div style={{ marginBottom: 8, fontWeight: 600 }}>Earning</div>
            <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 12 }}>
              <Field label="Points per ৳100 spent">
                <input inputMode="decimal" value={form.earnPointsPer100} onChange={set("earnPointsPer100")} placeholder="1" />
              </Field>
              <Field label="Minimum order to earn (৳)">
                <input inputMode="decimal" value={form.minOrderToEarn} onChange={set("minOrderToEarn")} placeholder="none" />
              </Field>
            </div>
          </div>

          <div className="ec-note">
            <div style={{ marginBottom: 8, fontWeight: 600 }}>Spending</div>
            <Field label="What one point takes off a bill (৳)">
              <input inputMode="decimal" value={form.pointValue} onChange={set("pointValue")} placeholder="there is no default" />
            </Field>
            <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 12, marginTop: 12 }}>
              <Field label="Fewest points that can be spent">
                <input inputMode="numeric" value={form.minPointsToRedeem} onChange={set("minPointsToRedeem")} placeholder="any number" />
              </Field>
              <Field label="Most of a bill they may pay (%)">
                <input inputMode="decimal" value={form.maxRedeemPercent} onChange={set("maxRedeemPercent")} placeholder="100" />
              </Field>
            </div>
            <div className="ec-sub" style={{ marginTop: 6 }}>
              This is your money: a point is worth whatever you say here, and nothing until you say it.
            </div>
          </div>

          <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 12 }}>
            <Field label="Unused points expire after (months)">
              <input inputMode="numeric" value={form.expiryMonths} onChange={set("expiryMonths")} placeholder="never" />
            </Field>
            <Field label="Who it applies to">
              <select value={form.customerTypeScope} onChange={set("customerTypeScope")}>
                {points.TIERS.map((t) => <option key={t.value} value={t.value}>{t.label}</option>)}
              </select>
            </Field>
          </div>

          <Field label="Where it applies">
            <select value={form.branchScope} onChange={set("branchScope")}>
              <option value="">Choose…</option>
              <option value="SELECTED_BRANCHES">Only the pharmacies I choose</option>
              <option value="ALL_BRANCHES">Every pharmacy on the platform</option>
            </select>
          </Field>
          {form.branchScope === "SELECTED_BRANCHES" && (
            <div className="ec-note">
              <div style={{ marginBottom: 6, fontWeight: 600 }}>Pharmacies</div>
              <div style={{ display: "flex", flexWrap: "wrap", gap: 8 }}>
                {branches.map((b) => (
                  <label key={b.id} style={{ display: "inline-flex", alignItems: "center", gap: 6, fontSize: 12.5 }}>
                    <input type="checkbox" checked={form.branchIds.includes(b.id)} onChange={() => toggleBranch(b.id)} />
                    {b.name}
                  </label>
                ))}
              </div>
              <div className="ec-sub" style={{ marginTop: 6 }}>
                You can only run a scheme at your own pharmacy; the server checks this again.
              </div>
            </div>
          )}

          <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 12 }}>
            <Field label="Opens on"><input type="date" value={form.startsAt} onChange={set("startsAt")} /></Field>
            <Field label="Closes after"><input type="date" value={form.endsAt} onChange={set("endsAt")} /></Field>
          </div>

          {!creating && (
            <HrButton onClick={() => { setEditing(false); load(); }}>Cancel the changes</HrButton>
          )}
        </div>
      )}
    </HrDrawer>
  );
}
