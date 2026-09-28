/**
 * RBAC — Role Based Access Control
 * Connected to backend API:
 * - Permission Matrix: toggle-all per row (module) and per column (permission type)
 * - Role management with color picker
 * - Module/Menu creation with 30+ icon options and live preview
 * - User management with role assignment
 */
import React, { useState, useMemo, useEffect, useRef } from "react";
import { Card, Pill, Field, Modal } from "../components/UI.jsx";
import { useApp } from "../lib/AppContext.jsx";
import { usePermission } from "../lib/usePermission.js";
import * as rbacService from "../services/rbacService.js";
import { getEmployees } from "../services/masterDataService.js";
import { ICON_PATHS } from "../components/Sidebar.jsx";
import { useConfirm } from "../lib/ConfirmProvider.jsx";
import { viewRulePatch } from "../lib/permissionRules.js";
import ModuleRoles from "./ModuleRoles.jsx";

/** Avatar URLs stored as relative paths (e.g. /uploads/avatars/user_7_xxx.jpg)
 *  must be served from the backend (port 8080), not the Vite dev server (port 5173).
 *  This helper converts any relative URL to a full absolute backend URL. */
const API_BASE = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080';
function resolveAvatarUrl(url) {
  if (!url) return null;
  if (url.startsWith('http://') || url.startsWith('https://')) return url; // already absolute
  if (url.startsWith('/')) return `${API_BASE}${url}`; // relative path → prepend backend origin
  return url;
}

/* ── Icon helper ── */
const Ic = ({ d, size = 15, color }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none"
    stroke={color || "currentColor"} strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round">
    {Array.isArray(d) ? d.map((p, i) => <path key={i} d={p} />) : <path d={d} />}
  </svg>
);

const PERM_KEYS = ["canView", "canCreate", "canEdit", "canDelete", "canApprove", "canExport"];

const PERM_META = {
  canView:     { label: "View",     color: "#2E6A62", icon: "M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6z" },
  canCreate:   { label: "Create",   color: "#4C7A5E", icon: "M12 5v14M5 12h14" },
  canEdit:     { label: "Edit",     color: "#B8752B", icon: "M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7 M18.5 2.5a2.1 2.1 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z" },
  canDelete:   { label: "Delete",   color: "#A8452E", icon: "M3 6h18 M8 6V4a2 2 0 0 1 2-2h4 M19 6l-1 14H6L5 6" },
  canApprove:  { label: "Approve",  color: "#2E7D8A", icon: "M20 6 9 17l-5-5" },
  canExport:   { label: "Export",   color: "#5A9B3C", icon: "M12 3v13 M6 11l6 6 6-6 M4 20h16" },
};

// Default role colors for UI
const DEFAULT_ROLE_COLORS = [
  "#6B5EA8", "#2E6A62", "#B8752B", "#4C7A5E",
  "#A8452E", "#2E7D8A", "#5A9B3C", "#9FB0A5", "#D4925A"
];

/* ── 30+ icon options for module creation ── */
const AVAILABLE_ICONS = [
  { key: "dashboard",       paths: ["M3 3h7v9H3z","M14 3h7v5h-7z","M14 12h7v9h-7z","M3 16h7v5H3z"] },
  { key: "master",          paths: ["M12 3 2 8l10 5 10-5-10-5Z","M2 13l10 5 10-5","M2 18l10 5 10-5"] },
  { key: "purchase",        paths: ["M6 2h12v20l-2.5-1.5L13 22l-2.5-1.5L8 22l-2-1.5V2z","M9 7h6M9 11h6M9 15h4"] },
  { key: "sales",           paths: ["M6 2h12v20l-2.5-1.5L13 22l-2.5-1.5L8 22l-2-1.5V2z","M9 7h6M9 11h6"] },
  { key: "quicksales",      paths: ["M4 2h16a2 2 0 0 1 2 2v16a2 2 0 0 1-2 2H4","M11 18h2"] },
  { key: "activity",        paths: ["M3 8h18M3 12h18M3 16h18","M3 4h18"] },
  { key: "delivery",        paths: ["M1 6h14v11H1z","M15 9h4l3 3v5h-7z","M5 20a2 2 0 1 0 0-4 2 2 0 0 0 0 4z"] },
  { key: "collection",      paths: ["M19 5H5a2 2 0 0 0-2 2v10a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V7a2 2 0 0 0-2-2z","M1 10h22"] },
  { key: "requisition",     paths: ["M9 2H5a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V9z","M13 2v7h7"] },
  { key: "inventory",       paths: ["M21 8 12 3 3 8l9 5 9-5z","M3 8v9l9 5 9-5V8","M12 13v9"] },
  { key: "reports",         paths: ["M4 20V10M12 20V4M20 20v-7","M2 20h20"] },
  { key: "ai",              paths: ["M12 2a6 6 0 0 1 6 6v2a6 6 0 0 1-6 6 6 6 0 0 1-6-6V8a6 6 0 0 1 6-6z","M8 14s1.5 2 4 2 4-2 4-2"] },
  { key: "rbac",            paths: ["M12 2 4 5v6c0 5 3.4 8.4 8 11 4.6-2.6 8-6 8-11V5l-8-3z","M9 12l2 2 4-4"] },
  { key: "users",           paths: ["M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2","M12 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8z"] },
  { key: "settings",        paths: ["M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6z","M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06A1.65 1.65 0 0 0 4.68 15a1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06A1.65 1.65 0 0 0 9 4.68a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06A1.65 1.65 0 0 0 19.4 9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z"] },
  { key: "chart",           paths: ["M18 20V10M12 20V4M6 20v-6"] },
  { key: "wallet",          paths: ["M19 5H5a2 2 0 0 0-2 2v10a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V7a2 2 0 0 0-2-2z","M1 10h22"] },
  { key: "tag",             paths: ["M20.59 13.41l-7.17 7.17a2 2 0 0 1-2.83 0L2 12V2h10l8.59 8.59a2 2 0 0 1 0 2.82z","M7 7h.01"] },
  { key: "box",             paths: ["M21 8 12 3 3 8l9 5 9-5z","M3 8v9l9 5 9-5V8","M12 13v9"] },
  { key: "truck",           paths: ["M1 6h14v11H1z","M15 9h4l3 3v5h-7z","M5 20a2 2 0 1 0 0-4 2 2 0 0 0 0 4z","M18 20a2 2 0 1 0 0-4 2 2 0 0 0 0 4z"] },
  { key: "calendar",        paths: ["M19 3H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V5a2 2 0 0 0-2-2z","M16 1v4M8 1v4M1 9h22"] },
  { key: "bell",            paths: ["M6 8a6 6 0 0 1 12 0c0 5 2 6 2 6H4s2-1 2-6z","M10 21a2 2 0 0 0 4 0"] },
  { key: "star",            paths: ["M12 2l3.09 6.26L22 9.27l-5 4.87 1.18 6.88L12 17.77l-6.18 3.25L7 14.14 2 9.27l6.91-1.01L12 2z"] },
  { key: "flask",           paths: ["M9 3h6M10 9l-4 12h12L14 9","M10 3v6","M14 3v6"] },
  { key: "pill",            paths: ["M10.5 20.5 3.5 13.5a5 5 0 0 1 7.07-7.07l7 7a5 5 0 0 1-7.07 7.07z","M8.5 8.5l7 7"] },
  { key: "clipboard",       paths: ["M16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2","M8 2h8a2 2 0 0 1 0 4H8a2 2 0 0 1 0-4z"] },
  { key: "database",        paths: ["M12 2C6.48 2 2 4.69 2 8v8c0 3.31 4.48 6 10 6s10-2.69 10-6V8c0-3.31-4.48-6-10-6z","M2 8c0 3.31 4.48 6 10 6s10-2.69 10-6","M2 12c0 3.31 4.48 6 10 6s10-2.69 10-6"] },
  { key: "layers",          paths: ["M12 3 2 8l10 5 10-5-10-5z","M2 13l10 5 10-5","M2 18l10 5 10-5"] },
  { key: "merge",           paths: ["M6 3v6a4 4 0 0 0 4 4h4","M18 3v6a4 4 0 0 1-4 4","M12 13v8","M9 18l3 3 3-3"] },
  { key: "percent",         paths: ["M19 5 5 19","M6.5 6.5a1 1 0 1 0 2 0 1 1 0 0 0-2 0z","M15.5 15.5a1 1 0 1 0 2 0 1 1 0 0 0-2 0z"] },
  { key: "trending",        paths: ["M22 7 13.5 15.5l-5-5L2 17","M16 7h6v6"] },
  { key: "map",             paths: ["M3 6l6-3 6 3 6-3v15l-6 3-6-3-6 3V6z","M9 3v15M15 6v15"] },

  /* ── the ones the sidebar already draws but the picker never offered ──
     Seven live modules were saved with these keys, so their cards rendered an
     empty SVG and they could not be re-picked after an edit. Same paths the
     sidebar uses, so a module looks the same in both places. */
  { key: "orgstructure",    paths: ["M3 21h18","M5 21V7l7-4 7 4v14","M9 21v-6h6v6","M9 11h.01M15 11h.01"] },
  { key: "productmaster",   paths: ["M10.5 20.5a7.78 7.78 0 0 1-11-11l9-9a7.78 7.78 0 0 1 11 11z","M8.5 8.5l7 7"] },
  { key: "salesperformance",paths: ["M3 3v18h18","M7 15l4-4 3 3 5-6"] },
  { key: "salesdiscount",   paths: ["M19 5 5 19","M6.5 6.5a1 1 0 1 0 2 0 1 1 0 0 0-2 0z","M15.5 15.5a1 1 0 1 0 2 0 1 1 0 0 0-2 0z"] },
  { key: "salesreturn",     paths: ["M9 14 4 9l5-5","M4 9h11a5 5 0 0 1 0 10H9"] },
  { key: "purchasereturn",  paths: ["M15 14l5-5-5-5","M20 9H9a5 5 0 0 0 0 10h6"] },
  { key: "supplierpayment", paths: ["M2 7h20v12H2z","M2 11h20","M6 15h4"] },

  /* ── and more to choose from ── */
  { key: "undo",            paths: ["M3 7v6h6","M3.5 13a9 9 0 1 0 2.6-6.4L3 9"] },
  { key: "search",          paths: ["M11 19a8 8 0 1 0 0-16 8 8 0 0 0 0 16z","M21 21l-4.3-4.3"] },
  { key: "catalogue",       paths: ["M4 4h16v16H4z","M4 9h16","M9 9v11"] },
  { key: "shield",          paths: ["M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"] },
  { key: "key",             paths: ["M21 2l-2 2","M15.5 8.5a5.5 5.5 0 1 1-7.8 7.8 5.5 5.5 0 0 1 7.8-7.8z","M16 7l4-4","M18 9l2-2"] },
  { key: "lock",            paths: ["M5 11h14v10H5z","M8 11V7a4 4 0 0 1 8 0v4"] },
  { key: "building",        paths: ["M3 21h18","M5 21V3h14v18","M9 7h2M13 7h2M9 11h2M13 11h2M9 15h2M13 15h2"] },
  { key: "branch",          paths: ["M6 3v12","M18 9a3 3 0 1 0 0-6 3 3 0 0 0 0 6z","M6 21a3 3 0 1 0 0-6 3 3 0 0 0 0 6z","M18 9a9 9 0 0 1-9 9"] },
  { key: "invoice",         paths: ["M6 2h12v20l-3-2-3 2-3-2-3 2V2z","M9 7h6M9 11h6M9 15h3"] },
  { key: "cart",            paths: ["M1 1h4l2.7 13.4a2 2 0 0 0 2 1.6h9.7a2 2 0 0 0 2-1.6L23 6H6","M10 21a1 1 0 1 0 0-2 1 1 0 0 0 0 2z","M19 21a1 1 0 1 0 0-2 1 1 0 0 0 0 2z"] },
  { key: "heart",           paths: ["M20.8 4.6a5.5 5.5 0 0 0-7.8 0L12 5.7l-1-1.1a5.5 5.5 0 0 0-7.8 7.8l8.8 8.8 8.8-8.8a5.5 5.5 0 0 0 0-7.8z"] },
  { key: "stethoscope",     paths: ["M6 3v6a4 4 0 0 0 8 0V3","M6 3h2M12 3h2","M10 13v3a5 5 0 0 0 10 0v-2","M20 10a2 2 0 1 0 0 4 2 2 0 0 0 0-4z"] },
  { key: "syringe",         paths: ["M18 2l4 4","M17 7l-11 11-4 1 1-4L14 4z","M12 6l6 6","M9 9l3 3"] },
  { key: "thermometer",     paths: ["M14 14.8V4a2 2 0 1 0-4 0v10.8a4 4 0 1 0 4 0z"] },
  { key: "clock",           paths: ["M12 22a10 10 0 1 0 0-20 10 10 0 0 0 0 20z","M12 6v6l4 2"] },
  { key: "mail",            paths: ["M2 5h20v14H2z","M2 5l10 7 10-7"] },
  { key: "phone",           paths: ["M22 16.9v3a2 2 0 0 1-2.2 2 19.8 19.8 0 0 1-8.6-3.1 19.5 19.5 0 0 1-6-6A19.8 19.8 0 0 1 2 4.2 2 2 0 0 1 4 2h3a2 2 0 0 1 2 1.7c.1 1 .4 1.9.7 2.8a2 2 0 0 1-.5 2.1L8.1 9.9a16 16 0 0 0 6 6l1.3-1.1a2 2 0 0 1 2.1-.5c.9.3 1.8.6 2.8.7a2 2 0 0 1 1.7 2z"] },
  { key: "printer",         paths: ["M6 9V2h12v7","M6 18H4a2 2 0 0 1-2-2v-5a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v5a2 2 0 0 1-2 2h-2","M6 14h12v8H6z"] },
  { key: "download",        paths: ["M12 3v13","M6 11l6 6 6-6","M4 21h16"] },
  { key: "upload",          paths: ["M12 21V8","M6 12l6-6 6 6","M4 3h16"] },
  { key: "filter",          paths: ["M3 4h18l-7 8v6l-4 2v-8L3 4z"] },
  { key: "grid",            paths: ["M3 3h7v7H3z","M14 3h7v7h-7z","M14 14h7v7h-7z","M3 14h7v7H3z"] },
  { key: "list",            paths: ["M8 6h13M8 12h13M8 18h13","M3 6h.01M3 12h.01M3 18h.01"] },
  { key: "book",            paths: ["M4 19.5A2.5 2.5 0 0 1 6.5 17H20","M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z"] },
  { key: "flag",            paths: ["M4 15s1-1 4-1 5 2 8 2 4-1 4-1V3s-1 1-4 1-5-2-8-2-4 1-4 1z","M4 22v-7"] },
  { key: "gift",           paths: ["M20 12v10H4V12","M2 7h20v5H2z","M12 22V7","M12 7H7.5a2.5 2.5 0 1 1 0-5C11 2 12 7 12 7z","M12 7h4.5a2.5 2.5 0 1 0 0-5C13 2 12 7 12 7z"] },

  /* ── pharmacy and clinical ── */
  { key: "capsule",        paths: ["M10.5 20.5a7.07 7.07 0 0 1-10-10l6-6a7.07 7.07 0 0 1 10 10z","M8.5 8.5l7 7"] },
  { key: "firstaid",       paths: ["M3 6h18v14H3z","M12 10v6","M9 13h6"] },
  { key: "bandage",        paths: ["M2 12a5 5 0 0 1 5-5h10a5 5 0 0 1 0 10H7a5 5 0 0 1-5-5z","M9 9l6 6","M15 9l-6 6"] },
  { key: "microscope",     paths: ["M6 18h12","M9 18a5 5 0 1 1 8-4","M12 3l3 3-4 4-3-3z","M10 14l2 2"] },
  { key: "dna",            paths: ["M4 2c0 6 16 6 16 12","M20 2c0 6-16 6-16 12","M4 22c0-4 16-4 16-8","M7 5h10M7 19h10"] },
  { key: "drop",           paths: ["M12 2s6 7 6 11a6 6 0 0 1-12 0c0-4 6-11 6-11z"] },
  { key: "shieldcheck",    paths: ["M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z","M9 12l2 2 4-4"] },
  { key: "hospital",       paths: ["M3 21h18","M6 21V8h12v13","M12 11v6","M9 14h6","M9 8V4h6v4"] },

  /* ── money and trade ── */
  { key: "coins",          paths: ["M9 14a6 3 0 1 0 0-6 6 3 0 0 0 0 6z","M3 8v8c0 1.7 2.7 3 6 3s6-1.3 6-3V8","M15 6.5c2.5.4 6 1.5 6 3.5v6c0 1.7-2.7 3-6 3"] },
  { key: "receipt",        paths: ["M5 2h14v20l-2.3-1.6L14.4 22 12 20.4 9.6 22l-2.3-1.6L5 22z","M9 8h6M9 12h6M9 16h3"] },
  { key: "bank",           paths: ["M3 10h18","M5 10v9M9 10v9M15 10v9M19 10v9","M2 21h20","M12 2l10 6H2z"] },
  { key: "creditcard",     paths: ["M2 5h20v14H2z","M2 10h20","M6 15h4"] },
  { key: "refund",         paths: ["M3 8v6h6","M3.5 14a9 9 0 1 0 2.1-7.2L3 9","M12 8v5l3 2"] },
  { key: "balance",        paths: ["M12 3v18","M4 7h16","M4 7l-3 6h6z","M20 7l-3 6h6z","M8 21h8"] },

  /* ── stock and movement ── */
  { key: "warehouse",      paths: ["M2 21V9l10-6 10 6v12","M6 21v-7h12v7","M9 14v7M15 14v7"] },
  { key: "package",        paths: ["M21 8l-9-5-9 5v8l9 5 9-5z","M3 8l9 5 9-5","M12 13v9","M7.5 5.5l9 5"] },
  { key: "forklift",       paths: ["M2 17h10","M4 17V7h5l3 4v6","M14 5h2v9h5","M5 20a2 2 0 1 0 0-4 2 2 0 0 0 0 4z","M11 20a2 2 0 1 0 0-4 2 2 0 0 0 0 4z"] },
  { key: "scan",           paths: ["M3 7V5a2 2 0 0 1 2-2h2","M17 3h2a2 2 0 0 1 2 2v2","M21 17v2a2 2 0 0 1-2 2h-2","M7 21H5a2 2 0 0 1-2-2v-2","M3 12h18"] },
  { key: "barcode",        paths: ["M3 5v14M6 5v14M9 5v10M12 5v14M15 5v10M18 5v14M21 5v14"] },
  { key: "swap",           paths: ["M7 4L3 8l4 4","M3 8h14a4 4 0 0 1 0 8h-1","M17 20l4-4-4-4"] },

  /* ── people and org ── */
  { key: "user",           paths: ["M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2","M12 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8z"] },
  { key: "usercheck",      paths: ["M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2","M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8z","M16 11l2 2 4-4"] },
  { key: "hierarchy",      paths: ["M9 2h6v4H9z","M2 18h6v4H2z","M16 18h6v4h-6z","M12 6v6","M5 18v-3h14v3"] },
  { key: "handshake",      paths: ["M11 17l-2 2-4-4 5-5 3 2 3-2 5 5-4 4-2-2","M9 14l3 3"] },
  { key: "id",             paths: ["M2 5h20v14H2z","M9 12a2 2 0 1 0 0-4 2 2 0 0 0 0 4z","M6 16c0-1.7 1.3-3 3-3s3 1.3 3 3","M15 9h4M15 13h4"] },

  /* ── time, alerts and status ── */
  { key: "alert",          paths: ["M12 3l10 17H2z","M12 9v5","M12 17h.01"] },
  { key: "info",           paths: ["M12 22a10 10 0 1 0 0-20 10 10 0 0 0 0 20z","M12 11v5","M12 7h.01"] },
  { key: "check",          paths: ["M20 6L9 17l-5-5"] },
  { key: "close",          paths: ["M18 6L6 18","M6 6l12 12"] },
  { key: "refresh",        paths: ["M21 3v6h-6","M3 21v-6h6","M3.5 9a9 9 0 0 1 14.9-3.4L21 9","M20.5 15a9 9 0 0 1-14.9 3.4L3 15"] },
  { key: "history",        paths: ["M3 3v6h6","M3.5 13a9 9 0 1 0 2.6-6.4L3 9","M12 7v5l4 2"] },
  { key: "hourglass",      paths: ["M6 2h12","M6 22h12","M6 2c0 5 6 6 6 10s-6 5-6 10","M18 2c0 5-6 6-6 10s6 5 6 10"] },

  /* ── documents and reporting ── */
  { key: "file",           paths: ["M14 2H6v20h12V6z","M14 2v4h4","M9 13h6M9 17h4"] },
  { key: "folder",         paths: ["M3 7h6l2 3h10v10H3z","M3 7V5h6"] },
  { key: "piechart",       paths: ["M21 15.5A9 9 0 1 1 8.5 3","M22 12A10 10 0 0 0 12 2v10z"] },
  { key: "barchart",       paths: ["M3 21h18","M7 21V11M12 21V5M17 21v-7"] },
  { key: "linechart",      paths: ["M3 3v18h18","M7 14l4-4 3 3 5-6"] },
  { key: "table",          paths: ["M3 4h18v16H3z","M3 10h18M3 15h18","M9 4v16M15 4v16"] },
  { key: "note",           paths: ["M4 3h12l4 4v14H4z","M16 3v4h4","M8 12h8M8 16h5"] },

  /* ── system ── */
  { key: "gear",           paths: ["M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6z","M19.4 15a1.6 1.6 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.6 1.6 0 0 0-2.7 1.1V21a2 2 0 1 1-4 0v-.1A1.6 1.6 0 0 0 7.5 19l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1A1.6 1.6 0 0 0 3 13.6H3a2 2 0 1 1 0-4h.1A1.6 1.6 0 0 0 4.7 7l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1A1.6 1.6 0 0 0 10 3V3a2 2 0 1 1 4 0v.1a1.6 1.6 0 0 0 2.7 1.1l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.6 1.6 0 0 0 1.1 2.7H21a2 2 0 1 1 0 4h-.1a1.6 1.6 0 0 0-1.5 1.3z"] },
  { key: "server",         paths: ["M3 3h18v6H3z","M3 15h18v6H3z","M7 6h.01M7 18h.01"] },
  { key: "cloud",          paths: ["M18 18a4 4 0 0 0 0-8 6 6 0 0 0-11.6-1.6A4.5 4.5 0 0 0 6.5 18z"] },
  { key: "link",           paths: ["M10 13a5 5 0 0 0 7.5.5l3-3a5 5 0 0 0-7-7l-1.7 1.7","M14 11a5 5 0 0 0-7.5-.5l-3 3a5 5 0 0 0 7 7l1.7-1.7"] },
  { key: "power",          paths: ["M18.4 6.6a9 9 0 1 1-12.7 0","M12 2v10"] },
  { key: "eye",            paths: ["M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z","M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6z"] },
  { key: "target",         paths: ["M12 22a10 10 0 1 0 0-20 10 10 0 0 0 0 20z","M12 18a6 6 0 1 0 0-12 6 6 0 0 0 0 12z","M12 14a2 2 0 1 0 0-4 2 2 0 0 0 0 4z"] },
  { key: "bookmark",       paths: ["M19 21l-7-5-7 5V5a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2z"] },
  { key: "home",           paths: ["M3 11l9-8 9 8","M5 10v11h14V10","M10 21v-6h4v6"] },
];

/* ─── Permission Matrix ─────────────────────────────────────────────────── */
/**
 * Modules in menu order, parents followed by their own children.
 *
 * Both RBAC screens used to keep only rows with no parent — harmless while
 * every module was a root, and a hole the moment the menu became a tree: ten
 * real pages (everything under Sales, Master Data and Delivery) vanished from
 * the permission matrix, so nobody could grant or revoke them. The containers
 * shown in their place hold no permissions at all, which made the screen look
 * complete while granting nothing.
 *
 * Containers are kept in the list as headings — marked `isContainer` so the
 * matrix renders them without checkboxes — because a flat list of children with
 * no indication of what they belong to is its own kind of unreadable.
 */
function flattenModules(all) {
  const rows = (all ?? []);
  const roots = rows.filter(m => !m.pidParentModule)
                    .sort((a, b) => (a.sortOrder ?? 0) - (b.sortOrder ?? 0));
  const out = [];
  for (const root of roots) {
    const kids = rows.filter(m => m.pidParentModule === root.pidModule)
                     .sort((a, b) => (a.sortOrder ?? 0) - (b.sortOrder ?? 0));
    // A heading is a row with children and no page behind it. One with children
    // *and* a route is still a real module and stays grantable.
    out.push({ ...root, depth: 0, isContainer: kids.length > 0 && !root.routePath });
    kids.forEach(k => out.push({ ...k, depth: 1, isContainer: false }));
  }
  // Anything whose parent is missing or inactive would otherwise disappear
  // silently — the case that caused this bug. Surface it rather than drop it.
  const seen = new Set(out.map(m => m.pidModule));
  rows.filter(m => !seen.has(m.pidModule))
      .forEach(m => out.push({ ...m, depth: 0, isContainer: false, orphan: true }));
  return out;
}

export function PermissionMatrix() {
  const confirm = useConfirm();
  const { addToast, currentUser } = useApp();
  const [roles, setRoles] = useState([]);
  const [modules, setModules] = useState([]);
  const [roleId, setRoleId] = useState(null);
  const [permissions, setPermissions] = useState([]);
  const [changed, setChanged] = useState(false);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);

  // Load roles and modules on mount
  useEffect(() => {
    loadData();
  }, []);

  // Load permissions when role changes
  useEffect(() => {
    if (roleId) {
      loadPermissions(roleId);
    }
  }, [roleId]);

  const loadData = async () => {
    setLoading(true);
    try {
      const [rolesData, modulesData] = await Promise.all([
        // With permissions, so each role row can count its own. Without them
        // every row rendered the *selected* role's tally — telling you Viewer
        // had the same access as Branch Manager.
        rbacService.getAllRoles(true),
        rbacService.getAllModules(true)
      ]);
      setRoles(rolesData);
      setModules(flattenModules(modulesData));
      if (rolesData.length > 0 && !roleId) {
        setRoleId(rolesData[0].pidGroup);
      }
    } catch (error) {
      addToast(`Failed to load RBAC data: ${error.message}`, "error");
    } finally {
      setLoading(false);
    }
  };

  const loadPermissions = async (pidGroup) => {
    try {
      const perms = await rbacService.getRolePermissions(pidGroup);
      setPermissions(perms);
      setChanged(false);
    } catch (error) {
      addToast(`Failed to load permissions: ${error.message}`, "error");
    }
  };

  /** Pages only — a heading cannot be granted, so it is not part of the total. */
  const grantableCount = modules.filter(m => !m.isContainer).length;

  const getPermission = (moduleId, key) => {
    const perm = permissions.find(p => p.pidModule === moduleId);
    // PermissionDto returns Integer (0|1); normalise to boolean for UI
    return perm ? (perm[key] === 1 || perm[key] === true) : false;
  };

  const toggle = (moduleId, key) => {
    const role = roles.find(r => r.pidGroup === roleId);
    if (role?.groupCode === 'ROLE01') {
      addToast("Super Admin permissions cannot be modified", "warning");
      return;
    }

    setPermissions(perms => {
      const existingIndex = perms.findIndex(p => p.pidModule === moduleId);
      const row = existingIndex >= 0 ? perms[existingIndex] : { pidModule: moduleId };
      const isOn = k => row[k] === 1 || row[k] === true;

      // View comes on with the right that needs it, and takes them with it when
      // it goes. See lib/permissionRules.js for why.
      const patch = viewRulePatch(key, !isOn(key), isOn);
      const merged = { ...row };
      // Normalised to 0/1 on the way in, so the row stays the shape the backend
      // sent rather than drifting into a mix of booleans and integers.
      for (const [k, on] of Object.entries(patch)) merged[k] = on ? 1 : 0;

      if (existingIndex >= 0) {
        const updated = [...perms];
        updated[existingIndex] = merged;
        return updated;
      }
      return [...perms, merged];
    });
    setChanged(true);
  };

  // Toggle ALL keys for one module row
  const toggleRow = (moduleId) => {
    const role = roles.find(r => r.pidGroup === roleId);
    if (role?.groupCode === 'ROLE01') return;

    const allSet = PERM_KEYS.every(k => getPermission(moduleId, k));
    setPermissions(perms => {
      const existingIndex = perms.findIndex(p => p.pidModule === moduleId);
      const newPerm = { pidModule: moduleId };
      PERM_KEYS.forEach(k => { newPerm[k] = !allSet; });

      if (existingIndex >= 0) {
        const updated = [...perms];
        updated[existingIndex] = { ...perms[existingIndex], ...newPerm };
        return updated;
      } else {
        return [...perms, newPerm];
      }
    });
    setChanged(true);
  };

  // Toggle ALL modules for one key column
  const toggleCol = (key) => {
    const role = roles.find(r => r.pidGroup === roleId);
    if (role?.groupCode === 'ROLE01') return;

    // Containers hold no permission row, so they take no part in a column toggle.
    const grantable = modules.filter(m => !m.isContainer);
    const allSet = grantable.every(m => getPermission(m.pidModule, key));
    const next = !allSet;
    setPermissions(perms => {
      const updated = [...perms];
      grantable.forEach(m => {
        const existingIndex = updated.findIndex(p => p.pidModule === m.pidModule);
        const row = existingIndex >= 0 ? updated[existingIndex] : { pidModule: m.pidModule };
        const isOn = k => row[k] === 1 || row[k] === true;

        // The same rule as a single tick — a whole column of Approve would
        // otherwise grant it on every page the role cannot open.
        const patch = viewRulePatch(key, next, isOn);
        const merged = { ...row };
        for (const [k, on] of Object.entries(patch)) merged[k] = on ? 1 : 0;

        if (existingIndex >= 0) updated[existingIndex] = merged;
        else updated.push(merged);
      });
      return updated;
    });
    setChanged(true);
  };

  const save = async () => {
    setSaving(true);
    try {
      // Build entries: only modules that have at least one flag set,
      // plus any modules already in backend perms (to allow clearing them).
      // We send the full matrix for every visible module.
      // Containers are excluded: they carry no permission row by design, and
      // writing zeroed rows for them would create records the menu must then
      // learn to ignore.
      const entries = modules.filter(m => !m.isContainer).map(m => {
        const perm = permissions.find(p => p.pidModule === m.pidModule) || {};
        return {
          pidModule:  m.pidModule,
          canView:    perm.canView    === 1 || perm.canView    === true ? 1 : 0,
          canCreate:  perm.canCreate  === 1 || perm.canCreate  === true ? 1 : 0,
          canEdit:    perm.canEdit    === 1 || perm.canEdit    === true ? 1 : 0,
          canDelete:  perm.canDelete  === 1 || perm.canDelete  === true ? 1 : 0,
          canExport:  perm.canExport  === 1 || perm.canExport  === true ? 1 : 0,
          canApprove: perm.canApprove === 1 || perm.canApprove === true ? 1 : 0,
        };
      });
      // setRolePermissions(pidGroup, entries[]) — service handles the { permissions } wrapper
      await rbacService.setRolePermissions(roleId, entries);
      const role = roles.find(r => r.pidGroup === roleId);
      addToast(`Permissions saved for ${role?.groupName}`);
      setChanged(false);
    } catch (error) {
      addToast(`Failed to save permissions: ${error.message}`, "error");
    } finally {
      setSaving(false);
    }
  };

  const resetAll = async () => {
    if (!(await confirm({
      title: "Reset every permission for this role?",
      body: "Every page and action currently granted to this role is cleared.",
      detail: "Anyone signed in with this role loses access until permissions are set again.",
      confirmLabel: "Reset permissions", tone: "danger",
    }))) return;
    setPermissions([]);
    setChanged(true);
  };

  if (loading) {
    return <div style={{ padding: 40, textAlign: "center", color: "var(--ink-soft)" }}>Loading RBAC data...</div>;
  }

  const role = roles.find(r => r.pidGroup === roleId);
  // Super Admin: groupCode is 'ROLE01' in the DB (not 'SUPER_ADMIN')
  const isSA = role?.groupCode === 'ROLE01' || role?.isSuperAdmin === true;
  const rc = role?.roleColor || "var(--amber)";

  return (
    <div className="rbac-panes" style={{ display: "flex", gap: 16, alignItems: "flex-start" }}>
      {/* Role picker sidebar */}
      <div style={{ width: 210, flexShrink: 0 }}>
        <Card>
          <div style={{ padding: "12px 14px", borderBottom: "1px solid var(--line)", fontFamily: "monospace", fontSize: 10.5, color: "var(--amber-deep)", textTransform: "uppercase", letterSpacing: ".1em", fontWeight: 700 }}>
            Roles ({roles.length})
          </div>
          {roles.map(r => {
            const rColor = r.roleColor || "#888";
            const isActive = r.pidGroup === roleId;
            // This row's own permissions, not the selected role's.
            const viewCount = (r.permissions ?? [])
              .filter(x => x.canView === 1 || x.canView === true).length;
            return (
              <button key={r.pidGroup} onClick={() => setRoleId(r.pidGroup)}
                style={{ width: "100%", padding: "10px 14px", display: "flex", alignItems: "center", gap: 10, border: "none", background: isActive ? rColor + "15" : "transparent", cursor: "pointer", textAlign: "left", borderLeft: `3px solid ${isActive ? rColor : "transparent"}`, transition: "all .12s" }}>
                <div style={{ width: 28, height: 28, borderRadius: "50%", background: rColor, color: "#fff", display: "flex", alignItems: "center", justifyContent: "center", fontWeight: 800, fontSize: 11, fontFamily: "monospace", flexShrink: 0 }}>
                  {r.groupName.slice(0, 2).toUpperCase()}
                </div>
                <div>
                  <div style={{ fontWeight: 700, fontSize: 13, color: isActive ? rColor : "var(--ink)" }}>{r.groupName}</div>
                  <div style={{ fontSize: 10.5, color: "var(--ink-soft)" }}>{r.groupCode === 'ROLE01' ? "Full access" : `${viewCount}/${grantableCount} views`}</div>
                </div>
              </button>
            );
          })}
        </Card>
      </div>

      {/* Matrix */}
      <div style={{ flex: 1, minWidth: 0 }}>
        {/* Header */}
        <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 12 }}>
          <div>
            <div style={{ fontWeight: 700, fontSize: 15 }}>
              <span style={{ display: "inline-block", width: 10, height: 10, borderRadius: "50%", background: rc, marginRight: 8 }} />
              {role?.groupName}
            </div>
            <div style={{ fontSize: 12, color: "var(--ink-soft)" }}>{role?.groupDescription}</div>
          </div>
          <div style={{ display: "flex", gap: 8 }}>
            <button className="btn btn-ghost btn-sm" onClick={resetAll}>Reset All</button>
            <button className="btn btn-primary btn-sm" onClick={save} disabled={!changed || saving} style={{ opacity: (changed && !saving) ? 1 : 0.5 }}>
              {saving ? "Saving..." : "Save Permissions"}
            </button>
          </div>
        </div>

        {isSA && (
          <div style={{ background: "var(--sage-soft)", border: "1px solid var(--sage)", borderRadius: 10, padding: "10px 14px", marginBottom: 14, fontSize: 13, color: "var(--sage)", display: "flex", gap: 8, alignItems: "center" }}>
            <Ic d="M20 6 9 17l-5-5" size={14} color="var(--sage)" />
            Super Admin has unrestricted access to all modules and actions.
          </div>
        )}

        <Card>
          <div style={{ overflowX: "auto" }}>
            <table style={{ width: "100%", borderCollapse: "collapse", fontSize: 12.5 }}>
              <thead>
                <tr>
                  <th style={{ padding: "10px 14px", textAlign: "left", fontFamily: "monospace", fontSize: 10.5, color: "var(--amber-deep)", fontWeight: 700, textTransform: "uppercase", letterSpacing: ".08em", borderBottom: "2px solid var(--ink)", whiteSpace: "nowrap", minWidth: 160 }}>
                    Module / Menu
                  </th>
                  {PERM_KEYS.map(k => {
                    const m = PERM_META[k];
                    const allSet = modules.filter(mn => !mn.isContainer)
                                          .every(mn => getPermission(mn.pidModule, k));
                    return (
                      <th key={k} style={{ padding: "8px 6px", textAlign: "center", borderBottom: "2px solid var(--ink)", cursor: isSA ? "default" : "pointer", userSelect: "none", whiteSpace: "nowrap" }}
                        onClick={() => toggleCol(k)}
                        title={`Toggle all ${m.label}`}>
                        <div style={{ display: "flex", flexDirection: "column", alignItems: "center", gap: 3 }}>
                          <svg width={13} height={13} viewBox="0 0 24 24" fill="none" stroke={m.color} strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round">
                            <path d={m.icon} />
                          </svg>
                          <span style={{ fontSize: 10, color: allSet && !isSA ? m.color : "var(--ink-soft)", fontWeight: allSet && !isSA ? 800 : 600, textTransform: "uppercase", letterSpacing: ".06em" }}>{m.label}</span>
                        </div>
                      </th>
                    );
                  })}
                </tr>
              </thead>
              <tbody>
                {modules.map((module, mi) => {
                  const rowAll = PERM_KEYS.every(k => getPermission(module.pidModule, k));

                  /* A heading, not a module: no page behind it and no permission
                     row to grant. Rendered as a band across the table so the
                     rows beneath it read as belonging to it. */
                  if (module.isContainer) {
                    return (
                      <tr key={module.pidModule}>
                        <td colSpan={PERM_KEYS.length + 1}
                          style={{ padding: "12px 14px 5px", borderBottom: "1px solid var(--line)", background: "var(--paper)" }}>
                          <span style={{ fontSize: 10.5, fontWeight: 700, textTransform: "uppercase", letterSpacing: ".09em", color: "var(--ink-soft)" }}>
                            {module.moduleName}
                          </span>
                          <span style={{ marginLeft: 8, fontSize: 10.5, color: "var(--ink-soft)", opacity: .75 }}>
                            menu group — grant the pages below
                          </span>
                        </td>
                      </tr>
                    );
                  }

                  return (
                    <tr key={module.pidModule} style={{ background: mi % 2 === 0 ? "transparent" : "var(--paper)" }}>
                      <td style={{ padding: "9px 14px", borderBottom: "1px solid var(--line)" }}>
                        <div style={{ display: "flex", alignItems: "center", gap: 8, paddingLeft: module.depth ? 18 : 0 }}>
                          <input type="checkbox" checked={rowAll || isSA} onChange={() => toggleRow(module.pidModule)}
                            disabled={isSA} title="Toggle all for this module"
                            style={{ width: 15, height: 15, accentColor: rc, cursor: isSA ? "default" : "pointer" }} />
                          <span style={{ fontWeight: module.depth ? 500 : 600 }}>{module.moduleName}</span>
                          {module.orphan && (
                            /* Its parent is missing or inactive, so it appears in
                               no menu — but it still has permissions, and hiding
                               it here is how it became invisible in the first place. */
                            <span title="This module's parent is missing or inactive, so it appears in no menu"
                              style={{ fontSize: 9, fontWeight: 800, padding: "1px 5px", borderRadius: 4, background: "var(--clay-soft)", color: "var(--clay)" }}>
                              ORPHAN
                            </span>
                          )}
                        </div>
                      </td>
                      {PERM_KEYS.map(k => {
                        const val = isSA ? true : getPermission(module.pidModule, k);
                        const m = PERM_META[k];
                        return (
                          <td key={k} style={{ padding: "9px 6px", textAlign: "center", borderBottom: "1px solid var(--line)", cursor: isSA ? "default" : "pointer" }}
                            onClick={() => toggle(module.pidModule, k)}>
                            <div style={{
                              width: 22, height: 22, borderRadius: 6, margin: "0 auto",
                              background: val ? m.color + "22" : "var(--paper-deep)",
                              border: `1.5px solid ${val ? m.color : "var(--line)"}`,
                              display: "flex", alignItems: "center", justifyContent: "center",
                              transition: "all .12s",
                            }}>
                              {val && (
                                <svg width={11} height={11} viewBox="0 0 24 24" fill="none" stroke={m.color} strokeWidth="3" strokeLinecap="round" strokeLinejoin="round">
                                  <path d="M20 6 9 17l-5-5" />
                                </svg>
                              )}
                            </div>
                          </td>
                        );
                      })}
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        </Card>
      </div>
    </div>
  );
}

/* ─── Icon Picker ─────────────────────────────────────────────────────────── */
function IconPicker({ value, onChange }) {
  const [open, setOpen] = useState(false);
  const [q, setQ] = useState("");
  const filtered = AVAILABLE_ICONS.filter(ic => ic.key.includes(q.toLowerCase()));
  const selected = AVAILABLE_ICONS.find(ic => ic.key === value);

  return (
    <div style={{ position: "relative" }}>
      <button type="button" onClick={() => setOpen(v => !v)}
        style={{ display: "flex", alignItems: "center", gap: 10, padding: "9px 12px", border: "1.5px solid var(--line)", borderRadius: 9, background: "var(--paper)", cursor: "pointer", width: "100%" }}>
        {selected ? (
          <>
            <div style={{ width: 28, height: 28, borderRadius: 7, background: "var(--teal)", display: "flex", alignItems: "center", justifyContent: "center" }}>
              <svg width={15} height={15} viewBox="0 0 24 24" fill="none" stroke="#fff" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round">
                {selected.paths.map((d, i) => <path key={i} d={d} />)}
              </svg>
            </div>
            <span style={{ fontWeight: 600, fontSize: 13.5 }}>{selected.key}</span>
          </>
        ) : (
          <span style={{ color: "var(--ink-soft)", fontSize: 13.5 }}>— Select Icon —</span>
        )}
        <span style={{ marginLeft: "auto" }}>▾</span>
      </button>

      {open && (
        <div style={{ position: "absolute", top: "calc(100% + 6px)", left: 0, right: 0, background: "var(--card)", border: "1px solid var(--line)", borderRadius: 12, boxShadow: "var(--shadow-lg)", zIndex: 100, padding: 12 }}>
          <input value={q} onChange={e => setQ(e.target.value)} placeholder="Search icon…"
            style={{ width: "100%", padding: "7px 10px", border: "1px solid var(--line)", borderRadius: 8, fontSize: 13, background: "var(--paper)", marginBottom: 10, boxSizing: "border-box" }}
            autoFocus />
          {/* How many are on offer, and how many the search left. With a
              hundred-odd icons a silent grid gives no sense of whether the
              search worked or the library is simply small. */}
          <div style={{ fontSize: 11, color: "var(--ink-soft)", marginBottom: 8 }}>
            {q
              ? `${filtered.length} of ${AVAILABLE_ICONS.length} match “${q}”`
              : `${AVAILABLE_ICONS.length} icons — type to narrow`}
          </div>
          <div style={{ display: "grid", gridTemplateColumns: "repeat(6, 1fr)", gap: 6, maxHeight: 320, overflowY: "auto" }}>
            {filtered.map(ic => (
              <button key={ic.key} type="button" title={ic.key}
                onClick={() => { onChange(ic.key); setOpen(false); setQ(""); }}
                style={{
                  padding: 8, borderRadius: 8, border: `1.5px solid ${value === ic.key ? "var(--amber)" : "var(--line)"}`,
                  background: value === ic.key ? "var(--amber-soft)" : "var(--paper)",
                  cursor: "pointer", display: "flex", flexDirection: "column", alignItems: "center", gap: 3, transition: "all .1s",
                }}>
                <svg width={18} height={18} viewBox="0 0 24 24" fill="none" stroke={value === ic.key ? "var(--amber-deep)" : "var(--ink-soft)"} strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round">
                  {ic.paths.map((d, i) => <path key={i} d={d} />)}
                </svg>
                <span style={{ fontSize: 8, color: "var(--ink-soft)", whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis", width: "100%", textAlign: "center" }}>{ic.key}</span>
              </button>
            ))}
          </div>
          {filtered.length === 0 && <div style={{ textAlign: "center", color: "var(--ink-soft)", fontSize: 13, padding: 12 }}>No icons found for "{q}"</div>}
        </div>
      )}
    </div>
  );
}

/* ─── Modules & Menus management ────────────────────────────────────────── */
function MenusModules({ perm = {} }) {
  const confirm = useConfirm();
  const { addToast } = useApp();
  const [modules, setModules] = useState([]);
  const [loading, setLoading] = useState(true);
  const [creating, setCreating] = useState(false);
  const [editItem, setEditItem] = useState(null);
  const [form, setForm] = useState({ moduleName: "", routePath: "", iconKey: "", moduleCode: "", pidParentModule: "", sortOrder: "" });
  const [errors, setErrors] = useState({});

  useEffect(() => {
    loadModules();
  }, []);

  // The module whose roles are open, if any.
  const [rolesFor, setRolesFor] = useState(null);

  const loadModules = async () => {
    setLoading(true);
    try {
      const data = await rbacService.getAllModules(true);
      // The whole tree. Keeping only roots hid every child module — you could
      // neither see nor edit the pages under Sales, Master Data or Delivery.
      setModules(flattenModules(data));
    } catch (error) {
      addToast(`Failed to load modules: ${error.message}`, "error");
    } finally {
      setLoading(false);
    }
  };

  const validate = () => {
    const err = {};
    if (!form.moduleName.trim()) err.moduleName = "Module name required";
    if (!form.iconKey)           err.iconKey    = "Select an icon";

    // A route is required for a page but wrong for a menu group: a heading with
    // a route both navigates and expands, and there is no page behind it. The
    // form asks which one this is rather than guessing from a blank field.
    if (form.isGroup) {
      if (form.routePath.trim()) err.routePath = "A menu group has no page of its own — leave this blank";
    } else if (!form.routePath.trim()) {
      err.routePath = "Route path required";
    }
    setErrors(err);
    return Object.keys(err).length === 0;
  };

  const save = async () => {
    if (!validate()) return;
    try {
      const payload = {
        moduleName: form.moduleName.trim(),
        moduleCode: form.moduleCode.trim() || undefined,
        iconKey: form.iconKey,
        // Blank means "no page", which is what a group is. Sent as null rather
        // than "" so the column holds NULL and the menu can tell them apart.
        routePath: form.isGroup ? null : form.routePath.trim(),
        pidParentModule: form.pidParentModule ? Number(form.pidParentModule) : null,
        sortOrder: form.sortOrder === "" ? null : Number(form.sortOrder),
      };
      let born = null;
      if (editItem) {
        await rbacService.updateModule(editItem.pidModule, payload);
        addToast(`Module "${form.moduleName}" updated`);
      } else {
        born = await rbacService.createModule(payload);
        addToast(`Module "${form.moduleName}" created — now choose who can reach it.`);
      }
      await loadModules();
      setCreating(false);
      setEditItem(null);
      setForm({ moduleName: "", routePath: "", iconKey: "", moduleCode: "", pidParentModule: "", sortOrder: "" });

      /* A brand-new page is granted to nobody, so it is invisible to every user
         including the person who just made it. Rather than leave that to be
         discovered, the roles dialog opens on it straight away. A menu group is
         skipped: it is a heading with no page behind it and holds no rights.

         Read from the payload, not from `form` — the form has been reset by the
         time this runs. */
      if (born?.pidModule && payload.routePath) {
        setRolesFor({ ...born, moduleName: born.moduleName ?? payload.moduleName });
      }
    } catch (error) {
      addToast(`Failed to save module: ${error.message}`, "error");
    }
  };

  const remove = async (pidModule) => {
    if (!(await confirm({
      title: "Delete this menu item?",
      body: "The item is removed, along with every role's permissions for it.",
      detail: "Any role that reached this page through the menu will no longer see it.",
      confirmLabel: "Delete menu item", tone: "danger",
    }))) return;
    try {
      await rbacService.deleteModule(pidModule);
      addToast("Module deleted", "warning");
      await loadModules();
    } catch (error) {
      addToast(`Failed to delete module: ${error.message}`, "error");
    }
  };

  const openEdit = (m) => {
    setEditItem(m);
    setForm({ moduleName: m.moduleName, routePath: m.routePath || "", iconKey: m.iconKey || "",
              moduleCode: m.moduleCode || "", pidParentModule: m.pidParentModule || "",
              sortOrder: m.sortOrder ?? "",
              // Derived from the row rather than stored: a group is what has no
              // route of its own, which is the same rule the menu applies.
              isGroup: !m.routePath });
    setCreating(true);
  };

  /**
   * The paths to draw for a module's icon.
   *
   * Falls through rather than returning nothing. An unknown key used to yield an
   * empty array, which rendered an SVG with no contents — a blank tile that
   * looked like a styling fault rather than a missing icon. The sidebar is tried
   * next because it knows keys the picker did not offer, and a plain square is
   * the last resort so there is always something to see.
   */
  const iconPaths = (key) =>
    AVAILABLE_ICONS.find(ic => ic.key === key)?.paths
    || ICON_PATHS[key]
    || ICON_PATHS.default
    || ["M4 4h16v16H4z"];

  /**
   * What may be picked as a parent.
   *
   * Only top-level entries, because the sidebar renders two levels and a
   * grandchild would be stored but never shown. A row cannot be its own parent
   * either — that would drop it and everything under it out of the menu with no
   * error to explain where they went.
   */
  const parentOptions = useMemo(
    () => modules.filter(m => !m.pidParentModule && m.pidModule !== editItem?.pidModule),
    [modules, editItem]);

  if (loading) {
    return <div style={{ padding: 40, textAlign: "center", color: "var(--ink-soft)" }}>Loading modules...</div>;
  }

  return (
    <div>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 16 }}>
        <div style={{ fontSize: 13.5, color: "var(--ink-soft)" }}>{modules.length} modules</div>
        {perm.canCreate && (
          <button className="btn btn-primary btn-sm" onClick={() => { setCreating(true); setEditItem(null); setForm({ moduleName: "", routePath: "", iconKey: "", moduleCode: "", pidParentModule: "", sortOrder: "" }); }}>
            + New Module
          </button>
        )}
      </div>

      {/* Wide enough for a name, a route and a row of actions. The previous
          220px could not hold them side by side, so the text column collapsed
          and a monospace route wrapped one character per line. */}
      <div className="mm-grid">
        {modules.map(m => {
          const paths = iconPaths(m.iconKey);
          const subtitle = m.routePath || (m.isContainer ? "menu group — no page" : m.moduleCode);
          return (
            <div key={m.pidModule}
                 className={`mm-card ${m.depth ? "mm-child" : ""} ${m.isContainer ? "mm-group" : ""}`}>

              {/* Identity: icon, name, route. Its own row, so nothing competes
                  with it for width and the route can be read. */}
              <div className="mm-head">
                <div className={`mm-icon ${m.isContainer ? "mm-icon-group" : ""}`}>
                  <svg width={20} height={20} viewBox="0 0 24 24" fill="none" stroke="#fff"
                       strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round">
                    {paths.map((d, i) => <path key={i} d={d} />)}
                  </svg>
                </div>
                <div className="mm-text">
                  {/* title= so a name too long to show is still readable on hover
                      rather than simply lost to the ellipsis. */}
                  <div className="mm-name" title={m.moduleName}>
                    {m.moduleName}
                    {m.isContainer && <span className="mm-tag">GROUP</span>}
                  </div>
                  <div className="mm-route" title={subtitle}>{subtitle}</div>
                </div>
              </div>

              {/* Actions, on their own row and separated by a rule. */}
              <div className="mm-actions">
                {/* Only a page can be granted — a menu group is a heading with
                    nothing behind it, so it has no roles to assign.

                    Labelled and carrying the count: a page no role holds is
                    invisible to everyone, and that is worth seeing from the list
                    rather than by opening each module in turn. */}
                {perm.canEdit && !m.isContainer ? (
                  <button className={`btn btn-sm mm-roles ${m.roleCount === 0 ? "btn-warn" : "btn-ghost"}`}
                          onClick={() => setRolesFor(m)}
                          title="Which roles can reach this page">
                    <Ic d={["M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2","M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8z","M23 21v-2a4 4 0 0 0-3-3.87","M16 3.13a4 4 0 0 1 0 7.75"]} size={13} />
                    {m.roleCount === 0 ? "No roles yet" : `${m.roleCount} role${m.roleCount === 1 ? "" : "s"}`}
                  </button>
                ) : <span className="mm-spacer" />}

                {perm.canEdit && (
                  <button className="icon-btn mm-btn" onClick={() => openEdit(m)} title="Edit module">
                    <Ic d="M12 20h9 M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4 12.5-12.5z" size={13} />
                  </button>
                )}
                {perm.canDelete && (
                  <button className="icon-btn mm-btn" onClick={() => remove(m.pidModule)} title="Delete module">
                    <Ic d={["M3 6h18","M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2","M19 6l-1 14H6L5 6"]} size={13} />
                  </button>
                )}
              </div>
            </div>
          );
        })}
      </div>

      {/* Which roles can reach this page. Reloads the list on save so the
          permission count on each row stays true. */}
      {rolesFor && (
        <ModuleRoles
          module={rolesFor}
          onClose={() => setRolesFor(null)}
          onSaved={loadModules}
        />
      )}

      {/* Create/Edit Modal */}
      {creating && (
        <Modal title={editItem ? `Edit Module — ${editItem.moduleName}` : "New Module"} onClose={() => { setCreating(false); setEditItem(null); }} width={560}
          footer={<>
            <button className="btn btn-ghost" onClick={() => { setCreating(false); setEditItem(null); }}>Cancel</button>
            {(editItem ? perm.canEdit : perm.canCreate) && (
              <button className="btn btn-primary" onClick={save}>{editItem ? "Save Changes" : "Create Module"}</button>
            )}
          </>}>
          <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: "0 20px" }}>
            <Field label="Module Name *" error={errors.moduleName}>
              <input value={form.moduleName} onChange={e => setForm(f => ({ ...f, moduleName: e.target.value }))} placeholder="e.g. Dashboard" />
            </Field>
            <Field label="Module Code (optional)" error={errors.moduleCode}>
              <input value={form.moduleCode || ""} onChange={e => setForm(f => ({ ...f, moduleCode: e.target.value.toUpperCase().replace(/\s+/g, "_") }))} placeholder="Auto-generated if empty" />
            </Field>
          </div>
          {/* What this row is. A menu group is a heading that expands; a page is
              something you navigate to. The distinction decides whether a route
              is required or forbidden, so it is asked outright rather than
              inferred from whether someone left a field blank. */}
          <Field label="This entry is">
            <div style={{ display: "flex", gap: 8 }}>
              {[{ v: false, label: "A page", hint: "has a route and its own permissions" },
                { v: true,  label: "A menu group", hint: "a heading holding other pages" }].map(o => (
                <button key={String(o.v)} type="button"
                  onClick={() => setForm(f => ({ ...f, isGroup: o.v, routePath: o.v ? "" : f.routePath }))}
                  style={{
                    flex: 1, textAlign: "left", padding: "9px 12px", borderRadius: 9, cursor: "pointer",
                    border: `1.5px solid ${!!form.isGroup === o.v ? "var(--teal)" : "var(--line)"}`,
                    background: !!form.isGroup === o.v ? "var(--teal-soft)" : "var(--paper)",
                  }}>
                  <div style={{ fontWeight: 700, fontSize: 12.5 }}>{o.label}</div>
                  <div style={{ fontSize: 11, color: "var(--ink-soft)" }}>{o.hint}</div>
                </button>
              ))}
            </div>
          </Field>

          <div style={{ display: "grid", gridTemplateColumns: "2fr 1fr", gap: "0 20px" }}>
            <Field label="Parent menu" error={errors.pidParentModule}>
              <select value={form.pidParentModule || ""}
                onChange={e => setForm(f => ({ ...f, pidParentModule: e.target.value }))}>
                <option value="">— Top level —</option>
                {parentOptions.map(m => (
                  <option key={m.pidModule} value={m.pidModule}>{m.moduleName}</option>
                ))}
              </select>
              <div style={{ fontSize: 11, color: "var(--ink-soft)", marginTop: 4 }}>
                {/* Two levels only: the sidebar renders roots and their children,
                    and a grandchild would be built but never displayed. */}
                Menus are two levels deep — a group cannot sit inside another group.
              </div>
            </Field>
            <Field label="Sort order">
              <input type="number" value={form.sortOrder ?? ""}
                onChange={e => setForm(f => ({ ...f, sortOrder: e.target.value }))}
                placeholder="e.g. 4" />
            </Field>
          </div>

          {!form.isGroup && (
            <Field label="Route Path *" error={errors.routePath}>
              <input value={form.routePath || ""} onChange={e => setForm(f => ({ ...f, routePath: e.target.value.toLowerCase() }))} placeholder="e.g. /dashboard" />
              <div style={{ fontSize: 11, color: "var(--ink-soft)", marginTop: 4 }}>
                The page must also be wired up in the app — a route with no screen behind it
                navigates to a blank page.
              </div>
            </Field>
          )}
          <Field label="Icon *" error={errors.iconKey}>
            <IconPicker value={form.iconKey} onChange={v => setForm(f => ({ ...f, iconKey: v }))} />
          </Field>
          {form.iconKey && (
            <div style={{ background: "var(--teal-soft)", borderRadius: 10, padding: "12px 16px", display: "flex", alignItems: "center", gap: 12, marginTop: 8 }}>
              <div style={{ width: 36, height: 36, borderRadius: 9, background: "var(--teal)", display: "flex", alignItems: "center", justifyContent: "center" }}>
                <svg width={18} height={18} viewBox="0 0 24 24" fill="none" stroke="#fff" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round">
                  {iconPaths(form.iconKey).map((d, i) => <path key={i} d={d} />)}
                </svg>
              </div>
              <div>
                <div style={{ fontWeight: 700, fontSize: 13.5 }}>{form.moduleName || "Module Preview"}</div>
                <div style={{ fontSize: 11.5, color: "var(--teal)", fontFamily: "monospace" }}>{form.routePath || form.moduleCode || "route-path"}</div>
              </div>
            </div>
          )}
        </Modal>
      )}
    </div>
  );
}

/* ─── Roles management ───────────────────────────────────────────────────── */
export function RolesList({ perm = {} }) {
  const confirm = useConfirm();
  const { addToast, currentUser } = useApp();
  const [roles, setRoles] = useState([]);
  const [loading, setLoading] = useState(true);
  const [modal, setModal] = useState(null);
  const [form, setForm] = useState({ groupName: "", groupDescription: "", roleColor: "#2E6A62" });
  const [error, setError] = useState("");

  useEffect(() => {
    loadRoles();
  }, []);

  const loadRoles = async () => {
    setLoading(true);
    try {
      const data = await rbacService.getAllRoles(false);
      setRoles(data);
    } catch (error) {
      addToast(`Failed to load roles: ${error.message}`, "error");
    } finally {
      setLoading(false);
    }
  };

  const save = async () => {
    if (!form.groupName.trim()) { setError("Role name is required"); return; }
    try {
      if (modal === "edit") {
        await rbacService.updateRole(form.pidGroup, {
          groupName: form.groupName,
          description: form.groupDescription,
          roleColor: form.roleColor,
          isActive: form.isActive ? 1 : 0
        });
        addToast("Role updated");
      } else {
        // Backend auto-generates groupCode
        await rbacService.createRole({
          groupName: form.groupName,
          description: form.groupDescription || "",
          roleColor: form.roleColor || "#2E6A62"
        });
        addToast("Role created");
      }
      await loadRoles();
      setModal(null);
      setError("");
    } catch (error) {
      addToast(`Failed to save role: ${error.message}`, "error");
    }
  };

  const remove = async (pidGroup) => {
    if (!(await confirm({
      title: "Delete this role?",
      body: "The role is removed, along with all of its permissions.",
      detail: "Users still assigned to it will have no access until they are given another role.",
      confirmLabel: "Delete role", tone: "danger",
    }))) return;
    try {
      await rbacService.deleteRole(pidGroup);
      addToast("Role deleted", "warning");
      await loadRoles();
    } catch (error) {
      addToast(`Failed to delete role: ${error.message}`, "error");
    }
  };

  if (loading) {
    return <div style={{ padding: 40, textAlign: "center", color: "var(--ink-soft)" }}>Loading roles...</div>;
  }

  return (
    <div>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 16 }}>
        <div style={{ fontSize: 13.5, color: "var(--ink-soft)" }}>{roles.length} roles defined</div>
        {perm.canCreate && (
          <button className="btn btn-primary btn-sm" onClick={() => { setForm({ groupName: "", groupDescription: "", roleColor: DEFAULT_ROLE_COLORS[roles.length % DEFAULT_ROLE_COLORS.length] }); setModal("create"); }}>
            + New Role
          </button>
        )}
      </div>
      <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fill,minmax(260px,1fr))", gap: 12 }}>
          {roles.map(r => {
          const rc = r.roleColor || DEFAULT_ROLE_COLORS[0];
          const ini = r.groupName.split(" ").map(s => s[0]).slice(0, 2).join("");
          return (
            <div key={r.pidGroup} style={{ background: "var(--card)", border: `1.5px solid ${rc}33`, borderRadius: 12, padding: "16px 18px", display: "flex", gap: 14, alignItems: "flex-start" }}>
              <div style={{ width: 44, height: 44, borderRadius: "50%", background: rc, color: "#fff", display: "flex", alignItems: "center", justifyContent: "center", fontWeight: 800, fontSize: 14, fontFamily: "monospace", flexShrink: 0 }}>
                {ini}
              </div>
              <div style={{ flex: 1, minWidth: 0 }}>
                <div style={{ fontWeight: 700, fontSize: 14 }}>{r.groupName}</div>
                <div style={{ fontSize: 12, color: "var(--ink-soft)", marginTop: 2 }}>{r.description}</div>
                <div style={{ marginTop: 8, display: "flex", gap: 6, alignItems: "center", flexWrap: "wrap" }}>
                  <span style={{ fontSize: 11, background: rc + "15", color: rc, borderRadius: 5, padding: "2px 8px", fontWeight: 600 }}>
                    {r.groupCode}
                  </span>
                  {r.isActive ? (
                    <span style={{ fontSize: 11, color: "var(--sage)" }}>● Active</span>
                  ) : (
                    <span style={{ fontSize: 11, color: "var(--clay)" }}>● Inactive</span>
                  )}
                </div>
              </div>
              <div style={{ display: "flex", gap: 4 }}>
                {perm.canEdit && (
                  <button className="icon-btn" style={{ width: 28, height: 28 }}
                    onClick={() => { setForm({ ...r, groupDescription: r.description }); setModal("edit"); }}>
                    <Ic d="M12 20h9 M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4 12.5-12.5z" size={13} />
                  </button>
                )}
                {perm.canDelete && r.groupCode !== 'ROLE01' && (
                  <button className="icon-btn" style={{ width: 28, height: 28 }}
                    onClick={() => remove(r.pidGroup)}>
                    <Ic d={["M3 6h18","M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2","M19 6l-1 14H6L5 6"]} size={13} />
                  </button>
                )}
              </div>
            </div>
          );
        })}
      </div>
      {modal && (
        <Modal title={modal === "edit" ? `Edit Role` : "New Role"} onClose={() => setModal(null)} width={480}
          footer={<>
            <button className="btn btn-ghost" onClick={() => setModal(null)}>Cancel</button>
            {(modal === "create" ? perm.canCreate : perm.canEdit) && (
              <button className="btn btn-primary" onClick={save}>Save Role</button>
            )}
          </>}>
          <Field label="Role Name *" error={error}><input value={form.groupName || ""} onChange={e => { setForm(f => ({ ...f, groupName: e.target.value })); setError(""); }} /></Field>
          <Field label="Description"><input value={form.groupDescription || ""} onChange={e => setForm(f => ({ ...f, groupDescription: e.target.value }))} /></Field>
          {modal === "edit" && <Field label="Role Code (system)"><input value={form.groupCode || ""} disabled style={{ opacity: 0.6 }} /></Field>}
          <Field label="Color">
            <div style={{ display: "flex", gap: 10, alignItems: "center" }}>
              <input type="color" value={form.roleColor || "#2E6A62"} onChange={e => setForm(f => ({ ...f, roleColor: e.target.value }))} style={{ width: 44, height: 36, borderRadius: 8, border: "1px solid var(--line)", padding: 2, cursor: "pointer" }} />
              <div style={{ width: 36, height: 36, borderRadius: "50%", background: form.roleColor || "#888", display: "flex", alignItems: "center", justifyContent: "center", color: "#fff", fontWeight: 800, fontSize: 13, fontFamily: "monospace" }}>
                {(form.groupName || "R").slice(0, 2).toUpperCase()}
              </div>
            </div>
          </Field>
        </Modal>
      )}
    </div>
  );
}

/* ─── Users management ───────────────────────────────────────────────────── */
export function UsersList({ perm = {} }) {
  const { addToast, currentUser, branches } = useApp();
  const [users, setUsers] = useState([]);
  const [roles, setRoles] = useState([]);
  const [employees, setEmployees] = useState([]);
  const [loading, setLoading] = useState(true);
  const [q, setQ] = useState("");
  // Avatar zoom lightbox
  const [zoomAvatar, setZoomAvatar] = useState(null); // { url, name }
  // Avatar upload modal
  const [uploadTarget, setUploadTarget] = useState(null); // { pidUser, fullName, currentUrl }
  const [uploadFile, setUploadFile]     = useState(null);
  const [uploadPreview, setUploadPreview] = useState(null);
  const [uploadError, setUploadError]   = useState("");
  const [uploading, setUploading]       = useState(false);
  const [uploadDrag, setUploadDrag]     = useState(false);
  const uploadInputRef = useRef(null);

  useEffect(() => { loadData(); }, []);

  const loadData = async () => {
    setLoading(true);
    try {
      const [usersData, rolesData, staff] = await Promise.all([
        rbacService.getAllUsers(currentUser?.pidOrg || 1),
        rbacService.getAllRoles(false),
        // Failure here must not blank the user list — the employee picker is
        // one column, not the reason this screen exists.
        getEmployees({ size: 200 }).catch(() => ({ items: [] })),
      ]);
      setUsers(usersData);
      setRoles(rolesData);
      setEmployees(staff?.items ?? []);
    } catch (error) {
      addToast(`Failed to load users: ${error.message}`, "error");
    } finally {
      setLoading(false);
    }
  };

  const toggleStatus = async (pidUser, isActive) => {
    try {
      if (isActive === 1) { await rbacService.deactivateUser(pidUser); addToast("User deactivated"); }
      else                { await rbacService.activateUser(pidUser);   addToast("User activated"); }
      await loadData();
    } catch (error) { addToast(`Failed to update user status: ${error.message}`, "error"); }
  };

  /**
   * Moves a login to another branch.
   *
   * This changes everything that account can see, so it reloads afterwards
   * rather than patching the row in place — a stale row here would show the old
   * branch beside data from the new one.
   */
  const setBranch = async (pidUser, value) => {
    try {
      await rbacService.updateUser(pidUser, { pidBranch: value ? Number(value) : null });
      addToast("Branch updated");
      await loadData();
    } catch (error) { addToast(`Could not move that user: ${error.message}`, "error"); }
  };

  const setRole = async (pidUser, pidGroup) => {
    try {
      await rbacService.updateUser(pidUser, { pidGroup });
      addToast("Role updated");
      await loadData();
    } catch (error) { addToast(`Failed to update role: ${error.message}`, "error"); }
  };

  /**
   * Links a login to a staff record, or clears the link.
   *
   * The server refuses the combinations that would mislead — a delivery login
   * with no link, one pointed at somebody who is not a driver, or an employee
   * already claimed by another account — and names the conflict when it does.
   * That message is shown as-is rather than wrapped in a generic failure.
   */
  const setEmployee = async (pidUser, value) => {
    try {
      await rbacService.updateUser(pidUser, { pidEmployee: value ? Number(value) : null });
      addToast(value ? "Employee linked" : "Employee link cleared");
      await loadData();
    } catch (error) { addToast(error.message, "error"); }
  };

  // ── Avatar upload modal helpers ──
  const openUploadModal = (u) => {
    setUploadTarget({ pidUser: u.pidUser, fullName: u.fullName || u.username, currentUrl: u.avatarUrl });
    setUploadFile(null);
    // Resolve relative URL so the preview in the modal shows correctly
    setUploadPreview(resolveAvatarUrl(u.avatarUrl) || null);
    setUploadError("");
  };
  const closeUploadModal = () => {
    if (uploadPreview && !uploadTarget?.currentUrl) URL.revokeObjectURL(uploadPreview);
    setUploadTarget(null);
    setUploadFile(null);
    setUploadPreview(null);
    setUploadError("");
    if (uploadInputRef.current) uploadInputRef.current.value = "";
  };
  const handleUploadFile = (file) => {
    setUploadError("");
    if (!file) return;
    if (!["image/jpeg","image/jpg","image/png"].includes(file.type)) {
      setUploadError("Only jpg, jpeg, png files are allowed"); return;
    }
    if (file.size > 2 * 1024 * 1024) { setUploadError("Image must be under 2 MB"); return; }
    setUploadFile(file);
    setUploadPreview(URL.createObjectURL(file));
  };
  const submitUpload = async () => {
    if (!uploadFile || !uploadTarget) return;
    setUploading(true);
    try {
      await rbacService.uploadUserAvatar(uploadTarget.pidUser, uploadFile);
      addToast(`Avatar updated for ${uploadTarget.fullName}`);
      closeUploadModal();
      await loadData(); // refresh table
    } catch (err) {
      addToast(`Failed to upload avatar: ${err.message}`, "error");
    } finally {
      setUploading(false);
    }
  };

  const filtered = q ? users.filter(u =>
    (u.fullName || "").toLowerCase().includes(q.toLowerCase()) ||
    (u.username || "").toLowerCase().includes(q.toLowerCase())
  ) : users;

  if (loading) return <div style={{ padding: 40, textAlign: "center", color: "var(--ink-soft)" }}>Loading users...</div>;

  return (
    <>
      {/* ── Avatar zoom lightbox ── */}
      {zoomAvatar && (
        <div onClick={() => setZoomAvatar(null)} style={{ position: "fixed", inset: 0, zIndex: 1000, background: "rgba(0,0,0,0.75)", backdropFilter: "blur(4px)", display: "flex", alignItems: "center", justifyContent: "center", cursor: "zoom-out" }}>
          <div style={{ position: "relative", maxWidth: "90vw", maxHeight: "90vh" }} onClick={e => e.stopPropagation()}>
            <img src={zoomAvatar.url} alt={zoomAvatar.name} style={{ maxWidth: "80vw", maxHeight: "80vh", borderRadius: 16, boxShadow: "0 24px 64px rgba(0,0,0,0.5)", objectFit: "contain", display: "block" }} />
            <div style={{ textAlign: "center", marginTop: 12, color: "#fff", fontSize: 14, fontWeight: 600 }}>{zoomAvatar.name}</div>
            <button onClick={() => setZoomAvatar(null)} style={{ position: "absolute", top: -14, right: -14, width: 32, height: 32, borderRadius: "50%", background: "rgba(255,255,255,0.15)", border: "1.5px solid rgba(255,255,255,0.3)", color: "#fff", cursor: "pointer", fontSize: 18, display: "flex", alignItems: "center", justifyContent: "center" }} title="Close">×</button>
          </div>
          <div style={{ position: "absolute", bottom: 24, color: "rgba(255,255,255,0.5)", fontSize: 12 }}>Click anywhere to close</div>
        </div>
      )}

      {/* ── Avatar upload modal ── */}
      {uploadTarget && (
        <div style={{ position: "fixed", inset: 0, zIndex: 900, background: "rgba(0,0,0,0.45)", display: "flex", alignItems: "center", justifyContent: "center" }} onClick={closeUploadModal}>
          <div style={{ background: "var(--card)", borderRadius: 16, padding: "28px 32px", width: 400, boxShadow: "var(--shadow-lg)" }} onClick={e => e.stopPropagation()}>
            <div style={{ fontWeight: 700, fontSize: 16, marginBottom: 4 }}>Update Avatar</div>
            <div style={{ fontSize: 13, color: "var(--ink-soft)", marginBottom: 20 }}>{uploadTarget.fullName}</div>

            {/* Drop zone */}
            <div
              onDragOver={e => { e.preventDefault(); setUploadDrag(true); }}
              onDragLeave={() => setUploadDrag(false)}
              onDrop={e => { e.preventDefault(); setUploadDrag(false); handleUploadFile(e.dataTransfer.files[0]); }}
              onClick={() => uploadInputRef.current?.click()}
              style={{ width: "100%", height: 160, borderRadius: 12, cursor: "pointer", border: `2px dashed ${uploadDrag ? "var(--teal)" : "var(--line)"}`, background: uploadDrag ? "var(--teal-soft)" : "var(--paper-deep)", display: "flex", alignItems: "center", justifyContent: "center", overflow: "hidden", transition: "all .15s", marginBottom: 12, position: "relative" }}
            >
              {uploadPreview ? (
                <img src={uploadPreview} alt="Preview" style={{ width: "100%", height: "100%", objectFit: "contain", borderRadius: 10 }} />
              ) : (
                <div style={{ textAlign: "center", color: "var(--ink-soft)" }}>
                  <svg width={36} height={36} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" style={{ marginBottom: 8 }}>
                    <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4M17 8l-5-5-5 5M12 3v12" />
                  </svg>
                  <div style={{ fontSize: 13 }}>Click or drag &amp; drop</div>
                  <div style={{ fontSize: 11.5, marginTop: 4 }}>jpg, jpeg, png · max 2 MB</div>
                </div>
              )}
            </div>

            {uploadError && <div style={{ color: "var(--clay)", fontSize: 12, marginBottom: 8 }}>{uploadError}</div>}
            {uploadFile && <div style={{ fontSize: 12, color: "var(--teal)", marginBottom: 12 }}>✓ {uploadFile.name}</div>}

            <input ref={uploadInputRef} type="file" accept=".jpg,.jpeg,.png,image/jpeg,image/png" style={{ display: "none" }} onChange={e => handleUploadFile(e.target.files?.[0])} />

            <div style={{ display: "flex", gap: 10, justifyContent: "flex-end" }}>
              <button className="btn btn-ghost" onClick={closeUploadModal} disabled={uploading}>Cancel</button>
              <button className="btn btn-primary" onClick={submitUpload} disabled={!uploadFile || uploading}>
                {uploading ? "Uploading…" : "Save Avatar"}
              </button>
            </div>
          </div>
        </div>
      )}

      <Card eyebrow="User Management" title="All Users"
        right={<input placeholder="Search users…" value={q} onChange={e => setQ(e.target.value)} style={{ border: "1px solid var(--line)", borderRadius: 9, padding: "7px 12px", fontSize: 13, width: 220 }} />}>
        <table className="data-table">
          <thead>
            <tr><th>User</th><th>Username</th><th>Role</th><th>Branch</th><th>Employee</th><th>Created</th><th>Status</th><th>Actions</th></tr>
          </thead>
          <tbody>
            {filtered.map(u => {
              const role = roles.find(r => r.pidGroup === u.pidGroup);
              const rc = role?.roleColor || DEFAULT_ROLE_COLORS[0];
              const ini = (u.fullName || u.username || "?").split(" ").map(s => s[0]).slice(0, 2).join("");
              const isActive = u.isActive === 1;
              return (
                <tr key={u.pidUser}>
                  <td>
                    <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
                      {/* Avatar — click to zoom, camera icon to upload */}
                      <div style={{ position: "relative", flexShrink: 0 }}>
                        {u.avatarUrl ? (
                          <img src={resolveAvatarUrl(u.avatarUrl)} alt={u.fullName} title="Click to zoom"
                            onClick={() => setZoomAvatar({ url: resolveAvatarUrl(u.avatarUrl), name: u.fullName || u.username })}
                            style={{ width: 36, height: 36, borderRadius: "50%", objectFit: "cover", cursor: "zoom-in", border: `2px solid ${rc}44`, boxShadow: "0 2px 8px rgba(0,0,0,0.12)", transition: "transform .15s" }}
                            onMouseEnter={e => e.currentTarget.style.transform = "scale(1.1)"}
                            onMouseLeave={e => e.currentTarget.style.transform = "scale(1)"} />
                        ) : (
                          <div style={{ width: 36, height: 36, borderRadius: "50%", background: rc, color: "#fff", display: "flex", alignItems: "center", justifyContent: "center", fontWeight: 700, fontSize: 12.5, fontFamily: "monospace" }}>{ini}</div>
                        )}
                        {/* Camera overlay button */}
                        {perm.canEdit && (
                          <button
                            title="Update avatar"
                            onClick={() => openUploadModal(u)}
                            style={{ position: "absolute", bottom: -3, right: -3, width: 18, height: 18, borderRadius: "50%", background: "var(--teal)", border: "1.5px solid var(--card)", display: "flex", alignItems: "center", justifyContent: "center", cursor: "pointer", padding: 0 }}>
                            <svg width={9} height={9} viewBox="0 0 24 24" fill="none" stroke="#fff" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
                              <path d="M23 19a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4l2-3h6l2 3h4a2 2 0 0 1 2 2z" /><circle cx="12" cy="13" r="4" />
                            </svg>
                          </button>
                        )}
                      </div>
                      <div>
                        <div style={{ fontWeight: 700, fontSize: 13.5 }}>{u.fullName}</div>
                        <div style={{ fontSize: 11, color: "var(--ink-soft)" }}>{u.email}</div>
                      </div>
                    </div>
                  </td>
                  <td className="mono" style={{ fontWeight: 600 }}>{u.username}</td>
                  <td>
                    {perm.canEdit ? (
                      <select value={u.pidGroup || ""} onChange={e => setRole(u.pidUser, parseInt(e.target.value))}
                        style={{ padding: "5px 8px", borderRadius: 7, border: `1.5px solid ${rc}66`, fontSize: 12.5, background: rc + "15", color: "var(--ink)", cursor: "pointer", fontWeight: 600 }}>
                        {roles.map(r => <option key={r.pidGroup} value={r.pidGroup}>{r.groupName}</option>)}
                      </select>
                    ) : (
                      <span style={{ fontSize: 12.5, fontWeight: 600, color: rc }}>{roles.find(r => r.pidGroup === u.pidGroup)?.groupName || "—"}</span>
                    )}
                  </td>
                  {/* Changeable here, not only at creation. An account made
                      against the wrong branch sees the wrong pharmacy for the
                      rest of its life, and there was no way to correct it. */}
                  <td>
                    {perm.canEdit ? (
                      <select value={u.pidBranch || ""}
                        onChange={e => setBranch(u.pidUser, e.target.value)}
                        style={{ padding: "5px 8px", borderRadius: 7, border: "1.5px solid var(--line)", fontSize: 12.5, background: "var(--paper)", color: "var(--ink)", cursor: "pointer" }}>
                        {!u.pidBranch && <option value="">No branch — cannot see anything</option>}
                        {branches.map(b => <option key={b.id} value={b.id}>{b.name}</option>)}
                      </select>
                    ) : (
                      <span style={{ fontSize: 12.5 }}>{u.branchName || "—"}</span>
                    )}
                  </td>
                  {/* Settable on an existing account, not only at creation.
                      Without this a delivery login made before the link existed
                      — or one whose driver changed — could never be corrected,
                      and that account sees an empty round forever. */}
                  <td>
                    {perm.canEdit ? (
                      <select value={u.pidEmployee || ""}
                        onChange={e => setEmployee(u.pidUser, e.target.value)}
                        style={{ padding: "5px 8px", borderRadius: 7, border: "1.5px solid var(--line)", fontSize: 12, background: "var(--paper)", color: "var(--ink)", cursor: "pointer", maxWidth: 170 }}>
                        <option value="">
                          {role?.groupCode === "ROLE06" ? "⚠ Not linked" : "—"}
                        </option>
                        {employees
                          .filter(e => role?.groupCode !== "ROLE06" || e.empType === "DELIVERY_MAN")
                          .map(e => <option key={e.id} value={e.id}>{e.empName}</option>)}
                      </select>
                    ) : (
                      <span style={{ fontSize: 12.5 }}>{u.employeeName || "—"}</span>
                    )}
                  </td>
                  <td className="mono" style={{ fontSize: 12 }}>{u.createdAt ? new Date(u.createdAt).toLocaleDateString() : "—"}</td>
                  <td><Pill tone={isActive ? "sage" : "clay"}>{isActive ? "Active" : "Inactive"}</Pill></td>
                  <td>
                    {perm.canEdit && (
                      <button className="btn btn-ghost btn-sm" onClick={() => toggleStatus(u.pidUser, u.isActive)} style={{ fontSize: 11 }}>
                        {isActive ? "Deactivate" : "Activate"}
                      </button>
                    )}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
        {filtered.length === 0 && <div className="empty-state">No users found.</div>}
      </Card>
    </>
  );
}

/* ─── User Entry form ────────────────────────────────────────────────────── */
export function UserEntry({ perm = {} }) {
  const { addToast, currentUser, branches } = useApp();
  const [roles, setRoles] = useState([]);
  const [loading, setLoading] = useState(true);
  const [form, setForm] = useState({
    username: "", fullName: "", email: "",
    mobileNo: "", pidRole: null, password: "", status: "Y",
    pidEmployee: "", pidBranch: "",
  });
  const [employees, setEmployees] = useState([]);
  const [errors, setErrors]     = useState({});
  const [saving, setSaving]     = useState(false);
  // Avatar state
  const [avatarFile, setAvatarFile]       = useState(null);   // File object
  const [avatarPreview, setAvatarPreview] = useState(null);   // local blob URL
  const [avatarError, setAvatarError]     = useState("");
  const fileInputRef = useRef(null);

  const set = (k, v) => setForm(f => ({ ...f, [k]: v }));

  useEffect(() => {
    loadRoles();
  }, []);

  const loadRoles = async () => {
    setLoading(true);
    try {
      // Employees load alongside the roles rather than when a delivery role is
      // picked: the field has to be populated the moment it appears, or the
      // first thing the user sees is an empty picker that fills in a beat later.
      const [data, staff] = await Promise.all([
        rbacService.getAllRoles(false),
        getEmployees({ size: 200 }).catch(() => ({ items: [] })),
      ]);
      setRoles(data);
      setEmployees(staff?.items ?? []);
      if (data.length > 0 && !form.pidRole) {
        setForm(f => ({ ...f, pidRole: data.find(r => r.groupCode !== 'ROLE01')?.pidGroup || data[0].pidGroup }));
      }
    } catch (error) {
      addToast(`Failed to load roles: ${error.message}`, "error");
    } finally {
      setLoading(false);
    }
  };

  /* ── Employee link ──────────────────────────────────────────────────────
     An invoice records its driver as an employee id, not a user id, so a
     delivery login that points at no staff record can be signed into but
     matches no work. The server refuses to create one; this makes the field
     visible and required before the request is sent, rather than after. */
  const selectedRole = roles.find(r => r.pidGroup === form.pidRole);
  const isDeliveryRole = selectedRole?.groupCode === "ROLE06";

  // Only delivery men may hold a delivery login — the server enforces it, and
  // offering the rest would be offering choices that get rejected. Whether an
  // employee already has a login is left to the server: it knows, and it says
  // which account holds them, which a filtered-out row could not.
  const linkableEmployees = useMemo(
    () => employees.filter(e => !isDeliveryRole || e.empType === "DELIVERY_MAN"),
    [employees, isDeliveryRole]);

  // ── Avatar file selection ──
  const handleAvatarChange = (file) => {
    setAvatarError("");
    if (!file) return;
    const allowed = ["image/jpeg", "image/jpg", "image/png"];
    if (!allowed.includes(file.type)) {
      setAvatarError("Only jpg, jpeg, png files are allowed");
      return;
    }
    if (file.size > 2 * 1024 * 1024) {
      setAvatarError("Image must be under 2 MB");
      return;
    }
    setAvatarFile(file);
    // Create local preview URL
    const url = URL.createObjectURL(file);
    setAvatarPreview(url);
  };

  const clearAvatar = () => {
    if (avatarPreview) URL.revokeObjectURL(avatarPreview);
    setAvatarFile(null);
    setAvatarPreview(null);
    setAvatarError("");
    if (fileInputRef.current) fileInputRef.current.value = "";
  };

  // ── Drag-and-drop ──
  const [dragging, setDragging] = useState(false);
  const onDrop = (e) => {
    e.preventDefault();
    setDragging(false);
    const file = e.dataTransfer.files[0];
    if (file) handleAvatarChange(file);
  };

  // Defaults to the branch of whoever is creating the account — the common
  // case — while leaving it visible and changeable rather than assumed.
  useEffect(() => {
    if (!form.pidBranch && currentUser?.branchId) {
      setForm(f => ({ ...f, pidBranch: String(currentUser.branchId) }));
    }
  }, [currentUser, form.pidBranch]);

  const validate = () => {
    const err = {};
    if (!form.username) err.username = "Username required";
    if (!form.fullName) err.fullName = "Full name required";
    if (!form.email)    err.email    = "Email required";
    if (!form.password) err.password = "Password required";
    if (!form.pidRole)  err.pidRole  = "Role required";
    // Without a branch the account signs in and can read nothing, because
    // every operational screen is scoped to the branch on the session.
    if (!form.pidBranch) err.pidBranch = "Choose the branch this user works at";
    // Delivery staff cannot be matched to an invoice without this, so it is
    // required here rather than left for the server to reject after the fact.
    if (isDeliveryRole && !form.pidEmployee) {
      err.pidEmployee = "Delivery staff must be linked to an employee record";
    }
    setErrors(err);
    return Object.keys(err).length === 0;
  };

  const submit = async () => {
    if (!validate()) return;
    setSaving(true);
    try {
      // 1 — Create the user account
      const created = await rbacService.createUser({
        username:  form.username,
        password:  form.password,
        fullName:  form.fullName,
        email:     form.email,
        phoneNo:   form.mobileNo,
        pidGroup:  form.pidRole,
        // The organisation is taken from the session on the server; the branch
        // is a deliberate choice, because it decides everything this login can
        // see. It used to read currentUser.pidBranch — a field that does not
        // exist on the session user, which is spelled branchId — so the "|| 1"
        // fired every time and every account ever created landed in branch 1.
        pidOrg:    currentUser?.orgId ?? currentUser?.pidOrg ?? 1,
        pidBranch: Number(form.pidBranch),
        pidEmployee: form.pidEmployee ? Number(form.pidEmployee) : null,
      });

      // 2 — Upload avatar if a file was selected
      if (avatarFile && created?.pidUser) {
        try {
          await rbacService.uploadUserAvatar(created.pidUser, avatarFile);
        } catch (avatarErr) {
          // User was created — just warn about avatar
          addToast(`User created but avatar upload failed: ${avatarErr.message}`, "warning");
        }
      }

      addToast(`User "${form.username}" created successfully`);

      // Reset form
      const defaultRole = roles.find(r => r.groupCode !== 'ROLE01')?.pidGroup || roles[0]?.pidGroup;
      setForm({ username: "", fullName: "", email: "", mobileNo: "", pidRole: defaultRole, password: "", status: "Y", pidEmployee: "" });
      setErrors({});
      clearAvatar();
    } catch (error) {
      addToast(`Failed to create user: ${error.message}`, "error");
    } finally {
      setSaving(false);
    }
  };

  if (loading) {
    return <div style={{ padding: 40, textAlign: "center", color: "var(--ink-soft)" }}>Loading...</div>;
  }

  return (
    <Card eyebrow="Create User" title="New User Account">
      {/* ── Avatar upload ── */}
      <div style={{ marginBottom: 20 }}>
        <div style={{ fontSize: 12, fontWeight: 600, color: "var(--ink-soft)", marginBottom: 8, textTransform: "uppercase", letterSpacing: ".06em" }}>
          Profile Picture (optional)
        </div>
        <div style={{ display: "flex", gap: 16, alignItems: "flex-start", flexWrap: "wrap" }}>
          {/* Drop zone */}
          <div
            onDragOver={e => { e.preventDefault(); setDragging(true); }}
            onDragLeave={() => setDragging(false)}
            onDrop={onDrop}
            onClick={() => fileInputRef.current?.click()}
            style={{
              width: 96, height: 96, borderRadius: 12, cursor: "pointer",
              border: `2px dashed ${dragging ? "var(--teal)" : avatarPreview ? "transparent" : "var(--line)"}`,
              background: dragging ? "var(--teal-soft)" : avatarPreview ? "transparent" : "var(--paper-deep)",
              display: "flex", alignItems: "center", justifyContent: "center",
              overflow: "hidden", position: "relative", flexShrink: 0,
              transition: "all .15s",
            }}
          >
            {avatarPreview ? (
              <img src={avatarPreview} alt="Preview"
                style={{ width: "100%", height: "100%", objectFit: "cover", borderRadius: 10 }} />
            ) : (
              <div style={{ textAlign: "center", color: "var(--ink-soft)" }}>
                <svg width={28} height={28} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" style={{ marginBottom: 4 }}>
                  <rect x="3" y="3" width="18" height="18" rx="4" />
                  <circle cx="8.5" cy="8.5" r="1.5" />
                  <path d="M21 15l-5-5L5 21" />
                </svg>
                <div style={{ fontSize: 10, lineHeight: 1.3 }}>Drop or<br />click</div>
              </div>
            )}
          </div>

          <div style={{ flex: 1, minWidth: 180 }}>
            <button type="button" className="btn btn-ghost btn-sm"
              onClick={() => fileInputRef.current?.click()}
              style={{ marginBottom: 8 }}>
              {avatarPreview ? "Change Image" : "Select Image"}
            </button>
            {avatarPreview && (
              <button type="button" className="btn btn-ghost btn-sm"
                onClick={clearAvatar}
                style={{ marginLeft: 8, color: "var(--clay)" }}>
                Remove
              </button>
            )}
            <div style={{ fontSize: 11.5, color: "var(--ink-soft)", lineHeight: 1.6 }}>
              Formats: jpg, jpeg, png<br />
              Max size: 2 MB<br />
              {avatarFile && <span style={{ color: "var(--teal)", fontWeight: 600 }}>✓ {avatarFile.name}</span>}
            </div>
            {avatarError && (
              <div style={{ fontSize: 12, color: "var(--clay)", marginTop: 4 }}>{avatarError}</div>
            )}
          </div>
        </div>
        {/* Hidden file input */}
        <input
          ref={fileInputRef}
          type="file"
          accept=".jpg,.jpeg,.png,image/jpeg,image/png"
          style={{ display: "none" }}
          onChange={e => handleAvatarChange(e.target.files?.[0])}
        />
      </div>

      {/* ── Form fields ── */}
      <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit,minmax(240px,1fr))", gap: "0 24px" }}>
        <Field label="Username *" error={errors.username}>
          <input value={form.username} onChange={e => set("username", e.target.value)} placeholder="e.g. john.doe" />
        </Field>
        <Field label="Full Name *" error={errors.fullName}>
          <input value={form.fullName} onChange={e => set("fullName", e.target.value)} />
        </Field>
        <Field label="Email *" error={errors.email}>
          <input type="email" value={form.email} onChange={e => set("email", e.target.value)} />
        </Field>
        <Field label="Mobile">
          <input value={form.mobileNo || ""} onChange={e => set("mobileNo", e.target.value)} placeholder="017XXXXXXXX" />
        </Field>
        <Field label="Password *" error={errors.password}>
          <input type="password" value={form.password} onChange={e => set("password", e.target.value)} />
        </Field>
        {/* Where this login works, and the only branch it will ever see. */}
        <Field label="Branch *" error={errors.pidBranch}>
          <select value={form.pidBranch || ""}
            onChange={e => set("pidBranch", e.target.value)}
            className={errors.pidBranch ? "field-error" : ""}>
            <option value="">Select Branch</option>
            {branches.map(b => (
              <option key={b.id} value={b.id}>{b.name}</option>
            ))}
          </select>
          <div style={{ fontSize: 11, color: "var(--ink-soft)", marginTop: 4 }}>
            Everything this user sees — sales, stock, purchases, reports — is the
            branch they belong to. They cannot see another.
          </div>
        </Field>

        <Field label="Role *" error={errors.pidRole}>
          <select value={form.pidRole || ""} onChange={e => set("pidRole", parseInt(e.target.value))}>
            <option value="">Select Role</option>
            {roles.map(r => <option key={r.pidGroup} value={r.pidGroup}>{r.groupName}</option>)}
          </select>
        </Field>
        {/* Shown for every role, because any login may belong to a member of
            staff — but starred and validated only for delivery staff, where the
            system genuinely cannot function without it. */}
        <Field label={isDeliveryRole ? "Employee *" : "Employee"} error={errors.pidEmployee}>
          <select value={form.pidEmployee || ""}
            onChange={e => set("pidEmployee", e.target.value)}>
            <option value="">
              {isDeliveryRole ? "Select the delivery man…" : "Not a member of staff"}
            </option>
            {linkableEmployees.map(e => (
              <option key={e.id} value={e.id}>
                {e.empName}{e.empTypeLabel ? ` · ${e.empTypeLabel}` : ""}
                {e.vehicleNo ? ` · ${e.vehicleNo}` : ""}
              </option>
            ))}
          </select>
          <div style={{ fontSize: 11, color: "var(--ink-soft)", marginTop: 4 }}>
            {isDeliveryRole
              ? (linkableEmployees.length === 0
                  ? "No delivery men exist yet — add one in Master Data › Employees first."
                  : "An invoice records its driver as an employee, so this is how they see their own round.")
              : "Optional. Links this login to a staff record."}
          </div>
        </Field>

        <Field label="Status">
          <select value={form.status} onChange={e => set("status", e.target.value)}>
            <option value="Y">Active</option>
            <option value="N">Inactive</option>
          </select>
        </Field>
      </div>

      <div style={{ marginTop: 16 }}>
        <button className="btn btn-primary" onClick={submit}
          disabled={saving || !perm.canCreate}
          title={!perm.canCreate ? "No permission to create users" : undefined}>
          {saving ? "Creating..." : "Create User Account"}
        </button>
      </div>
    </Card>
  );
}

/* ═══ MAIN EXPORT ══════════════════════════════════════════════════════════ */
export default function RBAC() {
  const [tab, setTab] = useState("Permissions");
  const [stats, setStats] = useState({ roles: 0, modules: 0, users: 0 });
  const { currentUser } = useApp();
  const perm = usePermission("rbac");
  const TABS = ["Permissions", "Roles", "Modules & Menus", "Users", ...(perm.canCreate ? ["New User"] : [])];

  useEffect(() => {
    loadStats();
  }, []);

  const loadStats = async () => {
    try {
      const [rolesData, modulesData, usersData] = await Promise.all([
        rbacService.getAllRoles(false),
        rbacService.getAllModules(true),
        rbacService.getAllUsers(currentUser?.pidOrg || 1)
      ]);
      setStats({
        roles: rolesData.length,
        modules: modulesData.filter(m => !m.pidParentModule).length,
        users: usersData.length
      });
    } catch (error) {
      // Silent fail for stats
    }
  };

  return (
    <div className="page">
      <div className="page-head">
        <div>
          <h1>RBAC — Role Based Access Control</h1>
          <div className="page-sub">Manage roles, module permissions, menus with icons, and user accounts.</div>
        </div>
        <div className="flex-gap">
          <Pill tone="teal">{stats.roles} Roles</Pill>
          <Pill tone="amber">{stats.modules} Modules</Pill>
          <Pill tone="sage">{stats.users} Users</Pill>
        </div>
      </div>

      {/* Tab bar */}
      <div style={{ display: "flex", gap: 6, marginBottom: 24, background: "var(--paper)", padding: 5, borderRadius: 12, border: "1px solid var(--line)" }}>
        {TABS.map(t => (
          <button key={t} onClick={() => setTab(t)}
            style={{ flex: 1, padding: "9px 10px", borderRadius: 9, border: "none", cursor: "pointer", fontWeight: 600, fontSize: 13, transition: "all .15s", background: tab === t ? "var(--card)" : "transparent", color: tab === t ? "var(--ink)" : "var(--ink-soft)", boxShadow: tab === t ? "var(--shadow)" : "none", whiteSpace: "nowrap" }}>
            {t}
          </button>
        ))}
      </div>

      {tab === "Permissions"    && <PermissionMatrix />}
      {tab === "Roles"          && <RolesList perm={perm} />}
      {tab === "Modules & Menus" && <MenusModules perm={perm} />}
      {tab === "Users"          && <UsersList perm={perm} />}
      {tab === "New User"       && <UserEntry perm={perm} />}
    </div>
  );
}
