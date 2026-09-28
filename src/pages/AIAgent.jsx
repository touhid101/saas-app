/**
 * AI Copilot — what to buy, what is moving, and what is about to be wasted.
 *
 * The name is inherited and slightly oversells it: there is no model here. The
 * page is arithmetic over the stock ledger, and it is built so a buyer can
 * disagree with it. Every recommendation shows the figures it came from, the
 * assumptions are on screen and adjustable rather than buried in a constant,
 * and the suggested quantity is a starting point the buyer edits before
 * anything is raised. A number nobody can check is a number nobody should
 * place an order against.
 */
import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Card, Pill, Table, QtyInput } from "../components/UI.jsx";
import { useToast } from "../hooks/useToast.js";
import { usePermission } from "../lib/usePermission.js";
import { getSuppliers } from "../services/masterDataService.js";
import { useApp } from "../lib/AppContext.jsx";
import { useConfirm } from "../lib/ConfirmProvider.jsx";
import aiService from "../services/aiService.js";

const money = (n) =>
  "৳ " + Number(n ?? 0).toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
const compact = (n) =>
  "৳ " + Number(n ?? 0).toLocaleString("en-US", { maximumFractionDigits: 0 });
const pcs = (n) => Number(n ?? 0).toLocaleString("en-US", { maximumFractionDigits: 0 });

const PRIORITY_TONE = { CRITICAL: "clay", HIGH: "amber", MEDIUM: "teal", LOW: "grey" };
const CONFIDENCE_TONE = { HIGH: "sage", MEDIUM: "amber", LOW: "grey" };
const SEVERITY_TONE = { CRITICAL: "clay", WARNING: "amber", INFO: "teal", GOOD: "sage" };
const MOMENTUM = {
  RISING:   { label: "Rising",   tone: "sage" },
  STEADY:   { label: "Steady",   tone: "teal" },
  SLIPPING: { label: "Slipping", tone: "amber" },
  STALLED:  { label: "Stalled",  tone: "clay" },
};
const STOCK_SIGNAL = {
  OK:       { label: "Healthy",  tone: "sage" },
  LOW:      { label: "Low",      tone: "amber" },
  CRITICAL: { label: "Critical", tone: "clay" },
  OUT:      { label: "Out",      tone: "clay" },
  DEAD:     { label: "Not selling", tone: "grey" },
};

const TABS = [
  { key: "reorder", label: "Buy now" },
  { key: "movers",  label: "What's moving" },
  { key: "watch",   label: "Expiry & dead stock" },
  { key: "ask",     label: "Ask" },
];

export default function AIAgent({ onNavigate }) {
  const addToast = useToast();
  const confirm = useConfirm();
  const perm = usePermission("ai");
  const { branches, currentUser } = useApp();

  // Stock and sales are both held per branch, so the whole board is about one
  // of them. Defaults to where the user signs in; "every branch" is available
  // for a head-office look but cannot raise an order, because an order has to
  // be delivered somewhere.
  const [branchId, setBranchId] = useState("");
  const allBranches = branchId === "ALL";

  const [board, setBoard] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [tab, setTab] = useState("reorder");

  // The assumptions the whole page rests on. They are on screen because a
  // reorder quantity means nothing without them: seven days of cover is
  // comfortable if the supplier delivers tomorrow and a crisis if they take
  // three weeks.
  const [leadTimeDays, setLeadTimeDays] = useState(7);
  const [coverDays, setCoverDays] = useState(14);
  const [safetyFactor, setSafetyFactor] = useState(1.3);

  // Narrowing to a supplier is what turns the list into an order somebody can
  // place: a supplier carries one manufacturer, so a list spanning four of them
  // is four separate phone calls.
  const [suppliers, setSuppliers] = useState([]);
  const [supplierId, setSupplierId] = useState("");

  useEffect(() => {
    getSuppliers({ size: 200 })
      .then((r) => setSuppliers((r?.items ?? r?.content ?? []).filter((s) => s.status !== "N")))
      .catch(() => setSuppliers([]));
  }, []);

  useEffect(() => {
    if (!branchId && currentUser?.branchId) setBranchId(String(currentUser.branchId));
  }, [currentUser, branchId]);

  const [selected, setSelected] = useState([]);
  const [editedBoxes, setEditedBoxes] = useState({});
  const [expanded, setExpanded] = useState(null);
  const [raising, setRaising] = useState(false);
  const [raised, setRaised] = useState(null);

  const load = useCallback(() => {
    setLoading(true);
    setError(null);
    return aiService
      .getBoard({
        leadTimeDays, coverDays, safetyFactor,
        supplierId: supplierId || undefined,
        branchId: allBranches ? undefined : (branchId || undefined),
        allBranches,
        limit: 50,
      })
      .then((b) => { setBoard(b); setError(null); })
      .catch((e) => setError(e?.message || "Could not read the stock ledger."))
      .finally(() => setLoading(false));
  }, [leadTimeDays, coverDays, safetyFactor, supplierId, branchId, allBranches]);

  useEffect(() => { load(); }, [load]);

  const reorder = board?.reorder ?? [];
  const chosen = reorder.filter((r) => selected.includes(r.productId));
  const boxesFor = (r) => editedBoxes[r.productId] ?? r.suggestedBoxes;
  const chosenTotal = useMemo(
    () => chosen.reduce((sum, r) => sum + boxesFor(r) * r.costPerBox, 0),
    [chosen, editedBoxes]);   // eslint-disable-line react-hooks/exhaustive-deps

  const allSelected = reorder.length > 0 && selected.length === reorder.length;
  const someSelected = selected.length > 0 && !allSelected;
  const toggleAll = () => setSelected(allSelected ? [] : reorder.map((r) => r.productId));
  const toggle = (id) =>
    setSelected((s) => (s.includes(id) ? s.filter((x) => x !== id) : [...s, id]));

  const raise = async () => {
    if (!chosen.length) {
      addToast("Pick at least one product to order.", "warning");
      return;
    }
    if (allBranches) {
      addToast("Choose a branch first — an order has to be delivered somewhere.", "warning");
      return;
    }
    const ok = await confirm({
      title: `Raise a requisition for ${chosen.length} product${chosen.length === 1 ? "" : "s"}?`,
      body: `${chosen.map((r) => r.productName).join(", ")} — about ${money(chosenTotal)}.`,
      detail: "It is saved as a draft. Nothing is ordered and no stock changes until somebody "
            + "reviews it and sends it for approval.",
      confirmLabel: "Raise draft requisition",
      tone: "teal",
    });
    if (!ok) return;

    setRaising(true);
    try {
      const res = await aiService.createRequisition({
        lines: chosen.map((r) => ({ productId: r.productId, boxes: boxesFor(r) })),
        remarks: `Raised from the Copilot — ${chosen.length} lines, `
               + `${leadTimeDays}d lead time, ${coverDays}d cover`,
        supplierId: supplierId || undefined,
        branchId: branchId && !allBranches ? Number(branchId) : undefined,
        leadTimeDays, coverDays, safetyFactor,
      });
      setRaised(res);
      setSelected([]);
      setEditedBoxes({});
      addToast(`${res.requisitionNo} drafted — ${res.lines} lines, about ${money(res.estimatedCost)}`);
    } catch (e) {
      addToast(e?.message || "Could not raise the requisition.", "error");
    } finally {
      setRaising(false);
    }
  };

  return (
    <div className="page">
      <div className="page-head">
        <div>
          <h1>AI Copilot</h1>
          <div className="page-sub">
            What to buy, what is moving and what is about to be wasted — worked out from the
            stock ledger, with the figures shown so you can disagree with them.
          </div>
        </div>
        <button className="btn btn-ghost btn-sm" onClick={load} disabled={loading}>
          {loading ? "Reading…" : "Refresh"}
        </button>
      </div>

      {error && (
        <Card>
          <div className="empty-state" style={{ color: "var(--clay)" }}>
            {error}
            <div style={{ marginTop: 10 }}>
              <button className="btn btn-ghost btn-sm" onClick={load}>Try again</button>
            </div>
          </div>
        </Card>
      )}

      {!error && loading && !board && (
        <Card><div className="empty-state">Reading the stock ledger…</div></Card>
      )}

      {board && (
        <>
          <SummaryStrip summary={board.summary} trend={board.trend} />

          {board.summary.thinHistory && (
            <div className="ai-thin">
              Only {board.summary.historyDays} trading days on record. A daily rate drawn from
              that is mostly noise — treat everything below as a starting point rather than a
              forecast, and lean on your own judgement of what sells.
            </div>
          )}

          {board.insights.length > 0 && (
            <div className="ai-insights">
              {board.insights.map((i) => (
                <button key={i.key} className={`ai-insight ai-insight-${(i.severity || "info").toLowerCase()}`}
                  onClick={() => i.action && setTab(i.action === "expiry" ? "watch" : i.action)}>
                  <div className="ai-insight-head">
                    <Pill tone={SEVERITY_TONE[i.severity] || "teal"}>{i.severity}</Pill>
                    {i.amount > 0 && <span className="ai-insight-amt mono">{compact(i.amount)}</span>}
                  </div>
                  <div className="ai-insight-title">{i.title}</div>
                  <div className="ai-insight-detail">{i.detail}</div>
                </button>
              ))}
            </div>
          )}

          <Assumptions
            leadTimeDays={leadTimeDays} setLeadTimeDays={setLeadTimeDays}
            coverDays={coverDays} setCoverDays={setCoverDays}
            safetyFactor={safetyFactor} setSafetyFactor={setSafetyFactor}
            suppliers={suppliers} supplierId={supplierId}
            setSupplierId={(id) => { setSupplierId(id); setSelected([]); setEditedBoxes({}); }}
            branches={branches} branchId={branchId}
            setBranchId={(id) => { setBranchId(id); setSelected([]); setEditedBoxes({}); }}
            busy={loading}
          />

          <div className="tabs" style={{ marginTop: 4 }}>
            {TABS.map((t) => (
              <button key={t.key} className={`tab-btn ${tab === t.key ? "active" : ""}`}
                onClick={() => setTab(t.key)}>
                {t.label}
                {t.key === "reorder" && reorder.length > 0 && (
                  <span className="tab-count">{reorder.length}</span>
                )}
                {t.key === "watch" && board.watch.length > 0 && (
                  <span className="tab-count">{board.watch.length}</span>
                )}
              </button>
            ))}
          </div>

          {tab === "reorder" && (
            <ReorderTab
              rows={reorder} selected={selected} toggle={toggle}
              allSelected={allSelected} someSelected={someSelected} toggleAll={toggleAll}
              boxesFor={boxesFor} setEditedBoxes={setEditedBoxes} editedBoxes={editedBoxes}
              expanded={expanded} setExpanded={setExpanded}
              chosenTotal={chosenTotal} chosenCount={chosen.length}
              leadTimeDays={leadTimeDays} coverDays={coverDays} safetyFactor={safetyFactor}
              raise={raise} raising={raising} canCreate={perm.canCreate} allBranches={allBranches}
              raised={raised} onNavigate={onNavigate}
            />
          )}

          {tab === "movers" && <MoversTab rows={board.movers} />}
          {tab === "watch"  && <WatchTab rows={board.watch} />}
          {tab === "ask"    && (
            <AskTab leadTimeDays={leadTimeDays} coverDays={coverDays}
                    safetyFactor={safetyFactor} onJump={setTab} />
          )}
        </>
      )}
    </div>
  );
}

/* ══════════════════════════════════════════════════════════════════════════
   Summary
   ══════════════════════════════════════════════════════════════════════════ */

function SummaryStrip({ summary, trend }) {
  return (
    <div className="ai-summary">
      <Stat label="Needs ordering" value={summary.needAttention}
            sub={`of ${summary.productsTracked} tracked`}
            tone={summary.needAttention > 0 ? "amber" : "sage"} />
      <Stat label="Suggested spend" value={compact(summary.suggestedSpend)}
            sub="at current cost prices" />
      <Stat label="Stock on hand" value={compact(summary.stockValue)} sub="at cost" />
      <Stat label="At risk" value={compact(summary.expiringValue + summary.deadStockValue)}
            sub="short-dated or not selling"
            tone={summary.expiringValue + summary.deadStockValue > 0 ? "clay" : "sage"} />
      <div className="ai-spark-wrap">
        <div className="ai-stat-label">Pieces sold · 90 days</div>
        <Sparkline points={trend} />
      </div>
    </div>
  );
}

function Stat({ label, value, sub, tone }) {
  return (
    <div className="ai-stat">
      <div className="ai-stat-label">{label}</div>
      <div className={`ai-stat-value${tone ? ` ai-stat-${tone}` : ""}`}>{value}</div>
      <div className="ai-stat-sub">{sub}</div>
    </div>
  );
}

/** A plain line, no axes — it is here to show shape, not to be read off. */
function Sparkline({ points }) {
  if (!points?.length) return <div className="ai-stat-sub">No sales recorded.</div>;
  const w = 240, h = 44, pad = 2;
  const max = Math.max(...points.map((p) => p.pcs), 1);
  const step = points.length > 1 ? (w - pad * 2) / (points.length - 1) : 0;
  const d = points
    .map((p, i) => `${i === 0 ? "M" : "L"} ${(pad + i * step).toFixed(1)} `
                 + `${(h - pad - (p.pcs / max) * (h - pad * 2)).toFixed(1)}`)
    .join(" ");
  return (
    <svg className="ai-spark" viewBox={`0 0 ${w} ${h}`} preserveAspectRatio="none"
         role="img" aria-label={`Daily pieces sold over ${points.length} days`}>
      <path d={d} fill="none" stroke="var(--teal)" strokeWidth="1.6"
            strokeLinejoin="round" strokeLinecap="round" />
    </svg>
  );
}

/* ══════════════════════════════════════════════════════════════════════════
   Assumptions
   ══════════════════════════════════════════════════════════════════════════ */

function Assumptions({ leadTimeDays, setLeadTimeDays, coverDays, setCoverDays,
                       safetyFactor, setSafetyFactor, suppliers, supplierId,
                       setSupplierId, branches, branchId, setBranchId, busy }) {
  const supplier = suppliers?.find((s) => String(s.id) === String(supplierId));
  return (
    <div className="ai-assume">
      <label className="ai-assume-field">
        at
        <select value={branchId} disabled={busy}
                onChange={(e) => setBranchId(e.target.value)}
                aria-label="Branch">
          {(branches ?? []).map((b) => (
            <option key={b.id} value={b.id}>{b.name}</option>
          ))}
          <option value="ALL">every branch</option>
        </select>
      </label>
      {branchId === "ALL" && (
        <span className="ai-assume-note ai-assume-warn">
          analysis only — a requisition has to be for one branch
        </span>
      )}
      <label className="ai-assume-field">
        buying from
        <select value={supplierId} disabled={busy}
                onChange={(e) => setSupplierId(e.target.value)}
                aria-label="Supplier">
          <option value="">every supplier</option>
          {(suppliers ?? []).map((s) => (
            <option key={s.id} value={s.id}>{s.name}</option>
          ))}
        </select>
      </label>
      {supplier && (
        <span className="ai-assume-note">
          {supplier.companyName
            ? `supplies ${supplier.companyName} only`
            : "no manufacturer set on this supplier"}
        </span>
      )}
      <div className="ai-assume-lead">
        <b>Working on the basis that</b>
        <span className="ai-assume-note">
          change any of these and the whole list is recalculated
        </span>
      </div>
      <label className="ai-assume-field">
        a delivery takes
        <input type="number" min="1" max="90" value={leadTimeDays} disabled={busy}
               onChange={(e) => setLeadTimeDays(clampNum(e.target.value, 1, 90, 7))} />
        days
      </label>
      <label className="ai-assume-field">
        an order should last
        <input type="number" min="1" max="180" value={coverDays} disabled={busy}
               onChange={(e) => setCoverDays(clampNum(e.target.value, 1, 180, 14))} />
        days
      </label>
      <label className="ai-assume-field">
        with
        <input type="number" min="1" max="3" step="0.1" value={safetyFactor} disabled={busy}
               onChange={(e) => setSafetyFactor(clampNum(e.target.value, 1, 3, 1.3))} />
        × headroom
      </label>
    </div>
  );
}

const clampNum = (v, lo, hi, fallback) => {
  const n = Number(v);
  if (!Number.isFinite(n)) return fallback;
  return Math.min(hi, Math.max(lo, n));
};

/* ══════════════════════════════════════════════════════════════════════════
   Buy now
   ══════════════════════════════════════════════════════════════════════════ */

function ReorderTab({ rows, selected, toggle, allSelected, someSelected, toggleAll,
                      boxesFor, setEditedBoxes, editedBoxes, expanded, setExpanded,
                      chosenTotal, chosenCount, leadTimeDays, coverDays, safetyFactor,
                      raise, raising, canCreate, allBranches, raised, onNavigate }) {
  if (!rows.length) {
    return (
      <Card>
        <div className="empty-state">
          Nothing needs ordering. Every line that is selling has enough cover for the next{" "}
          {leadTimeDays + coverDays} days.
        </div>
      </Card>
    );
  }

  return (
    <Card
      eyebrow={`${rows.length} product${rows.length === 1 ? "" : "s"} below target cover`}
      title="Buy now"
      right={raised && (
        <button className="btn btn-teal btn-sm" onClick={() => onNavigate?.("requisition")}>
          Open {raised.requisitionNo} →
        </button>
      )}
    >
      <Table head={[
        <input key="all" type="checkbox" checked={allSelected} onChange={toggleAll}
               ref={(el) => { if (el) el.indeterminate = someSelected; }}
               aria-label={allSelected ? "Clear the selection" : "Select every product"} />,
        "Product", "Priority", "Stock", "Sells", "Cover", "Order", "Est. cost", "",
      ]}>
        {rows.map((r) => (
          <React.Fragment key={r.productId}>
            <tr className={selected.includes(r.productId) ? "ai-row-on" : ""}>
              <td>
                <input type="checkbox" checked={selected.includes(r.productId)}
                       onChange={() => toggle(r.productId)}
                       aria-label={`Order ${r.productName}`} />
              </td>
              <td>
                <div className="ai-prod">{r.productName}</div>
                <div className="ai-prod-sub">
                  {r.companyName || "—"} · {r.pcsPerBox} pcs/box
                  {r.reasons.slice(0, 2).map((x) => (
                    <span key={x} className="ai-reason">{x}</span>
                  ))}
                </div>
              </td>
              <td>
                <Pill tone={PRIORITY_TONE[r.priority] || "grey"}>{r.priority}</Pill>
              </td>
              <td className="mono">{pcs(r.stockPcs)}</td>
              <td className="mono">{r.velocityPerDay}/day</td>
              <td className="mono">
                {r.daysOfCover == null
                  ? <span className="muted">—</span>
                  : <span className={r.daysOfCover <= leadTimeDays ? "ai-danger" : ""}>
                      {r.daysOfCover}d
                    </span>}
              </td>
              <td>
                <QtyInput
                  value={boxesFor(r)} min={1} step={1}
                  ariaLabel={`Boxes of ${r.productName}`}
                  onChange={(v) => setEditedBoxes((p) => ({ ...p, [r.productId]: Math.max(1, Number(v)) }))}
                />
                {boxesFor(r) !== r.suggestedBoxes && (
                  <button className="ai-reset"
                    onClick={() => setEditedBoxes((p) => { const n = { ...p }; delete n[r.productId]; return n; })}>
                    reset to {r.suggestedBoxes}
                  </button>
                )}
              </td>
              <td className="mono" style={{ fontWeight: 700 }}>
                {money(boxesFor(r) * r.costPerBox)}
              </td>
              <td>
                <button className="btn btn-ghost btn-sm"
                  onClick={() => setExpanded(expanded === r.productId ? null : r.productId)}>
                  {expanded === r.productId ? "Hide" : "Why?"}
                </button>
              </td>
            </tr>
            {expanded === r.productId && (
              <tr>
                <td colSpan={9} className="ai-why-cell">
                  <Why r={r} leadTimeDays={leadTimeDays} coverDays={coverDays}
                       safetyFactor={safetyFactor} />
                </td>
              </tr>
            )}
          </React.Fragment>
        ))}
      </Table>

      <div className="ai-order-bar">
        <div>
          <div className="ai-stat-label">{chosenCount} selected</div>
          <div className="ai-order-total mono">{money(chosenTotal)}</div>
        </div>
        <button className="btn btn-primary" onClick={raise}
          disabled={!chosenCount || raising || !canCreate || allBranches}
          title={allBranches
            ? "Choose a branch — an order has to be delivered somewhere"
            : !canCreate ? "You do not have permission to raise requisitions" : undefined}>
          {raising ? "Raising…" : "Raise draft requisition"}
        </button>
      </div>
    </Card>
  );
}

/** The workings, so the buyer can find the step they disagree with. */
function Why({ r, leadTimeDays, coverDays, safetyFactor }) {
  const wasted = Math.max(0, r.stockPcs - r.sellablePcs);
  return (
    <div className="ai-why">
      <ul>
        <li>
          Sold <b>{pcs(r.velocityPerDay * 30)} pcs</b> in the last 30 days — about{" "}
          <b>{r.velocityPerDay}/day</b>, across <b>{r.sellingDays}</b> selling day
          {r.sellingDays === 1 ? "" : "s"}.
          {r.trendPct != null && (
            <> That is <b>{r.trendPct > 0 ? "+" : ""}{r.trendPct}%</b> against the 30 days before.</>
          )}
        </li>
        <li>
          <b>{pcs(r.stockPcs)} pcs</b> on hand
          {wasted > 0 ? (
            <> — but only <b>{pcs(r.sellablePcs)}</b> can sell before it expires, so{" "}
              <b>{pcs(wasted)} pcs</b> will not count as cover.</>
          ) : <>, all of it sellable before expiry.</>}
          {r.daysOfCover != null && (
            <> That is <b>{r.daysOfCover} day{r.daysOfCover === 1 ? "" : "s"}</b> of cover.</>
          )}
        </li>
        <li>
          Target is <b>{pcs(r.targetStockPcs)} pcs</b> — {leadTimeDays} days to cover the wait for
          delivery, {coverDays} days for the order to last, plus{" "}
          <b>{pcs(r.safetyStockPcs)} pcs</b> headroom at {safetyFactor}×.
        </li>
        <li>
          Short by <b>{pcs(r.deficitPcs)} pcs</b> → <b>{r.suggestedBoxes} box
          {r.suggestedBoxes === 1 ? "" : "es"}</b> of {r.pcsPerBox} at {money(r.costPerBox)}.
        </li>
        {r.expiringPcs > 0 && (
          <li className="ai-danger">
            <b>{pcs(r.expiringPcs)} pcs</b> already on the shelf expire from {r.nearestExpiry}{" "}
            ({money(r.expiringValue)} at cost). Order around it, not on top of it.
          </li>
        )}
        {r.stockoutOn && (
          <li>At this rate the shelf empties around <b>{r.stockoutOn}</b>.</li>
        )}
      </ul>
      <div className="ai-why-foot">
        <Pill tone={CONFIDENCE_TONE[r.confidence] || "grey"}>{r.confidence} confidence</Pill>
        <span className="ai-stat-sub">
          {r.confidence === "HIGH"
            ? "It sold on most days this month, so the rate is reliable."
            : r.confidence === "MEDIUM"
              ? "It sold on some days this month — the rate is indicative."
              : "It barely sold this month. Treat the quantity as a prompt to think, not an answer."}
        </span>
      </div>
    </div>
  );
}

/* ══════════════════════════════════════════════════════════════════════════
   Movers
   ══════════════════════════════════════════════════════════════════════════ */

function MoversTab({ rows }) {
  if (!rows.length) {
    return <Card><div className="empty-state">Nothing has sold in the last 30 days.</div></Card>;
  }
  return (
    <Card eyebrow="Last 30 days" title="What's moving"
      right={<span className="ai-stat-sub">Margin is against the cost of the batches that went out</span>}>
      <Table head={["#", "Product", "Revenue", "Margin", "Sold", "Rate", "Trend", "Momentum", "Stock"]}>
        {rows.map((m, i) => (
          <tr key={m.productId}>
            <td className="mono muted">{i + 1}</td>
            <td>
              <div className="ai-prod">{m.productName}</div>
              <div className="ai-prod-sub">{m.companyName || "—"}</div>
            </td>
            <td className="mono" style={{ fontWeight: 700 }}>{money(m.revenue)}</td>
            <td className="mono">
              <span className={m.marginPct < 0 ? "ai-danger" : ""}>{m.marginPct}%</span>
              <div className="ai-prod-sub mono">{money(m.margin)}</div>
            </td>
            <td className="mono">{pcs(m.pcs)} pcs</td>
            <td className="mono">{m.velocityPerDay}/day</td>
            <td className="mono">
              {m.trendPct == null
                ? <span className="muted">new</span>
                : <span className={m.trendPct < 0 ? "ai-danger" : "ai-good"}>
                    {m.trendPct > 0 ? "+" : ""}{m.trendPct}%
                  </span>}
            </td>
            <td><Pill tone={MOMENTUM[m.momentum]?.tone || "grey"}>
              {MOMENTUM[m.momentum]?.label || m.momentum}</Pill></td>
            <td>
              <Pill tone={STOCK_SIGNAL[m.stockSignal]?.tone || "grey"}>
                {STOCK_SIGNAL[m.stockSignal]?.label || m.stockSignal}</Pill>
              {m.daysOfCover != null && (
                <div className="ai-prod-sub mono">{m.daysOfCover}d cover</div>
              )}
            </td>
          </tr>
        ))}
      </Table>
    </Card>
  );
}

/* ══════════════════════════════════════════════════════════════════════════
   Expiry & dead stock
   ══════════════════════════════════════════════════════════════════════════ */

function WatchTab({ rows }) {
  if (!rows.length) {
    return (
      <Card>
        <div className="empty-state">
          Nothing is short-dated and nothing has stalled. Every product with stock has sold
          recently and has shelf life to spare.
        </div>
      </Card>
    );
  }
  const atRisk = rows.reduce((s, w) => s + w.atRiskValue, 0);
  const tied = rows.reduce((s, w) => s + w.stockValue, 0);

  return (
    <Card
      eyebrow={`${money(tied)} tied up · ${money(atRisk)} unlikely to be recovered`}
      title="Expiry & dead stock"
      right={<span className="ai-stat-sub">
        &ldquo;At risk&rdquo; is what will not sell before it dates, at the current rate
      </span>}>
      <Table head={["Product", "Kind", "Stock", "Will sell", "At risk", "Expires", "Last sold", "What to do"]}>
        {rows.map((w) => (
          <tr key={w.productId}>
            <td>
              <div className="ai-prod">{w.productName}</div>
              <div className="ai-prod-sub">{w.companyName || "—"}</div>
            </td>
            <td>
              <Pill tone={w.kind === "BOTH" ? "clay" : w.kind === "DEAD" ? "grey" : "amber"}>
                {w.kind === "BOTH" ? "Dead & dated" : w.kind === "DEAD" ? "Not selling" : "Short-dated"}
              </Pill>
            </td>
            <td className="mono">{pcs(w.stockPcs)}</td>
            <td className="mono">{pcs(w.sellablePcs)}</td>
            <td className="mono">
              {w.atRiskPcs > 0
                ? <><span className="ai-danger">{pcs(w.atRiskPcs)} pcs</span>
                    <div className="ai-prod-sub mono">{money(w.atRiskValue)}</div></>
                : <span className="muted">—</span>}
            </td>
            <td className="mono">
              {w.nearestExpiry
                ? <><span className={w.daysToExpiry != null && w.daysToExpiry < 45 ? "ai-danger" : ""}>
                      {w.nearestExpiry}</span>
                    {w.daysToExpiry != null && (
                      <div className="ai-prod-sub">{w.daysToExpiry} days</div>)}</>
                : <span className="muted">—</span>}
            </td>
            <td className="mono">
              {w.lastSold
                ? <>{w.lastSold}
                    <div className="ai-prod-sub">
                      {w.idleDays === 0 ? "today"
                        : `${w.idleDays} day${w.idleDays === 1 ? "" : "s"} ago`}
                    </div></>
                : <span className="muted">never</span>}
            </td>
            <td className="ai-suggestion">{w.suggestion}</td>
          </tr>
        ))}
      </Table>
    </Card>
  );
}

/* ══════════════════════════════════════════════════════════════════════════
   Ask
   ══════════════════════════════════════════════════════════════════════════ */

const SUGGESTED = [
  "What should I buy today?",
  "What will run out first?",
  "Which products are near expiry?",
  "What is not selling?",
  "What are my best sellers?",
];

function AskTab({ leadTimeDays, coverDays, safetyFactor, onJump }) {
  const [thread, setThread] = useState([]);
  const [input, setInput] = useState("");
  const [busy, setBusy] = useState(false);
  const endRef = useRef(null);

  useEffect(() => { endRef.current?.scrollIntoView({ block: "nearest" }); }, [thread]);

  const send = async (text) => {
    const q = (text ?? input).trim();
    if (!q || busy) return;
    setInput("");
    setThread((t) => [...t, { from: "you", text: q }]);
    setBusy(true);
    try {
      const a = await aiService.ask(q, { leadTimeDays, coverDays, safetyFactor });
      setThread((t) => [...t, { from: "ai", ...a }]);
    } catch (e) {
      setThread((t) => [...t, { from: "ai", headline: e?.message || "Could not answer that.", bullets: [] }]);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Card eyebrow="Answers come from the same figures as the tabs" title="Ask">
      <div className="ai-chat">
        {thread.length === 0 && (
          <div className="empty-state" style={{ textAlign: "left" }}>
            Ask about buying, stockouts, expiry or what is selling. The answers are read straight
            off the ledger rather than generated, so you can check any of them against the tabs.
          </div>
        )}
        {thread.map((m, i) => (
          <div key={i} className={`ai-msg ai-msg-${m.from}`}>
            {m.from === "you" ? m.text : (
              <>
                <div className="ai-msg-head">{m.headline}</div>
                {m.bullets?.length > 0 && (
                  <ul className="ai-msg-list">
                    {m.bullets.map((b, k) => <li key={k}>{b}</li>)}
                  </ul>
                )}
                {m.action && (
                  <button className="ai-msg-jump"
                    onClick={() => onJump(m.action === "expiry" ? "watch" : m.action)}>
                    Show me →
                  </button>
                )}
              </>
            )}
          </div>
        ))}
        {busy && <div className="ai-msg ai-msg-ai ai-msg-wait">Reading the ledger…</div>}
        <div ref={endRef} />
      </div>

      <div className="ai-chips">
        {SUGGESTED.map((q) => (
          <button key={q} className="ai-chip" onClick={() => send(q)} disabled={busy}>{q}</button>
        ))}
      </div>

      <div className="ai-ask-row">
        <input className="input" value={input} onChange={(e) => setInput(e.target.value)}
               onKeyDown={(e) => e.key === "Enter" && send()}
               placeholder="Ask about buying, stockouts, expiry or what is selling…"
               disabled={busy} aria-label="Ask the Copilot" />
        <button className="btn btn-primary" onClick={() => send()} disabled={busy || !input.trim()}>
          Ask
        </button>
      </div>
    </Card>
  );
}
