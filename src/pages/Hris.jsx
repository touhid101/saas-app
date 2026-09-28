/**
 * Hris.jsx — employee self-service: my HR, my requests, my progress.
 *
 * The one page a person opens to know where they stand. It is theirs alone:
 * every figure comes from /api/v1/hr/me, where the server resolves the
 * employee from the login and nothing takes an employee id from the browser.
 *
 * The order is the order of urgency — who I am, what is waiting on me, what I
 * can do, what is in flight, then the figures and the record. Three sections,
 * one on screen at a time: Overview, My requests, Attendance & calendar.
 */
import React, { useCallback, useEffect, useMemo, useState } from "react";
import { Card } from "../components/UI.jsx";
import { useApp } from "../lib/AppContext.jsx";
import EmployeeAvatar from "../components/EmployeeAvatar.jsx";
import {
  HrPage, HrTabBar, HrTabs, Kpis, Kpi, HrButton, HrSearch, HrFilterBar, Filter, HrTable, HrPagination,
  HrRequestTimeline, HrEmptyState, HrErrorState, HrSkeleton, HrPageSkeleton, HrProgress, Status, Facts, Fact,
  HrDrawer, Ic, usePagedList, humanError, fmtWhen, P,
} from "../components/HrKit.jsx";
import { money, days } from "../services/hrService.js";
import * as hris from "../services/hrisService.js";
import { LeaveForm, ExpenseForm, AdvanceForm, LoanForm, CorrectionForm } from "./hris/HrisForms.jsx";
import HrisRequestDrawer, { TYPE_ICON } from "./hris/HrisRequestDrawer.jsx";
import HrisAttendance, { TodayCard } from "./hris/HrisAttendance.jsx";
import "../hris.css";

const fmt = (n) => Number(n || 0).toLocaleString("en-US");
const STATUS_ICON = { DRAFT: P.edit, PENDING: P.clock, APPROVED: P.check, PROCESSING: P.timer, COMPLETED: P.check, REJECTED: P.x, CANCELLED: P.ban };

export default function Hris({ onNavigate }) {
  const { addToast } = useApp();
  const [pane, setPane] = useState("overview");
  const [me, setMe] = useState(null);
  const [summary, setSummary] = useState(null);
  const [loadErr, setLoadErr] = useState(null);
  const [reloadToken, setReloadToken] = useState(0);
  const bump = () => setReloadToken((n) => n + 1);

  const [form, setForm] = useState(null);           // "leave" | "expense" | "advance" | "loan" | "correction"
  const [correctDay, setCorrectDay] = useState(null);
  const [open, setOpen] = useState(null);           // { type, id }
  const [profileOpen, setProfileOpen] = useState(false);
  const [attRows, setAttRows] = useState([]);       // recent recorded days, for the correction form

  const load = useCallback(async () => {
    try {
      const m = await hris.getMe();
      setMe(m);
      if (m?.linked) {
        const [s, a] = await Promise.all([hris.getSummary(), hris.getAttendance().catch(() => null)]);
        setSummary(s);
        // Two months of recorded days for the correction picker.
        const prev = await hris.getAttendance(shiftMonth(new Date().toISOString().slice(0, 7), -1)).catch(() => null);
        setAttRows([...(prev?.rows || []), ...(a?.rows || [])]);
      }
      setLoadErr(null);
    } catch (err) {
      setLoadErr(humanError(err, "Your HR information could not be loaded."));
    }
  }, []);
  useEffect(() => { load(); }, [load, reloadToken]);

  /* After a form: say what happened, then refresh only what it touched — the
     summary (tiles, progress, activity) and the list. */
  const done = (r) => {
    setForm(null); setCorrectDay(null);
    bump();
    if (r?.id && !r.quiet) setOpen({ type: r.type, id: r.id });
  };

  if (loadErr && !me) {
    return (
      <HrPage crumbs={[{ label: "HRIS" }]} title="HRIS" subtitle="Manage your HR activities and track your requests">
        <HrErrorState title="Your HR information could not be loaded" hint={loadErr} onRetry={load} />
      </HrPage>
    );
  }
  if (!me) return <HrPageSkeleton label="Loading your HR…" kpis={4} />;

  if (!me.linked) {
    return (
      <HrPage crumbs={[{ label: "HRIS" }]} title="HRIS" subtitle="Manage your HR activities and track your requests">
        <div className="hris-unlinked">
          <HrEmptyState icon={P.user} title="Your login is not linked to an employee record"
                        hint="Self-service shows one person's own HR, and this login has no employee behind it at this branch. Ask HR to link your login to your employee record; nothing else is needed." />
        </div>
      </HrPage>
    );
  }

  const s = summary;
  const tabs = [
    { id: "overview", label: "Overview", icon: P.user },
    { id: "requests", label: "My requests", icon: P.doc, badge: s?.pendingRequests || 0 },
    { id: "attendance", label: "Attendance", icon: P.calendar, badge: s?.correctionsPending || 0 },
  ];

  const quick = [
    { id: "leave", icon: P.calendar, label: "Apply for leave", hint: "Request time off" },
    { id: "expense", icon: P.receipt, label: "Expense claim", hint: "Get money back" },
    { id: "advance", icon: P.wallet, label: "Advance", hint: "Salary ahead of the payslip" },
    { id: "loan", icon: P.money, label: "Loan", hint: "Repaid by instalments" },
    { id: "correction", icon: P.clock, label: "Attendance correction", hint: "Put a recorded day right" },
  ];

  return (
    <HrPage crumbs={[{ label: "HRIS" }]} title="HRIS" subtitle="Manage your HR activities and track your requests"
            actions={<HrButton iconOnly icon={P.reset} title="Refresh" onClick={bump} />}>

      {/* ── Who I am ── */}
      <div className="hris-id">
        <EmployeeAvatar src={me.imagePath} initials={me.initials} name={me.empName} size={56} />
        <div className="hris-id-text">
          <h2 className="hris-id-name">{me.preferredName || me.empName}</h2>
          <p className="hris-id-line">
            <span>Code <b>{me.empId}</b></span>
            {me.designationName && <span>{me.designationName}</span>}
            {me.departmentName && <span>{me.departmentName}</span>}
            {me.branchName && <span>{me.branchName}</span>}
            <Status code={me.empStatus === "Active" ? "APPROVED" : "CANCELLED"} label={me.empStatus} />
          </p>
        </div>
        <div className="hris-id-actions">
          <HrButton icon={P.eye} onClick={() => setProfileOpen(true)}>View profile</HrButton>
        </div>
      </div>

      <HrTabBar tabs={tabs} active={pane} onChange={setPane} />

      {pane === "overview" && (<>
        {/* ── What needs me ── */}
        {s?.actions?.length > 0 && (
          <div className="hris-attn" role="status">
            <Ic path={P.alert} size={18} />
            <div className="hris-attn-body">
              <b>{s.actions.length === 1 ? "One request is waiting on you" : `${s.actions.length} requests are waiting on you`}</b>
              <ul className="hris-attn-list">
                {s.actions.map((a) => (
                  <li key={a.type + a.id}>
                    <Ic path={TYPE_ICON[a.type]} size={13} />
                    <b>{a.typeLabel}{a.requestNo ? ` ${a.requestNo}` : ""}</b>
                    <span>{a.actionText}</span>
                    <HrButton size="sm" variant="primary" onClick={() => setOpen({ type: a.type, id: a.id })}>Take action</HrButton>
                  </li>
                ))}
              </ul>
            </div>
          </div>
        )}

        {/* ── Quick actions ── */}
        <section className="hris-sec" aria-label="Quick actions">
          <div className="hris-sec-head"><h3>Quick actions</h3><p>File something new. Each opens a short form; nothing is sent until you submit.</p></div>
          <div className="hris-quick">
            {quick.map((q) => (
              <button key={q.id} type="button" className="hris-quick-btn" onClick={() => setForm(q.id)}>
                <span className="hris-quick-icon"><Ic path={q.icon} size={18} /></span>
                <span className="hris-quick-text"><b>{q.label}</b><span>{q.hint}</span></span>
              </button>
            ))}
          </div>
        </section>

        {/* ── My HR summary ── */}
        {!s ? <HrPageSkeleton label="Loading your figures…" kpis={4} rows={0} /> : (
          <Kpis>
            <Kpi label="Leave available" value={`${days(s.leaveAvailable)} d`} icon={P.calendar}
                 note={s.leaveBalances?.length ? `${s.leaveBalances.length} kind${s.leaveBalances.length === 1 ? "" : "s"} of leave` : "No balance opened this year"}
                 onClick={() => setForm("leave")} />
            <Kpi label="Pending requests" value={fmt(s.pendingRequests)} icon={P.clock} warn={s.pendingRequests > 0}
                 note={s.pendingRequests ? "Waiting for a decision" : "Nothing waiting"} onClick={() => setPane("requests")} />
            <Kpi label="Approved · 90 days" value={fmt(s.approvedRequests)} icon={P.check} tone="sage"
                 note={s.rejectedRequests ? `${s.rejectedRequests} rejected` : "None rejected"} onClick={() => setPane("requests")} />
            <Kpi label="Attendance issues" value={fmt(s.attendanceIssues)} icon={P.alert} warn={s.attendanceIssues > 0}
                 note={`${s.absentThisMonth} absent · ${s.lateThisMonth} late · ${s.correctionsPending} correction${s.correctionsPending === 1 ? "" : "s"} pending`}
                 onClick={() => setPane("attendance")} />
            <Kpi label="Outstanding advance / loan" value={money(Number(s.advanceOutstanding || 0) + Number(s.loanOutstanding || 0))} icon={P.wallet}
                 note={Number(s.monthlyRecovery || 0) > 0 ? `${money(s.monthlyRecovery)} recovered per payslip` : "Nothing being recovered"} />
            <Kpi label="Expense claims · this month" value={money(s.expenseClaimedThisMonth)} icon={P.receipt}
                 note={`${money(s.expensePaidThisMonth)} paid · ${s.expensePending} pending`} />
            {s.latestPayslip && (
              <Kpi label="Latest payslip" value={money(s.latestPayslip.netSalary)} icon={P.payroll}
                   note={`${s.latestPayslip.periodLabel || s.latestPayslip.payrollNo} · ${String(s.latestPayslip.paymentStatus || "").toLowerCase()}`} />
            )}
          </Kpis>
        )}

        {/* ── My request progress ── */}
        <section className="hris-sec" aria-label="My request progress">
          <div className="hris-sec-head">
            <h3>My request progress</h3><p>What is in flight, and where each one is.</p>
            <div className="hris-sec-act"><HrButton size="sm" variant="ghost" icon={P.doc} onClick={() => setPane("requests")}>All requests</HrButton></div>
          </div>
          {!s ? <HrSkeleton w="100%" h={120} /> : s.active.length === 0 ? (
            <HrEmptyState icon={P.check} title="Nothing is in progress" hint="You have no request waiting on anybody. File one from Quick actions." />
          ) : (
            <div className="hris-reqs">
              {s.active.map((r) => <RequestCard key={r.type + r.id} r={r} onOpen={() => setOpen({ type: r.type, id: r.id })} rail />)}
            </div>
          )}
        </section>

        {/* ── Widgets ── */}
        <div className="hris-grid-2">
          <TodayCard today={s?.today} month={s?.month} onCorrect={(d) => { setCorrectDay(d); setForm("correction"); }} />

          <Card title="My leave" right={<HrButton size="sm" variant="primary" icon={P.plus} onClick={() => setForm("leave")}>Apply</HrButton>}>
            {!s ? <HrSkeleton w="100%" h={80} /> : s.leaveBalances?.length ? (
              <div className="hris-bal">
                {s.leaveBalances.map((b) => {
                  const total = Number(b.entitled || 0) + Number(b.carriedForward || 0);
                  return (
                    <div className="hris-bal-row" key={b.id}>
                      <b>{b.leaveTypeName}</b>
                      <span className="hris-bal-num">{days(b.available)} <span style={{ fontWeight: 400 }}>of {days(total)}</span></span>
                      <HrProgress value={Number(b.taken || 0) + Number(b.pending || 0)} max={total} label={`${days(b.taken)} taken · ${days(b.pending)} pending`} tone="teal" />
                    </div>
                  );
                })}
              </div>
            ) : <HrEmptyState icon={P.calendar} title="No leave balance this year" hint="HR opens a balance per kind of leave. You can still apply." />}
            <RecentOfType s={s} type="LEAVE" onOpen={setOpen} onAll={() => setPane("requests")} />
          </Card>

          <Card title="My expenses" right={<HrButton size="sm" variant="primary" icon={P.plus} onClick={() => setForm("expense")}>New claim</HrButton>}>
            {!s ? <HrSkeleton w="100%" h={80} /> : (
              <div className="hris-money">
                <div className="hris-stat"><span>Claimed this month</span><b>{money(s.expenseClaimedThisMonth)}</b></div>
                <div className="hris-stat"><span>Approved</span><b>{money(s.expenseApprovedThisMonth)}</b></div>
                <div className="hris-stat"><span>Paid</span><b>{money(s.expensePaidThisMonth)}</b></div>
                <div className="hris-stat"><span>Pending</span><b className={s.expensePending > 0 ? "is-warn" : ""}>{s.expensePending}</b>{s.expenseRejected > 0 && <small>{s.expenseRejected} rejected recently</small>}</div>
              </div>
            )}
            <RecentOfType s={s} type="EXPENSE" onOpen={setOpen} onAll={() => setPane("requests")} />
          </Card>

          <Card title="My advance & loan" right={<>
            <HrButton size="sm" icon={P.wallet} onClick={() => setForm("advance")}>Advance</HrButton>
            <HrButton size="sm" variant="primary" icon={P.money} onClick={() => setForm("loan")}>Loan</HrButton>
          </>}>
            {!s ? <HrSkeleton w="100%" h={80} /> : (
              <div className="hris-money">
                <div className="hris-stat"><span>Advance outstanding</span><b>{money(s.advanceOutstanding)}</b></div>
                <div className="hris-stat"><span>Loan outstanding</span><b>{money(s.loanOutstanding)}</b></div>
                <div className="hris-stat"><span>Recovered per payslip</span><b>{money(s.monthlyRecovery)}</b></div>
                <div className="hris-stat"><span>Pending requests</span><b>{s.active.filter((r) => ["ADVANCE", "LOAN"].includes(r.type) && r.status === "PENDING").length}</b></div>
              </div>
            )}
            <RecentOfType s={s} type={["ADVANCE", "LOAN"]} onOpen={setOpen} onAll={() => setPane("requests")} />
          </Card>
        </div>

        {/* ── Recent activity ── */}
        <Card title="My recent activity" eyebrow="Last 60 days">
          {!s ? <HrSkeleton w="100%" h={80} /> : s.activity.length === 0 ? (
            <HrEmptyState icon={P.inbox} title="Nothing recent" hint="Anything you file, and every decision on it, appears here." />
          ) : (
            <ul className="hris-activity">
              {s.activity.map((a) => (
                <li key={a.type + a.id + a.at}>
                  <span><Ic path={TYPE_ICON[a.type] || P.doc} size={14} /></span>
                  <button type="button" onClick={() => setOpen({ type: a.type, id: a.id })}>
                    {a.text} <Status code={a.status} label={hris.REQUEST_STATUSES.includes(a.status) ? undefined : a.status} />
                  </button>
                  <time dateTime={a.at}>{fmtWhen(a.at)}</time>
                </li>
              ))}
            </ul>
          )}
        </Card>
      </>)}

      {pane === "requests" && <RequestHistory reloadToken={reloadToken} onOpen={setOpen} onNew={() => setForm("leave")} />}

      {pane === "attendance" && (<>
        <TodayCard today={s?.today} month={s?.month} onCorrect={(d) => { setCorrectDay(d); setForm("correction"); }} />
        <HrisAttendance reloadToken={reloadToken}
                        onOpen={(row) => (row.correctionStatus ? setOpen({ type: "ATTENDANCE", id: row.id }) : (setCorrectDay(row), setForm("correction")))}
                        onCorrect={(d) => { setCorrectDay(d); setForm("correction"); }} />
      </>)}

      <HrTabs tabs={tabs} active={pane} onChange={setPane} />

      {/* ── Forms ── */}
      {form === "leave" && <LeaveForm onClose={() => setForm(null)} onDone={done} addToast={addToast} />}
      {form === "expense" && <ExpenseForm onClose={() => setForm(null)} onDone={done} addToast={addToast} />}
      {form === "advance" && <AdvanceForm onClose={() => setForm(null)} onDone={done} addToast={addToast} outstanding={s?.advanceOutstanding} />}
      {form === "loan" && <LoanForm onClose={() => setForm(null)} onDone={done} addToast={addToast} outstanding={s?.loanOutstanding} />}
      {form === "correction" && <CorrectionForm onClose={() => { setForm(null); setCorrectDay(null); }} onDone={done} addToast={addToast} rows={attRows} initial={correctDay} />}

      {/* ── One request ── */}
      {open && <HrisRequestDrawer type={open.type} id={open.id} onClose={() => setOpen(null)} onChanged={bump} addToast={addToast} />}

      {/* ── My profile, read-only ── */}
      {profileOpen && (
        <HrDrawer title="My profile" subtitle="What HR holds about you. To change any of it, ask HR — it is edited on the employee record." width={460} onClose={() => setProfileOpen(false)}
                  footer={<HrButton onClick={() => setProfileOpen(false)}>Close</HrButton>}>
          <div style={{ display: "flex", alignItems: "center", gap: 14, marginBottom: 16 }}>
            <EmployeeAvatar src={me.imagePath} initials={me.initials} name={me.empName} size={64} />
            <div><b style={{ fontSize: 16 }}>{me.empName}</b><div className="hr-dash" style={{ fontSize: 13 }}>{me.empId}</div></div>
          </div>
          <Facts>
            <Fact label="Designation" value={me.designationName || "—"} />
            <Fact label="Department" value={me.departmentName || "—"} />
            <Fact label="Branch" value={me.branchName || "—"} />
            <Fact label="Employment" value={me.employmentTypeLabel || me.employmentType || "—"} />
            <Fact label="Status" value={me.empStatus} />
            <Fact label="Joined" value={me.hireDate || "—"} />
            <Fact label="Reports to" value={me.managerName || "—"} />
            <Fact label="Shift" value={me.shiftName || "—"} />
            <Fact label="Phone" value={me.phone || "—"} />
            <Fact label="Email" value={me.email || "—"} />
          </Facts>
        </HrDrawer>
      )}
    </HrPage>
  );
}

/* ── One request, as a card ─────────────────────────────────────────────── */

function RequestCard({ r, onOpen, rail = false }) {
  const [stages, setStages] = useState(null);
  useEffect(() => {
    if (!rail) return;
    let live = true;
    hris.getRequest(r.type, r.id).then((d) => { if (live) setStages(d?.stages || []); }).catch(() => { if (live) setStages([]); });
    return () => { live = false; };
  }, [r.type, r.id, rail]);
  return (
    <div className={`hris-req is-${String(r.status).toLowerCase()}`} role="button" tabIndex={0}
         onClick={onOpen} onKeyDown={(e) => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); onOpen(); } }}
         aria-label={`${r.typeLabel} ${r.requestNo || ""}: ${r.stage}. Open`}>
      <span className="hris-req-icon" aria-hidden="true"><Ic path={TYPE_ICON[r.type] || P.doc} size={18} /></span>
      <div className="hris-req-main">
        <div className="hris-req-top"><b>{r.typeLabel}</b>{r.requestNo && <span className="hris-req-no">{r.requestNo}</span>}</div>
        <div className="hris-req-title">{r.title}</div>
        {r.subtitle && <div className="hris-req-sub">{r.subtitle}</div>}
        <div className="hris-req-stage"><Ic path={STATUS_ICON[r.status] || P.clock} size={13} /><span>{r.stage}</span></div>
        {rail && stages && stages.length > 0 && <HrRequestTimeline stages={stages} compact />}
      </div>
      <div className="hris-req-side">
        <Status code={r.status} label={r.statusLabel} />
        {r.amount != null && <span className="hris-req-amt">{money(r.approvedAmount ?? r.amount)}</span>}
        {r.days != null && r.amount == null && <span className="hris-req-amt">{days(r.days)} d</span>}
        {r.updatedAt && <span className="hris-req-when">Updated {fmtWhen(r.updatedAt)}</span>}
      </div>
    </div>
  );
}

function RecentOfType({ s, type, onOpen, onAll }) {
  if (!s) return null;
  const types = Array.isArray(type) ? type : [type];
  const rows = (s.activity || []).filter((a) => types.includes(a.type)).slice(0, 3);
  return (
    <div style={{ marginTop: 12 }}>
      <div className="hris-sec-head" style={{ marginBottom: 6 }}>
        <p>Recent</p>
        <div className="hris-sec-act"><HrButton size="sm" variant="ghost" onClick={onAll}>View history</HrButton></div>
      </div>
      {rows.length === 0 ? <p className="hr-dash" style={{ margin: 0, fontSize: 12.5 }}>Nothing yet.</p> : (
        <ul className="hris-activity">
          {rows.map((a) => (
            <li key={a.type + a.id}>
              <span><Ic path={TYPE_ICON[a.type]} size={13} /></span>
              <button type="button" onClick={() => onOpen({ type: a.type, id: a.id })}>{a.text}</button>
              <time dateTime={a.at}>{fmtWhen(a.at)}</time>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

/* ── My request history ─────────────────────────────────────────────────── */

function RequestHistory({ reloadToken, onOpen }) {
  const [type, setType] = useState("");
  const [status, setStatus] = useState("");
  const [q, setQ] = useState("");
  const list = usePagedList(({ page, size }) => hris.getRequests({ type, status, q, page, size }), [type, status, q, reloadToken]);

  return (
    <Card title={`My request history · ${fmt(list.total)}`}>
      <div className="hris-chips" role="tablist" aria-label="Kind of request">
        {[{ id: "", label: "All" }, ...hris.REQUEST_TYPES].map((t) => (
          <button key={t.id} type="button" role="tab" aria-selected={type === t.id}
                  className={`hris-chip${type === t.id ? " is-on" : ""}`} onClick={() => setType(t.id)}>{t.label}</button>
        ))}
      </div>
      <HrFilterBar active={(status ? 1 : 0) + (q ? 1 : 0)} onReset={() => { setStatus(""); setQ(""); }}>
        <Filter label="Search" grow><HrSearch value={q} onChange={setQ} placeholder="Number, kind, description or stage" /></Filter>
        <Filter label="Status">
          <select value={status} onChange={(e) => setStatus(e.target.value)}>
            <option value="">Any</option>
            {hris.REQUEST_STATUSES.map((x) => <option key={x} value={x}>{x.charAt(0) + x.slice(1).toLowerCase()}</option>)}
          </select>
        </Filter>
      </HrFilterBar>
      <HrTable
        rowKey={(r) => r.type + r.id}
        rows={list.rows}
        loading={list.busy && !list.rows.length}
        error={list.err}
        onRetry={list.reload}
        onRowClick={(r) => onOpen({ type: r.type, id: r.id })}
        caption="My requests"
        empty={(type || status || q)
          ? { icon: P.filter, title: "No requests match that", hint: "Widen the filter, or reset it.", action: <HrButton icon={P.reset} onClick={() => { setType(""); setStatus(""); setQ(""); }}>Reset</HrButton> }
          : { icon: P.inbox, title: "No requests yet", hint: "Anything you file — leave, a claim, an advance, a loan, a correction — is listed here with where it is." }}
        cols={[
          { key: "requestNo", label: "Request", lead: true, render: (r) => <span><span className="hris-req-no">{r.requestNo || "—"}</span><br /><span className="hr-dash">{r.typeLabel}</span></span> },
          { key: "requestDate", label: "Filed", sortable: true, render: (r) => r.requestDate || "—" },
          { key: "title", label: "Description", render: (r) => <span><b>{r.title}</b>{r.subtitle && <><br /><span className="hr-dash">{r.subtitle}</span></>}</span> },
          { key: "amount", label: "Amount / days", num: true, render: (r) => (r.amount != null ? money(r.approvedAmount ?? r.amount) : r.days != null ? `${days(r.days)} d` : "—") },
          { key: "stage", label: "Current stage", advisory: true },
          { key: "status", label: "Status", render: (r) => <Status code={r.status} label={r.statusLabel} /> },
          { key: "updatedAt", label: "Updated", advisory: true, render: (r) => fmtWhen(r.updatedAt) },
          { key: "act", label: "", actions: true, render: (r) => <HrButton size="sm" icon={P.eye} onClick={() => onOpen({ type: r.type, id: r.id })}>View</HrButton> },
        ]}
      />
      <HrPagination page={list.page} size={list.size} total={list.total} totalPages={list.totalPages} onPage={list.setPage} onSize={list.setSize} />
    </Card>
  );
}

const shiftMonth = (ym, n) => {
  const [y, m] = ym.split("-").map(Number);
  const d = new Date(y, m - 1 + n, 1);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}`;
};
