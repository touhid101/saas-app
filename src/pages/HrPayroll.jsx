/**
 * HrPayroll.jsx — periods, the monthly run, payslips, payment (MOD36).
 *
 * <b>The lifecycle is the screen.</b> A run is DRAFT, CALCULATED, REVIEWED,
 * APPROVED, POSTED, PAID, and the rail across the top says which — because a
 * status word alone tells you where a run is and not what comes next.
 *
 * <b>The buttons come from the server.</b> Every run carries
 * {@code allowedActions}, so this screen holds no second copy of the lifecycle
 * and cannot offer a button the server will refuse. A button that produces an
 * error is worse than no button.
 *
 * <b>A posted run is read-only.</b> It has been handed to Accounts and a
 * journal exists against it; a correction is a reversal and a fresh run.
 */
import React, { useState, useEffect, useCallback, useMemo } from "react";
import { Card, Modal, Field } from "../components/UI.jsx";
import { useApp } from "../lib/AppContext.jsx";
import { usePermission } from "../lib/usePermission.js";
import { useConfirm } from "../lib/ConfirmProvider.jsx";
import {
  HrPage, Kpis, Kpi, HrTable, Status, HrTabs, HrTabBar, Filter, HrFilterBar, HrSearch,
  PayrollSteps, PolicyNote, Blocked, Facts, Fact, HrButton, HrRowActions,
  HrPagination, HrEmployeePicker, HrLines, usePagedList, useLocalPage,
  HrPageSkeleton, HrErrorState, humanError, P, HR_CRUMB,
} from "../components/HrKit.jsx";
import {
  getPeriods, createPeriod, closePeriod, searchPayrolls, getPayroll,
  calculatePayroll, reviewPayroll, approvePayroll, postPayroll, cancelPayroll,
  paySalary, getPayments, cancelPayment,
  getStructures, createStructure, updateStructure, retireStructure,
  getPfScheme, setPfScheme, getPfLedger, getPfStatement, recordPfEntry,
  searchEmployees, PF_ENTRY_TYPES, PF_BASES,
  getVocabulary, money, days, hoursOf, isoToday,
} from "../services/hrService.js";
import { runReport, emptyFilters } from "../services/reportService.js";
import PayslipPrint from "../components/PayslipPrint.jsx";

const searchPeople = async (q) => (await searchEmployees(q)).items;
const fmt = (n) => Number(n || 0).toLocaleString("en-US");
const hasText = (v, q) => String(v || "").toLowerCase().includes(q);

const emptyComponent = () => ({
  componentCode: "", componentName: "", componentType: "EARNING",
  calcMethod: "PCT_OF_BASIC", amount: "", pctValue: "", isTaxable: 1, isPf: 0, sortOrder: 100,
});

/** The report grades its own findings; this only reads the grade. */
const isError = (r) => String(r?.severity || "").toUpperCase() === "ERROR";

export default function HrPayroll() {
  const { addToast } = useApp();
  const confirm = useConfirm();
  const perm = usePermission("payroll");

  const [pane, setPane]           = useState("run");
  const [periods, setPeriods]     = useState([]);
  /* The most recent run — the one the tiles and the lifecycle rail describe. */
  const [current, setCurrent]     = useState(null);
  const [reloadToken, setReloadToken] = useState(0);
  const [runStatus, setRunStatus] = useState("");
  const [periodYear, setPeriodYear] = useState("");
  const [pfWho, setPfWho]         = useState("");
  const [pfKind, setPfKind]       = useState("");
  const [slipQ, setSlipQ]         = useState("");
  const [slipOnly, setSlipOnly]   = useState("");
  const [payQ, setPayQ]           = useState("");
  const [structures, setStructures] = useState([]);
  const [vocab, setVocab]         = useState({});
  const [busy, setBusy]           = useState(true);
  const [loadErr, setLoadErr]     = useState(null);
  /* Which lifecycle step is in flight, as `runId:ACTION`. Null when idle. */
  const [acting, setActing]       = useState(null);
  /* null while the exceptions report is in flight, [] once it has answered. */
  const [exceptions, setExceptions] = useState(null);

  const [openRun, setOpenRun]     = useState(null);
  const [payments, setPayments]   = useState([]);
  const [periodForm, setPeriodForm] = useState(null);
  const [structForm, setStructForm] = useState(null);
  const [payForm, setPayForm]     = useState(null);
  const [slip, setSlip]           = useState(null);
  /* The payslip being printed: { payrollId, lineId }. Its own overlay, in the
     shape of the sales invoice, fed by a document the server assembles. */
  const [printSlip, setPrintSlip] = useState(null);

  /* Provident fund.

     On the Payroll screen rather than a page of its own: PF is deducted by a
     payroll run, its rates sit beside the salary structures that shape a
     payslip, and the same people administer both. */
  const [pfScheme, setPfSchemeState] = useState(null);
  const [pfForm, setPfForm]          = useState(null);
  const [pfEntryForm, setPfEntryForm] = useState(null);
  const [pfStatement, setPfStatement] = useState(null);

  const loadPf = useCallback(async () => {
    try { setPfSchemeState(await getPfScheme()); }
    catch { /* the pane just stays empty */ }
  }, []);

  useEffect(() => { loadPf(); }, [loadPf, reloadToken]);

  /* The ledger pages on the server, narrowed by person and kind. */
  const ledger = usePagedList(
    ({ page, size }) => getPfLedger({ page, size, employeeId: pfWho || undefined, entryType: pfKind || undefined }),
    [pfWho, pfKind, reloadToken]);

  const savePfScheme = async () => {
    try {
      await setPfScheme({
        schemeName: pfForm.schemeName,
        /* "" means the rate has not been decided, and that is a real answer —
           so it is sent as null rather than coerced to nought. */
        employeePct: pfForm.employeePct === "" ? null : Number(pfForm.employeePct),
        employerPct: pfForm.employerPct === "" ? null : Number(pfForm.employerPct),
        contributionBase: pfForm.contributionBase,
        wageCeiling: pfForm.wageCeiling === "" ? null : Number(pfForm.wageCeiling),
        minServiceMonths: pfForm.minServiceMonths === "" ? null : Number(pfForm.minServiceMonths),
        effectiveFrom: pfForm.effectiveFrom || null,
        remarks: pfForm.remarks || null,
      });
      setPfForm(null);
      addToast("Provident fund scheme set.");
      loadPf();
    } catch (err) {
      addToast(humanError(err, "Could not set that scheme."), "error");
    }
  };

  const savePfEntry = async () => {
    try {
      await recordPfEntry({
        employeeId: Number(pfEntryForm.employeeId),
        entryType: pfEntryForm.entryType,
        entryDate: pfEntryForm.entryDate || null,
        employeeAmount: pfEntryForm.employeeAmount === "" ? null : Number(pfEntryForm.employeeAmount),
        employerAmount: pfEntryForm.employerAmount === "" ? null : Number(pfEntryForm.employerAmount),
        referenceNo: pfEntryForm.referenceNo || null,
        remarks: pfEntryForm.remarks || null,
      });
      setPfEntryForm(null);
      addToast("Movement recorded.");
      setReloadToken((t) => t + 1);
    } catch (err) {
      addToast(humanError(err, "Could not record that movement."), "error");
    }
  };

  const openStatement = async (employeeId) => {
    try { setPfStatement(await getPfStatement(employeeId)); }
    catch (err) { addToast(humanError(err, "Could not read that statement."), "error"); }
  };

  const load = useCallback(async () => {
    setBusy(true);
    try {
      const [p, r, s, v] = await Promise.all([
        getPeriods().catch(() => []),
        searchPayrolls({ page: 0, size: 1 }).catch(() => ({ items: [] })),
        getStructures(false).catch(() => []),
        getVocabulary().catch(() => ({})),
      ]);
      setPeriods(p || []);
      setCurrent((r.items || [])[0] || null);
      setStructures(s || []);
      setVocab(v || {});
      setLoadErr(null);
      setReloadToken((t) => t + 1);
    } catch (err) {
      setLoadErr(humanError(err, "Payroll could not be loaded."));
    } finally {
      setBusy(false);
    }
  }, []);

  useEffect(() => { load(); }, [load]);

  /* Runs page on the server; one per month, but ten years is a hundred and
     twenty rows and a status filter is how "which runs are still owing" is
     answered. */
  const runs = usePagedList(
    ({ page, size }) => searchPayrolls({ page, size, status: runStatus || undefined }),
    [runStatus, reloadToken]);

  /* Periods arrive whole (one request per branch) and are narrowed by year here. */
  const periodRows = useMemo(
    () => (periodYear ? periods.filter((p) => String(p.periodYear) === String(periodYear)) : periods),
    [periods, periodYear]);
  const periodPage = useLocalPage(periodRows, { size: 24 });
  const periodYears = useMemo(
    () => [...new Set(periods.map((p) => p.periodYear).filter(Boolean))].sort((a, b) => b - a),
    [periods]);

  /* The payslips of the open run: 2,500 lines arrive with the run and are
     searched and paged here, never drawn all at once. */
  const slipRows = useMemo(() => {
    const all = openRun?.lines || [];
    if (slipOnly === "UNPAID") return all.filter((l) => Number(l.unpaid || 0) > 0);
    if (slipOnly === "PAID") return all.filter((l) => Number(l.unpaid || 0) === 0);
    return all;
  }, [openRun, slipOnly]);
  const slips = useLocalPage(slipRows, { q: slipQ, match: (r, q) => hasText(r.empName, q) || hasText(r.empId, q) });
  const payRows = useLocalPage(payments, { q: payQ, match: (r, q) => hasText(r.empName, q) || hasText(r.voucherNo, q) || hasText(r.referenceNo, q) });

  const open = async (id) => {
    setOpenRun(null);
    setPayments([]);
    setExceptions(null);
    setSlipQ(""); setSlipOnly(""); setPayQ("");
    try {
      const run = await getPayroll(id);
      setOpenRun(run);
      if (run.frozen) setPayments(await getPayments(id).catch(() => []));
      loadExceptions(run);
    } catch (err) {
      addToast(humanError(err, "Could not open that run."), "error");
    }
  };

  /**
   * What the exceptions report says about this run's period.
   *
   * <p>Not a second opinion computed on the screen — the same report an
   * operator would run from Reports, asked for the run's own dates. Two places
   * deriving "who will be skipped" from different rules is how a screen and a
   * report come to disagree about whether a payroll is safe to post.
   *
   * <p>Failure is quiet: an exception panel that cannot load is not a reason to
   * refuse to show a payroll run.
   */
  const loadExceptions = async (r) => {
    if (!r?.periodFrom || !r?.periodTo) { setExceptions([]); return; }
    try {
      const grid = await runReport("hr-payroll-exception",
                                   { ...emptyFilters(), from: r.periodFrom, to: r.periodTo });
      setExceptions(grid?.rows || []);
    } catch {
      setExceptions([]);
    }
  };

  /**
   * Run one lifecycle step, and say so while it is running.
   *
   * <p>`acting` holds the key of the step in flight. Every lifecycle button on
   * the screen consults it: the one pressed shows a spinner, the rest go flat.
   * Posting a payroll is not idempotent, and a second press on a slow network
   * was previously accepted — this is the fix.
   */
  const run = async (fn, msg, reopenId, key) => {
    if (acting) return;
    setActing(key || "work");
    try {
      await fn();
      addToast(msg);
      await load();
      if (reopenId) await open(reopenId);
    } catch (err) {
      addToast(humanError(err, "That did not go through."), "error");
    } finally {
      setActing(null);
    }
  };

  /* ── The lifecycle, driven by what the server says is allowed ── */

  const act = async (runRow, action) => {
    const id = runRow.id;
    const key = `${id}:${action}`;
    if (action === "CALCULATE") {
      return run(() => calculatePayroll({ periodId: runRow.periodId }),
                 "Payroll calculated.", id, key);
    }
    if (action === "REVIEW") {
      return run(() => reviewPayroll(id), "Marked reviewed.", id, key);
    }
    if (action === "APPROVE") {
      const ok = await confirm({
        title: "Approve this payroll?",
        body: `${runRow.employeeCount} payslips, ${money(runRow.totalNet)} net.`,
        detail: "Approving is a signature. The next step, posting, freezes the run for good.",
        confirmLabel: "Approve",
      });
      if (!ok) return;
      return run(() => approvePayroll(id), "Payroll approved.", id, key);
    }
    if (action === "POST") {
      const ok = await confirm({
        title: "Post this payroll?",
        body: "Posting freezes the run. It cannot be recalculated, edited or cancelled afterwards.",
        detail: "A correction after this point is a reversal and a fresh run — the rule the general "
              + "ledger lives by.",
        confirmLabel: "Post it",
      });
      if (!ok) return;
      return run(() => postPayroll(id), "Payroll posted.", id, key);
    }
    if (action === "CANCEL") {
      const ok = await confirm({
        title: "Cancel this run?",
        body: "The run stays on the record as evidence, and everything it claimed — loan "
            + "installments, advance recoveries — is released so the month can be started again.",
        confirmLabel: "Cancel the run", tone: "danger",
      });
      if (!ok) return;
      return run(() => cancelPayroll(id), "Run cancelled.", id, key);
    }
    if (action === "PAY") {
      setPayForm({ payrollId: id, payrollLineId: null, empName: null,
                   amount: runRow.unpaid, paymentDate: isoToday(),
                   paymentMode: "CASH", bankName: "", chequeNo: "", referenceNo: "", remarks: "" });
    }
  };

  const ACTION_LABEL = {
    CALCULATE: "Recalculate", REVIEW: "Mark reviewed", APPROVE: "Approve",
    POST: "Post", PAY: "Pay", CANCEL: "Cancel run",
  };
  /* The progressive tense, so a busy button says what it is doing rather than
     what it was asked to do. */
  const ACTION_BUSY = {
    CALCULATE: "Calculating…", REVIEW: "Marking…", APPROVE: "Approving…",
    POST: "Posting…", PAY: "Paying…", CANCEL: "Cancelling…",
  };
  const ACTION_ICON = {
    CALCULATE: P.payroll, REVIEW: P.doc, APPROVE: P.check,
    POST: P.lock, PAY: P.money, CANCEL: P.ban,
  };

  /* ── Structures ── */

  const saveStructure = async () => {
    const f = structForm;
    const body = {
      structureName: f.structureName,
      description: f.description || null,
      standardHoursPerDay: f.standardHoursPerDay === "" ? null : Number(f.standardHoursPerDay),
      overtimeMultiplier: f.overtimeMultiplier === "" ? null : Number(f.overtimeMultiplier),
      absenceBasis: f.absenceBasis,
      components: (f.components || []).map((c, i) => ({
        componentCode: c.componentCode,
        componentName: c.componentName,
        componentType: c.componentType,
        calcMethod: c.calcMethod,
        amount: c.calcMethod === "FIXED" ? Number(c.amount || 0) : null,
        pctValue: c.calcMethod === "FIXED" ? null : Number(c.pctValue || 0),
        isTaxable: c.isTaxable,
        /* Only meaningful once a PF scheme exists: the calculator then skips
           this component and takes PF from the scheme, so it is not deducted
           twice. Nothing here guesses it from the component's name. */
        isPf: c.componentType === "DEDUCTION" ? (c.isPf ? 1 : 0) : 0,
        sortOrder: c.sortOrder ?? (i + 1) * 10,
      })),
    };
    try {
      if (f.id) await updateStructure(f.id, body);
      else await createStructure(body);
      setStructForm(null);
      addToast("Structure saved.");
      load();
    } catch (err) {
      addToast(humanError(err, "Could not save that structure."), "error");
    }
  };

  if (busy && !periods.length && !current && !loadErr) {
    return <HrPageSkeleton label="Loading payroll…" kpis={5} />;
  }

  if (loadErr && !periods.length && !current) {
    return (
      <HrPage crumbs={[HR_CRUMB, { label: "Payroll" }]} title="Payroll">
        <HrErrorState title="Payroll could not be loaded" hint={loadErr}
                      onRetry={load} retrying={busy} />
      </HrPage>
    );
  }

  const openPeriods = periods.filter((p) => p.periodStatus === "OPEN");
  const tabs = [
    { id: "run",        label: "Runs",       icon: P.payroll },
    { id: "periods",    label: "Periods",    icon: P.calendar, badge: openPeriods.length },
    { id: "structures", label: "Structures", icon: P.edit },
    { id: "pf",         label: "Fund",       icon: P.wallet, badge: pfScheme && !pfScheme.deducting ? 1 : 0 },
  ];

  return (
    <HrPage
      crumbs={[HR_CRUMB, { label: "Payroll" }]}
      title="Payroll"
      subtitle="Calculate, review, approve, post and pay. A posted run cannot be changed"
      actions={
        <>
          <HrButton iconOnly icon={P.reset} title="Refresh" onClick={load} busy={busy} />
          {perm.canCreate && (
            <HrButton variant="primary" icon={P.plus} onClick={() => {
             const now = new Date();
             setPeriodForm({
             periodYear: now.getFullYear(), periodMonth: now.getMonth() + 1,
             periodFrom: "", periodTo: "", workingDays: "", remarks: "",
             });
             }}>
              Open a period
            </HrButton>
          )}
        </>
      }
    >
      <HrTabBar tabs={tabs} active={pane} onChange={setPane} />

      {pane === "run" && (<>
        {current && (
          <>
            <PayrollSteps status={current.payrollStatus} />
            <Kpis>
              <Kpi label="Period" value={current.periodLabel || "—"} icon={P.calendar}
                   note={`${current.periodFrom} to ${current.periodTo}`} />
              <Kpi label="Payslips" value={current.employeeCount ?? 0} icon={P.people}
                   onClick={() => open(current.id)} note="Open the run" />
              <Kpi label="Gross" value={money(current.totalGross)} icon={P.money} />
              <Kpi label="Net" value={money(current.totalNet)} icon={P.wallet} />
              <Kpi label="Still owing" value={money(current.unpaid)} icon={P.alert}
                   warn={Number(current.unpaid || 0) > 0} />
            </Kpis>
          </>
        )}

        <Card title={`Payroll runs · ${fmt(runs.total)}`}>
          <HrFilterBar active={runStatus ? 1 : 0} onReset={() => setRunStatus("")}>
            <Filter label="Status">
              <select value={runStatus} onChange={(e) => setRunStatus(e.target.value)}>
                <option value="">Any</option>
                {["DRAFT", "CALCULATED", "REVIEWED", "APPROVED", "POSTED", "PAID", "CANCELLED"].map((x) => (
                  <option key={x} value={x}>{x.charAt(0) + x.slice(1).toLowerCase()}</option>
                ))}
              </select>
            </Filter>
          </HrFilterBar>
          <HrTable
            rowKey={(r) => r.id}
            rows={runs.rows}
            loading={runs.busy && !runs.rows.length}
            error={runs.err}
            onRetry={runs.reload}
            onRowClick={(r) => open(r.id)}
            caption="Payroll runs at this branch"
            empty={runStatus
              ? { icon: P.filter, title: "No runs in that state", hint: "Reset the filter to see every run.",
                  action: <HrButton icon={P.reset} onClick={() => setRunStatus("")}>Reset</HrButton> }
              : {
              icon: P.payroll,
              title: "No runs yet",
              hint: "A run is calculated against an open period. Open one first — the period "
                  + "carries the working-day count every absence is valued against.",
            }}
            cols={[
              { key: "payrollNo", label: "Run", lead: true,
                render: (r) => `${r.payrollNo} — ${r.periodLabel || ""}` },
              { key: "employeeCount", label: "Payslips", num: true },
              { key: "totalGross", label: "Gross", num: true, advisory: true,
                render: (r) => money(r.totalGross) },
              { key: "totalNet", label: "Net", num: true, render: (r) => money(r.totalNet) },
              { key: "unpaid", label: "Owing", num: true, render: (r) => money(r.unpaid) },
              { key: "payrollStatus", label: "Status",
                render: (r) => <Status code={r.payrollStatus} label={r.payrollStatusLabel} /> },
              { key: "act", label: "", actions: true,
                render: (r) => {
                  /* One visible action — the next step in the lifecycle — and
                     everything else in the menu. Five buttons of equal weight
                     gave Post and Cancel run the same emphasis, side by side. */
                  const allowed = (r.allowedActions || []).filter((a) => a !== "CALCULATE");
                  const next = allowed.find((a) => a !== "CANCEL");
                  const rest = allowed.filter((a) => a !== next);
                  return (
                    <HrRowActions
                      items={rest.map((a) => ({
                        label: ACTION_LABEL[a] || a,
                        icon: ACTION_ICON[a],
                        danger: a === "CANCEL",
                        disabled: !!acting,
                        onClick: () => act(r, a),
                      }))}>
                      <HrButton size="sm" icon={P.eye} onClick={() => open(r.id)}>Open</HrButton>
                      {next && (
                        <HrButton size="sm" variant="primary" icon={ACTION_ICON[next]}
                                  busy={acting === `${r.id}:${next}`}
                                  busyLabel={ACTION_BUSY[next]}
                                  disabled={!!acting}
                                  onClick={() => act(r, next)}>
                          {ACTION_LABEL[next] || next}
                        </HrButton>
                      )}
                    </HrRowActions>
                  );
                } },
            ]}
          />
          <HrPagination page={runs.page} size={runs.size} total={runs.total} totalPages={runs.totalPages}
                        onPage={runs.setPage} onSize={runs.setSize} />
        </Card>
      </>)}

      {pane === "periods" && (
        <Card title={`Pay periods · ${fmt(periodPage.total)}`}>
          {periodYears.length > 1 && (
            <HrFilterBar active={periodYear ? 1 : 0} onReset={() => setPeriodYear("")}>
              <Filter label="Year">
                <select value={periodYear} onChange={(e) => setPeriodYear(e.target.value)}>
                  <option value="">Every year</option>
                  {periodYears.map((y) => <option key={y} value={y}>{y}</option>)}
                </select>
              </Filter>
            </HrFilterBar>
          )}
          <HrTable
            rowKey={(r) => r.id}
            rows={periodPage.rows}
            caption="Pay periods"
            empty={{
              icon: P.calendar,
              title: "No periods opened",
              hint: "A pay period is the month payroll is calculated for, and it carries the "
                  + "working-day count. Nothing can be calculated until one exists.",
            }}
            cols={[
              { key: "label", label: "Period", lead: true, sortable: true },
              { key: "periodFrom", label: "From" },
              { key: "periodTo", label: "To", advisory: true },
              { key: "workingDays", label: "Working days", num: true,
                render: (r) => days(r.workingDays) },
              { key: "periodStatus", label: "Status",
                render: (r) => <Status code={r.periodStatus === "OPEN" ? "SUBMITTED" : "CLOSED"}
                                       label={r.periodStatus} /> },
              { key: "payrollStatus", label: "Run", advisory: true,
                render: (r) => r.payrollStatus
                  ? <Status code={r.payrollStatus} label={r.payrollStatus} />
                  : "—" },
              { key: "act", label: "", actions: true,
                render: (r) => (
                  <HrRowActions
                    items={r.periodStatus === "OPEN" && perm.canEdit
                      ? [{ label: "Close this period", icon: P.lock, disabled: !!acting,
                           onClick: () => run(() => closePeriod(r.id), "Period closed.",
                                              null, `p${r.id}:CLOSE`) }]
                      : []}>
                    {r.periodStatus === "OPEN" && perm.canCreate && !r.payrollId && (
                      <HrButton size="sm" variant="primary" icon={P.payroll}
                                busy={acting === `p${r.id}:CALC`}
                                busyLabel="Calculating…"
                                disabled={!!acting}
                                onClick={() => run(() => calculatePayroll({ periodId: r.id }),
                                                   "Payroll calculated.", null, `p${r.id}:CALC`)}>
                        Calculate
                      </HrButton>
                    )}
                  </HrRowActions>
                ) },
            ]}
          />
          <HrPagination page={periodPage.page} size={periodPage.size} total={periodPage.total}
                        totalPages={periodPage.totalPages} onPage={periodPage.setPage} onSize={periodPage.setSize} />
          {openPeriods.length === 0 && (
            <PolicyNote>
              No period is open. Payroll is calculated against a period, and the period carries the
              working-day count that an absence is valued against — a figure this application asks
              for rather than guessing, because dividing by the wrong number changes what everybody
              is paid.
            </PolicyNote>
          )}
        </Card>
      )}

      {pane === "structures" && (
        <Card title="Salary structures"
              right={perm.canCreate && (
                <HrButton variant="ghost" icon={P.plus} onClick={() => setStructForm({
                 structureName: "", description: "",
                 standardHoursPerDay: "", overtimeMultiplier: "",
                 absenceBasis: "BASIC", components: [emptyComponent()],
                        })}>Add a structure</HrButton>
              )}>
          <HrTable
            rowKey={(r) => r.id}
            rows={structures}
            empty="No structures. Payroll cannot value anybody without one."
            cols={[
              { key: "structureName", label: "Structure", lead: true },
              { key: "components", label: "Components", num: true,
                render: (r) => (r.components || []).length },
              { key: "paysOvertime", label: "Overtime",
                render: (r) => r.paysOvertime
                  ? `${r.overtimeMultiplier}× of ${r.standardHoursPerDay}h day`
                  : <span style={{ color: "var(--amber-deep)" }}>recorded, not paid</span> },
              { key: "absenceBasis", label: "Absence on", advisory: true },
              { key: "employeesOn", label: "Staff", num: true },
              { key: "status", label: "Active", advisory: true,
                render: (r) => (r.status === "Y" ? "Yes" : "No") },
              { key: "act", label: "", actions: true,
                render: (r) => (
                  <>
                    {perm.canEdit && (
                      <HrButton variant="ghost" icon={P.edit} onClick={() => setStructForm({
                       id: r.id, structureName: r.structureName,
                       description: r.description || "",
                       standardHoursPerDay: r.standardHoursPerDay ?? "",
                       overtimeMultiplier: r.overtimeMultiplier ?? "",
                       absenceBasis: r.absenceBasis || "BASIC",
                       components: (r.components || []).map((c) => ({
                       componentCode: c.componentCode, componentName: c.componentName,
                       componentType: c.componentType, calcMethod: c.calcMethod,
                       amount: c.amount ?? "", pctValue: c.pctValue ?? "",
                       isTaxable: c.isTaxable, isPf: c.isPf ?? 0,
                       sortOrder: c.sortOrder,
                       })),
                              })}>Edit</HrButton>
                    )}
                    {perm.canDelete && r.status === "Y" && (
                      <HrButton variant="ghost" icon={P.x} onClick={() => run(() => retireStructure(r.id), "Structure retired.")}>Retire</HrButton>
                       )}
                       </>
                ) },
            ]}
          />
          <PolicyNote>
            A structure is the shape of a payslip, not the money on it — what somebody earns is
            their basic salary. Leaving the overtime multiplier empty is a valid answer and the one
            this application ships with: overtime is still recorded, and still shown in hours on
            every payslip, but it is not valued until this pharmacy decides what an hour is worth.
          </PolicyNote>
        </Card>
      )}

      {pane === "pf" && (<>
        <Card
          title="Provident fund"
          right={perm.canEdit && (
            <HrButton variant="primary" icon={P.edit} onClick={() => setPfForm({
             schemeName: pfScheme?.schemeName || "Provident fund",
             employeePct: pfScheme?.employeePct ?? "",
             employerPct: pfScheme?.employerPct ?? "",
             contributionBase: pfScheme?.contributionBase || "BASIC",
             wageCeiling: pfScheme?.wageCeiling ?? "",
             minServiceMonths: pfScheme?.minServiceMonths ?? "",
             effectiveFrom: isoToday(),
             remarks: "",
                    })}>{pfScheme ? "Change the scheme" : "Set the scheme"}</HrButton>
          )}>

          {!pfScheme ? (
            <Blocked>
              <strong>No provident fund scheme has been set</strong>, so payroll deducts no PF from
              anybody — exactly how this application behaved before schemes existed. The employee
              share, the employer share, the wage they are taken on and any ceiling are business
              policy, and in places statutory policy. Nothing here will choose them.
            </Blocked>
          ) : (
            <>
              <Facts>
                <Fact label="Scheme" value={pfScheme.schemeName} />
                <Fact label="Employee"
                      value={pfScheme.employeePct == null ? "not set" : pfScheme.employeePct + "%"} />
                <Fact label="Employer"
                      value={pfScheme.employerPct == null ? "not set" : pfScheme.employerPct + "%"} />
                <Fact label="Taken on" value={pfScheme.contributionBase === "GROSS" ? "Gross" : "Basic"} />
                <Fact label="Ceiling"
                      value={pfScheme.wageCeiling == null ? "none" : money(pfScheme.wageCeiling)} />
                <Fact label="Qualifying"
                      value={!pfScheme.minServiceMonths ? "from the first payslip"
                                                       : pfScheme.minServiceMonths + " months"} />
                <Fact label="In force from" value={pfScheme.effectiveFrom} />
              </Facts>

              {!pfScheme.deducting && (
                <PolicyNote>
                  This scheme <strong>takes nothing</strong>: neither rate has been decided. A blank
                  rate is not nought per cent — it is a question nobody has answered, and payroll
                  behaves as though there were no scheme at all until somebody does.
                </PolicyNote>
              )}

              {(pfScheme.unmarkedPfComponents || []).length > 0 && pfScheme.deducting && (
                <PolicyNote>
                  <strong>Possible double deduction.</strong> These salary components look like PF
                  and are not marked as such: {pfScheme.unmarkedPfComponents.join(", ")}. They are
                  deducted <em>on top of</em> this scheme. Open the structure on the Structures tab
                  and tick <em>Is PF</em> on the right one — the scheme then replaces it. This is a
                  guess from the component name, so nothing has been changed for you.
                </PolicyNote>
              )}
            </>
          )}
        </Card>

        <Card
          title={`Fund movements · ${fmt(ledger.total)}`}
          right={perm.canApprove && (
            <HrButton variant="primary" icon={P.plus} onClick={() => setPfEntryForm({
             employeeId: "", entryType: "WITHDRAWAL", entryDate: isoToday(),
             employeeAmount: "", employerAmount: "", referenceNo: "", remarks: "",
                    })}>Record a movement</HrButton>
          )}>
          <HrFilterBar active={(pfWho ? 1 : 0) + (pfKind ? 1 : 0)} onReset={() => { setPfWho(""); setPfKind(""); }}>
            <Filter label="Employee" grow>
              <HrEmployeePicker value={pfWho} onChange={setPfWho} search={searchPeople} allowEmpty emptyLabel="Everyone" />
            </Filter>
            <Filter label="Kind">
              <select value={pfKind} onChange={(e) => setPfKind(e.target.value)}>
                <option value="">Any</option>
                <option value="CONTRIBUTION">Contribution</option>
                {PF_ENTRY_TYPES.map((t) => <option key={t} value={t}>{t.charAt(0) + t.slice(1).toLowerCase()}</option>)}
              </select>
            </Filter>
          </HrFilterBar>
          <HrTable
            rowKey={(r) => r.id}
            rows={ledger.rows}
            loading={ledger.busy && !ledger.rows.length}
            error={ledger.err}
            onRetry={ledger.reload}
            empty={(pfWho || pfKind)
              ? { icon: P.filter, title: "Nothing in the fund matches that", hint: "Widen the filter, or reset it.",
                  action: <HrButton icon={P.reset} onClick={() => { setPfWho(""); setPfKind(""); }}>Reset</HrButton> }
              : { icon: P.wallet, title: "Nothing has moved through the fund",
                  hint: "Contributions appear when a payroll run is posted." }}
            cols={[
              { key: "empName", label: "Employee", lead: true },
              { key: "entryDate", label: "Date" },
              { key: "entryType", label: "Kind",
                render: (r) => <Status code={r.entryType} label={r.entryTypeLabel} /> },
              { key: "employeeAmount", label: "Employee", num: true,
                render: (r) => money(r.employeeAmount) },
              { key: "employerAmount", label: "Employer", num: true,
                render: (r) => money(r.employerAmount) },
              { key: "signedTotal", label: "Effect", num: true,
                render: (r) => money(r.signedTotal) },
              { key: "remarks", label: "Note", advisory: true },
              { key: "act", label: "", actions: true,
                render: (r) => (
                  <HrButton variant="ghost" size="sm" icon={P.doc} onClick={() => openStatement(r.employeeId)}>Statement</HrButton>
                ) },
            ]}
          />
          <HrPagination page={ledger.page} size={ledger.size} total={ledger.total} totalPages={ledger.totalPages}
                        onPage={ledger.setPage} onSize={ledger.setSize} />
          <PolicyNote>
            A contribution appears here when a payroll run is <strong>posted</strong>, not when it
            is calculated: a run can be recalculated any number of times before that, and a fund
            whose balance moved each time would not be a fund. Contributions cannot be typed in —
            money in the fund that no payslip accounts for is money nobody can explain.
          </PolicyNote>
        </Card>
      </>)}

      <HrTabs active={pane} onChange={setPane} tabs={tabs} />

      {pfForm && (
        <Modal title="Provident fund scheme" width={560} onClose={() => setPfForm(null)}
               footer={
                 <>
                   <HrButton variant="ghost" onClick={() => setPfForm(null)}>Cancel</HrButton>
                   <HrButton variant="primary" onClick={savePfScheme}>Save</HrButton>
                    </>
               }>
          <div className="hr-form">
            <Field label="Name">
              <input value={pfForm.schemeName}
                     onChange={(e) => setPfForm({ ...pfForm, schemeName: e.target.value })} />
            </Field>
            <Field label="Employee share (%)" hint="Leave blank if this has not been decided.">
              <input type="number" min="0" max="100" step="0.01" value={pfForm.employeePct}
                     onChange={(e) => setPfForm({ ...pfForm, employeePct: e.target.value })} />
            </Field>
            <Field label="Employer share (%)" hint="Leave blank if this has not been decided.">
              <input type="number" min="0" max="100" step="0.01" value={pfForm.employerPct}
                     onChange={(e) => setPfForm({ ...pfForm, employerPct: e.target.value })} />
            </Field>
            <Field label="Taken on">
              <select value={pfForm.contributionBase}
                      onChange={(e) => setPfForm({ ...pfForm, contributionBase: e.target.value })}>
                {PF_BASES.map((b) => (
                  <option key={b} value={b}>{b === "GROSS" ? "Gross salary" : "Basic salary"}</option>
                ))}
              </select>
            </Field>
            <Field label="Wage ceiling" hint="Blank means no ceiling.">
              <input type="number" min="0" step="0.01" value={pfForm.wageCeiling}
                     onChange={(e) => setPfForm({ ...pfForm, wageCeiling: e.target.value })} />
            </Field>
            <Field label="Qualifying service (months)" hint="Blank means from the first payslip.">
              <input type="number" min="0" value={pfForm.minServiceMonths}
                     onChange={(e) => setPfForm({ ...pfForm, minServiceMonths: e.target.value })} />
            </Field>
            <Field label="In force from">
              <input type="date" value={pfForm.effectiveFrom}
                     onChange={(e) => setPfForm({ ...pfForm, effectiveFrom: e.target.value })} />
            </Field>
          </div>
          <PolicyNote>
            This supersedes the scheme in force rather than editing it — last month's payroll was
            computed against last month's rates, and rewriting them would make those payslips
            impossible to explain.
          </PolicyNote>
        </Modal>
      )}

      {pfEntryForm && (
        <Modal title="Record a fund movement" width={520} onClose={() => setPfEntryForm(null)}
               footer={
                 <>
                   <HrButton variant="ghost" onClick={() => setPfEntryForm(null)}>Cancel</HrButton>
                   <HrButton variant="primary" disabled={!pfEntryForm.employeeId
                    || (!pfEntryForm.employeeAmount && !pfEntryForm.employerAmount)}
                           onClick={savePfEntry}>Record</HrButton>
                 </>
               }>
          <div className="hr-form">
            <div className="hr-form-full">
              <Field label="Employee">
                <HrEmployeePicker value={pfEntryForm.employeeId} search={searchPeople}
                                  onChange={(id) => setPfEntryForm({ ...pfEntryForm, employeeId: id })} />
              </Field>
            </div>
            <Field label="Kind">
              <select value={pfEntryForm.entryType}
                      onChange={(e) => setPfEntryForm({ ...pfEntryForm, entryType: e.target.value })}>
                {PF_ENTRY_TYPES.map((t) => (
                  <option key={t} value={t}>{t.charAt(0) + t.slice(1).toLowerCase()}</option>
                ))}
              </select>
            </Field>
            <Field label="Date">
              <input type="date" value={pfEntryForm.entryDate}
                     onChange={(e) => setPfEntryForm({ ...pfEntryForm, entryDate: e.target.value })} />
            </Field>
            <Field label="Employee share">
              <input type="number" min="0" step="0.01" value={pfEntryForm.employeeAmount}
                     onChange={(e) => setPfEntryForm({ ...pfEntryForm, employeeAmount: e.target.value })} />
            </Field>
            <Field label="Employer share">
              <input type="number" min="0" step="0.01" value={pfEntryForm.employerAmount}
                     onChange={(e) => setPfEntryForm({ ...pfEntryForm, employerAmount: e.target.value })} />
            </Field>
            <Field label="Reference">
              <input value={pfEntryForm.referenceNo}
                     onChange={(e) => setPfEntryForm({ ...pfEntryForm, referenceNo: e.target.value })} />
            </Field>
            <Field label="Note">
              <textarea rows={2} value={pfEntryForm.remarks}
                        onChange={(e) => setPfEntryForm({ ...pfEntryForm, remarks: e.target.value })} />
            </Field>
          </div>
          <PolicyNote>
            A withdrawal is refused when the fund does not hold it. An adjustment adds — for money
            the fund holds that payroll did not put there, like a balance carried in from
            elsewhere.
          </PolicyNote>
        </Modal>
      )}

      {pfStatement && (
        <Modal title={`Provident fund — ${pfStatement.empName}`} width={860}
               onClose={() => setPfStatement(null)}>
          <div style={{ display: "flex", flexDirection: "column", gap: 16 }}>
            <Facts>
              <Fact label="Employee paid in" value={money(pfStatement.employeeTotal)} />
              <Fact label="Employer paid in" value={money(pfStatement.employerTotal)} />
              <Fact label="Balance" value={money(pfStatement.balance)} />
              <Fact label="In the scheme" value={pfStatement.enrolled ? "Yes" : "No"} />
            </Facts>
            <HrTable
              rowKey={(r) => r.id}
              rows={pfStatement.entries || []}
              empty="Nothing yet."
              cols={[
                { key: "entryDate", label: "Date", lead: true },
                { key: "entryType", label: "Kind",
                  render: (r) => <Status code={r.entryType} label={r.entryTypeLabel} /> },
                { key: "baseAmount", label: "On", num: true, advisory: true,
                  render: (r) => (r.baseAmount == null ? "—" : money(r.baseAmount)) },
                { key: "rates", label: "Rates", advisory: true,
                  render: (r) => [r.employeePct, r.employerPct]
                    .filter((p) => p != null).map((p) => p + "%").join(" / ") || "—" },
                { key: "employeeAmount", label: "Employee", num: true,
                  render: (r) => money(r.employeeAmount) },
                { key: "employerAmount", label: "Employer", num: true,
                  render: (r) => money(r.employerAmount) },
                { key: "balanceAfter", label: "Balance", num: true,
                  render: (r) => money(r.balanceAfter) },
              ]}
            />
            <PolicyNote>
              The rates on each row are the ones that applied at the time, copied onto the row
              rather than read from today's scheme — a statement printed next year has to show what
              was actually contributed.
            </PolicyNote>
          </div>
        </Modal>
      )}

      {/* ── One run ── */}
      {openRun && (
        <Modal title={`${openRun.payrollNo} — ${openRun.periodLabel || ""}`} width={980}
               onClose={() => setOpenRun(null)}>
          <div style={{ display: "flex", flexDirection: "column", gap: 16 }}>
            <PayrollSteps status={openRun.payrollStatus}
                          exceptions={(exceptions || []).filter(isError).length} />

            {openRun.frozen && (
              <Blocked>
                This run is posted and cannot be changed. A correction is a reversal and a fresh
                run — the rule the general ledger lives by, and the reason a payslip issued last
                month still says what it said.
              </Blocked>
            )}

            <Facts>
              <Fact label="Status" value={openRun.payrollStatusLabel} />
              <Fact label="Payslips" value={openRun.employeeCount} />
              <Fact label="Working days" value={days(openRun.workingDays)} />
              <Fact label="Gross" value={money(openRun.totalGross)} />
              <Fact label="Deductions" value={money(openRun.totalDeduction)} />
              <Fact label="Net" value={money(openRun.totalNet)} />
              <Fact label="Paid" value={money(openRun.totalPaid)} />
              <Fact label="Calculated by" value={openRun.calculatedBy} />
              <Fact label="Approved by" value={openRun.approvedBy} />
              <Fact label="Posted by" value={openRun.postedBy} />
            </Facts>

            <div className="hr-head-actions">
              {(openRun.allowedActions || []).map((a) => (
                <HrButton key={a}
                          variant={a === "CANCEL" ? "danger" : "primary"}
                          icon={ACTION_ICON[a]}
                          busy={acting === `${openRun.id}:${a}`}
                          busyLabel={ACTION_BUSY[a]}
                          disabled={!!acting}
                          onClick={() => act(openRun, a)}>
                  {ACTION_LABEL[a] || a}
                </HrButton>
              ))}
            </div>

            {/* ── What would go wrong, before it is approved ──
                The report grades each finding and says what it causes, which is
                the difference between "check the payroll" and knowing whether
                somebody will be skipped. */}
            {exceptions !== null && exceptions.length > 0 && (
              <Card title="Exceptions over this period"
                    eyebrow={`${exceptions.filter(isError).length} error`
                             + `${exceptions.filter(isError).length === 1 ? "" : "s"}`
                             + ` · ${exceptions.filter((x) => !isError(x)).length} warning`
                             + `${exceptions.filter((x) => !isError(x)).length === 1 ? "" : "s"}`}>
                <HrTable
                  rowKey={(r, i) => i}
                  rows={exceptions}
                  caption="Payroll exceptions over this pay period"
                  cols={[
                    { key: "name", label: "Employee", lead: true,
                      render: (r) => <span>{r.name}{r.empId ? ` (${r.empId})` : ""}</span> },
                    { key: "severity", label: "Severity",
                      render: (r) => <Status code={isError(r) ? "REJECTED" : "SUBMITTED"}
                                             label={r.severity} /> },
                    { key: "issue", label: "Issue" },
                    { key: "effect", label: "What happens", advisory: true },
                  ]}
                />
                <PolicyNote>
                  An <strong>error</strong> means somebody will be skipped or cannot be paid; a
                  <strong> warning</strong> means the figures compute but may not be what was meant.
                  This is the same reading as the Payroll Exceptions report, asked for this run&rsquo;s
                  own dates.
                </PolicyNote>
              </Card>
            )}

            <Card title={`Payslips · ${fmt(slips.total)}${slips.total !== (openRun.lines || []).length ? ` of ${fmt((openRun.lines || []).length)}` : ""}`}>
              <HrFilterBar active={(slipQ ? 1 : 0) + (slipOnly ? 1 : 0)} onReset={() => { setSlipQ(""); setSlipOnly(""); }}>
                <Filter label="Search" grow>
                  <HrSearch value={slipQ} onChange={setSlipQ} placeholder="Name or code" />
                </Filter>
                {openRun.frozen && (
                  <Filter label="Show">
                    <select value={slipOnly} onChange={(e) => setSlipOnly(e.target.value)}>
                      <option value="">Everyone</option>
                      <option value="UNPAID">Still owing</option>
                      <option value="PAID">Paid in full</option>
                    </select>
                  </Filter>
                )}
              </HrFilterBar>
              <HrTable
                rowKey={(r) => r.id}
                rows={slips.rows}
                caption="Payslips in this run"
                empty={(slipQ || slipOnly)
                  ? { icon: P.filter, title: "No payslip matches that", hint: "Try another spelling, or reset the filter.",
                      action: <HrButton icon={P.reset} onClick={() => { setSlipQ(""); setSlipOnly(""); }}>Reset</HrButton> }
                  : {
                  icon: P.alert,
                  title: "Nobody was payroll ready",
                  hint: "Every active person was skipped — usually a missing basic salary or "
                      + "salary structure. The exceptions above name who and why.",
                }}
                cols={[
                  { key: "empName", label: "Employee", lead: true, sortable: true },
                  { key: "presentDays", label: "Present", num: true, advisory: true,
                    render: (r) => days(r.presentDays) },
                  { key: "absentDays", label: "Absent", num: true, advisory: true,
                    render: (r) => days(r.absentDays) },
                  /* Leave belongs beside present and absent, not only inside the
                     payslip. Paid leave costs nothing, so it leaves no line in
                     the arithmetic — and without a column here the days appear
                     nowhere on this screen, which reads as payroll having missed
                     them. The unpaid figure is shown beside it because that is
                     the half that actually reduces pay. */
                  { key: "leaveDays", label: "Leave", num: true, advisory: true,
                    render: (r) => (Number(r.leaveDays || 0) === 0
                      ? days(0)
                      : <span>{days(r.leaveDays)}
                          {Number(r.unpaidLeaveDays || 0) > 0 && (
                            <span style={{ color: "var(--clay)" }}>
                              {" "}({days(r.unpaidLeaveDays)} unpaid)
                            </span>
                          )}
                        </span>) },
                  { key: "grossSalary", label: "Gross", num: true,
                    render: (r) => money(r.grossSalary) },
                  { key: "deductionTotal", label: "Deductions", num: true,
                    render: (r) => money(r.deductionTotal) },
                  { key: "netSalary", label: "Net", num: true,
                    render: (r) => <strong>{money(r.netSalary)}</strong> },
                  { key: "paymentStatus", label: "Paid",
                    render: (r) => <Status code={r.paymentStatus} label={r.paymentStatus} /> },
                  { key: "act", label: "", actions: true,
                    render: (r) => (
                      <>
                        <HrButton size="sm" icon={P.doc} onClick={() => setSlip(r)}>Payslip</HrButton>
                        <HrButton size="sm" iconOnly icon={P.printer} title="Print this payslip"
                                  onClick={() => setPrintSlip({ payrollId: openRun.id, lineId: r.id })} />
                        {openRun.frozen && Number(r.unpaid || 0) > 0 && perm.canEdit && (
                          <HrButton size="sm" variant="primary" icon={P.money}
                                    onClick={() => setPayForm({
                                      payrollId: openRun.id, payrollLineId: r.id,
                                      empName: r.empName, amount: r.unpaid,
                                      paymentDate: isoToday(), paymentMode: "CASH",
                                      bankName: "", chequeNo: "", referenceNo: "", remarks: "",
                                    })}>
                            Pay
                          </HrButton>
                        )}
                      </>
                    ) },
                ]}
              />
              <HrPagination page={slips.page} size={slips.size} total={slips.total} totalPages={slips.totalPages}
                            onPage={slips.setPage} onSize={slips.setSize} />
            </Card>

            {payments.length > 0 && (
              <Card title={`Payments · ${fmt(payRows.total)}`}
                    right={<HrSearch value={payQ} onChange={setPayQ} placeholder="Name, voucher or reference" />}>
                <HrTable
                  rowKey={(r) => r.id}
                  rows={payRows.rows}
                  empty={{ icon: P.filter, title: "No payment matches that", hint: "Try another spelling, or clear the search." }}
                  cols={[
                    { key: "voucherNo", label: "Voucher", lead: true },
                    { key: "empName", label: "Employee" },
                    { key: "paymentDate", label: "Date", advisory: true },
                    { key: "amount", label: "Amount", num: true, render: (r) => money(r.amount) },
                    { key: "paymentMode", label: "Method",
                      render: (r) => r.paymentModeLabel || r.paymentMode },
                    { key: "paymentStatus", label: "Status",
                      render: (r) => <Status code={r.paymentStatus} label={r.paymentStatus} /> },
                    { key: "act", label: "", actions: true,
                      render: (r) => r.paymentStatus === "PAID" && perm.canDelete && (
                        <HrButton size="sm" icon={P.ban} disabled={!!acting}
                                  busy={acting === `pay${r.id}`}
                                  busyLabel="Cancelling…"
                                  onClick={() => run(() => cancelPayment(r.id, "Cancelled"),
                                                     "Payment cancelled.", openRun.id,
                                                     `pay${r.id}`)}>
                          Cancel
                        </HrButton>
                      ) },
                  ]}
                />
                <HrPagination page={payRows.page} size={payRows.size} total={payRows.total} totalPages={payRows.totalPages}
                              onPage={payRows.setPage} onSize={payRows.setSize} />
              </Card>
            )}
          </div>
        </Modal>
      )}

      {/* ── One payslip, with its arithmetic ── */}
      {slip && (
        <Modal title={`Payslip — ${slip.empName}`} width={720} onClose={() => setSlip(null)}
               footer={
                 <>
                   <HrButton onClick={() => setSlip(null)}>Close</HrButton>
                   {/* Printing opens the A4 document — the same shape as the
                       sales invoice, assembled by the server — rather than
                       printing this dialog. */}
                   <HrButton variant="primary" icon={P.printer}
                             onClick={() => setPrintSlip({ payrollId: openRun?.id, lineId: slip.id })}>Print</HrButton>
                 </>
               }>
          <div className="hr-payslip">
            <div className="hr-payslip-col">
              <h4>Earnings</h4>
              {(slip.components || []).filter((c) => c.componentType === "EARNING").map((c, i) => (
                <div className="hr-payslip-line" key={`e${i}`}>
                  <span className="hr-payslip-name">
                    {c.componentName}
                    {c.pctValue != null && c.baseAmount != null && (
                      <span className="hr-payslip-basis">
                        {c.pctValue}% of {money(c.baseAmount)}
                      </span>
                    )}
                  </span>
                  <span className="hr-payslip-amount">{money(c.amount)}</span>
                </div>
              ))}
              <div className="hr-payslip-sub">
                <span>Gross</span><span>{money(slip.grossSalary)}</span>
              </div>
            </div>

            <div className="hr-payslip-col">
              <h4>Deductions</h4>
              {(slip.components || []).filter((c) => c.componentType === "DEDUCTION").length === 0 && (
                <div className="hr-payslip-line"><span>Nothing deducted</span><span>—</span></div>
              )}
              {(slip.components || []).filter((c) => c.componentType === "DEDUCTION").map((c, i) => (
                <div className="hr-payslip-line" key={`d${i}`}>
                  <span className="hr-payslip-name">
                    {c.componentName}
                    {c.pctValue != null && c.baseAmount != null && (
                      <span className="hr-payslip-basis">
                        {c.pctValue}% of {money(c.baseAmount)}
                      </span>
                    )}
                  </span>
                  <span className="hr-payslip-amount">{money(c.amount)}</span>
                </div>
              ))}
              <div className="hr-payslip-sub">
                <span>Total deductions</span><span>{money(slip.deductionTotal)}</span>
              </div>
            </div>

            <div className="hr-payslip-net">
              <span>Net salary</span>
              <span className="hr-payslip-amount">{money(slip.netSalary)}</span>
            </div>
          </div>

          <Facts>
            <Fact label="Present" value={days(slip.presentDays)} />
            <Fact label="Absent" value={days(slip.absentDays)} />
            <Fact label="Leave" value={days(slip.leaveDays)} />
            <Fact label="Unpaid leave" value={days(slip.unpaidLeaveDays)} />
            {Number(slip.leaveDays || 0) > 0
              && Number(slip.unpaidLeaveDays || 0) === 0 && (
              <Fact label="Leave cost" value="nothing — paid leave" />
            )}
            <Fact label="Overtime" value={hoursOf(slip.overtimeMinutes)} />
            <Fact label="Tax" value={money(slip.taxAmount)} />
          </Facts>

          <PolicyNote>
            Tax is zero because no rate, slab or exemption is configured anywhere in this
            application. Putting a plausible figure here would be inventing a number no accountant
            chose. The taxable base is tracked, so a rate — once this pharmacy and its adviser
            settle one — has something to apply to.
          </PolicyNote>
        </Modal>
      )}

      {printSlip && (
        <PayslipPrint payrollId={printSlip.payrollId} lineId={printSlip.lineId} onClose={() => setPrintSlip(null)} />
      )}

      {/* ── Open a period ── */}
      {periodForm && (
        <Modal title="Open a pay period" width={540} onClose={() => setPeriodForm(null)}
               footer={
                 <>
                   <HrButton variant="ghost" onClick={() => setPeriodForm(null)}>Cancel</HrButton>
                   <HrButton variant="primary" disabled={!periodForm.workingDays}
                    onClick={() => run(async () => {
                    await createPeriod({
                    ...periodForm,
                    periodFrom: periodForm.periodFrom || null,
                    periodTo: periodForm.periodTo || null,
                    workingDays: Number(periodForm.workingDays),
                    });
                    setPeriodForm(null);
                    }, "Period opened.")}>
                     Open
                   </HrButton>
                 </>
               }>
          <div className="hr-form">
            <Field label="Year">
              <input type="number" value={periodForm.periodYear}
                     onChange={(e) => setPeriodForm({ ...periodForm,
                                                      periodYear: Number(e.target.value) })} />
            </Field>
            <Field label="Month">
              <select value={periodForm.periodMonth}
                      onChange={(e) => setPeriodForm({ ...periodForm,
                                                       periodMonth: Number(e.target.value) })}>
                {["January", "February", "March", "April", "May", "June", "July",
                  "August", "September", "October", "November", "December"]
                  .map((m, i) => <option key={m} value={i + 1}>{m}</option>)}
              </select>
            </Field>
            <Field label="From" hint="Blank means the first of the month">
              <input type="date" value={periodForm.periodFrom}
                     onChange={(e) => setPeriodForm({ ...periodForm, periodFrom: e.target.value })} />
            </Field>
            <Field label="To" hint="Blank means the last of the month">
              <input type="date" value={periodForm.periodTo}
                     onChange={(e) => setPeriodForm({ ...periodForm, periodTo: e.target.value })} />
            </Field>
            <Field label="Working days">
              <input type="number" min="1" step="0.5" value={periodForm.workingDays}
                     onChange={(e) => setPeriodForm({ ...periodForm, workingDays: e.target.value })} />
            </Field>
            <div className="hr-form-full">
              <PolicyNote>
                The working-day count is required, not defaulted. Payroll divides by it to value an
                absence, and it is stored on the period rather than recomputed, so a run reproduced
                next year uses the figure that was used then — not one derived from a calendar
                somebody has since edited.
              </PolicyNote>
            </div>
          </div>
        </Modal>
      )}

      {/* ── Salary payment ── */}
      {payForm && (
        <Modal title={payForm.empName ? `Pay — ${payForm.empName}` : "Pay the whole run"}
               width={540} onClose={() => setPayForm(null)}
               footer={
                 <>
                   <HrButton variant="ghost" onClick={() => setPayForm(null)}>Cancel</HrButton>
                   <HrButton variant="primary" onClick={() => run(async () => {
                    await paySalary({
                    payrollId: payForm.payrollId,
                    payrollLineId: payForm.payrollLineId,
                    amount: payForm.payrollLineId ? Number(payForm.amount) : null,
                    paymentDate: payForm.paymentDate,
                    paymentMode: payForm.paymentMode,
                    bankName: payForm.bankName || null,
                    chequeNo: payForm.chequeNo || null,
                    referenceNo: payForm.referenceNo || null,
                    remarks: payForm.remarks || null,
                    });
                    const id = payForm.payrollId;
                    setPayForm(null);
                    await open(id);
                    }, "Payment recorded.")}>
                     Record
                   </HrButton>
                 </>
               }>
          <div className="hr-form">
            <Field label="Amount">
              <input type="number" min="0" step="0.01" value={payForm.amount}
                     readOnly={!payForm.payrollLineId}
                     onChange={(e) => setPayForm({ ...payForm, amount: e.target.value })} />
            </Field>
            <Field label="Date">
              <input type="date" value={payForm.paymentDate}
                     onChange={(e) => setPayForm({ ...payForm, paymentDate: e.target.value })} />
            </Field>
            <Field label="Method">
              <select value={payForm.paymentMode}
                      onChange={(e) => setPayForm({ ...payForm, paymentMode: e.target.value })}>
                {(vocab.paymentMode || []).map((o) => (
                  <option key={o.value} value={o.value}>{o.label}</option>
                ))}
              </select>
            </Field>
            <Field label="Reference">
              <input value={payForm.referenceNo}
                     onChange={(e) => setPayForm({ ...payForm, referenceNo: e.target.value })} />
            </Field>
            {(payForm.paymentMode === "BANK" || payForm.paymentMode === "CHEQUE") && (
              <>
                <Field label="Bank">
                  <input value={payForm.bankName}
                         onChange={(e) => setPayForm({ ...payForm, bankName: e.target.value })} />
                </Field>
                <Field label="Cheque no.">
                  <input value={payForm.chequeNo}
                         onChange={(e) => setPayForm({ ...payForm, chequeNo: e.target.value })} />
                </Field>
              </>
            )}
            <div className="hr-form-full">
              <PolicyNote>
                {payForm.payrollLineId
                  ? "One payment row is written against this payslip."
                  : "One payment row per payslip still owing, so cancelling one person's payment "
                    + "later does not unpick everybody's."}
                {" "}No accounting entry is written — Accounts owns the general ledger and does not
                exist in this build yet.
              </PolicyNote>
            </div>
          </div>
        </Modal>
      )}

      {/* ── Structure editor ── */}
      {structForm && (
        <Modal title={structForm.id ? "Edit salary structure" : "Add a salary structure"}
               width={860} onClose={() => setStructForm(null)}
               footer={
                 <>
                   <HrButton variant="ghost" onClick={() => setStructForm(null)}>Cancel</HrButton>
                   <HrButton variant="primary" onClick={saveStructure}
                           disabled={!structForm.structureName}>Save</HrButton>
                 </>
               }>
          <div className="hr-form">
            <Field label="Name">
              <input value={structForm.structureName}
                     onChange={(e) => setStructForm({ ...structForm, structureName: e.target.value })} />
            </Field>
            <Field label="Absence deducted on">
              <select value={structForm.absenceBasis}
                      onChange={(e) => setStructForm({ ...structForm, absenceBasis: e.target.value })}>
                <option value="BASIC">Basic salary</option>
                <option value="GROSS">Gross salary</option>
              </select>
            </Field>
            <Field label="Standard day (hours)">
              <input type="number" min="0" step="0.5" value={structForm.standardHoursPerDay}
                     onChange={(e) => setStructForm({ ...structForm,
                                                      standardHoursPerDay: e.target.value })} />
            </Field>
            <Field label="Overtime multiplier">
              <input type="number" min="0" step="0.1" value={structForm.overtimeMultiplier}
                     placeholder="leave empty — overtime unpaid"
                     onChange={(e) => setStructForm({ ...structForm,
                                                      overtimeMultiplier: e.target.value })} />
            </Field>
            <div className="hr-form-full">
              <PolicyNote>
                Both overtime fields empty means overtime is recorded but not paid, which is what
                this application ships with: a rate is a policy question and in places a statutory
                one, and a plausible-looking 1.5 seeded here would be read as the answer. Absence is
                deducted on basic by default because it is the narrower of the two — deducting on
                gross takes more money.
              </PolicyNote>
            </div>
          </div>

          <div className="hr-lines-head">
            <h4>Components · {(structForm.components || []).length}</h4>
            <p>Evaluated top to bottom. Basic salary is on every payslip already.</p>
          </div>
          <HrLines
            cols={[
              { key: "name", label: "Name", width: "30%" },
              { key: "type", label: "Type", width: "18%" },
              { key: "method", label: "Worked out as", width: "20%" },
              { key: "value", label: "Amount / %", num: true, width: "16%" },
              { key: "pf", label: "PF", width: "8%" },
            ]}
            rows={structForm.components || []}
            onRemove={(i) => setStructForm({ ...structForm, components: structForm.components.filter((_, j) => j !== i) })}
            onAdd={() => setStructForm({ ...structForm, components: [...(structForm.components || []), emptyComponent()] })}
            addLabel="Add a component"
            empty="No components — the payslip is basic salary alone."
            cell={(c, i, key) => {
              const patch = (obj) => { const next = [...structForm.components]; next[i] = { ...c, ...obj }; setStructForm({ ...structForm, components: next }); };
              switch (key) {
                case "name": return (
                  <input placeholder="House rent" aria-label="Component name" value={c.componentName}
                         onChange={(e) => patch({ componentName: e.target.value,
                           componentCode: c.componentCode || e.target.value.toUpperCase().replace(/[^A-Z0-9]/g, "").slice(0, 10) })} />);
                case "type": return (
                  <select value={c.componentType} aria-label="Type" onChange={(e) => patch({ componentType: e.target.value })}>
                    <option value="EARNING">Earning</option>
                    <option value="DEDUCTION">Deduction</option>
                  </select>);
                case "method": return (
                  <select value={c.calcMethod} aria-label="Worked out as" onChange={(e) => patch({ calcMethod: e.target.value })}>
                    <option value="FIXED">Fixed amount</option>
                    <option value="PCT_OF_BASIC">% of basic</option>
                    <option value="PCT_OF_GROSS">% of gross</option>
                  </select>);
                case "value": return (
                  <input type="number" min="0" step="0.01" aria-label={c.calcMethod === "FIXED" ? "Amount" : "Percent"}
                         placeholder={c.calcMethod === "FIXED" ? "0.00" : "%"}
                         value={c.calcMethod === "FIXED" ? c.amount : c.pctValue}
                         onChange={(e) => patch(c.calcMethod === "FIXED" ? { amount: e.target.value } : { pctValue: e.target.value })} />);
                case "pf": return c.componentType === "DEDUCTION" ? (
                  <label className="hr-lines-check" title="This component is the provident fund deduction">
                    <input type="checkbox" checked={!!c.isPf} onChange={(e) => patch({ isPf: e.target.checked ? 1 : 0 })} />
                    Is PF
                  </label>) : <span className="hr-lines-static hr-dash">—</span>;
                default: return null;
              }
            }}
          />
          <PolicyNote>
            Tick <strong>Is PF</strong> on the component that is the provident fund deduction. It
            changes nothing until this branch has a PF scheme; once it does, the scheme's rate is
            used and this component is skipped, so PF is not taken twice. Nothing is inferred from
            the component's name.
          </PolicyNote>
          <PolicyNote>
            Components are evaluated in the order they appear, and the order is arithmetic rather
            than presentation: a &ldquo;% of gross&rdquo; line placed before the allowances that
            make up that gross computes against a smaller number and produces a different answer.
            Basic salary is on every payslip already and comes from the employee — a structure
            naming it would pay it twice, and the server refuses that.
          </PolicyNote>
        </Modal>
      )}
    </HrPage>
  );
}
