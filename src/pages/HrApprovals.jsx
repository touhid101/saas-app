/**
 * HrApprovals.jsx — the inbox, and the chains behind it.
 *
 * <b>The inbox is the point.</b> "What is waiting on me" was previously six
 * screens, which is why it went unanswered. It works whether or not a single
 * chain has been configured: with none it aggregates the per-module queues that
 * already existed.
 *
 * <b>Nothing is configured out of the box.</b> With no chain for a workflow,
 * that workflow takes one signature exactly as it did before — the same
 * contract the working policy, the PF scheme and the gratuity formula follow. A
 * chain only ever narrows who may sign; it can never hand somebody an authority
 * the RBAC screen did not give them.
 *
 * Three sections, one on screen at a time: what is waiting on the reader, what
 * is waiting on somebody else, and the chains. The inbox arrives whole — it is
 * a union of six queues, none of which pages — so it is searched, narrowed by
 * workflow and paged here.
 */
import React, { useState, useEffect, useCallback, useMemo } from "react";
import { Card, Modal, Field } from "../components/UI.jsx";
import { useApp } from "../lib/AppContext.jsx";
import { usePermission } from "../lib/usePermission.js";
import { useConfirm } from "../lib/ConfirmProvider.jsx";
import {
  HrPage, Kpis, Kpi, HrTable, HrTabs, HrTabBar, Facts, Fact, HrSearch, Filter, HrFilterBar,
  HrButton, HrRowActions, HrDrawer, HrTimeline, HrPagination, HrPageSkeleton, HrErrorState, HrLines,
  HrFormSection, Ic, PolicyNote, Blocked, useLocalPage, humanError, P, HR_CRUMB,
} from "../components/HrKit.jsx";
import {
  getWorkflows, getApprovalFlows, createApprovalFlow, updateApprovalFlow,
  retireApprovalFlow, getApprovalInbox, getApprovalChain, getExpenseClaim, toneFor, money,
  APPROVER_ROLES, WORKFLOW_UNIT, WORKFLOW_PAGE,
} from "../services/hrService.js";
import { ReceiptGallery, ReceiptViewer, receiptItems } from "../components/ReceiptKit.jsx";

const emptyLevel = () => ({ approverRole: "MANAGER", levelLabel: "" });

/* The roles that appear more than once — the server refuses the chain, so
   the form says so first. */
const duplicateRoles = (levels) => {
  const seen = new Set(); const dup = new Set();
  for (const l of levels || []) { if (seen.has(l.approverRole)) dup.add(l.approverRole); seen.add(l.approverRole); }
  return dup;
};
const roleLabel = (code) => APPROVER_ROLES.find((r) => r.value === code)?.label || code;
const fmt = (n) => Number(n || 0).toLocaleString("en-US");
const titleCase = (w) => w.charAt(0) + w.slice(1).toLowerCase();
const matchRow = (r, q) =>
  [r.empName, r.workflowLabel, r.summary, r.flowName].some((v) => String(v || "").toLowerCase().includes(q));

export default function HrApprovals({ onNavigate }) {
  const { addToast } = useApp();
  const confirm = useConfirm();
  const perm = usePermission("payroll");

  const [pane, setPane]   = useState("mine");
  const [ready, setReady] = useState(false);
  const [busy, setBusy]   = useState(false);
  const [inbox, setInbox] = useState([]);
  const [flows, setFlows] = useState([]);
  const [workflows, setWorkflows] = useState([]);
  const [form, setForm]   = useState(null);
  const [loadErr, setLoadErr] = useState(null);

  const [q, setQ] = useState("");
  const [kind, setKind] = useState("");

  /* The row being read in the drawer, and the signatures behind it. `chain`
     stays null while it is being fetched so the panel can say "loading" rather
     than "nothing recorded yet", which is a different and untrue answer. */
  const [review, setReview] = useState(null);
  /* An expense claim waiting here is decided on its own screen, but the
     receipts are the thing an approver wants to glance at before going there
     — so the claim's lines are read alongside the signatures. */
  const [claim, setClaim] = useState(null);
  const [viewer, setViewer] = useState(null);   // { items, index }
  const [chain, setChain]   = useState(null);
  const [chainErr, setChainErr] = useState(null);

  const load = useCallback(async () => {
    setBusy(true);
    try {
      const [i, f, w] = await Promise.all([
        getApprovalInbox().catch(() => []),
        getApprovalFlows().catch(() => []),
        getWorkflows().catch(() => []),
      ]);
      setInbox(i || []);
      setFlows(f || []);
      setWorkflows(w || []);
      setLoadErr(null);
    } catch (err) {
      setLoadErr(humanError(err, "The approval inbox could not be loaded."));
    } finally {
      setBusy(false);
      setReady(true);
    }
  }, []);

  useEffect(() => { load(); }, [load]);

  /* Memoised: useLocalPage resets to page one whenever the rows array changes
     identity, and a filter() in the render body would hand it a new one each
     time — so no page other than the first could ever be reached. */
  const mineAll = useMemo(() => inbox.filter((i) => i.mine), [inbox]);
  const othersAll = useMemo(() => inbox.filter((i) => !i.mine), [inbox]);
  const mineRows = useMemo(() => (kind ? mineAll.filter((r) => r.workflowCode === kind) : mineAll), [mineAll, kind]);
  const otherRows = useMemo(() => (kind ? othersAll.filter((r) => r.workflowCode === kind) : othersAll), [othersAll, kind]);
  const mine = useLocalPage(mineRows, { q, match: matchRow });
  const others = useLocalPage(otherRows, { q, match: matchRow });

  /**
   * Open one waiting object and fetch its signatures.
   *
   * <p>An object with no chain has no signatures to fetch — it is simply
   * submitted — so the drawer shows the request and says so, rather than firing
   * a request that can only come back empty.
   */
  const openReview = async (row) => {
    setReview(row);
    setChain(null);
    setChainErr(null);
    setClaim(null);
    if (row.workflowCode === "EXPENSE" && row.refId) {
      getExpenseClaim(row.refId).then(setClaim).catch(() => setClaim(null));
    }
    if (!row.chained) { setChain([]); return; }
    try {
      setChain(await getApprovalChain(row.workflowCode, row.refId));
    } catch (err) {
      setChainErr(humanError(err, "The signatures could not be read."));
    }
  };

  const lookAt = (line) => {
    const items = receiptItems(claim?.lines || [], claim || {});
    const index = Math.max(0, items.findIndex((it) => it.lineId === line.id));
    if (items.length) setViewer({ items, index });
  };

  const save = async () => {
    try {
      const body = {
        workflowCode: form.workflowCode,
        flowName: form.flowName || null,
        minValue: form.minValue === "" ? null : Number(form.minValue),
        description: form.description || null,
        levels: (form.levels || []).map((l) => ({
          approverRole: l.approverRole,
          levelLabel: l.levelLabel || null,
        })),
      };
      if (form.id) await updateApprovalFlow(form.id, body);
      else await createApprovalFlow(body);
      setForm(null);
      addToast("Approval chain saved.");
      load();
    } catch (err) {
      addToast(humanError(err, "Could not save that chain."), "error");
    }
  };

  const retire = async (f) => {
    const ok = await confirm({
      title: `Retire ${f.flowName}?`,
      body: "New requests take one signature again. Requests already travelling through it "
          + "finish under the levels they started with.",
      confirmLabel: "Retire", tone: "danger",
    });
    if (!ok) return;
    try {
      await retireApprovalFlow(f.id);
      addToast("Chain retired.");
      load();
    } catch (err) { addToast(humanError(err), "error"); }
  };

  if (!ready) return <HrPageSkeleton label="Loading approvals…" kpis={3} />;

  if (loadErr && !inbox.length && !flows.length) {
    return (
      <HrPage crumbs={[HR_CRUMB, { label: "Approvals" }]} title="Approvals">
        <HrErrorState title="The approval inbox could not be loaded" hint={loadErr} onRetry={load} retrying={busy} />
      </HrPage>
    );
  }

  const tabs = [
    { id: "mine",   label: "Waiting on me", icon: P.inbox, badge: mineAll.length },
    { id: "others", label: "On somebody else", icon: P.clock, badge: othersAll.length },
    { id: "chains", label: "Chains", icon: P.settings },
  ];

  /* The workflows actually present in the inbox — a filter offering "Gratuity"
     when nothing of the kind is waiting is a filter that returns nothing. */
  const kinds = [...new Map(inbox.map((r) => [r.workflowCode, r.workflowLabel])).entries()];

  const inboxFilters = (
    <HrFilterBar active={(q ? 1 : 0) + (kind ? 1 : 0)} onReset={() => { setQ(""); setKind(""); }}>
      <Filter label="Search" grow>
        <HrSearch value={q} onChange={setQ} placeholder="Name, kind, amount or chain" />
      </Filter>
      <Filter label="Kind">
        <select value={kind} onChange={(e) => setKind(e.target.value)}>
          <option value="">Any</option>
          {kinds.map(([code, label]) => <option key={code} value={code}>{label}</option>)}
        </select>
      </Filter>
    </HrFilterBar>
  );
  const filtered = Boolean(q || kind);
  const noMatch = {
    icon: P.filter, title: "Nothing waiting matches that", hint: "Try another spelling, or reset the filter.",
    action: <HrButton icon={P.reset} onClick={() => { setQ(""); setKind(""); }}>Reset</HrButton>,
  };

  return (
    <HrPage
      crumbs={[HR_CRUMB, { label: "Approvals" }]}
      title="Approvals"
      subtitle="Everything waiting for a decision, and who signs it"
      actions={
        <>
          <HrButton iconOnly icon={P.reset} title="Refresh" onClick={load} busy={busy} />
          {perm.canEdit && (
            <HrButton variant="primary" icon={P.plus}
                      onClick={() => setForm({
                        workflowCode: workflows[0] || "LEAVE", flowName: "",
                        minValue: "", description: "", levels: [emptyLevel()],
                      })}>
              Add a chain
            </HrButton>
          )}
        </>
      }>

      <Kpis>
        <Kpi label="Waiting on me" value={fmt(mineAll.length)} warn={mineAll.length > 0}
             icon={P.inbox} onClick={() => setPane("mine")} note={mineAll.length ? "Yours to sign" : "Nothing to sign"} />
        <Kpi label="Waiting on somebody else" value={fmt(othersAll.length)} icon={P.clock}
             onClick={() => setPane("others")} note={othersAll.length ? "Somebody else signs these" : "Nothing held up"} />
        <Kpi label="Chains configured" value={fmt(flows.length)} icon={P.settings}
             onClick={() => setPane("chains")}
             note={flows.length === 0 ? "one signature everywhere" : "Open the chains"} />
      </Kpis>

      <HrTabBar tabs={tabs} active={pane} onChange={setPane} />

      {pane === "mine" && (
        <Card title={`Waiting on me · ${fmt(mine.total)}`}>
          {inboxFilters}
          <HrTable
            rowKey={(r) => r.workflowCode + r.refId}
            rows={mine.rows}
            onRowClick={openReview}
            caption="Requests this reader can sign now"
            empty={filtered ? noMatch : {
              icon: P.check,
              title: "Nothing needs you",
              hint: "Every request you are entitled to sign has been decided. "
                  + "Requests waiting on somebody else are under the next tab.",
            }}
            cols={[
              { key: "workflowLabel", label: "What", lead: true, sortable: true },
              { key: "empName", label: "Who", sortable: true },
              { key: "summary", label: "How much", num: true },
              { key: "when", label: "When", sortable: true },
              { key: "progress", label: "Step", advisory: true,
                render: (r) => (r.chained
                  ? <span>{r.progress}<span className="hr-dash"> · {r.flowName}</span></span>
                  : <span className="hr-dash">one signature</span>) },
              { key: "act", label: "", actions: true,
                render: (r) => (
                  <HrRowActions
                    items={[{ label: "Decide it on its own screen", icon: P.chevron,
                              onClick: () => onNavigate?.(WORKFLOW_PAGE[r.workflowCode] || "hr") }]}>
                    <HrButton size="sm" icon={P.eye} onClick={() => openReview(r)}>Review</HrButton>
                  </HrRowActions>
                ) },
            ]}
          />
          <HrPagination page={mine.page} size={mine.size} total={mine.total} totalPages={mine.totalPages}
                        onPage={mine.setPage} onSize={mine.setSize} />
          <PolicyNote>
            Decisions are still made on each workflow's own screen — this says what is waiting,
            not what the decision is. A row only appears where you hold the Approve right for that
            module.
          </PolicyNote>
        </Card>
      )}

      {pane === "others" && (
        <Card title={`Waiting on somebody else · ${fmt(others.total)}`}>
          {inboxFilters}
          <HrTable
            rowKey={(r) => r.workflowCode + r.refId}
            rows={others.rows}
            onRowClick={openReview}
            caption="Requests waiting on somebody else"
            empty={filtered ? noMatch : {
              icon: P.check, title: "Nothing is held up",
              hint: "No request is waiting on a signature other than yours.",
            }}
            cols={[
              { key: "workflowLabel", label: "What", lead: true, sortable: true },
              { key: "empName", label: "Who", sortable: true },
              { key: "summary", label: "How much", num: true },
              { key: "when", label: "When", sortable: true },
              { key: "progress", label: "Waiting at", advisory: true },
              { key: "flowName", label: "Chain", advisory: true },
            ]}
          />
          <HrPagination page={others.page} size={others.size} total={others.total} totalPages={others.totalPages}
                        onPage={others.setPage} onSize={others.setSize} />
          <PolicyNote>
            Shown rather than hidden, because "why has my leave not been approved" is a question
            somebody asks — and the answer is here.
          </PolicyNote>
        </Card>
      )}

      {pane === "chains" && (
        <Card title="Approval chains">
          {flows.length === 0 ? (
            <Blocked>
              <strong>No chains are configured</strong>, so every workflow takes one signature —
              exactly how this application behaved before chains existed. Add one to say, for
              instance, that leave over three days needs the manager <em>and</em> the branch
              administrator.
            </Blocked>
          ) : (
            <HrTable
              rowKey={(r) => r.id}
              rows={flows}
              empty=""
              cols={[
                { key: "workflowLabel", label: "Workflow", lead: true, sortable: true },
                { key: "flowName", label: "Chain" },
                { key: "minValue", label: "From", num: true,
                  render: (r) => (r.minValue == null
                    ? <span className="hr-dash">anything</span>
                    : `${r.minValue} ${WORKFLOW_UNIT[r.workflowCode] || ""}`) },
                { key: "levels", label: "Signatures",
                  render: (r) => (r.levels || []).map((l) => l.levelLabel || roleLabel(l.approverRole)).join(" → ") },
                { key: "act", label: "", actions: true,
                  render: (r) => perm.canEdit && (
                    <HrRowActions
                      items={[{ label: "Retire this chain", icon: P.ban, danger: true, onClick: () => retire(r) }]}>
                      <HrButton size="sm" icon={P.edit}
                                onClick={() => setForm({
                                  id: r.id, workflowCode: r.workflowCode,
                                  flowName: r.flowName, minValue: r.minValue ?? "",
                                  description: r.description || "",
                                  levels: (r.levels || []).map((l) => ({
                                    approverRole: l.approverRole, levelLabel: l.levelLabel || "",
                                  })),
                                })}>
                        Edit
                      </HrButton>
                    </HrRowActions>
                  ) },
              ]}
            />
          )}
          <PolicyNote>
            A chain <strong>narrows</strong> who may sign and never widens it: the module's Approve
            right is still required, so a chain cannot give somebody an authority the access screen
            did not.
          </PolicyNote>
          <PolicyNote>
            Payroll is not on the list on purpose. A run already has five signatures of its own —
            calculate, review, approve, post, pay — and a second chain around them would give it
            two answers to "who signs next".
          </PolicyNote>
        </Card>
      )}

      <HrTabs active={pane} onChange={setPane} tabs={tabs} />

      {form && (
        <Modal title={form.id ? "Change a chain" : "Add a chain"} width={680}
               subtitle="Who has to sign a request of this kind, and in what order"
               onClose={() => setForm(null)}
               footer={
                 <>
                   <span className="hr-foot-status">
                     <b>{(form.levels || []).length}</b> {(form.levels || []).length === 1 ? "signature" : "signatures"}
                     {form.minValue !== "" ? <> · from <b>{form.minValue} {WORKFLOW_UNIT[form.workflowCode] || ""}</b></> : " · every request"}
                   </span>
                   <HrButton onClick={() => setForm(null)}>Cancel</HrButton>
                   <HrButton variant="primary" icon={P.check}
                             disabled={!(form.levels || []).length || duplicateRoles(form.levels).size > 0}
                             onClick={save}>{form.id ? "Save changes" : "Add the chain"}</HrButton>
                 </>
               }>
          <HrFormSection icon={P.settings} title="The chain" hint="What it is called, and which requests take it.">
            <Field label="Workflow" hint="Which kind of request this chain applies to.">
              <select value={form.workflowCode} onChange={(e) => setForm({ ...form, workflowCode: e.target.value })}>
                {workflows.map((w) => <option key={w} value={w}>{titleCase(w)}</option>)}
              </select>
            </Field>
            <Field label="Chain name" hint="How it appears on the inbox and the request.">
              <input value={form.flowName} placeholder="e.g. Long leave" onChange={(e) => setForm({ ...form, flowName: e.target.value })} />
            </Field>
            <Field label={`Applies from (${WORKFLOW_UNIT[form.workflowCode] || "value"})`}
                   hint="Requests at or above this take the chain. Blank means every request does.">
              <input type="number" min="0" step="0.01" value={form.minValue} placeholder="Everything"
                     onChange={(e) => setForm({ ...form, minValue: e.target.value })} />
            </Field>
          </HrFormSection>

          <HrFormSection icon={P.check} title={`Signatures, in order · ${(form.levels || []).length}`}
                         hint="The request moves down this list; each step waits for the one above it.">
          <div className="hr-form-full">
          <HrLines
            cols={[
              { key: "who", label: "Who signs", width: "46%" },
              { key: "label", label: "What to call this step" },
            ]}
            rows={form.levels || []}
            onRemove={(i) => setForm({ ...form, levels: form.levels.filter((_, j) => j !== i) })}
            onAdd={() => setForm({ ...form, levels: [...(form.levels || []), emptyLevel()] })}
            addLabel="Add a signature"
            empty="A chain needs at least one signature."
            cell={(l, i, key) => {
              const patch = (k, v) => { const next = [...form.levels]; next[i] = { ...l, [k]: v }; setForm({ ...form, levels: next }); };
              const dup = duplicateRoles(form.levels).has(l.approverRole);
              if (key === "who") return (
                <select value={l.approverRole} aria-label={`Level ${i + 1}: who signs`}
                        className={dup ? "is-invalid" : ""} aria-invalid={dup || undefined}
                        onChange={(e) => patch("approverRole", e.target.value)}>
                  {APPROVER_ROLES.map((r) => <option key={r.value} value={r.value}>{r.label}</option>)}
                </select>);
              return <input placeholder={roleLabel(l.approverRole)} aria-label={`Level ${i + 1}: label`} value={l.levelLabel}
                            onChange={(e) => patch("levelLabel", e.target.value)} />;
            }}
          />

          {duplicateRoles(form.levels).size > 0 && (
            <div className="hr-lines-problem" role="status">
              <Ic path={P.alert} size={14} />
              {[...duplicateRoles(form.levels)].map(roleLabel).join(", ")} appears twice — one signature cannot be given twice.
            </div>
          )}

          {/* The chain as it will run, so the order is seen rather than inferred. */}
          {(form.levels || []).length > 0 && (
            <div className="hr-chain" aria-label="The chain as it will run">
              <span className="hr-chain-end">Request</span>
              {(form.levels || []).map((l, i) => (
                <React.Fragment key={i}>
                  <span className="hr-chain-arrow" aria-hidden="true"><Ic path={P.chevron} size={13} /></span>
                  <span className={`hr-chain-step${duplicateRoles(form.levels).has(l.approverRole) ? " is-dup" : ""}`}>
                    <i>{i + 1}</i>{l.levelLabel || roleLabel(l.approverRole)}
                  </span>
                </React.Fragment>
              ))}
              <span className="hr-chain-arrow" aria-hidden="true"><Ic path={P.chevron} size={13} /></span>
              <span className="hr-chain-end">Approved</span>
            </div>
          )}

          <div className="hr-form-note">
            <Ic path={P.info} size={14} />
            <span><strong>The employee's own manager</strong> is read from their record; where nobody is set, that level refuses
              and says so rather than being skipped. A chain narrows who may sign and never widens it.</span>
          </div>
          </div>
          </HrFormSection>
        </Modal>
      )}

      {review && (
        <HrDrawer
          title={review.workflowLabel}
          subtitle={`${review.empName} · ${review.summary || ""}`}
          width={480}
          onClose={() => { setReview(null); setChain(null); setChainErr(null); setClaim(null); }}
          footer={
            <>
              <HrButton onClick={() => { setReview(null); setChain(null); setClaim(null); }}>Close</HrButton>
              <HrButton variant="primary" icon={P.chevron}
                        onClick={() => { setReview(null); onNavigate?.(WORKFLOW_PAGE[review.workflowCode] || "hr"); }}>
                Open {review.workflowLabel.toLowerCase()}
              </HrButton>
            </>
          }>

          <Facts>
            <Fact label="Employee" value={review.empName} />
            <Fact label="Amount" value={review.summary} num />
            <Fact label="Filed" value={review.when} />
            <Fact label="Chain" value={review.chained ? review.flowName : "One signature"} />
          </Facts>

          {/* What the claim is for, and the receipts behind it — read here, decided
              on the Expense Claims screen. View only: the evidence cannot change
              once a claim has been submitted. */}
          {review.workflowCode === "EXPENSE" && claim && (
            <div style={{ display: "flex", flexDirection: "column", gap: 12 }}>
              {claim.purpose && <PolicyNote>{claim.purpose}</PolicyNote>}
              <HrTable
                rowKey={(r) => r.id}
                rows={claim.lines || []}
                caption="Expenses on this claim"
                cols={[
                  { key: "typeName", label: "Kind", lead: true },
                  { key: "expenseDate", label: "Date" },
                  { key: "detail", label: "Detail", advisory: true,
                    render: (r) => [r.fromPlace, r.toPlace].filter(Boolean).join(" to ") || r.description || "—" },
                  { key: "claimedAmount", label: "Claimed", num: true, render: (r) => money(r.claimedAmount) },
                ]}
              />
              <ReceiptGallery lines={claim.lines || []} editable={false} onOpen={lookAt} />
            </div>
          )}

          <div>
            <h4 style={{ margin: "0 0 10px", fontSize: 13 }}>Signatures</h4>
            {chainErr
              ? <HrErrorState title="Signatures unavailable" hint={chainErr} onRetry={() => openReview(review)} />
              : chain === null
                ? <p className="hr-timeline-empty">Reading the signatures…</p>
                : <HrTimeline
                    empty={review.chained
                      ? "The chain has begun, but nobody has signed a level yet."
                      : "No chain applies, so this takes one signature and keeps no level history."}
                    items={chainTimeline(chain, review)} />}
          </div>

          <PolicyNote>
            {review.mine
              ? "You may sign this. The decision itself is still made on the workflow's own screen, "
                + "where the figures it changes are in front of you."
              : "This is waiting on somebody else. It is shown so that “why has this not been "
                + "approved” has an answer."}
          </PolicyNote>
        </HrDrawer>
      )}

      {viewer && (
        <ReceiptViewer items={viewer.items} index={viewer.index}
                       onIndex={(index) => setViewer({ ...viewer, index })} onClose={() => setViewer(null)} />
      )}
    </HrPage>
  );
}

/**
 * The signatures on one object, oldest first, as timeline entries.
 *
 * <p>The level still waiting is drawn as an entry of its own rather than left
 * off: "approved by the manager" and "approved by the manager, now with the
 * administrator" are different states, and only the second one explains the
 * delay somebody is asking about.
 */
function chainTimeline(rows, review) {
  const chain = (rows || [])[0];
  if (!chain) return [];

  const items = (chain.actions || []).map((a) => ({
    title: a.levelLabel || `Level ${a.levelNo}`,
    who: a.decidedBy,
    when: a.decidedAt ? String(a.decidedAt).replace("T", " ").slice(0, 16) : null,
    note: a.remarks,
    tone: toneFor(a.decision),
    icon: a.decision === "REJECTED" ? P.x : P.check,
  }));

  if (chain.requestStatus === "PENDING" && chain.currentLevel) {
    items.push({
      title: `Level ${chain.currentLevel} of ${chain.totalLevels}`,
      who: review?.mine ? "Waiting on you" : "Waiting on somebody else",
      tone: "amber",
      icon: P.clock,
    });
  }
  return items;
}
