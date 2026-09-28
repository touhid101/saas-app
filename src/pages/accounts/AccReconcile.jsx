/**
 * AccReconcile — the event monitor, the reconciliation centre and the
 * controlled backfill of history.
 *
 * Every business transaction leaves a financial event; this screen shows
 * them (posted, skipped, failed), lets a FAILED or SKIPPED one be re-run
 * from its source document, compares the operational tables with the
 * ledger, and brings pre-ledger history into the books batch by batch.
 */
import React, { useState } from "react";
import { useApp } from "../../lib/AppContext.jsx";
import { usePermission } from "../../lib/usePermission.js";
import { HrPage, HrTable, HrFilterBar, Filter, HrButton, HrTabBar, HrPagination, Status, Kpis, Kpi, Facts, Fact, PolicyNote, usePagedList, humanError, P } from "../../components/HrKit.jsx";
import { getEvents, retryEvent, getReconciliation, runBackfill, getBackfillBatches } from "../../services/accountsService.js";
import { amount, fmtDate, fmtDateTime, num } from "../../utils/financial.js";
import { ACC_CRUMB, AI, AccTabs, useAsync, Money, DateRange, useDateRange, BranchPicker, Source, takeIntent } from "./AccKit.jsx";

const MODULES = ["SALES", "PURCHASE", "SUPPLIER_PAYMENT", "COLLECTION", "HR", "SUBSCRIPTION", "MANUAL", "CASH", "INVESTOR", "PROFIT"];

export default function AccReconcile() {
  const perm = usePermission("accreconcile");
  const [intent] = useState(() => takeIntent("accreconcile"));
  const [tab, setTab] = useState(intent?.tab || "events");
  return (
    <HrPage className="acc-page" icon={AI.reconcile} crumbs={[ACC_CRUMB]} title="Reconciliation" subtitle="Every business transaction leaves a financial event. Watch them post, re-run the ones that failed, and check the ledger against the business records.">
      <AccTabs tabs={[{ id: "events", label: "Event monitor", icon: AI.list }, { id: "checks", label: "Reconciliation", icon: AI.scale }, { id: "backfill", label: "Backfill history", icon: AI.history }]} active={tab} onChange={setTab} />
      {tab === "events" && <EventMonitor perm={perm} initialStatus={intent?.status || ""} />}
      {tab === "checks" && <Checks perm={perm} />}
      {tab === "backfill" && <Backfill perm={perm} />}
    </HrPage>
  );
}

function EventMonitor({ perm, initialStatus = "" }) {
  const { addToast } = useApp();
  const [range, setRange] = useDateRange(initialStatus ? "2000-01-01" : undefined);
  const [filters, setFilters] = useState({ status: initialStatus, module: "", branchId: null });
  const [open, setOpen] = useState(null);
  const list = usePagedList(({ page, size }) => getEvents({ ...filters, from: range.from, to: range.to, page, size }), [filters, range]);
  const retry = async (e) => { try { await retryEvent(e.pidEvent); addToast("Event re-run."); list.reload(); } catch (x) { addToast(humanError(x), "error"); } };
  return (
    <>
      <HrFilterBar active={(filters.status ? 1 : 0) + (filters.module ? 1 : 0)} onReset={() => setFilters({ status: "", module: "", branchId: null })}>
        <DateRange from={range.from} to={range.to} onChange={setRange} />
        <Filter label="Status"><select value={filters.status} onChange={(e) => setFilters((f) => ({ ...f, status: e.target.value }))}><option value="">Any</option>{["POSTED", "FAILED", "SKIPPED", "REVERSED"].map((s) => <option key={s}>{s}</option>)}</select></Filter>
        <Filter label="Module"><select value={filters.module} onChange={(e) => setFilters((f) => ({ ...f, module: e.target.value }))}><option value="">Any</option>{MODULES.map((m) => <option key={m}>{m}</option>)}</select></Filter>
        <BranchPicker value={filters.branchId} onChange={(b) => setFilters((f) => ({ ...f, branchId: b }))} allowAll />
      </HrFilterBar>
      <HrTable rows={list.rows} rowKey={(e) => e.pidEvent} loading={list.busy} error={list.err} onRetry={list.reload} onRowClick={setOpen} empty="No event in this range."
        cols={[{ key: "eventDate", label: "Date", render: (e) => fmtDate(e.eventDate) }, { key: "eventType", label: "Event", lead: true, render: (e) => <><b>{e.eventType}</b><div className="acc-muted">{e.reference}</div></> },
               { key: "source", label: "Source", render: (e) => <Source module={e.sourceModule} type={e.sourceType} /> }, { key: "amount", label: "Amount", num: true, render: (e) => <Money v={e.amount} /> },
               { key: "eventStatus", label: "Status", render: (e) => <Status code={e.eventStatus} /> }, { key: "pidJournal", label: "Journal", render: (e) => e.pidJournal ? `#${e.pidJournal}` : <span className="acc-muted">{e.errorMessage ? "see error" : "—"}</span> },
               { key: "actions", label: "", actions: true, render: (e) => perm.canEdit && ["FAILED", "SKIPPED"].includes(e.eventStatus) && <HrButton size="sm" onClick={(ev) => { ev.stopPropagation(); retry(e); }}>Retry</HrButton> }]} />
      <HrPagination page={list.page} size={list.size} total={list.total} totalPages={list.totalPages} onPage={list.setPage} onSize={list.setSize} />
      {open && (
        <div className="modal-backdrop" onMouseDown={(e) => { if (e.target === e.currentTarget) setOpen(null); }}>
          <div className="modal" style={{ maxWidth: 640 }}>
            <div className="modal-head"><h3>{open.eventType} · <Status code={open.eventStatus} /></h3></div>
            <div className="modal-body">
              <Facts>
                <Fact label="Source"><Source module={open.sourceModule} type={open.sourceType} /> #{open.sourceId}</Fact>
                <Fact label="Event key">{open.eventKey}</Fact>
                <Fact label="Date">{fmtDate(open.eventDate)}</Fact>
                <Fact label="Amount">{amount(open.amount)}</Fact>
                <Fact label="Reference">{open.reference}</Fact>
                <Fact label="Journal">{open.pidJournal ? `#${open.pidJournal}` : "—"}</Fact>
                <Fact label="Processed">{fmtDateTime(open.processedAt)}</Fact>
                <Fact label="Batch">{open.pidBackfillBatch ? `#${open.pidBackfillBatch}` : "live"}</Fact>
              </Facts>
              {open.description && <p>{open.description}</p>}
              {open.errorMessage && <div className="acc-alert is-clay"><b>Why it did not post</b><div>{open.errorMessage}</div></div>}
              {open.payload && <details className="acc-details"><summary>Event payload</summary><pre>{pretty(open.payload)}</pre></details>}
            </div>
            <div className="modal-foot"><HrButton onClick={() => setOpen(null)}>Close</HrButton>{perm.canEdit && ["FAILED", "SKIPPED"].includes(open.eventStatus) && <HrButton variant="primary" onClick={() => { retry(open); setOpen(null); }}>Retry</HrButton>}</div>
          </div>
        </div>
      )}
    </>
  );
}

function pretty(s) { try { return JSON.stringify(JSON.parse(s), null, 2); } catch { return s; } }

function Checks({ perm }) {
  // This month by default. The checks read every document and journal in the
  // range; since-2000 was the migration audit and took ten seconds a click.
  // Widen the range for a full audit.
  const [range, setRange] = useDateRange();
  const [branchId, setBranchId] = useState(null);
  const { data, busy, err, reload } = useAsync(() => getReconciliation({ branchId, from: range.from, to: range.to }), [branchId, range]);
  const integrity = data?.integrity || {};
  const bad = Object.entries(integrity).filter(([k, v]) => k !== "window" && Number(v) > 0).length;
  const INTEGRITY = {
    unbalancedJournals: "Posted journals whose debits ≠ credits", journalsWithLineMismatch: "Journals whose header totals ≠ their lines", failedEvents: "Events that failed to post",
    skippedEvents: "Events skipped (posting off or nothing to post)", postedEventsWithoutJournal: "Posted events with no journal behind them", linesOnWrongOrg: "Lines on another organisation's books",
    linesOnGroupAccounts: "Lines posted to a group (header) account", journalsInLockedPeriods: "Journals posted after their period was locked",
  };
  return (
    <>
      <PolicyNote>The ledger is authoritative. A check compares the business documents in the range with the events posted for them, and today's balances with the ledger's. A difference is something to explain, not something to fix here — trace it in the Journal Register or re-run the event that failed.</PolicyNote>
      <HrFilterBar active={0}>
        <DateRange from={range.from} to={range.to} onChange={setRange} />
        <BranchPicker value={branchId} onChange={setBranchId} allowAll />
        <Filter label=" "><HrButton icon={P.reset} onClick={reload} busy={busy}>Run again</HrButton></Filter>
      </HrFilterBar>
      <Kpis>
        <Kpi icon={AI.check} label="Checks matched" value={`${(data?.checks || []).filter((c) => c.status === "MATCHED").length} / ${(data?.checks || []).length}`} tone={(data?.checks || []).every((c) => c.status === "MATCHED") ? "sage" : "amber"} />
        <Kpi icon={AI.alert} label="Integrity issues" value={num(bad)} tone={bad ? "clay" : "sage"} />
        <Kpi icon={AI.x} label="Failed events" value={num(integrity.failedEvents)} tone={Number(integrity.failedEvents) ? "clay" : "sage"} />
        <Kpi icon={AI.till} label="Days not closed" value={num((data?.cash || []).length)} tone={(data?.cash || []).length ? "amber" : "sage"} />
      </Kpis>
      <h3 className="acc-h3">Business records against the ledger</h3>
      <HrTable rows={data?.checks || []} rowKey={(c) => c.check} loading={busy} error={err} onRetry={reload}
        cols={[{ key: "check", label: "Check", lead: true, render: (c) => <><b>{c.check}</b>{c.businessLabel && <div className="acc-muted">{c.businessLabel} <span aria-hidden="true">vs</span> {c.ledgerLabel}</div>}{c.note && <div className="acc-muted acc-note">{c.note}</div>}</> },
               { key: "documents", label: "Documents", num: true, render: (c) => (c.documents != null ? num(c.documents) : <span className="acc-muted">—</span>) },
               { key: "documentAmount", label: "Business amount", num: true, render: (c) => <Money v={c.documentAmount} /> },
               { key: "postedEvents", label: "Posted events", num: true, render: (c) => (c.postedEvents != null ? num(c.postedEvents) : <span className="acc-muted">—</span>) },
               { key: "ledgerAmount", label: "Ledger amount", num: true, render: (c) => <Money v={c.ledgerAmount} /> },
               { key: "missingJournals", label: "Missing", num: true, render: (c) => (c.missingJournals != null ? num(c.missingJournals) : <span className="acc-muted">—</span>) },
               { key: "difference", label: "Difference", num: true, render: (c) => <Money v={c.difference} paren /> }, { key: "status", label: "Status", render: (c) => <Status code={c.status} /> }]} />
      <h3 className="acc-h3">Ledger integrity</h3>
      <div className="acc-integrity">
        {Object.entries(INTEGRITY).map(([k, label]) => (
          <div key={k} className={`acc-check${Number(integrity[k]) > 0 ? " is-bad" : " is-ok"}`}><span className="acc-count">{num(integrity[k] ?? 0)}</span><span>{label}</span></div>))}
      </div>
      {(data?.cash || []).length > 0 && (
        <>
          <h3 className="acc-h3">Business days not yet closed</h3>
          <HrTable rows={data.cash} rowKey={(d) => d.pidBusinessDay} cols={[{ key: "businessDate", label: "Date", lead: true, render: (d) => fmtDate(d.businessDate) }, { key: "pidBranch", label: "Branch", render: (d) => `#${d.pidBranch}` }, { key: "dayStatus", label: "Status", render: (d) => <Status code={d.dayStatus} /> }, { key: "openedBy", label: "Opened by" }]} />
        </>
      )}
    </>
  );
}

function Backfill({ perm }) {
  const { addToast, currentUser } = useApp();
  const platform = currentUser?.roleCode === "ROLE01";
  // The batch list alone: the full reconciliation is ten seconds of checks the Backfill tab does not show.
  const { data, busy, reload } = useAsync(() => getBackfillBatches().then((rows) => ({ batches: rows || [] })), []);
  const [f, setF] = useState({ module: "", from: "", to: "", limit: 500, dryRun: true, orgId: "", branchId: null });
  const [running, setRunning] = useState(false);
  const [result, setResult] = useState(null);
  const run = async () => {
    if (!f.dryRun && !window.confirm(`Post up to ${f.limit} historical documents into the ledger? Each is dated when it happened; a document already posted is skipped.`)) return;
    setRunning(true);
    try {
      const body = { module: f.module || null, from: f.from || null, to: f.to || null, limit: Number(f.limit), dryRun: f.dryRun, branchId: f.branchId };
      if (platform && f.orgId) body.orgId = Number(f.orgId);
      const b = await runBackfill(body);
      setResult(b); addToast(f.dryRun ? "Dry run finished." : "Batch finished."); reload();
    } catch (e) { addToast(humanError(e), "error"); }
    finally { setRunning(false); }
  };
  return (
    <>
      <PolicyNote>History is brought into the ledger under a recorded batch: one journal per business document, dated when it happened, never twice. Run a dry run first — it counts and reports without posting. The books are provisional until the reconciliation checks above match.</PolicyNote>
      {perm.canApprove && (
        <div className="acc-card">
          <div className="acc-card-head"><h4 className="acc-h4">Run a batch</h4></div>
          <div className="acc-form-grid">
            {platform && <div className="field"><label>Organisation id</label><input value={f.orgId} onChange={(e) => setF({ ...f, orgId: e.target.value })} placeholder="blank = mine" /></div>}
            <div className="field"><label>Module</label><select value={f.module} onChange={(e) => setF({ ...f, module: e.target.value })}><option value="">All (sales, returns, purchases, supplier payments)</option>{["SALES", "PURCHASE", "SUPPLIER_PAYMENT"].map((m) => <option key={m}>{m}</option>)}</select></div>
            <div className="field"><label>From date</label><input type="date" value={f.from} onChange={(e) => setF({ ...f, from: e.target.value })} /></div>
            <div className="field"><label>To date</label><input type="date" value={f.to} onChange={(e) => setF({ ...f, to: e.target.value })} /></div>
            <div className="field"><label>Documents per batch</label><input type="number" min="1" max="5000" value={f.limit} onChange={(e) => setF({ ...f, limit: e.target.value })} className="hr-num" /></div>
            <div className="field"><label>Mode</label><select value={f.dryRun ? "dry" : "post"} onChange={(e) => setF({ ...f, dryRun: e.target.value === "dry" })}><option value="dry">Dry run — count only</option><option value="post">Post to the ledger</option></select></div>
          </div>
          <div className="acc-actions"><HrButton variant="primary" busy={running} onClick={run}>{f.dryRun ? "Dry run" : "Run batch"}</HrButton></div>
          {result && <div className={`acc-alert ${result.failedCount ? "is-amber" : "is-sage"}`}><b>Batch #{result.pidBackfillBatch} · <Status code={result.batchStatus} /></b><div>Scanned {num(result.scannedCount)} · posted {num(result.postedCount)} · skipped {num(result.skippedCount)} · failed {num(result.failedCount)}</div>{result.summary && <pre>{result.summary}</pre>}</div>}
        </div>
      )}
      <h3 className="acc-h3">Batches</h3>
      <HrTable rows={data?.batches || []} rowKey={(b) => b.pidBackfillBatch} loading={busy} empty="No batch has run."
        cols={[{ key: "pidBackfillBatch", label: "#", lead: true, render: (b) => <b>#{b.pidBackfillBatch}</b> }, { key: "scopeModule", label: "Scope", render: (b) => `${b.scopeModule || "ALL"}${b.pidBranch ? " · branch " + b.pidBranch : ""}` },
               { key: "dateFrom", label: "Dates", render: (b) => `${b.dateFrom ? fmtDate(b.dateFrom) : "…"} – ${b.dateTo ? fmtDate(b.dateTo) : "…"}` }, { key: "startedAt", label: "Started", render: (b) => `${fmtDateTime(b.startedAt)} · ${b.startedBy}` },
               { key: "finishedAt", label: "Finished", render: (b) => fmtDateTime(b.finishedAt) }, { key: "batchStatus", label: "Status", render: (b) => <Status code={b.batchStatus} /> }, { key: "postedCount", label: "Scanned / posted / skipped / failed", num: true, render: (b) => `${num(b.scannedCount)} / ${num(b.postedCount)} / ${num(b.skippedCount)} / ${num(b.failedCount)}` },
               { key: "summary", label: "Notes", render: (b) => b.summary ? <details className="acc-details"><summary>notes</summary><pre>{b.summary}</pre></details> : "" }]} />
    </>
  );
}
