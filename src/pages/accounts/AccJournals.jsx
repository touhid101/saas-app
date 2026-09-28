/**
 * AccJournals — the journal register, a journal's detail, manual journals and reversal.
 *
 * Automatic journals show their source; manual ones are drafted here and
 * posted with Approve. A posted journal is read-only; the only way to change
 * it is a reversal with a reason, which this screen offers on every posted row.
 */
import React, { useEffect, useMemo, useState } from "react";
import { useApp } from "../../lib/AppContext.jsx";
import { usePermission } from "../../lib/usePermission.js";
import { Modal } from "../../components/UI.jsx";
import { HrPage, HrTable, HrFilterBar, Filter, HrSearch, HrButton, HrDrawer, HrPagination, Status, HrLines, Facts, Fact, usePagedList, humanError, P } from "../../components/HrKit.jsx";
import { searchJournals, getJournal, createJournal, updateJournal, discardJournal, postJournal, reverseJournal, getChart, getCostCenters } from "../../services/accountsService.js";
import { amount, drcr, fmtDate, fmtDateTime, isoToday, isoMonthStart } from "../../utils/financial.js";
import { ACC_CRUMB, AI, DateRange, BranchPicker, ReasonPrompt, Source, Money, takeIntent } from "./AccKit.jsx";

export default function AccJournals({ onNavigate }) {
  const perm = usePermission("accjournals");
  const { addToast } = useApp();
  // Another screen may have sent us here: a journal to open, a status to filter, or "start a new one".
  const [intent] = useState(() => takeIntent("accjournals"));
  const [range, setRange] = useState({ from: intent?.journalId ? "2000-01-01" : isoMonthStart(), to: isoToday() });
  const [filters, setFilters] = useState({ status: intent?.status || "", type: "", q: "", branchId: null });
  const [open, setOpen] = useState(intent?.journalId || null);        // journal id in the drawer
  const [editing, setEditing] = useState(intent?.action === "new" ? {} : null);  // null | {} (new) | journal (draft)
  const [reversing, setReversing] = useState(null);
  const list = usePagedList(({ page, size }) => searchJournals({ ...filters, from: range.from, to: range.to, page, size }),
    [filters.status, filters.type, filters.q, filters.branchId, range.from, range.to]);

  const cols = useMemo(() => [
    { key: "journalDate", label: "Date", sortable: true, render: (j) => fmtDate(j.journalDate) },
    { key: "journalNo", label: "Journal", lead: true, sortable: true, render: (j) => <><b>{j.journalNo}</b><div className="acc-muted">{j.reference}</div></> },
    { key: "description", label: "Description", render: (j) => j.description || "—" },
    { key: "journalType", label: "Type", sortable: true, render: (j) => <span className="acc-type">{j.journalType}</span> },
    { key: "source", label: "Source", render: (j) => <Source module={j.sourceModule} type={j.sourceType} /> },
    { key: "totalDebit", label: "Debit", num: true, sortable: true, sortValue: (j) => Number(j.totalDebit), render: (j) => drcr(j.totalDebit) },
    { key: "totalCredit", label: "Credit", num: true, sortable: true, sortValue: (j) => Number(j.totalCredit), render: (j) => drcr(j.totalCredit) },
    { key: "journalStatus", label: "Status", render: (j) => <Status code={j.journalStatus} /> },
  ], []);

  const reverse = async (reason) => {
    try { await reverseJournal(reversing.pidJournal, reason); addToast(`${reversing.journalNo} reversed.`); setReversing(null); list.reload(); if (open) setOpen(open); }
    catch (e) { addToast(humanError(e, "Could not reverse the journal."), "error"); }
  };

  return (
    <HrPage className="acc-page" icon={AI.journal} crumbs={[ACC_CRUMB]} title="Journals" subtitle="Every posting in the books — automatic from the business screens, manual from here. Posted journals are immutable; corrections are reversals."
            actions={perm.canCreate && <HrButton variant="primary" icon={P.plus} onClick={() => setEditing({})}>New manual journal</HrButton>}>
      <HrFilterBar active={(filters.status ? 1 : 0) + (filters.type ? 1 : 0) + (filters.q ? 1 : 0)} onReset={() => setFilters({ status: "", type: "", q: "", branchId: null })}>
        <Filter label="Search" grow><HrSearch value={filters.q} onChange={(q) => setFilters((f) => ({ ...f, q }))} placeholder="Journal no, reference, description…" /></Filter>
        <DateRange from={range.from} to={range.to} onChange={setRange} />
        <Filter label="Status"><select value={filters.status} onChange={(e) => setFilters((f) => ({ ...f, status: e.target.value }))}>
          <option value="">Any</option><option>DRAFT</option><option>POSTED</option><option>REVERSED</option></select></Filter>
        <Filter label="Type"><select value={filters.type} onChange={(e) => setFilters((f) => ({ ...f, type: e.target.value }))}>
          <option value="">Any</option>{["AUTO", "MANUAL", "REVERSAL", "ADJUSTMENT", "OPENING", "CLOSING"].map((t) => <option key={t}>{t}</option>)}</select></Filter>
        <BranchPicker value={filters.branchId} onChange={(branchId) => setFilters((f) => ({ ...f, branchId }))} allowAll />
      </HrFilterBar>

      <HrTable cols={cols} rows={list.rows} rowKey={(j) => j.pidJournal} loading={list.busy} error={list.err} onRetry={list.reload}
               onRowClick={(j) => setOpen(j.pidJournal)} caption={`${list.total} journal(s)`}
               empty={{ icon: AI.journal, title: "No journal in this window", hint: filters.status || filters.type || filters.q ? "Clear the filters or widen the dates." : "Journals appear here as sales, purchases, payments and payroll are recorded — or start a manual one.", action: perm.canCreate && !filters.q ? <HrButton size="sm" icon={P.plus} onClick={() => setEditing({})}>New manual journal</HrButton> : null }} />
      <HrPagination page={list.page} size={list.size} total={list.total} totalPages={list.totalPages} onPage={list.setPage} onSize={list.setSize} />

      {open && <JournalDrawer id={open} onClose={() => setOpen(null)} perm={perm}
                              onEdit={(j) => { setOpen(null); setEditing(j); }}
                              onReverse={(j) => setReversing(j)}
                              onChanged={() => list.reload()} onNavigate={onNavigate} />}
      {editing && <JournalForm initial={editing.pidJournal ? editing : null} onClose={() => setEditing(null)} perm={perm}
                               onSaved={(j, posted) => { setEditing(null); list.reload(); addToast(posted ? `${j.journalNo} posted.` : `${j.journalNo} saved as draft.`); }} />}
      {reversing && <ReasonPrompt title={`Reverse ${reversing.journalNo}`} hint="A reversal journal is posted with the opposite entries. The original stays in the ledger, marked reversed." confirmLabel="Reverse" onConfirm={reverse} onClose={() => setReversing(null)} />}
    </HrPage>
  );
}

function JournalDrawer({ id, onClose, perm, onEdit, onReverse, onChanged, onNavigate }) {
  const { addToast } = useApp();
  const [d, setD] = useState(null);
  const [busy, setBusy] = useState(false);
  const load = async () => { try { setD(await getJournal(id)); } catch (e) { addToast(humanError(e), "error"); onClose(); } };
  useEffect(() => { load(); }, [id]); // eslint-disable-line react-hooks/exhaustive-deps
  if (!d) return <HrDrawer title="Journal" onClose={onClose}><p className="acc-muted">Loading…</p></HrDrawer>;
  const j = d.journal;
  const post = async () => { setBusy(true); try { await postJournal(j.pidJournal); addToast(`${j.journalNo} posted.`); await load(); onChanged(); } catch (e) { addToast(humanError(e, "Could not post."), "error"); } finally { setBusy(false); } };
  const discard = async () => { setBusy(true); try { await discardJournal(j.pidJournal); addToast("Draft discarded."); onChanged(); onClose(); } catch (e) { addToast(humanError(e), "error"); } finally { setBusy(false); } };
  const footer = (
    <>
      {j.journalStatus === "DRAFT" && perm.canEdit && <HrButton onClick={() => onEdit(j)}>Edit draft</HrButton>}
      {j.journalStatus === "DRAFT" && perm.canDelete && <HrButton onClick={discard} busy={busy}>Discard</HrButton>}
      {j.journalStatus === "DRAFT" && perm.canApprove && <HrButton variant="primary" onClick={post} busy={busy}>Post</HrButton>}
      {j.journalStatus === "POSTED" && perm.canApprove && <HrButton icon={P.undo} onClick={() => onReverse(j)}>Reverse…</HrButton>}
    </>
  );
  return (
    <HrDrawer title={j.journalNo} subtitle={`${j.journalType} · ${fmtDate(j.journalDate)}`} onClose={onClose} footer={footer} width={640}>
      <div className="acc-drawer-status"><Status code={j.journalStatus} /> <Source module={j.sourceModule} type={j.sourceType} /></div>
      <Facts>
        <Fact label="Reference" value={j.reference || "—"} />
        <Fact label="Description" value={j.description || "—"} />
        <Fact label="Posted" value={j.postedAt ? `${fmtDateTime(j.postedAt)} by ${j.postedBy}` : "—"} />
        {j.reversedAt && <Fact label="Reversed" value={`${fmtDateTime(j.reversedAt)} by ${j.reversedByUser} — ${j.reversalReason}`} />}
        {d.reversal && <Fact label="Reversed by" value={d.reversal} />}
        {d.reversalOf && <Fact label="Reversal of" value={d.reversalOf} />}
        {d.event && <Fact label="Accounting event" value={`${d.event.eventType} · ${d.event.eventStatus}`} />}
      </Facts>
      <table className="hr-table acc-lines-table">
        <thead><tr><th>#</th><th>Account</th><th>Description</th><th className="hr-num">Debit</th><th className="hr-num">Credit</th><th>Party</th></tr></thead>
        <tbody>{(d.lines || []).map((l) => (
          <tr key={l.lineNo}><td>{l.lineNo}</td><td><b>{l.code}</b> {l.account}</td><td>{l.description || ""}</td>
            <td className="hr-num">{drcr(l.debit)}</td><td className="hr-num">{drcr(l.credit)}</td><td>{l.partyName || ""}</td></tr>))}
        </tbody>
        <tfoot><tr><td colSpan={3}>Total</td><td className="hr-num"><b>{drcr(j.totalDebit)}</b></td><td className="hr-num"><b>{drcr(j.totalCredit)}</b></td><td /></tr></tfoot>
      </table>
      {j.sourceModule && j.sourceModule !== "MANUAL" && (
        <p className="acc-muted">Booked from {j.sourceModule.toLowerCase().replace("_", " ")} #{j.sourceId}. Open the source screen to see the business document; this journal cannot be edited here.</p>
      )}
    </HrDrawer>
  );
}

function JournalForm({ initial, onClose, onSaved, perm }) {
  const { addToast } = useApp();
  const [chart, setChart] = useState([]);
  const [costCentres, setCostCentres] = useState([]);
  const [busy, setBusy] = useState(false);
  const [form, setForm] = useState({ journalDate: initial?.journalDate || isoToday(), reference: initial?.reference || "", description: initial?.description || "", journalType: initial?.journalType || "MANUAL" });
  const [lines, setLines] = useState(initial ? [] : [{ key: 1, accountId: "", debit: "", credit: "", description: "", costCenterId: "" }, { key: 2, accountId: "", debit: "", credit: "", description: "", costCenterId: "" }]);
  useEffect(() => {
    getChart().then((c) => setChart(c.filter((a) => a.isGroup === 0 && a.isActive === 1)));
    /* Optional, and often not set up at all: a failure here must not stop a
       journal being written. */
    getCostCenters().then(setCostCentres).catch(() => setCostCentres([]));
    if (initial) getJournal(initial.pidJournal).then((d) => setLines((d.lines || []).map((l, i) => ({ key: i + 1, accountId: l.accountId, debit: Number(l.debit) || "", credit: Number(l.credit) || "", description: l.description || "", costCenterId: l.costCenterId || "" }))));
  }, [initial]);
  const totals = lines.reduce((t, l) => ({ d: t.d + (Number(l.debit) || 0), c: t.c + (Number(l.credit) || 0) }), { d: 0, c: 0 });
  const balanced = Math.abs(totals.d - totals.c) < 0.005 && totals.d > 0;
  const set = (k, v) => setForm((f) => ({ ...f, [k]: v }));
  const setLine = (key, k, v) => setLines((ls) => ls.map((l) => (l.key === key ? { ...l, [k]: v, ...(k === "debit" && v ? { credit: "" } : {}), ...(k === "credit" && v ? { debit: "" } : {}) } : l)));
  const save = async () => {
    const body = { ...form, lines: lines.filter((l) => l.accountId).map((l) => ({ accountId: Number(l.accountId), debit: Number(l.debit) || 0, credit: Number(l.credit) || 0, description: l.description, costCenterId: l.costCenterId ? Number(l.costCenterId) : null })) };
    setBusy(true);
    try { const j = initial ? await updateJournal(initial.pidJournal, body) : await createJournal(body); onSaved(j, j.journalStatus === "POSTED"); }
    catch (e) { addToast(humanError(e, "Could not save the journal."), "error"); }
    finally { setBusy(false); }
  };
  /* Per line, not per journal: one payment often splits across centres, and a
     header-only dimension would force a second journal to say so. */
  const cols = [{ key: "account", label: "Account", width: "30%" }, { key: "description", label: "Description" },
    ...(costCentres.length ? [{ key: "centre", label: "Cost centre", width: "20%" }] : []),
    { key: "debit", label: "Debit", num: true, width: 130 }, { key: "credit", label: "Credit", num: true, width: 130 }];
  return (
    <Modal title={initial ? `Edit draft ${initial.journalNo}` : "New manual journal"} subtitle="Debits must equal credits. System-posted accounts (receivables, inventory, payables) cannot be entered by hand." onClose={onClose} width={860}
      footer={<><HrButton onClick={onClose} disabled={busy}>Cancel</HrButton><HrButton variant="primary" busy={busy} disabled={!balanced} onClick={save}>{initial ? "Save draft" : "Save"}</HrButton></>}>
      <div className="acc-form-grid">
        <div className="field"><label>Date</label><input type="date" value={form.journalDate} onChange={(e) => set("journalDate", e.target.value)} /></div>
        <div className="field"><label>Type</label><select value={form.journalType} onChange={(e) => set("journalType", e.target.value)}><option>MANUAL</option><option>ADJUSTMENT</option><option>OPENING</option></select></div>
        <div className="field"><label>Reference</label><input value={form.reference} onChange={(e) => set("reference", e.target.value)} placeholder="Bill no, memo…" /></div>
        <div className="field acc-span2"><label>Description</label><input value={form.description} onChange={(e) => set("description", e.target.value)} placeholder="What this entry records" /></div>
      </div>
      <HrLines cols={cols} rows={lines} numbered onRemove={(l) => setLines((ls) => ls.filter((x) => x.key !== l.key))}
        onAdd={() => setLines((ls) => [...ls, { key: Date.now(), accountId: "", debit: "", credit: "", description: "", costCenterId: "" }])} addLabel="Add a line"
        cell={(l, i, key) => {
          if (key === "account") return (
            <select value={l.accountId} onChange={(e) => setLine(l.key, "accountId", e.target.value)} aria-label={`Account for line ${i + 1}`}>
              <option value="">Choose an account…</option>
              {chart.map((a) => <option key={a.pidAccount} value={a.pidAccount} disabled={a.allowManual === 0 && form.journalType !== "OPENING"}>{a.accountCode} {a.accountName}{a.allowManual === 0 ? " (system)" : ""}</option>)}
            </select>);
          if (key === "description") return <input value={l.description} onChange={(e) => setLine(l.key, "description", e.target.value)} />;
          if (key === "centre") return (
            <select value={l.costCenterId} onChange={(e) => setLine(l.key, "costCenterId", e.target.value)} aria-label={`Cost centre for line ${i + 1}`}>
              <option value="">— not set —</option>
              {costCentres.map((c) => <option key={c.pidCostCenter} value={c.pidCostCenter}>{c.ccCode} {c.ccName}</option>)}
            </select>);
          return <input type="number" min="0" step="0.01" value={l[key]} onChange={(e) => setLine(l.key, key, e.target.value)} className="hr-num" />;
        }}
        foot={<tr><td colSpan={costCentres.length ? 4 : 3}>Totals</td><td className="hr-lines-num"><b>{amount(totals.d)}</b></td><td className="hr-lines-num"><b>{amount(totals.c)}</b></td><td /></tr>} />
      <p className={`acc-balance-note${balanced ? " is-ok" : " is-bad"}`}>{balanced ? "Balanced." : totals.d === 0 && totals.c === 0 ? "Enter the lines." : `Out of balance by ${amount(Math.abs(totals.d - totals.c))}.`}</p>
    </Modal>
  );
}
