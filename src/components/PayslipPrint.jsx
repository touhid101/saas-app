/**
 * The printed payslip.
 *
 * The same shape as the A4 sales invoice in PrintDocument.jsx — the branch's
 * own letterhead, the document title, the parties, the lines, the totals block,
 * the amount in words, the signature slots, the footer — because a payslip and
 * an invoice leave the same pharmacy and should read as though they did.
 *
 * Every figure here, down to the words, arrives computed from the server
 * (`GET /payroll/{id}/lines/{lineId}/document`). Nothing is added up on the
 * screen, and nothing is re-read from today's salary: the sheet says what the
 * run said the month it was paid.
 *
 * Black on white, no theme variables, no colour saying anything a rule
 * already says. The hierarchy is net, then lines, then everything else.
 */
import React, { useEffect, useState } from "react";
import { createPortal } from "react-dom";
import uploadUrl from "../lib/uploadUrl.js";
import { getPayslipDocument } from "../services/hrService.js";

/* ── formatting — identical to PrintDocument's ───────────────────────────── */

const n2 = (v) =>
  Number(v ?? 0).toLocaleString("en-BD", { minimumFractionDigits: 2, maximumFractionDigits: 2 });

const dayz = (v) => {
  const x = Number(v ?? 0);
  return Number.isInteger(x) ? String(x) : String(Number(x.toFixed(2)));
};

const MONTHS = ["JAN","FEB","MAR","APR","MAY","JUN","JUL","AUG","SEP","OCT","NOV","DEC"];

function fmtDate(iso) {
  if (!iso) return "";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return String(iso);
  return `${String(d.getDate()).padStart(2, "0")}-${MONTHS[d.getMonth()]}-${d.getFullYear()}`;
}

function fmtStamp(iso) {
  if (!iso) return "";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return String(iso);
  let h = d.getHours();
  const ampm = h >= 12 ? "PM" : "AM";
  h = h % 12 || 12;
  return `${String(d.getDate()).padStart(2, "0")}-${MONTHS[d.getMonth()]}-${String(d.getFullYear()).slice(2)}`
       + ` ${String(h).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")} ${ampm}`;
}

/**
 * Paid, part paid or unpaid — read from the figures, not the stored status,
 * so the stamp and the totals block can never contradict each other.
 */
function settlement(totals) {
  const due = Number(totals?.balanceDue ?? 0);
  const paid = Number(totals?.paid ?? 0);
  if (due <= 0 && Number(totals?.netSalary ?? 0) > 0) return { label: "Paid", due: false };
  if (paid > 0) return { label: "Part Paid", due: true };
  return { label: "Unpaid", due: true };
}

/* ── letterhead ──────────────────────────────────────────────────────────── */

function Brand({ branch }) {
  const logo = uploadUrl(branch?.logoUrl);
  return (
    <div className="pd-brand">
      {logo && <img className="pd-logo" src={logo} alt="" />}
      <div className="pd-brand-text">
        <div className="pd-org">{branch?.tradingName || branch?.name || ""}</div>
        {branch?.address && <div className="pd-org-line">{branch.address}</div>}
        {branch?.phone && <div className="pd-org-line">Phone: {branch.phone}</div>}
        {branch?.email && <div className="pd-org-line">{branch.email}</div>}
      </div>
    </div>
  );
}

/* ═══════════════════════════════════════════════════════════════════════════
   A4 PAYSLIP
   ═══════════════════════════════════════════════════════════════════════════ */

function A4({ doc }) {
  const { branch, slip, employee, earnings, deductions, attendance, totals, payments, amountInWords } = doc;
  const state = settlement(totals);
  const rows = Math.max(earnings?.length || 0, deductions?.length || 0);
  const period = slip?.periodFrom && slip?.periodTo
    ? `${fmtDate(slip.periodFrom)} to ${fmtDate(slip.periodTo)}` : "";

  return (
    <div className="pd-a4 pd-slip">
      <header className="pd-mast">
        <Brand branch={branch} />
        <div>
          <div className="pd-doc-title">Payslip</div>
          <div className="pd-doc-sub pd-num">{slip?.payslipNo}</div>
          <div style={{ textAlign: "right" }}>
            <span className={state.due ? "pd-stamp pd-stamp-due" : "pd-stamp"}>{state.label}</span>
          </div>
        </div>
      </header>

      <section className="pd-parties">
        <div>
          <div className="pd-panel-label">Paid to</div>
          <div className="pd-party-name">{employee?.name}</div>
          <div className="pd-party-line pd-num">
            {employee?.code}
            {employee?.designation ? ` · ${employee.designation}` : ""}
          </div>
          {employee?.department && <div className="pd-party-line">{employee.department}</div>}
          {employee?.hireDate && <div className="pd-party-line pd-num">Joined {fmtDate(employee.hireDate)}</div>}
        </div>

        <dl className="pd-facts">
          <dt>Pay period</dt>
          <dd>{slip?.periodLabel || "—"}</dd>

          {period && (<><dt>Covering</dt><dd className="pd-num">{period}</dd></>)}

          <dt>Payroll run</dt>
          <dd className="pd-num">{slip?.payrollNo}</dd>

          <dt>Run status</dt>
          <dd>{slip?.payrollStatusLabel || slip?.payrollStatus || "—"}</dd>

          {slip?.approvedBy && (<><dt>Approved by</dt><dd>{slip.approvedBy}</dd></>)}

          {employee?.paymentModeLabel && (
            <>
              <dt>Paid by</dt>
              <dd>
                {employee.paymentModeLabel}
                {employee.bankName ? ` · ${employee.bankName}` : ""}
                {employee.bankAccountMasked ? ` ${employee.bankAccountMasked}` : ""}
                {!employee.bankAccountMasked && employee.mobileWalletMasked ? ` ${employee.mobileWalletMasked}` : ""}
              </dd>
            </>
          )}
        </dl>
      </section>

      {/* Earnings and deductions side by side, the way a payslip is read:
          what came in on the left, what went out on the right, the two
          subtotals on one line, and the net under both. */}
      <table className="pd-table pd-slip-table">
        <thead>
          <tr>
            <th className="pd-l" style={{ width: "6mm" }}>#</th>
            <th className="pd-l">Earnings</th>
            <th>Amount</th>
            <th className="pd-slip-gap" />
            <th className="pd-l" style={{ width: "6mm" }}>#</th>
            <th className="pd-l">Deductions</th>
            <th>Amount</th>
          </tr>
        </thead>
        <tbody>
          {Array.from({ length: rows }, (_, i) => {
            const e = earnings?.[i];
            const d = deductions?.[i];
            return (
              <tr key={i}>
                <td className="pd-l pd-num pd-muted">{e ? e.sl : ""}</td>
                <td className="pd-l">
                  {e && <div className="pd-item">{e.name}</div>}
                  {e?.basis && <div className="pd-item-sub pd-num">{e.basis}</div>}
                </td>
                <td className="pd-num">{e ? n2(e.amount) : ""}</td>
                <td className="pd-slip-gap" />
                <td className="pd-l pd-num pd-muted">{d ? d.sl : ""}</td>
                <td className="pd-l">
                  {d && <div className="pd-item">{d.name}</div>}
                  {d?.basis && <div className="pd-item-sub pd-num">{d.basis}</div>}
                </td>
                <td className="pd-num">{d ? n2(d.amount) : ""}</td>
              </tr>
            );
          })}
          {rows === 0 && (
            <tr><td className="pd-l pd-muted" colSpan={7}>Nothing was computed for this payslip.</td></tr>
          )}
        </tbody>
        <tfoot>
          <tr>
            <td className="pd-l" colSpan={2}>Gross earnings</td>
            <td className="pd-num pd-strong">{n2(totals?.grossSalary)}</td>
            <td className="pd-slip-gap" />
            <td className="pd-l" colSpan={2}>Total deductions</td>
            <td className="pd-num pd-strong">{n2(totals?.totalDeductions)}</td>
          </tr>
        </tfoot>
      </table>

      <div className="pd-foot pd-slip-foot">
        <div className="pd-slip-att">
          <div className="pd-panel-label">Attendance this period</div>
          <dl className="pd-facts">
            <dt>Working days</dt><dd className="pd-num">{dayz(attendance?.workingDays)}</dd>
            <dt>Present</dt><dd className="pd-num">{dayz(attendance?.presentDays)}</dd>
            <dt>Absent</dt><dd className="pd-num">{dayz(attendance?.absentDays)}</dd>
            <dt>Leave</dt>
            <dd className="pd-num">
              {dayz(attendance?.leaveDays)}
              {Number(attendance?.unpaidLeaveDays || 0) > 0 && ` (${dayz(attendance.unpaidLeaveDays)} unpaid)`}
            </dd>
            <dt>Overtime</dt><dd className="pd-num">{dayz(attendance?.overtimeHours)} h</dd>
          </dl>
          {Number(totals?.pfEmployer || 0) > 0 && (
            <div className="pd-slip-note">
              Employer's provident fund contribution of {n2(totals.pfEmployer)} is paid into the
              fund and is not part of this salary.
            </div>
          )}
        </div>

        <div className="pd-totals">
          <Total label="Basic salary" value={n2(totals?.basicSalary)} />
          <Total label="Gross earnings" value={n2(totals?.grossSalary)} />
          <Total label="Less deductions" value={n2(totals?.totalDeductions)} sep />
          {Number(totals?.taxAmount) > 0 && <Total label="of which tax" value={n2(totals.taxAmount)} />}

          <div className="pd-total-row pd-total-grand">
            <span>Net salary</span><span className="pd-num">{n2(totals?.netSalary)}</span>
          </div>

          <Total label="Paid" value={n2(totals?.paid)} />
          <div className="pd-total-row pd-total-due">
            <span>Balance Due</span><span className="pd-num">{n2(totals?.balanceDue)}</span>
          </div>
        </div>
      </div>

      <div className="pd-words">
        <span className="pd-words-label">Net salary in words</span>
        {amountInWords}
      </div>

      {payments?.length > 0 && (
        <div className="pd-account">
          <div className="pd-account-head">
            <span className="pd-account-title">Payments against this payslip</span>
            <span className="pd-account-sub">{payments.length} payment{payments.length === 1 ? "" : "s"}</span>
          </div>
          <table className="pd-account-table">
            <thead>
              <tr>
                <th className="pd-l">Date</th>
                <th className="pd-l">Voucher</th>
                <th className="pd-l">Method</th>
                <th className="pd-l">Reference</th>
                <th>Amount</th>
              </tr>
            </thead>
            <tbody>
              {payments.map((p) => (
                <tr key={p.voucherNo || p.date + p.amount}>
                  <td className="pd-l pd-num">{fmtDate(p.date)}</td>
                  <td className="pd-l pd-num">{p.voucherNo || "—"}</td>
                  <td className="pd-l">{p.modeLabel || p.mode}</td>
                  <td className="pd-l pd-num">{p.reference || "—"}</td>
                  <td className="pd-num pd-strong">{n2(p.amount)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {slip?.remarks && <div className="pd-note"><b>Note:</b> {slip.remarks}</div>}

      <div className="pd-sign">
        <div className="pd-sign-slot">Prepared by</div>
        <div className="pd-sign-slot">Received by</div>
        <div className="pd-sign-slot">Authorised signature</div>
      </div>

      <footer className="pd-a4-foot">
        <span>{branch?.tradingName || branch?.name}</span>
        <span className="pd-num">
          {slip?.payslipNo} · Printed {fmtStamp(slip?.printedAt)}
        </span>
      </footer>
    </div>
  );
}

const Total = ({ label, value, sep }) => (
  <div className={sep ? "pd-total-row pd-sep" : "pd-total-row"}>
    <span>{label}</span><span className="pd-num">{value}</span>
  </div>
);

/* ═══════════════════════════════════════════════════════════════════════════
   THE OVERLAY — same chrome as the invoice's
   ═══════════════════════════════════════════════════════════════════════════ */

/**
 * Loads one payslip and shows it on A4, ready to print.
 *
 * @param payrollId the run
 * @param lineId    the payslip within it
 */
export default function PayslipPrint({ payrollId, lineId, onClose }) {
  const [doc, setDoc] = useState(null);
  const [error, setError] = useState(null);

  useEffect(() => {
    let live = true;
    setDoc(null); setError(null);
    getPayslipDocument(payrollId, lineId)
      .then((d) => { if (live) setDoc(d); })
      .catch((e) => { if (live) setError(e?.message || "Could not load this payslip."); });
    return () => { live = false; };
  }, [payrollId, lineId]);

  /* @page cannot be scoped to a component, so the paper size is injected while
     this overlay is mounted and removed with it — as the invoice does. */
  useEffect(() => {
    const el = document.createElement("style");
    el.setAttribute("data-print-page", "payslip");
    el.textContent = "@page { size: A4 portrait; margin: 10mm; }";
    document.head.appendChild(el);
    return () => el.remove();
  }, []);

  useEffect(() => {
    const onKey = (e) => { if (e.key === "Escape") onClose?.(); };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  /* Rendered on <body>, outside the app shell, so the print stylesheet can
     hide the shell — sidebar, topbar and the page behind the dialog — and
     leave only the sheet. */
  return createPortal(
    <div className="pd-backdrop pd-portal" onMouseDown={(e) => { if (e.target === e.currentTarget) onClose?.(); }}>
      <div className="pd-shell" role="dialog" aria-label="Print preview">
        <div className="pd-toolbar no-print">
          <span className="pd-toolbar-title">
            Payslip
            <span className="pd-toolbar-meta">
              A4 portrait
              {doc?.employee?.name ? ` · ${doc.employee.name}` : ""}
              {doc?.slip?.periodLabel ? ` · ${doc.slip.periodLabel}` : ""}
            </span>
          </span>
          <div className="pd-toolbar-actions">
            <button className="btn btn-ghost btn-sm" onClick={onClose}>Close</button>
            <button className="btn btn-primary btn-sm" disabled={!doc} onClick={() => window.print()}>Print</button>
          </div>
        </div>

        <div className="pd-paper-wrap">
          {error ? (
            <div className="empty-state" role="alert">{error}</div>
          ) : !doc ? (
            <div className="empty-state">Preparing…</div>
          ) : (
            <A4 doc={doc} />
          )}
        </div>
      </div>
    </div>,
    document.body,
  );
}
