/**
 * HrReports.jsx — every HR report, behind the HR Reports right (MOD39).
 *
 * The general Reports screen with a different registry and heading: the same
 * index, viewer, filter bar and CSV export. Nothing is duplicated — the grid
 * is the grid — only the door is HR's own, so a role that sees sales reports
 * does not see payroll figures with them.
 */
import React from "react";
import Reports from "./Reports.jsx";
import { HR_REPORT_GROUPS } from "../services/reportService.js";

export default function HrReports() {
  return (
    <Reports groups={HR_REPORT_GROUPS} permission="hrreports" title="HR Reports"
             subtitle="People, attendance and leave, pay, money owed — read from the live tables, never estimated." />
  );
}
