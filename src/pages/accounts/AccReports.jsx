/**
 * AccReports — every acc- report, behind the Accounts Reports right (MOD51).
 *
 * The general Reports screen with the accounting registry and heading: the
 * same index, viewer, filter bar and CSV export. Every figure is read from
 * the posted ledger — the books the journals and daily cash sheet show.
 */
import React, { useState } from "react";
import Reports from "../Reports.jsx";
import { ACC_REPORT_GROUPS } from "../../services/reportService.js";
import { takeIntent } from "./AccKit.jsx";

export default function AccReports() {
  // The dashboard can open a report with its own range already applied.
  const [intent] = useState(() => takeIntent("accreports"));
  const filters = intent ? { from: intent.from || "", to: intent.to || "", branchId: intent.branchId ? String(intent.branchId) : "" } : null;
  return (
    <Reports groups={ACC_REPORT_GROUPS} permission="accreports" title="Accounts Reports" initialReportId={intent?.report || null} initialFilters={filters}
             subtitle="Statements, ledgers, ageing, cash books, investor and payroll figures — every number read from the posted ledger, never estimated." />
  );
}
