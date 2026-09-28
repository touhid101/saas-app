/**
 * HrEmployees.jsx — the HR home screen (MOD32).
 *
 * Three things: what the branch looks like today, who is on the payroll, and
 * one person's whole record.
 *
 * <b>There is no employee form here.</b> Creating and editing a staff record
 * stays on Master Data, where it has always been and where the delivery list
 * and the invoice salesperson read from. This screen adds what that form has no
 * business knowing about — what somebody is paid, what they have taken, what
 * they owe, and their departure.
 *
 * The one thing it does write is the pay basis, gated by Edit on Employees
 * rather than by the Master Data right, so a manager can maintain staff records
 * without being able to see or set salaries.
 */
import React, { useState, useEffect, useCallback } from "react";
import { Card, Modal, Field } from "../components/UI.jsx";
import { useApp } from "../lib/AppContext.jsx";
import { usePermission } from "../lib/usePermission.js";
import { useConfirm } from "../lib/ConfirmProvider.jsx";
import EmployeeAvatar from "../components/EmployeeAvatar.jsx";
import EmployeeEntryForm from "./hr/EmployeeEntryForm.jsx";
import {
  HrPage, Kpis, Kpi, HrTable, Status, HrTabs, HrTabBar, Filters, Filter,
  Facts, Fact, Blocked, PolicyNote, HrButton, HrRowActions, HrSearch,
  HrPageSkeleton, HrErrorState, HrFilterBar, HrSkeleton,
  HrPagination, HrEmployeePicker, usePagedList, useLocalPage,
  humanError, P, HR_CRUMB,
} from "../components/HrKit.jsx";
import {
  getSummary, getProfile, setPayBasis, getEmployees, searchEmployees, getStructures, getVocabulary,
  searchSeparations, fileSeparation, approveSeparation, rejectSeparation,
  cancelSeparation, settleSeparation, uploadEmployeeImage, removeEmployeeImage,
  searchIncrements, getPendingIncrements, getDueIncrements, proposeIncrement,
  approveIncrement, rejectIncrement, applyIncrement, cancelIncrement,
  INCREMENT_TYPES,
  getGratuityPolicy, setGratuityPolicy, getGratuityEstimate, searchGratuity,
  awardGratuity, approveGratuity, rejectGratuity, payGratuity, cancelGratuity,
  GRATUITY_BASES, PARTIAL_YEAR_RULES,
  IMAGE_ACCEPT, money, days, isoToday,
} from "../services/hrService.js";

const searchPeople = async (q) => (await searchEmployees(q)).items;
const fmt = (n) => Number(n || 0).toLocaleString("en-US");
const statusOptions = (codes) => codes.map((x) => (
  <option key={x} value={x}>{x.charAt(0) + x.slice(1).toLowerCase()}</option>
));

export default function HrEmployees() {
  const { addToast } = useApp();
  const confirm = useConfirm();
  const perm = usePermission("hr");

  const [pane, setPane]         = useState("overview");
  const [summary, setSummary]   = useState(null);
  const [structures, setStructures] = useState([]);
  const [vocab, setVocab]       = useState({});
  const [busy, setBusy]         = useState(true);
  /* Bumped by every loader; the server-paged lists reload on it. */
  const [reloadToken, setReloadToken] = useState(0);
  const bump = () => setReloadToken((t) => t + 1);
  const [sepStatus, setSepStatus] = useState("");
  const [sepWho, setSepWho]       = useState("");
  const [incWho, setIncWho]       = useState("");
  const [gratStatus, setGratStatus] = useState("");
  const [gratWho, setGratWho]     = useState("");
  /* Separate from the toast: a toast is gone in three seconds, and somebody who
     looked away still needs to know why the page is empty — and how to retry. */
  const [loadErr, setLoadErr]   = useState(null);
  const [q, setQ]               = useState("");
  const [openId, setOpenId]     = useState(null);
  const [profile, setProfile]   = useState(null);
  const [payForm, setPayForm]   = useState(null);
  const [sepForm, setSepForm]   = useState(null);
  /* The entry form: { id: null } to add, { id, profile } to edit. */
  const [entry, setEntry] = useState(null);
  const [uploading, setUploading] = useState(false);
  const fileInput = React.useRef(null);

  /**
   * Replaces the open profile's photograph.
   *
   * <p>The input is cleared afterwards whatever happened, or choosing the same
   * file again fires no change event and the second attempt looks ignored.
   */
  const pickImage = async (file) => {
    if (!file || !profile) return;
    setUploading(true);
    try {
      const { imagePath } = await uploadEmployeeImage(profile.employeeId, file);
      setProfile((p) => ({ ...p, imagePath }));
      addToast("Photograph updated.");
      load();
    } catch (err) {
      addToast(humanError(err, "That image was not accepted."), "error");
    } finally {
      setUploading(false);
      if (fileInput.current) fileInput.current.value = "";
    }
  };

  const dropImage = async () => {
    const ok = await confirm({
      title: "Remove this photograph?",
      body: "The record keeps their initials instead.",
      confirmLabel: "Remove", tone: "danger",
    });
    if (!ok) return;
    try {
      await removeEmployeeImage(profile.employeeId);
      setProfile((p) => ({ ...p, imagePath: null }));
      addToast("Photograph removed.");
      load();
    } catch (err) {
      addToast(humanError(err, "Could not remove it."), "error");
    }
  };

  /* Increments.

     On the Employees screen because an increment is a change to a person's
     record, and the figure it moves — BASIC_SALARY — is set two tabs away on
     this same page. A separate screen would have somebody editing the salary
     in one place and its history in another.

     Loaded on their own rather than in load(), because approving one should
     not reload the whole staff list. */
  const [incQueue, setIncQueue]   = useState([]);
  const [incDue, setIncDue]       = useState([]);
  const [incForm, setIncForm]     = useState(null);
  const [incFilter, setIncFilter] = useState("");

  const loadIncrements = useCallback(async () => {
    try {
      const [queue, due] = await Promise.all([
        getPendingIncrements().catch(() => []),
        getDueIncrements().catch(() => []),
      ]);
      setIncQueue(queue || []);
      setIncDue(due || []);
    } catch { /* the pane just stays empty */ }
    bump();
  }, []);

  useEffect(() => { loadIncrements(); }, [loadIncrements]);

  /* The history pages on the server; the two queues above it are short and
     are paged here. */
  const incRows = usePagedList(
    ({ page, size }) => searchIncrements({ page, size, status: incFilter || undefined, employeeId: incWho || undefined }),
    [incFilter, incWho, reloadToken]);
  const incQueuePage = useLocalPage(incQueue, { size: 10 });
  const incDuePage = useLocalPage(incDue, { size: 10 });

  /* What the chosen person is currently on.

     Read from their profile rather than the staff list, because the list comes
     from the shared /api/employees endpoint and carries no salary — and adding
     one there would put pay on a response every screen in the application
     reads. */
  const [incBase, setIncBase] = useState(null);

  useEffect(() => {
    const id = incForm?.employeeId;
    if (!id) { setIncBase(null); return; }
    let live = true;
    getProfile(id)
      .then((p) => { if (live) setIncBase(p?.basicSalary == null ? null : Number(p.basicSalary)); })
      .catch(() => { if (live) setIncBase(null); });
    return () => { live = false; };
  }, [incForm?.employeeId]);

  /** What the proposal is worth against that salary today. Indicative only. */
  const incPreview = () => {
    if (!incForm || incBase == null) return null;
    const amount = incForm.incrementBasis === "FIXED"
      ? Number(incForm.fixedAmount || 0)
      : Math.round(incBase * Number(incForm.pctValue || 0)) / 100;
    if (!amount) return null;
    return { current: incBase, amount, next: incBase + amount };
  };

  const fileIncrement = async () => {
    try {
      await proposeIncrement({
        employeeId: Number(incForm.employeeId),
        incrementType: incForm.incrementType,
        incrementBasis: incForm.incrementBasis,
        pctValue: incForm.incrementBasis === "PERCENT" ? Number(incForm.pctValue) : null,
        fixedAmount: incForm.incrementBasis === "FIXED" ? Number(incForm.fixedAmount) : null,
        effectiveDate: incForm.effectiveDate,
        newGrade: incForm.newGrade || null,
        reason: incForm.reason || null,
        submit: true,
      });
      setIncForm(null);
      addToast("Increment proposed.");
      loadIncrements();
    } catch (err) {
      addToast(humanError(err, "Could not propose that increment."), "error");
    }
  };

  const decideIncrement = async (row, approve) => {
    const ok = await confirm({
      title: approve ? "Approve this increment?" : "Reject this increment?",
      body: approve
        ? money(row.previousSalary) + " becomes " + money(row.newSalary) + " from "
          + row.effectiveDate + ". The amount is fixed at this decision — it is not worked out "
          + "again later, so a change to the salary in the meantime cannot move it."
        : "Nothing changes. The proposal stays on the record as refused.",
      confirmLabel: approve ? "Approve" : "Reject",
      tone: approve ? "primary" : "danger",
    });
    if (!ok) return;
    try {
      if (approve) await approveIncrement(row.id, {});
      else await rejectIncrement(row.id, "Not accepted");
      addToast(approve ? "Increment approved." : "Increment rejected.");
      loadIncrements();
    } catch (err) {
      addToast(humanError(err, "That did not go through."), "error");
    }
  };

  const applyOne = async (row) => {
    const ok = await confirm({
      title: "Apply this increment?",
      body: row.empName + " goes on to " + money(row.newSalary) + ". Payroll runs already "
          + "calculated are untouched; the new figure is what the next calculation reads.",
      confirmLabel: "Apply", tone: "primary",
    });
    if (!ok) return;
    try {
      await applyIncrement(row.id);
      addToast("Increment applied.");
      loadIncrements();
      load();
    } catch (err) {
      addToast(humanError(err, "Could not apply that increment."), "error");
    }
  };

  const dropIncrement = async (row) => {
    const ok = await confirm({
      title: "Cancel this increment?",
      body: "The proposal is withdrawn. Nothing on the salary changes.",
      confirmLabel: "Cancel increment", tone: "danger",
    });
    if (!ok) return;
    try {
      await cancelIncrement(row.id, "Withdrawn");
      addToast("Increment cancelled.");
      loadIncrements();
    } catch (err) {
      addToast(humanError(err, "Could not cancel that increment."), "error");
    }
  };

  /* Gratuity.

     On the Employees screen because gratuity is part of somebody leaving, and
     Leavers is already here. A separate page would put the award somewhere
     other than the separation it settles. */
  const [gratPolicy, setGratPolicy] = useState(null);
  const [gratPending, setGratPending] = useState(0);
  const [gratForm, setGratForm]     = useState(null);
  const [polForm, setPolForm]       = useState(null);
  const [estimate, setEstimate]     = useState(null);

  const loadGratuity = useCallback(async () => {
    try {
      const [p, waiting] = await Promise.all([
        getGratuityPolicy().catch(() => null),
        searchGratuity({ status: "SUBMITTED", page: 0, size: 1 }).catch(() => null),
      ]);
      setGratPolicy(p);
      setGratPending(waiting?.total || 0);
    } catch { /* the pane just stays empty */ }
    bump();
  }, []);

  useEffect(() => { loadGratuity(); }, [loadGratuity]);

  const gratRows = usePagedList(
    ({ page, size }) => searchGratuity({ page, size, status: gratStatus || undefined, employeeId: gratWho || undefined }),
    [gratStatus, gratWho, reloadToken]);

  const saveGratuityPolicy = async () => {
    try {
      await setGratuityPolicy({
        policyName: polForm.policyName,
        /* "" is sent as null, because a blank is a question nobody has
           answered rather than an answer of nought. */
        daysPerYear: polForm.daysPerYear === "" ? null : Number(polForm.daysPerYear),
        daysInMonth: polForm.daysInMonth === "" ? null : Number(polForm.daysInMonth),
        wageBasis: polForm.wageBasis,
        minServiceYears: polForm.minServiceYears === "" ? null : Number(polForm.minServiceYears),
        maxServiceYears: polForm.maxServiceYears === "" ? null : Number(polForm.maxServiceYears),
        partialYearRule: polForm.partialYearRule,
        effectiveFrom: polForm.effectiveFrom || null,
      });
      setPolForm(null);
      addToast("Gratuity policy set.");
      loadGratuity();
    } catch (err) {
      addToast(humanError(err, "Could not set that policy."), "error");
    }
  };

  const showEstimate = async (employeeId) => {
    try { setEstimate(await getGratuityEstimate(employeeId)); }
    catch (err) { addToast(humanError(err, "Could not work that out."), "error"); }
  };

  const fileAward = async () => {
    try {
      await awardGratuity({
        employeeId: Number(gratForm.employeeId),
        serviceTo: gratForm.serviceTo || null,
        reason: gratForm.reason || null,
        submit: true,
      });
      setGratForm(null);
      addToast("Gratuity awarded.");
      loadGratuity();
    } catch (err) {
      addToast(humanError(err, "Could not make that award."), "error");
    }
  };

  const decideAward = async (row, approve) => {
    const ok = await confirm({
      title: approve ? "Approve this award?" : "Reject this award?",
      body: approve
        ? `${money(row.gratuityAmount)} for ${row.serviceYears} years of service. The figures are `
          + "worked out once more now and then frozen — nothing recomputes them afterwards."
        : "Nothing is paid. The award stays on the record as refused.",
      confirmLabel: approve ? "Approve" : "Reject",
      tone: approve ? "primary" : "danger",
    });
    if (!ok) return;
    try {
      if (approve) await approveGratuity(row.id);
      else await rejectGratuity(row.id, "Not accepted");
      addToast(approve ? "Gratuity approved." : "Award rejected.");
      loadGratuity();
    } catch (err) {
      addToast(humanError(err, "That did not go through."), "error");
    }
  };

  const payAward = async (row) => {
    const ok = await confirm({
      title: `Pay ${money(row.gratuityAmount)}?`,
      body: "Recorded as paid by bank transfer. The final settlement on the separation is not "
          + "touched — what belongs in one is a question this application has not answered.",
      confirmLabel: "Record the payment", tone: "primary",
    });
    if (!ok) return;
    try {
      await payGratuity(row.id, { paymentMode: "BANK", paidDate: isoToday() });
      addToast("Gratuity paid.");
      loadGratuity();
    } catch (err) {
      addToast(humanError(err, "Could not record that payment."), "error");
    }
  };

  const dropAward = async (row) => {
    const ok = await confirm({
      title: "Cancel this award?", body: "The award is withdrawn. Nothing is paid.",
      confirmLabel: "Cancel award", tone: "danger",
    });
    if (!ok) return;
    try {
      await cancelGratuity(row.id, "Withdrawn");
      addToast("Award cancelled.");
      loadGratuity();
    } catch (err) {
      addToast(humanError(err, "Could not cancel that award."), "error");
    }
  };

  const load = useCallback(async () => {
    setBusy(true);
    try {
      const [s, st, v] = await Promise.all([
        getSummary(),
        getStructures(true).catch(() => []),
        getVocabulary().catch(() => ({})),
      ]);
      setSummary(s);
      setStructures(st || []);
      setVocab(v || {});
      setLoadErr(null);
      bump();
    } catch (err) {
      setLoadErr(humanError(err, "The HR records could not be read."));
    } finally {
      setBusy(false);
    }
  }, []);

  useEffect(() => { load(); }, [load]);

  /* The staff list is searched and paged on the server — 2,500 people are
     never fetched to be filtered in the browser. */
  const staffList = usePagedList(
    ({ page, size }) => getEmployees({ q: q.trim() || undefined, page, size }),
    [q, reloadToken], { size: 50 });

  const separations = usePagedList(
    ({ page, size }) => searchSeparations({ page, size, status: sepStatus || undefined, employeeId: sepWho || undefined }),
    [sepStatus, sepWho, reloadToken]);

  const openProfile = async (id) => {
    setOpenId(id);
    setProfile(null);
    try {
      setProfile(await getProfile(id));
    } catch (err) {
      addToast(humanError(err, "Could not open that record."), "error");
      setOpenId(null);
    }
  };

  /* ── The employee record ──
     Entered and changed through EmployeeEntryForm: one front for the identity
     half on /api/employees (shared with Master Data) and the HR half on
     /api/v1/hr/employees/{id}. Nothing is stored twice. */

  /* ── Pay basis ── */

  const startPayBasis = (p) => setPayForm({
    employeeId: p.employeeId,
    empName: p.empName,
    basicSalary: p.basicSalary ?? "",
    salaryStructureId: p.salaryStructureId ?? "",
    employmentType: p.employmentType ?? "",
    salaryPaymentMode: p.salaryPaymentMode ?? "CASH",
    bankName: p.bankName ?? "",
    bankBranch: p.bankBranch ?? "",
    bankAccountNo: p.bankAccountNo ?? "",
    mobileWalletNo: p.mobileWalletNo ?? "",
    confirmationDate: p.confirmationDate ?? "",
  });

  const savePayBasis = async () => {
    const f = payForm;
    try {
      const updated = await setPayBasis(f.employeeId, {
        basicSalary: f.basicSalary === "" ? null : Number(f.basicSalary),
        salaryStructureId: f.salaryStructureId || null,
        employmentType: f.employmentType || null,
        salaryPaymentMode: f.salaryPaymentMode || null,
        bankName: f.bankName || null,
        bankBranch: f.bankBranch || null,
        bankAccountNo: f.bankAccountNo || null,
        mobileWalletNo: f.mobileWalletNo || null,
        confirmationDate: f.confirmationDate || null,
      });
      setProfile(updated);
      setPayForm(null);
      addToast("Pay basis updated.");
      load();
    } catch (err) {
      addToast(humanError(err, "Could not save the pay basis."), "error");
    }
  };

  /* ── Separation ── */

  const saveSeparation = async () => {
    try {
      await fileSeparation({
        employeeId: Number(sepForm.employeeId),
        separationType: sepForm.separationType,
        noticeDate: sepForm.noticeDate || null,
        lastWorkingDate: sepForm.lastWorkingDate,
        noticePeriodDays: sepForm.noticePeriodDays === "" ? null : Number(sepForm.noticePeriodDays),
        reason: sepForm.reason || null,
        submit: true,
      });
      setSepForm(null);
      addToast("Separation filed.");
      load();
      if (openId) openProfile(openId);
    } catch (err) {
      addToast(humanError(err, "Could not file the separation."), "error");
    }
  };

  const decide = async (fn, id, verb) => {
    const ok = await confirm({
      title: `${verb} this separation?`,
      body: verb === "Approve"
        ? "Approving deactivates the employee. The record itself is kept — invoices and "
          + "delivery history still point at it — but they leave every picker and every payroll run."
        : "This can be filed again later.",
      confirmLabel: verb,
      tone: verb === "Approve" ? "primary" : "danger",
    });
    if (!ok) return;
    try {
      await fn(id);
      addToast(`Separation ${verb.toLowerCase()}d.`);
      load();
      if (openId) openProfile(openId);
    } catch (err) {
      addToast(humanError(err, "That did not go through."), "error");
    }
  };

  if (busy && !summary) return <HrPageSkeleton label="Loading HR…" kpis={6} />;

  if (loadErr && !summary) {
    return (
      <HrPage crumbs={[HR_CRUMB, { label: "Employees" }]} title="Human Resources">
        <HrErrorState title="The HR records could not be read" hint={loadErr}
                      onRetry={load} retrying={busy} />
      </HrPage>
    );
  }

  const s = summary || {};
  const pending = (s.pendingLeaveRequests || 0) + (s.pendingAdvances || 0)
                + (s.pendingLoans || 0) + (s.pendingSeparations || 0);
  const tabs = [
    { id: "overview",    label: "Overview", icon: P.payroll },
    { id: "staff",       label: "Staff",    icon: P.people },
    { id: "increments",  label: "Increments", icon: P.money, badge: incQueue.length + incDue.length },
    { id: "gratuity",    label: "Gratuity", icon: P.wallet, badge: gratPending },
    { id: "separations", label: "Leavers",  icon: P.away, badge: s.pendingSeparations || 0 },
  ];

  return (
    <HrPage
      crumbs={[HR_CRUMB, { label: "Employees" }]}
      title="Human Resources"
      subtitle="The people at this pharmacy — their record, their pay basis and their departure"
      actions={
        <>
          <HrButton iconOnly icon={P.reset} title="Refresh" onClick={load} busy={busy} />
          {perm.canCreate && (
            <HrButton variant="primary" icon={P.plus} onClick={() => setEntry({ id: null })}>Add employee</HrButton>
          )}
        </>
      }
    >
      <HrTabBar tabs={tabs} active={pane} onChange={setPane} />

      {pane === "overview" && (<>
        <Kpis>
          <Kpi label="Active staff" value={s.activeEmployees ?? "—"} icon={P.people}
               onClick={() => setPane("staff")}
               note={`${s.payrollReady ?? 0} ready for payroll`} />
          <Kpi label="In today" value={s.presentToday ?? 0} icon={P.check}
               note={`${s.absentToday ?? 0} absent · ${s.onLeaveToday ?? 0} on leave`} />
          <Kpi label="Not marked today" value={s.attendanceNotRecordedToday ?? 0}
               warn={(s.attendanceNotRecordedToday ?? 0) > 0} icon={P.calendar}
               note="Attendance still to record" />
          <Kpi label="Waiting on a decision" value={pending} warn={pending > 0} icon={P.clock}
               note={`${s.pendingLeaveRequests ?? 0} leave · ${s.pendingAdvances ?? 0} advance · ${s.pendingLoans ?? 0} loan`} />
          <Kpi label="Advances outstanding" value={money(s.advanceOutstanding)} icon={P.wallet} />
          <Kpi label="Loans outstanding" value={money(s.loanOutstanding)} icon={P.money} />
        </Kpis>

        {(s.notPayrollReady ?? 0) > 0 && (
          <Blocked>
            {s.notPayrollReady} active {s.notPayrollReady === 1 ? "person is" : "people are"} not
            ready for payroll and will be skipped by every run. Open the record to see what is
            missing — usually a basic salary or a salary structure.
          </Blocked>
        )}

        {s.latestPayroll && (
          <Card title="Latest payroll" eyebrow={s.latestPayroll.periodLabel}
                right={<Status code={s.latestPayroll.payrollStatus}
                               label={s.latestPayroll.payrollStatusLabel} />}>
            <Facts>
              <Fact label="Payroll no." value={s.latestPayroll.payrollNo} />
              <Fact label="Payslips" value={s.latestPayroll.employeeCount} />
              <Fact label="Gross" value={money(s.latestPayroll.totalGross)} />
              <Fact label="Net" value={money(s.latestPayroll.totalNet)} />
              <Fact label="Paid" value={money(s.latestPayroll.totalPaid)} />
              <Fact label="Still owing" value={money(s.latestPayroll.unpaid)} />
            </Facts>
          </Card>
        )}
      </>)}

      {pane === "staff" && (
        <Card title={`Staff · ${fmt(staffList.total)}`} right={
          <div style={{ minWidth: 260 }}>
            <HrSearch value={q} onChange={setQ}
                      label="Search staff"
                      placeholder="Name, code or phone" />
          </div>
        }>
          <HrTable
            rowKey={(r) => r.id}
            rows={staffList.rows}
            loading={staffList.busy && !staffList.rows.length}
            error={staffList.err}
            onRetry={staffList.reload}
            onRowClick={(r) => openProfile(r.id)}
            caption="Staff at this branch"
            empty={q
              ? { icon: P.search, title: "No staff match that",
                  hint: `Nothing here answers to \u201C${q}\u201D. The search reads the name, the code and the phone number.`,
                  action: <HrButton icon={P.reset} onClick={() => setQ("")}>Clear the search</HrButton> }
              : { icon: P.people, title: "No staff yet",
                  hint: "Nobody has been added at this branch. Payroll, attendance and leave all start here.",
                  action: perm.canCreate && <HrButton variant="primary" icon={P.plus} onClick={() => setEntry({ id: null })}>Add the first employee</HrButton> }}
            cols={[
              { key: "empName", label: "Name", lead: true, sortable: true,
                render: (r) => (
                  <span style={{ display: "flex", alignItems: "center", gap: 10 }}>
                    <EmployeeAvatar src={r.imagePath} initials={r.initials}
                                    name={r.empName} size={30} />
                    <span>{r.empName}</span>
                  </span>
                ) },
              { key: "empId", label: "Code", sortable: true },
              { key: "empType", label: "Role", render: (r) => r.empTypeLabel || r.empType },
              { key: "phone", label: "Phone", advisory: true },
              { key: "status", label: "Status",
                render: (r) => <Status code={r.status === "Y" ? "APPROVED" : "CANCELLED"}
                                       label={r.status === "Y" ? "Active" : "Inactive"} /> },
              { key: "open", label: "", actions: true,
                render: (r) => (
                  <HrButton size="sm" icon={P.eye} onClick={() => openProfile(r.id)}>Open</HrButton>
                ) },
            ]}
          />
          <HrPagination page={staffList.page} size={staffList.size} total={staffList.total}
                        totalPages={staffList.totalPages} onPage={staffList.setPage} onSize={staffList.setSize} />
        </Card>
      )}

      {pane === "increments" && (<>
        {incDue.length > 0 && (
          <Card title={`Approved and due to be applied · ${fmt(incDue.length)}`}>
            <HrTable
              rowKey={(r) => r.id}
              rows={incDuePage.rows}
              empty=""
              cols={[
                { key: "empName", label: "Employee", lead: true },
                { key: "effectiveDate", label: "Effective" },
                { key: "previousSalary", label: "From", num: true,
                  render: (r) => money(r.previousSalary) },
                { key: "newSalary", label: "To", num: true,
                  render: (r) => money(r.newSalary) },
                { key: "act", label: "", actions: true,
                  render: (r) => perm.canApprove && (
                    <HrButton size="sm" variant="primary" icon={P.check}
                              onClick={() => applyOne(r)}>Apply</HrButton>
                  ) },
              ]}
            />
            <HrPagination page={incDuePage.page} size={incDuePage.size} total={incDuePage.total}
                          totalPages={incDuePage.totalPages} onPage={incDuePage.setPage} onSize={incDuePage.setSize} />
            <PolicyNote>
              Applying is a deliberate step, not something that happens on a date passing.
              An increment approved against a salary that has since changed is refused here rather
              than written over the top of whatever moved it.
            </PolicyNote>
          </Card>
        )}

        <Card
          title="Increments"
          right={perm.canCreate && (
            <HrButton variant="primary" icon={P.plus} onClick={() => setIncForm({
             employeeId: "", incrementType: "ANNUAL", incrementBasis: "PERCENT",
             pctValue: "", fixedAmount: "", effectiveDate: isoToday(),
             newGrade: "", reason: "",
                    })}>Propose an increment</HrButton>
          )}>

          {incQueue.length > 0 && (<>
            <HrTable
              rowKey={(r) => r.id}
              rows={incQueuePage.rows}
              empty=""
              cols={[
                { key: "empName", label: "Waiting on a decision", lead: true },
                { key: "incrementType", label: "Kind",
                  render: (r) => r.incrementTypeLabel || r.incrementType },
                { key: "proposed", label: "Proposed", num: true,
                  render: (r) => (r.incrementBasis === "PERCENT"
                    ? r.pctValue + "%"
                    : money(r.fixedAmount)) },
                { key: "newSalary", label: "Would become", num: true,
                  render: (r) => money(r.newSalary) },
                { key: "effectiveDate", label: "Effective" },
                { key: "act", label: "", actions: true,
                  render: (r) => perm.canApprove && (
                    <HrRowActions
                      items={[{ label: "Reject this increment", icon: P.x, danger: true,
                                onClick: () => decideIncrement(r, false) }]}>
                      <HrButton size="sm" variant="primary" icon={P.check}
                                onClick={() => decideIncrement(r, true)}>Approve</HrButton>
                    </HrRowActions>
                  ) },
              ]}
            />
            <HrPagination page={incQueuePage.page} size={incQueuePage.size} total={incQueuePage.total}
                          totalPages={incQueuePage.totalPages} onPage={incQueuePage.setPage} onSize={incQueuePage.setSize} />
          </>)}

          <HrFilterBar active={(incFilter ? 1 : 0) + (incWho ? 1 : 0)} onReset={() => { setIncFilter(""); setIncWho(""); }}>
            <Filter label="Employee" grow>
              <HrEmployeePicker value={incWho} onChange={setIncWho} search={searchPeople} allowEmpty emptyLabel="Everyone" />
            </Filter>
            <Filter label="Status">
              <select value={incFilter} onChange={(e) => setIncFilter(e.target.value)}>
                <option value="">Any</option>
                {statusOptions(["DRAFT", "SUBMITTED", "APPROVED", "APPLIED", "REJECTED", "CANCELLED"])}
              </select>
            </Filter>
          </HrFilterBar>

          <HrTable
            rowKey={(r) => r.id}
            rows={incRows.rows}
            loading={incRows.busy && !incRows.rows.length}
            error={incRows.err}
            onRetry={incRows.reload}
            caption="Increments, and the salary history they make up"
            empty={(incFilter || incWho)
              ? { icon: P.filter, title: "No increments match that",
                  hint: "Widen the filter, or reset it to see everything.",
                  action: <HrButton icon={P.reset} onClick={() => { setIncFilter(""); setIncWho(""); }}>Reset</HrButton> }
              : { icon: P.money, title: "No increments have been proposed",
                  hint: "These rows are the salary history — the employee record holds only the "
                      + "current figure." }}
            cols={[
              { key: "empName", label: "Employee", lead: true, sortable: true },
              { key: "incrementType", label: "Kind", advisory: true,
                render: (r) => r.incrementTypeLabel || r.incrementType },
              { key: "effectiveDate", label: "Effective" },
              { key: "previousSalary", label: "From", num: true,
                render: (r) => money(r.previousSalary) },
              { key: "incrementAmount", label: "Rise", num: true,
                render: (r) => money(r.incrementAmount) },
              { key: "newSalary", label: "To", num: true,
                render: (r) => money(r.newSalary) },
              { key: "incrementStatus", label: "Status",
                render: (r) => <Status code={r.incrementStatus} label={r.incrementStatusLabel} /> },
              { key: "act", label: "", actions: true,
                render: (r) => (
                  <HrRowActions
                    items={["DRAFT", "SUBMITTED", "APPROVED"].includes(r.incrementStatus)
                            && perm.canDelete
                      ? [{ label: "Cancel this increment", icon: P.ban, danger: true,
                           onClick: () => dropIncrement(r) }]
                      : []}>
                    {r.applicable && perm.canApprove && (
                      <HrButton size="sm" variant="primary" icon={P.check}
                                onClick={() => applyOne(r)}>Apply</HrButton>
                    )}
                  </HrRowActions>
                ) },
            ]}
          />
          <HrPagination page={incRows.page} size={incRows.size} total={incRows.total}
                        totalPages={incRows.totalPages} onPage={incRows.setPage} onSize={incRows.setSize} />

          <PolicyNote>
            These rows <strong>are</strong> the salary history — the employee record holds only
            the current figure. An applied increment is never deleted; the way to undo one is a
            further increment that says so.
          </PolicyNote>
          <PolicyNote>
            <strong>The money is frozen when the increment is approved.</strong> A percentage
            signed off against 20,000 is worth 2,000 whatever the salary says by the time it is
            applied. Recomputing it later would quietly change a decision after it was made.
          </PolicyNote>
        </Card>
      </>)}

      {pane === "gratuity" && (<>
        <Card
          title="Gratuity policy"
          right={perm.canEdit && (
            <HrButton variant="primary" icon={P.edit} onClick={() => setPolForm({
             policyName: gratPolicy?.policyName || "Gratuity",
             daysPerYear: gratPolicy?.daysPerYear ?? "",
             daysInMonth: gratPolicy?.daysInMonth ?? "",
             wageBasis: gratPolicy?.wageBasis || "BASIC",
             minServiceYears: gratPolicy?.minServiceYears ?? "",
             maxServiceYears: gratPolicy?.maxServiceYears ?? "",
             partialYearRule: gratPolicy?.partialYearRule || "IGNORE",
             effectiveFrom: isoToday(),
                    })}>{gratPolicy ? "Change the policy" : "Set the policy"}</HrButton>
          )}>

          {!gratPolicy ? (
            <Blocked>
              <strong>No gratuity policy has been set</strong>, so nothing is computed for anybody.
              Days of pay per year of service, the wage they are valued on, the divisor that turns
              a monthly wage into a daily one, the qualifying period and what a part year is worth
              are all business policy — and in places statute. Nothing here will choose them.
            </Blocked>
          ) : (
            <>
              <Facts>
                <Fact label="Policy" value={gratPolicy.policyName} />
                <Fact label="Days per year"
                      value={gratPolicy.daysPerYear == null ? "not set" : gratPolicy.daysPerYear} />
                <Fact label="Days in a month"
                      value={gratPolicy.daysInMonth == null ? "not set" : gratPolicy.daysInMonth} />
                <Fact label="Valued on" value={gratPolicy.wageBasis === "GROSS" ? "Gross" : "Basic"} />
                <Fact label="Qualifying"
                      value={!gratPolicy.minServiceYears ? "none"
                                                        : gratPolicy.minServiceYears + " years"} />
                <Fact label="Capped at"
                      value={!gratPolicy.maxServiceYears ? "no cap"
                                                        : gratPolicy.maxServiceYears + " years"} />
                <Fact label="A part year" value={gratPolicy.partialYearRuleLabel} />
              </Facts>
              {!gratPolicy.complete && (
                <PolicyNote>
                  This policy <strong>computes nothing</strong>: days per year or the days-in-a-month
                  divisor is still blank. A blank is not nought — it is a question nobody has
                  answered, and the estimate says so rather than printing a figure.
                </PolicyNote>
              )}
            </>
          )}
        </Card>

        <Card
          title={`Awards · ${fmt(gratRows.total)}`}
          right={perm.canCreate && gratPolicy?.complete && (
            <HrButton size="sm" variant="primary" icon={P.plus}
                      onClick={() => setGratForm({ employeeId: "", serviceTo: isoToday(), reason: "" })}>
              Award gratuity
            </HrButton>
          )}>
          <Filters>
            <Filter label="See what somebody would be owed" grow>
              <HrEmployeePicker value="" placeholder="Type a name or code for an estimate…"
                                onChange={(id) => id && showEstimate(id)} search={searchPeople} />
            </Filter>
          </Filters>

          <HrFilterBar active={(gratStatus ? 1 : 0) + (gratWho ? 1 : 0)} onReset={() => { setGratStatus(""); setGratWho(""); }}>
            <Filter label="Employee" grow>
              <HrEmployeePicker value={gratWho} onChange={setGratWho} search={searchPeople} allowEmpty emptyLabel="Everyone" />
            </Filter>
            <Filter label="Status">
              <select value={gratStatus} onChange={(e) => setGratStatus(e.target.value)}>
                <option value="">Any</option>
                {statusOptions(["DRAFT", "SUBMITTED", "APPROVED", "PAID", "REJECTED", "CANCELLED"])}
              </select>
            </Filter>
          </HrFilterBar>

          <HrTable
            rowKey={(r) => r.id}
            rows={gratRows.rows}
            loading={gratRows.busy && !gratRows.rows.length}
            error={gratRows.err}
            onRetry={gratRows.reload}
            caption="Gratuity awards"
            empty={(gratStatus || gratWho)
              ? { icon: P.filter, title: "No awards match that", hint: "Widen the filter, or reset it.",
                  action: <HrButton icon={P.reset} onClick={() => { setGratStatus(""); setGratWho(""); }}>Reset</HrButton> }
              : { icon: P.doc, title: "Nothing has been awarded",
                     hint: "An award needs a complete gratuity policy — days per year, the "
                         + "divisor, the basis and the qualifying period — before it can compute." }}
            cols={[
              { key: "empName", label: "Employee", lead: true, sortable: true },
              { key: "serviceTo", label: "Service to" },
              { key: "serviceYears", label: "Years", num: true },
              { key: "daysAwarded", label: "Days", num: true },
              { key: "gratuityAmount", label: "Amount", num: true,
                render: (r) => money(r.gratuityAmount) },
              { key: "awardStatus", label: "Status",
                render: (r) => <Status code={r.awardStatus} label={r.awardStatusLabel} /> },
              { key: "act", label: "", actions: true,
                render: (r) => (
                  <HrRowActions
                    items={[
                      r.awardStatus === "SUBMITTED" && perm.canApprove
                        && { label: "Reject this award", icon: P.x, danger: true,
                             onClick: () => decideAward(r, false) },
                      ["DRAFT", "SUBMITTED", "APPROVED"].includes(r.awardStatus) && perm.canDelete
                        && { label: "Cancel this award", icon: P.ban, danger: true,
                             onClick: () => dropAward(r) },
                    ].filter(Boolean)}>
                    {r.awardStatus === "SUBMITTED" && perm.canApprove && (
                      <HrButton size="sm" variant="primary" icon={P.check}
                                onClick={() => decideAward(r, true)}>Approve</HrButton>
                    )}
                    {r.awardStatus === "APPROVED" && perm.canApprove && (
                      <HrButton size="sm" variant="primary" icon={P.money}
                                onClick={() => payAward(r)}>Pay</HrButton>
                    )}
                  </HrRowActions>
                ) },
            ]}
          />
          <HrPagination page={gratRows.page} size={gratRows.size} total={gratRows.total}
                        totalPages={gratRows.totalPages} onPage={gratRows.setPage} onSize={gratRows.setSize} />
          <PolicyNote>
            Every figure on an award is <strong>frozen when it is approved</strong> — the service
            years, the wage, the daily rate, the days and the amount. Changing somebody's salary
            afterwards does not move an award that was already signed off.
          </PolicyNote>
          <PolicyNote>
            Paying an award does <strong>not</strong> write a final settlement. What belongs in one
            — notice pay, leave encashment, gratuity — is still an open question, so the settlement
            on the separation stays something a person enters.
          </PolicyNote>
        </Card>
      </>)}

      {pane === "separations" && (
        <Card title={`Separations · ${fmt(separations.total)}`}
              right={perm.canCreate && (
                <HrButton size="sm" variant="primary" icon={P.plus}
                          onClick={() => setSepForm({
                            employeeId: "", separationType: "RESIGNATION",
                            noticeDate: isoToday(), lastWorkingDate: "",
                            noticePeriodDays: "", reason: "",
                          })}>
                  File a separation
                </HrButton>
              )}>
          <HrFilterBar active={(sepStatus ? 1 : 0) + (sepWho ? 1 : 0)} onReset={() => { setSepStatus(""); setSepWho(""); }}>
            <Filter label="Employee" grow>
              <HrEmployeePicker value={sepWho} onChange={setSepWho} search={searchPeople} allowEmpty emptyLabel="Everyone" />
            </Filter>
            <Filter label="Status">
              <select value={sepStatus} onChange={(e) => setSepStatus(e.target.value)}>
                <option value="">Any</option>
                {statusOptions(["DRAFT", "SUBMITTED", "APPROVED", "REJECTED", "CANCELLED"])}
              </select>
            </Filter>
          </HrFilterBar>
          <HrTable
            rowKey={(r) => r.id}
            rows={separations.rows}
            loading={separations.busy && !separations.rows.length}
            error={separations.err}
            onRetry={separations.reload}
            caption="Separations"
            empty={(sepStatus || sepWho)
              ? { icon: P.filter, title: "No separations match that", hint: "Widen the filter, or reset it.",
                  action: <HrButton icon={P.reset} onClick={() => { setSepStatus(""); setSepWho(""); }}>Reset</HrButton> }
              : { icon: P.people, title: "Nobody has left",
                     hint: "An employee record is never deleted — invoices and delivery history "
                         + "point at it. Leaving is a record beside the person." }}
            cols={[
              { key: "empName", label: "Name", lead: true, sortable: true },
              { key: "separationType", label: "Reason",
                render: (r) => r.separationTypeLabel || r.separationType },
              { key: "lastWorkingDate", label: "Last day" },
              { key: "separationStatus", label: "Status",
                render: (r) => <Status code={r.separationStatus} label={r.separationStatusLabel} /> },
              { key: "settlementStatus", label: "Settlement", advisory: true,
                render: (r) => <Status code={r.settlementStatus} label={r.settlementStatus} /> },
              { key: "act", label: "", actions: true,
                render: (r) => (
                  <HrRowActions
                    items={[
                      r.separationStatus === "SUBMITTED" && perm.canApprove
                        && { label: "Reject this separation", icon: P.x, danger: true,
                             onClick: () => decide((id) => rejectSeparation(id), r.id, "Reject") },
                      ["DRAFT", "SUBMITTED", "APPROVED"].includes(r.separationStatus)
                        && perm.canDelete
                        && { label: "Cancel this separation", icon: P.ban, danger: true,
                             onClick: () => decide((id) => cancelSeparation(id), r.id, "Cancel") },
                    ].filter(Boolean)}>
                    {r.separationStatus === "SUBMITTED" && perm.canApprove && (
                      <HrButton size="sm" variant="primary" icon={P.check}
                                onClick={() => decide((id) => approveSeparation(id), r.id, "Approve")}>
                        Approve
                      </HrButton>
                    )}
                    {r.separationStatus === "APPROVED" && perm.canEdit
                      && r.settlementStatus === "PENDING" && (
                      <HrButton size="sm" variant="primary" icon={P.money}
                                onClick={async () => {
                                  try {
                                    await settleSeparation(r.id, {
                                      settlementStatus: "SETTLED",
                                      settlementDate: isoToday(),
                                    });
                                    addToast("Settlement recorded.");
                                    load();
                                  } catch (err) { addToast(humanError(err), "error"); }
                                }}>
                        Settle
                      </HrButton>
                    )}
                  </HrRowActions>
                ) },
            ]}
          />
          <HrPagination page={separations.page} size={separations.size} total={separations.total}
                        totalPages={separations.totalPages} onPage={separations.setPage} onSize={separations.setSize} />
        </Card>
      )}

      <HrTabs active={pane} onChange={setPane} tabs={tabs} />

      {polForm && (
        <Modal title="Gratuity policy" width={560} onClose={() => setPolForm(null)}
               footer={
                 <>
                   <HrButton onClick={() => setPolForm(null)}>Cancel</HrButton>
                   <HrButton variant="primary" icon={P.check} onClick={saveGratuityPolicy}>Save</HrButton>
                 </>
               }>
          <div className="hr-form">
            <Field label="Name">
              <input value={polForm.policyName}
                     onChange={(e) => setPolForm({ ...polForm, policyName: e.target.value })} />
            </Field>
            <Field label="Days of pay per year of service"
                   hint="Leave blank if this has not been decided — nothing is computed then.">
              <input type="number" min="0" step="0.5" value={polForm.daysPerYear}
                     onChange={(e) => setPolForm({ ...polForm, daysPerYear: e.target.value })} />
            </Field>
            <Field label="Days in a month"
                   hint="The divisor for the daily wage. 26 and 30 give materially different answers.">
              <input type="number" min="1" max="31" step="0.5" value={polForm.daysInMonth}
                     onChange={(e) => setPolForm({ ...polForm, daysInMonth: e.target.value })} />
            </Field>
            <Field label="Valued on">
              <select value={polForm.wageBasis}
                      onChange={(e) => setPolForm({ ...polForm, wageBasis: e.target.value })}>
                {GRATUITY_BASES.map((b) => (
                  <option key={b} value={b}>
                    {b === "GROSS" ? "Gross on the last posted payslip" : "Current basic salary"}
                  </option>
                ))}
              </select>
            </Field>
            <Field label="Qualifying service (years)" hint="Blank means no qualifying period.">
              <input type="number" min="0" step="0.5" value={polForm.minServiceYears}
                     onChange={(e) => setPolForm({ ...polForm, minServiceYears: e.target.value })} />
            </Field>
            <Field label="Service cap (years)" hint="Blank means no cap.">
              <input type="number" min="0" step="0.5" value={polForm.maxServiceYears}
                     onChange={(e) => setPolForm({ ...polForm, maxServiceYears: e.target.value })} />
            </Field>
            <Field label="A part year">
              <select value={polForm.partialYearRule}
                      onChange={(e) => setPolForm({ ...polForm, partialYearRule: e.target.value })}>
                {PARTIAL_YEAR_RULES.map((r) => (
                  <option key={r.value} value={r.value}>{r.label}</option>
                ))}
              </select>
            </Field>
            <Field label="In force from">
              <input type="date" value={polForm.effectiveFrom}
                     onChange={(e) => setPolForm({ ...polForm, effectiveFrom: e.target.value })} />
            </Field>
          </div>
          <PolicyNote>
            This supersedes the policy in force rather than editing it — an award already approved
            was approved under the old one, and rewriting it would change a decision after it was
            made.
          </PolicyNote>
        </Modal>
      )}

      {gratForm && (
        <Modal title="Award gratuity" width={520} onClose={() => setGratForm(null)}
               footer={
                 <>
                   <HrButton variant="ghost" onClick={() => setGratForm(null)}>Cancel</HrButton>
                   <HrButton variant="primary" disabled={!gratForm.employeeId}
                           onClick={fileAward}>Award</HrButton>
                 </>
               }>
          <div className="hr-form">
            <div className="hr-form-full">
              <Field label="Employee">
                <HrEmployeePicker value={gratForm.employeeId} search={searchPeople}
                                  onChange={(id) => setGratForm({ ...gratForm, employeeId: id })} />
              </Field>
            </div>
            <Field label="Service to" hint="Their last working day. Service starts at their hire date.">
              <input type="date" value={gratForm.serviceTo}
                     onChange={(e) => setGratForm({ ...gratForm, serviceTo: e.target.value })} />
            </Field>
            <Field label="Note">
              <textarea rows={2} value={gratForm.reason}
                        onChange={(e) => setGratForm({ ...gratForm, reason: e.target.value })} />
            </Field>
          </div>
        </Modal>
      )}

      {estimate && (
        <Modal title={`Gratuity estimate — ${estimate.empName}`} width={620}
               onClose={() => setEstimate(null)}>
          {!estimate.configured ? (
            <Blocked>{estimate.unansweredPolicy}</Blocked>
          ) : (
            <div style={{ display: "flex", flexDirection: "column", gap: 16 }}>
              <Facts>
                <Fact label="Service" value={`${estimate.rawYears} years`} />
                <Fact label="Counted" value={`${estimate.countableYears} years`} />
                <Fact label="Wage" value={money(estimate.monthlyWage)} />
                <Fact label="Daily" value={money(estimate.dailyWage)} />
                <Fact label="Days" value={estimate.daysAwarded} />
                <Fact label="Would be owed" value={money(estimate.gratuityAmount)} />
              </Facts>
              {!estimate.qualified && (
                <PolicyNote>
                  <strong>Not yet qualified.</strong> This policy requires
                  {" " + estimate.minServiceYears} years, and {estimate.empName} has
                  {" " + estimate.rawYears}.
                </PolicyNote>
              )}
              {!estimate.entitled && (
                <PolicyNote>
                  Gratuity is <strong>not ticked</strong> on this employee's record. The figure is
                  what the policy works out to; whether they are in the scheme is a separate answer
                  on their profile.
                </PolicyNote>
              )}
              <PolicyNote>
                {estimate.wageNote} This is what the policy works out to today and nothing has been
                saved — the figure that counts is the one frozen when an award is approved.
              </PolicyNote>
            </div>
          )}
        </Modal>
      )}

      {incForm && (
        <Modal title="Propose an increment" width={560} onClose={() => setIncForm(null)}
               footer={
                 <>
                   <HrButton variant="ghost" onClick={() => setIncForm(null)}>Cancel</HrButton>
                   <HrButton variant="primary" disabled={!incForm.employeeId || !incForm.effectiveDate
                    || !(incForm.incrementBasis === "FIXED" ? incForm.fixedAmount : incForm.pctValue)}
                           onClick={fileIncrement}>Propose</HrButton>
                 </>
               }>
          <div className="hr-form">
            <div className="hr-form-full">
              <Field label="Employee">
                <HrEmployeePicker value={incForm.employeeId} search={searchPeople}
                                  onChange={(id) => setIncForm({ ...incForm, employeeId: id })} />
              </Field>
            </div>
            <Field label="Kind">
              <select value={incForm.incrementType}
                      onChange={(e) => setIncForm({ ...incForm, incrementType: e.target.value })}>
                {INCREMENT_TYPES.map((t) => (
                  <option key={t} value={t}>{t.charAt(0) + t.slice(1).toLowerCase()}</option>
                ))}
              </select>
            </Field>
            <Field label="Basis">
              <select value={incForm.incrementBasis}
                      onChange={(e) => setIncForm({ ...incForm, incrementBasis: e.target.value })}>
                <option value="PERCENT">A percentage of the basic</option>
                <option value="FIXED">A fixed amount</option>
              </select>
            </Field>
            {incForm.incrementBasis === "PERCENT" ? (
              <Field label="Percentage">
                <input type="number" min="0.01" step="0.01" value={incForm.pctValue}
                       onChange={(e) => setIncForm({ ...incForm, pctValue: e.target.value })} />
              </Field>
            ) : (
              <Field label="Amount">
                <input type="number" min="0.01" step="0.01" value={incForm.fixedAmount}
                       onChange={(e) => setIncForm({ ...incForm, fixedAmount: e.target.value })} />
              </Field>
            )}
            <Field label="Effective from">
              <input type="date" value={incForm.effectiveDate}
                     onChange={(e) => setIncForm({ ...incForm, effectiveDate: e.target.value })} />
            </Field>
            <Field label="New grade" hint="Only if this moves them. Leave blank otherwise.">
              <input value={incForm.newGrade}
                     onChange={(e) => setIncForm({ ...incForm, newGrade: e.target.value })} />
            </Field>
            <Field label="Reason">
              <textarea rows={2} value={incForm.reason}
                        onChange={(e) => setIncForm({ ...incForm, reason: e.target.value })} />
            </Field>
          </div>

          {(() => {
            const p = incPreview();
            return p ? (
              <Facts>
                <Fact label="Now" value={money(p.current)} />
                <Fact label="Rise" value={money(p.amount)} />
                <Fact label="Becomes" value={money(p.next)} />
              </Facts>
            ) : null;
          })()}

          {incForm.employeeId && incBase == null && (
            <PolicyNote>
              This employee has <strong>no basic salary set</strong>. A percentage of nothing is
              nothing — set the pay basis on their record first, or propose a fixed amount, which
              is how a salary gets put on a record that never had one.
            </PolicyNote>
          )}
          <PolicyNote>
            This is what it works out to today. The figure that counts is the one frozen when
            somebody approves it.
          </PolicyNote>
        </Modal>
      )}

      {/* ── One person ── */}
      {openId && (
        <Modal title={profile ? profile.empName : "Employee"} width={860}
               onClose={() => { setOpenId(null); setProfile(null); }}>
          {!profile ? (
            /* The shape of the profile, not a word — the avatar, the name, the
               three cards below. Replacing a modal's contents with one line of
               text and then with a full record reads as two different dialogs. */
            <div style={{ display: "flex", flexDirection: "column", gap: 16 }} aria-busy="true">
              <span className="sr-only" role="status">Loading the record…</span>
              <div style={{ display: "flex", alignItems: "center", gap: 16 }} aria-hidden="true">
                <HrSkeleton w={84} h={84} r="50%" />
                <div style={{ display: "flex", flexDirection: "column", gap: 8, flex: 1 }}>
                  <HrSkeleton w="46%" h={17} />
                  <HrSkeleton w="62%" h={11} />
                </div>
              </div>
              {[0, 1, 2].map((i) => (
                <div className="card" key={i} aria-hidden="true">
                  <div className="card-pad">
                    <HrSkeleton w={120} h={13} style={{ marginBottom: 14 }} />
                    <div className="hr-facts">
                      {[0, 1, 2, 3, 4, 5].map((j) => (
                        <div className="hr-fact" key={j}>
                          <HrSkeleton w="60%" h={9} />
                          <HrSkeleton w="85%" h={12} />
                        </div>
                      ))}
                    </div>
                  </div>
                </div>
              ))}
            </div>
          ) : (
            <div style={{ display: "flex", flexDirection: "column", gap: 16 }}>
              {/* The header: who this is, at a glance, with the photograph. */}
              <div style={{ display: "flex", alignItems: "center", gap: 16, flexWrap: "wrap" }}>
                <EmployeeAvatar src={profile.imagePath} initials={profile.initials}
                                name={profile.empName} size={84} />
                <div style={{ display: "flex", flexDirection: "column", gap: 4, minWidth: 0 }}>
                  <strong style={{ fontSize: 17 }}>
                    {profile.preferredName || profile.empName}
                  </strong>
                  <span style={{ fontSize: 13, color: "var(--ink-soft)" }}>
                    {[profile.designationName, profile.departmentName, profile.empTypeLabel]
                      .filter(Boolean).join(" · ") || profile.empId}
                  </span>
                  {perm.canEdit && (
                    <div style={{ display: "flex", gap: 8, marginTop: 6, flexWrap: "wrap" }}>
                      {/* The input is hidden and driven by the button: a bare
                          file input cannot be styled to match anything, and
                          every other control on this screen is a .btn. */}
                      <input ref={fileInput} type="file" accept={IMAGE_ACCEPT}
                             style={{ display: "none" }}
                             onChange={(e) => pickImage(e.target.files?.[0])} />
                      <HrButton size="sm" icon={P.upload}
                                busy={uploading} busyLabel="Uploading…"
                                onClick={() => fileInput.current?.click()}>
                        {profile.imagePath ? "Change photo" : "Add photo"}
                      </HrButton>
                      {profile.imagePath && (
                        <HrButton size="sm" icon={P.trash} disabled={uploading}
                                  onClick={dropImage}>Remove</HrButton>
                      )}
                    </div>
                  )}
                </div>
              </div>

              {profile.payrollBlockedReason && (
                <Blocked>
                  Payroll will skip {profile.empName}: {profile.payrollBlockedReason}
                </Blocked>
              )}

              <Card title="Personal"
                    right={perm.canEdit && (
                      <HrButton size="sm" icon={P.edit}
                                onClick={() => setEntry({ id: profile.employeeId, profile })}>Edit record</HrButton>
                    )}>
                <Facts>
                  <Fact label="Code" value={profile.empId} />
                  <Fact label="Preferred name" value={profile.preferredName} />
                  <Fact label="Date of birth" value={profile.dateOfBirth} />
                  <Fact label="Gender" value={profile.gender} />
                  <Fact label="Marital status" value={profile.maritalStatus} />
                  <Fact label="Blood group" value={profile.bloodGroup} />
                  <Fact label="National ID" value={profile.nationalId} />
                  <Fact label="Phone" value={profile.phone} />
                  <Fact label="Emergency contact" value={profile.emergencyContact} />
                  <Fact label="Email" value={profile.email} />
                  <Fact label="Father" value={profile.fatherName} />
                  <Fact label="Mother" value={profile.motherName} />
                  <Fact label="Present address" value={profile.presentAddress} />
                  <Fact label="Permanent address" value={profile.address} />
                </Facts>
              </Card>

              <Card title="Employment">
                <Facts>
                  <Fact label="Role" value={profile.empTypeLabel} />
                  <Fact label="Engaged as" value={profile.employmentTypeLabel || "—"} />
                  <Fact label="Department" value={profile.departmentName} />
                  <Fact label="Designation" value={profile.designationName} />
                  <Fact label="Grade" value={profile.grade} />
                  <Fact label="Reports to" value={profile.managerName} />
                  <Fact label="Shift" value={profile.shiftName} />
                  <Fact label="Joined" value={profile.hireDate} />
                  <Fact label="Confirmed" value={profile.confirmationDate} />
                  <Fact label="Probation" value={profile.probationMonths
                    ? `${profile.probationMonths} months` : null} />
                  <Fact label="Notice period" value={profile.noticePeriodDays
                    ? `${profile.noticePeriodDays} days` : null} />
                  <Fact label="Vehicle" value={profile.vehicleNo} />
                </Facts>
              </Card>

              <Card title="Pay"
                    right={perm.canEdit && (
                      <HrButton size="sm" icon={P.edit}
                                onClick={() => startPayBasis(profile)}>Pay basis</HrButton>
                    )}>
                <Facts>
                  <Fact label="Basic salary" value={money(profile.basicSalary)} />
                  <Fact label="Structure" value={profile.salaryStructureName} />
                  <Fact label="Paid by" value={profile.salaryPaymentMode} />
                  <Fact label="Bank" value={profile.bankName} />
                  <Fact label="Bank account" value={profile.bankAccountNo} />
                  <Fact label="Wallet" value={profile.mobileWalletNo} />
                  <Fact label="Provident fund"
                        value={profile.pfApplicable === 1 ? "Applies" : "Does not apply"} />
                  <Fact label="Gratuity"
                        value={profile.gratuityApplicable === 1 ? "Applies" : "Does not apply"} />
                  <Fact label="Tax"
                        value={profile.taxApplicable === 1 ? "Applies" : "Does not apply"} />
                </Facts>
                {(profile.pfApplicable === 1 || profile.gratuityApplicable === 1) && (
                  <PolicyNote>
                    These flags are <strong>recorded only</strong>. Nothing computes a provident
                    fund contribution or a gratuity entitlement, because no rate or formula is
                    configured anywhere in this application — both are policy, and in places
                    statute. A provident fund deduction can meanwhile be modelled as a component
                    on the salary structure.
                  </PolicyNote>
                )}
              </Card>

              <Card title="Leave balances" eyebrow={String(new Date().getFullYear())}>
                {(profile.leaveBalances || []).length === 0 ? (
                  <PolicyNote>
                    No balances have been opened. A balance needs an annual quota on the leave
                    type, and the quota is this pharmacy&rsquo;s policy — the application seeds the
                    kinds of leave but deliberately does not invent how many days each is worth.
                  </PolicyNote>
                ) : (
                  <HrTable
                    rowKey={(r) => r.id}
                    rows={profile.leaveBalances}
                    cols={[
                      { key: "leaveTypeName", label: "Type", lead: true },
                      { key: "entitled", label: "Entitled", num: true, render: (r) => days(r.entitled) },
                      { key: "carriedForward", label: "Carried", num: true, advisory: true,
                        render: (r) => days(r.carriedForward) },
                      { key: "taken", label: "Taken", num: true, render: (r) => days(r.taken) },
                      { key: "pending", label: "Pending", num: true, render: (r) => days(r.pending) },
                      { key: "available", label: "Left", num: true,
                        render: (r) => <strong>{days(r.available)}</strong> },
                    ]}
                  />
                )}
              </Card>

              {(profile.advances || []).length > 0 && (
                <Card title="Advances">
                  <HrTable
                    rowKey={(r) => r.id}
                    rows={profile.advances}
                    cols={[
                      { key: "requestDate", label: "Requested", lead: true },
                      { key: "approvedAmount", label: "Approved", num: true,
                        render: (r) => money(r.approvedAmount ?? r.amount) },
                      { key: "recoveredAmount", label: "Recovered", num: true,
                        render: (r) => money(r.recoveredAmount) },
                      { key: "outstanding", label: "Outstanding", num: true,
                        render: (r) => money(r.outstanding) },
                      { key: "advanceStatus", label: "Status",
                        render: (r) => <Status code={r.advanceStatus} label={r.advanceStatusLabel} /> },
                    ]}
                  />
                </Card>
              )}

              {(profile.loans || []).length > 0 && (
                <Card title="Loans">
                  <HrTable
                    rowKey={(r) => r.id}
                    rows={profile.loans}
                    cols={[
                      { key: "requestDate", label: "Requested", lead: true },
                      { key: "approvedPrincipal", label: "Principal", num: true,
                        render: (r) => money(r.approvedPrincipal ?? r.principal) },
                      { key: "installments", label: "Installments", num: true, advisory: true },
                      { key: "outstanding", label: "Outstanding", num: true,
                        render: (r) => money(r.outstanding) },
                      { key: "loanStatus", label: "Status",
                        render: (r) => <Status code={r.loanStatus} label={r.loanStatusLabel} /> },
                    ]}
                  />
                </Card>
              )}

              {(profile.recentPayslips || []).length > 0 && (
                <Card title="Recent payslips">
                  <HrTable
                    rowKey={(r) => r.id}
                    rows={profile.recentPayslips}
                    cols={[
                      { key: "grossSalary", label: "Gross", lead: true,
                        render: (r) => money(r.grossSalary) },
                      { key: "deductionTotal", label: "Deductions", num: true,
                        render: (r) => money(r.deductionTotal) },
                      { key: "netSalary", label: "Net", num: true,
                        render: (r) => <strong>{money(r.netSalary)}</strong> },
                      { key: "paymentStatus", label: "Paid",
                        render: (r) => <Status code={r.paymentStatus} label={r.paymentStatus} /> },
                    ]}
                  />
                </Card>
              )}
            </div>
          )}
        </Modal>
      )}

      {/* ── The employee record: one form for both halves ── */}
      {entry && (
        <EmployeeEntryForm employeeId={entry.id} profile={entry.profile} canPay={perm.canEdit}
                           addToast={addToast}
                           onClose={() => setEntry(null)}
                           onSaved={(updated, id, partial) => {
                             if (!partial) setEntry(null);
                             if (updated) setProfile(updated);
                             load();
                             if (id && !partial) openProfile(id);
                           }} />
      )}

      {/* ── Pay basis ── */}
      {payForm && (
        <Modal title={`Pay basis — ${payForm.empName}`} width={620}
               onClose={() => setPayForm(null)}
               footer={
                 <>
                   <HrButton onClick={() => setPayForm(null)}>Cancel</HrButton>
                   <HrButton variant="primary" icon={P.check} onClick={savePayBasis}>Save</HrButton>
                 </>
               }>
          <div className="hr-form">
            <Field label="Basic salary">
              <input type="number" min="0" step="0.01" value={payForm.basicSalary}
                     onChange={(e) => setPayForm({ ...payForm, basicSalary: e.target.value })} />
            </Field>
            <Field label="Salary structure">
              <select value={payForm.salaryStructureId}
                      onChange={(e) => setPayForm({ ...payForm, salaryStructureId: e.target.value })}>
                <option value="">— none —</option>
                {structures.map((s2) => (
                  <option key={s2.id} value={s2.id}>{s2.structureName}</option>
                ))}
              </select>
            </Field>
            <Field label="Engaged as">
              <select value={payForm.employmentType}
                      onChange={(e) => setPayForm({ ...payForm, employmentType: e.target.value })}>
                <option value="">— not set —</option>
                {(vocab.employmentType || []).map((o) => (
                  <option key={o.value} value={o.value}>{o.label}</option>
                ))}
              </select>
            </Field>
            <Field label="Confirmed on">
              <input type="date" value={payForm.confirmationDate}
                     onChange={(e) => setPayForm({ ...payForm, confirmationDate: e.target.value })} />
            </Field>
            <Field label="Paid by">
              <select value={payForm.salaryPaymentMode}
                      onChange={(e) => setPayForm({ ...payForm, salaryPaymentMode: e.target.value })}>
                {(vocab.paymentMode || []).map((o) => (
                  <option key={o.value} value={o.value}>{o.label}</option>
                ))}
              </select>
            </Field>
            {payForm.salaryPaymentMode === "MOBILE" && (
              <Field label="Wallet number">
                <input value={payForm.mobileWalletNo}
                       onChange={(e) => setPayForm({ ...payForm, mobileWalletNo: e.target.value })} />
              </Field>
            )}
            {(payForm.salaryPaymentMode === "BANK" || payForm.salaryPaymentMode === "CHEQUE") && (
              <>
                <Field label="Bank">
                  <input value={payForm.bankName}
                         onChange={(e) => setPayForm({ ...payForm, bankName: e.target.value })} />
                </Field>
                <Field label="Branch">
                  <input value={payForm.bankBranch}
                         onChange={(e) => setPayForm({ ...payForm, bankBranch: e.target.value })} />
                </Field>
                <Field label="Account number">
                  <input value={payForm.bankAccountNo}
                         onChange={(e) => setPayForm({ ...payForm, bankAccountNo: e.target.value })} />
                </Field>
              </>
            )}
          </div>
        </Modal>
      )}

      {/* ── File a separation ── */}
      {sepForm && (
        <Modal title="File a separation" width={560} onClose={() => setSepForm(null)}
               footer={
                 <>
                   <HrButton variant="ghost" onClick={() => setSepForm(null)}>Cancel</HrButton>
                   <HrButton variant="primary" onClick={saveSeparation}
                    disabled={!sepForm.employeeId || !sepForm.lastWorkingDate}>
                     File
                   </HrButton>
                 </>
               }>
          <div className="hr-form">
            <div className="hr-form-full">
              <Field label="Employee">
                <HrEmployeePicker value={sepForm.employeeId} search={searchPeople}
                                  onChange={(id) => setSepForm({ ...sepForm, employeeId: id })} />
              </Field>
            </div>
            <Field label="Reason">
              <select value={sepForm.separationType}
                      onChange={(e) => setSepForm({ ...sepForm, separationType: e.target.value })}>
                {(vocab.separationType || []).map((o) => (
                  <option key={o.value} value={o.value}>{o.label}</option>
                ))}
              </select>
            </Field>
            <Field label="Notice given">
              <input type="date" value={sepForm.noticeDate}
                     onChange={(e) => setSepForm({ ...sepForm, noticeDate: e.target.value })} />
            </Field>
            <Field label="Last working day">
              <input type="date" value={sepForm.lastWorkingDate}
                     onChange={(e) => setSepForm({ ...sepForm, lastWorkingDate: e.target.value })} />
            </Field>
            <div className="hr-form-full">
              <Field label="Notes">
                <textarea value={sepForm.reason}
                          onChange={(e) => setSepForm({ ...sepForm, reason: e.target.value })} />
              </Field>
            </div>
            <div className="hr-form-full">
              <PolicyNote>
                Approving this deactivates the employee; the record itself is kept, because
                invoices and delivery history point at it. Approval is refused while an advance or
                loan is still outstanding — a deactivated employee is skipped by every payroll run,
                so the money could never be recovered from a payslip afterwards.
              </PolicyNote>
            </div>
          </div>
        </Modal>
      )}
    </HrPage>
  );
}
