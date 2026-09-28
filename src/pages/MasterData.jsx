/**
 * MasterData.jsx — REAL API CONNECTED
 *
 * All 16 master-data entities wired to the backend via masterDataService.js.
 * UI structure, layout, styles, groups unchanged — only the data layer replaced.
 *
 * Backend gaps (Division, District, Thana = no endpoints; Area/Leaf/Box/Dept/Desig = no PUT):
 *   - Gap entities show a read-only banner; create/edit buttons are hidden.
 *   - "Delete" = soft deactivate via PUT status=N where supported.
 *
 * Field-name mapping (response DTO → UI):
 *   All responses use `id` as PK (not pidXxx).
 *   Name fields: org/company/branch/category/subcategory/generic/dept/desig → `name`
 *                leaf → `type`, employee → `empName`
 */
import React, { useState, useEffect, useCallback, useMemo } from "react";
import { Card, Pill, Field, Modal } from "../components/UI.jsx";
import { useApp } from "../lib/AppContext.jsx";
import { usePermission } from "../lib/usePermission.js";
import {
  getOrganizations, createOrganization, updateOrganization, deactivateOrganization,
  getCompanies,     createCompany,     updateCompany,     deactivateCompany,
  getBranches,      createBranch,      updateBranch,      deactivateBranch,
  getRegions,       createRegion,      updateRegion,
  getDivisions,     createDivision,    updateDivision,
  getDistricts,     createDistrict,    updateDistrict,
  getThanas,        createThana,       updateThana,
  getAreas,         createArea,        updateArea,
  getCategories,    createCategory,    updateCategory,    deactivateCategory,
  getSubCategories, createSubCategory, updateSubCategory, deactivateSubCategory,
  getGenerics,      createGeneric,     updateGeneric,     deactivateGeneric,
  getProductCompanies, createProductCompany, updateProductCompany, deactivateProductCompany,
  getLeafs,         createLeaf,        updateLeaf,
  getBoxes,         createBox,         updateBox,
  getUnits,         createUnit,
  getDepartments,   createDepartment,  updateDepartment,
  getDesignations,  createDesignation, updateDesignation,
  getCustomers,     createCustomer,    updateCustomer,    toggleCustomerStatus,
  getSuppliers,     createSupplier,    updateSupplier,    toggleSupplierStatus,
  getEntityCapabilities,
} from "../services/masterDataService.js";
import { money } from "../lib/calc.js";
import { useConfirm } from "../lib/ConfirmProvider.jsx";
import apiClient from "../lib/apiClient.js";
import uploadUrl from "../lib/uploadUrl.js";
import { useToast } from "../hooks/useToast.js";

/* ── icon helper ── */
const Ic = ({ d, size = 15 }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor"
    strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round">
    {Array.isArray(d) ? d.map((p, i) => <path key={i} d={p} />) : <path d={d} />}
  </svg>
);

/* ── Spinner ── */
const Spinner = () => (
  <div style={{ padding: 40, textAlign: "center", color: "var(--ink-soft)" }}>
    <svg width={28} height={28} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"
      style={{ animation: "spin 1s linear infinite" }} strokeLinecap="round">
      <path d="M12 2v4M12 18v4M4.93 4.93l2.83 2.83M16.24 16.24l2.83 2.83M2 12h4M18 12h4M4.93 19.07l2.83-2.83M16.24 7.76l2.83-2.83" />
    </svg>
    <div style={{ marginTop: 8, fontSize: 13 }}>Loading…</div>
  </div>
);

/* ── Gap banner for entities with no backend endpoints ── */
const GapBanner = ({ message }) => (
  <div style={{ padding: "14px 18px", background: "var(--amber-soft)", border: "1px solid var(--amber)",
    borderRadius: 10, fontSize: 13, color: "var(--amber-deep)", marginBottom: 12 }}>
    <b>⚠ Backend endpoint not yet available:</b> {message}
    <div style={{ fontSize: 11.5, marginTop: 4, opacity: 0.8 }}>
      See <code>docs/master-data-integration-notes.md</code> for details and the fix required.
    </div>
  </div>
);

/* ═══════════════════════════════════════════════════════════════════════════
   ENTITY CONFIG
   Each entry describes: id, label, group, service fns, column definitions.
   cols drive both the table headers and the form fields.
   Dependent loaders (cascading dropdowns) are declared per-entity.
═══════════════════════════════════════════════════════════════════════════ */

/**
 * Returns the full entity config array.
 * Receives live data bags (lookups) so forms can populate cascading selects.
 */
/**
 * The branch form, in one place.
 *
 * Master Data edits a branch here; the tenant console edits the same branch
 * from Tenants > Manage. Two copies of this list would drift the moment a
 * column was added to one of them — which is how the tenant console ended up
 * unable to set a region, a trade type or the coordinates at all.
 */
export function branchCols({ orgs = [], companies = [], regions = [] }) {
  return [
        { key: "name",      label: "Branch Name",  type: "text",   required: true },
        { key: "orgId",     label: "Organization", type: "select", source: orgs,      displayKey: "name", required: true },
        { key: "companyId", label: "Company",      type: "select", source: companies, displayKey: "name", required: true,
          dependsOn: "orgId", filterFn: (items, vals) => items.filter(c => !vals.orgId || c.orgId === vals.orgId) },
        { key: "regionId",  label: "Region",       type: "select", source: regions,   displayKey: "name" },
        { key: "address",   label: "Address",      type: "text" },
        { key: "phone",     label: "Phone",        type: "text" },
        { key: "email",     label: "Email",        type: "text" },
        /* Shown in this pharmacy's own sidebar and browser tab. Uploaded rather
           than typed, and saved on its own — see LogoField. */
        { key: "logoUrl",   label: "Logo",         type: "logo" },
        /* [V103] The online store (docs/ecommerce). Off until switched on here;
           customers find the pharmacy by distance, so the coordinates are
           required before it can be switched on. */
        { key: "ecomEnabled",          label: "Online store",          type: "select", options: ["N", "Y"], hint: "Y lists this pharmacy on the e-commerce platform" },
        /* [V112] Who this pharmacy may sell to online. Every existing branch is
           RETAIL, which is what they were already doing — a branch becomes a
           wholesale pharmacy because somebody said so here, never by default.
           Wholesale needs the region above it: wholesale customers are only
           shown the pharmacies in their own approved region. */
        { key: "branchTradeType",      label: "Sells to",              type: "select", options: ["RETAIL", "WHOLESALE", "BOTH"], hint: "Retail = nearby shoppers · Wholesale = wholesale accounts in this region · Both = either" },
        { key: "drugLicenseNo",        label: "Drug licence no.",      hint: "The number on the licence you were shown." },
        { key: "branchVerified",       label: "Licence seen",          type: "select", options: ["N", "Y"], hint: "Y only once someone has actually seen this pharmacy's drug licence. The storefront shows a Verified badge to customers for Y and nothing for N." },
        /* One control for the pair, with a lookup from the address above — see
           CoordsField. Typing them separately from a map is where a digit gets
           dropped and a Dhaka branch lands in the Bay of Bengal. */
        { key: "latitude",             label: "Coordinates",           type: "coords" },
        { key: "ecomDeliveryRadiusKm", label: "Delivery radius (km)",  type: "text", placeholder: "e.g. 8" },
        { key: "ecomMinOrderAmount",   label: "Minimum order (৳)",     type: "text", placeholder: "e.g. 500" },
        { key: "ecomOpeningHours",     label: "Opening hours",         type: "text", placeholder: "e.g. 9:00–23:00, Fri 15:00–23:00" },
        { key: "ecomDescription",      label: "About (online)",        type: "text", placeholder: "A line customers see on the pharmacy card" },
        /* [V108] What the pharmacy charges to deliver. None is what every branch
           did before this existed, so an untouched branch keeps delivering free. */
        { key: "ecomDeliveryFeeType",  label: "Delivery charge",       type: "select", options: ["NONE", "FLAT", "DISTANCE"], hint: "None = free · Flat = one fee · Distance = base + per km" },
        { key: "ecomDeliveryFlatFee",  label: "Flat fee (৳)",          type: "text", placeholder: "e.g. 60 — also the base for Distance" },
        { key: "ecomDeliveryFeePerKm", label: "Per km (৳)",            type: "text", placeholder: "e.g. 15 — Distance only" },
        { key: "ecomDeliveryFreeAbove", label: "Free above (৳)",       type: "text", placeholder: "e.g. 2000 — leave empty for no free threshold" },
        { key: "status",    label: "Status",       type: "status" },
  ];
}

function buildEntities(lookups) {
  const {
    orgs = [], companies = [], branches = [], regions = [],
    divisions = [], districts = [],
    categories = [], leafs = [], boxes = [],
    productCompanies = [], departments = [], designations = [],
  } = lookups;

  return [
    /* ── ORG STRUCTURE ─────────────────────────────────────────────── */
    {
      id: "organization", label: "Organization", group: "Org Structure", page: "orgstructure",
      serviceKey: "organization",
      listFn:   ()          => getOrganizations(),
      createFn: (data)      => createOrganization(data),
      updateFn: (id, data)  => updateOrganization(id, data),
      deactivateFn: (id) => deactivateOrganization(id),
      cols: [
        { key: "name",      label: "Org Name",   type: "text",   required: true },
        { key: "shortName", label: "Short Name", type: "text" },
        { key: "phone",     label: "Phone",      type: "text" },
        { key: "email",     label: "Email",      type: "text" },
        { key: "address",   label: "Address",    type: "text" },
        { key: "status",    label: "Status",     type: "status" },
      ],
    },
    {
      id: "company", label: "Company", group: "Org Structure", page: "orgstructure",
      serviceKey: "company",
      listFn:   (ctx)       => getCompanies(ctx?.currentUser?.orgId || 1),
      createFn: (data, ctx) => createCompany({ ...data, orgId: data.orgId || ctx?.currentUser?.orgId || 1 }),
      updateFn: (id, data, ctx) => updateCompany(id, { ...data, orgId: data.orgId || ctx?.currentUser?.orgId || 1 }),
      deactivateFn: (id, row, ctx) => deactivateCompany(id, row.orgId || ctx?.currentUser?.orgId || 1),
      cols: [
        { key: "name",      label: "Company Name", type: "text",   required: true },
        { key: "orgId",     label: "Organization", type: "select", source: orgs,      displayKey: "name", required: true },
        { key: "shortName", label: "Short Name",   type: "text" },
        { key: "phone",     label: "Phone",        type: "text" },
        { key: "email",     label: "Email",        type: "text" },
        { key: "address",   label: "Address",      type: "text" },
        { key: "status",    label: "Status",       type: "status" },
      ],
    },
    {
      id: "branch", label: "Branch", group: "Branches", page: "master",
      serviceKey: "branch",
      /* Opening a pharmacy is the platform's act, not a pharmacy's. A Branch
         Administrator maintains the one they are in — the address, the phone,
         the logo — and creating another is not part of that job.

         The role alone is the whole test: ROLE01 exists only at the platform
         branch, because PermissionServiceImpl refuses it anywhere else. And
         the server refuses the POST as well, so this hides a button that would
         only ever come back 403 rather than being the thing that stops it. */
      createAllowed: (user) => user?.roleCode === "ROLE01",
      listFn:   (ctx)       => getBranches(ctx?.currentUser?.orgId || 1),
      createFn: (data, ctx) => createBranch({ ...data, orgId: data.orgId || ctx?.currentUser?.orgId || 1 }),
      updateFn: (id, data, ctx) => updateBranch(id, { ...data, orgId: data.orgId || ctx?.currentUser?.orgId || 1 }),
      deactivateFn: (id, row, ctx) => deactivateBranch(id, { ...row, orgId: row.orgId || ctx?.currentUser?.orgId || 1 }),
      tableDisplay: r => (
        /* The mark sits beside the name rather than in a column of its own: the
           table shows only the first few supporting fields, so a logo column
           would be dropped — and a picture identifies a row faster next to what
           it names anyway. */
        <div className="md-branch-row">
          <span className="td-logo md-logo-sm">
            {r.logoUrl
              ? <img src={uploadUrl(r.logoUrl)} alt="" />
              : <span className="td-logo-initial" aria-hidden="true">
                  {(r.name || "?").trim().charAt(0).toUpperCase() || "?"}
                </span>}
          </span>
          <div style={{ minWidth: 0 }}>
            <b>{r.name}</b>
            <div style={{ fontSize: 11, color: "var(--ink-soft)" }}>
              {orgs.find(o => o.id === r.orgId)?.name || "—"} ›{" "}
              {companies.find(c => c.id === r.companyId)?.name || "—"}
            </div>
          </div>
        </div>
      ),
      cols: branchCols({ orgs, companies, regions }),
    },

    /* ── GEOGRAPHY ──────────────────────────────────────────────────── */
    {
      id: "region", label: "Region", group: "Geography", page: "orgstructure",
      serviceKey: "region",
      listFn:   ()         => getRegions(),
      createFn: (data)     => createRegion(data.name),
      updateFn: (id, data) => updateRegion(id, data.name),
      deactivateFn: null,
      cols: [
        { key: "name", label: "Region Name", type: "text", required: true },
      ],
    },
    {
      id: "division", label: "Division", group: "Geography", page: "orgstructure",
      serviceKey: "division",
      listFn:   () => getDivisions(),
      createFn: (data) => createDivision({ ...data, pidRegion: data.regionId ?? data.pidRegion }),
      updateFn: (id, data) => updateDivision(id, { ...data, pidRegion: data.regionId ?? data.pidRegion }),
      deactivateFn: null,
      // Show division name + region name in the table primary cell
      tableDisplay: r => (
        <div>
          <b>{r.name}</b>
          {r.regionName && (
            <div style={{ fontSize: 11, color: "var(--ink-soft)" }}>
              {r.regionName}
            </div>
          )}
        </div>
      ),
      cols: [
        { key: "name",      label: "Division Name", type: "text",   required: true },
        { key: "nameBn",    label: "Name (Bangla)", type: "text" },
        // key "regionId" matches the response DTO field so edit modal pre-fills correctly
        { key: "regionId",  label: "Region",        type: "select", source: regions, displayKey: "name", required: true },
      ],
    },
    {
      id: "district", label: "District", group: "Geography", page: "orgstructure",
      serviceKey: "district",
      listFn:   () => getDistricts(),
      createFn: (data) => createDistrict(data),
      updateFn: (id, data) => updateDistrict(id, data),
      deactivateFn: null,
      tableDisplay: r => (
        <div>
          <b>{r.name}</b>
          {r.divisionName && (
            <div style={{ fontSize: 11, color: "var(--ink-soft)" }}>
              {r.divisionName}
            </div>
          )}
        </div>
      ),
      cols: [
        { key: "name",       label: "District Name",  type: "text",   required: true },
        { key: "nameBn",     label: "Name (Bangla)",  type: "text" },
        { key: "divisionId", label: "Division",       type: "select", source: divisions, displayKey: "name", required: true },
      ],
    },
    {
      id: "thana", label: "Thana", group: "Geography", page: "orgstructure",
      serviceKey: "thana",
      listFn:   () => getThanas(),
      createFn: (data) => createThana(data),
      updateFn: (id, data) => updateThana(id, data),
      deactivateFn: null,
      tableDisplay: r => (
        <div>
          <b>{r.name}</b>
          {r.districtName && (
            <div style={{ fontSize: 11, color: "var(--ink-soft)" }}>
              {r.districtName}
            </div>
          )}
        </div>
      ),
      cols: [
        { key: "name",       label: "Thana Name",    type: "text",   required: true },
        { key: "nameBn",     label: "Name (Bangla)", type: "text" },
        { key: "districtId", label: "District",      type: "select", source: districts, displayKey: "name", required: true },
      ],
    },
    {
      id: "area", label: "Area", group: "Geography", page: "orgstructure",
      serviceKey: "area",
      listFn:   (ctx, vals) => getAreas(vals?.filterBranchId),
      createFn: (data, ctx) => createArea({ ...data, branchId: data.branchId || ctx?.currentUser?.branchId }),
      updateFn: (id, data, ctx) => updateArea(id, { ...data, branchId: data.branchId || ctx?.currentUser?.branchId }),
      deactivateFn: null,
      cols: [
        { key: "name",     label: "Area Name", type: "text",   required: true },
        { key: "branchId", label: "Branch",    type: "select", source: branches, displayKey: "name", required: true },
      ],
    },

    /* ── PRODUCTS ───────────────────────────────────────────────────── */
    {
      id: "category", label: "Category", group: "Products", page: "master",
      serviceKey: "category",
      listFn:   (ctx)       => getCategories(ctx?.currentUser?.orgId || null),
      createFn: (data)      => createCategory(data),
      updateFn: (id, data)  => updateCategory(id, data),
      deactivateFn: (id)    => deactivateCategory(id),
      cols: [
        { key: "name",   label: "Category Name", type: "text",   required: true },
        { key: "status", label: "Status",        type: "status" },
      ],
    },
    {
      id: "subcategory", label: "Sub-Category", group: "Products", page: "master",
      serviceKey: "subcategory",
      listFn:   (ctx)       => getSubCategories(null, ctx?.currentUser?.orgId || null),
      createFn: (data)      => createSubCategory(data),
      updateFn: (id, data)  => updateSubCategory(id, data),
      deactivateFn: (id)      => deactivateSubCategory(id),
      cols: [
        { key: "name",       label: "Sub-Category", type: "text",   required: true },
        { key: "categoryId", label: "Category",     type: "select", source: categories, displayKey: "name", required: true },
        { key: "status",     label: "Status",       type: "status" },
      ],
    },
    {
      id: "generic", label: "Generic", group: "Products", page: "master",
      serviceKey: "generic",
      listFn:   (ctx)       => getGenerics(ctx?.currentUser?.orgId || null),
      createFn: (data)      => createGeneric(data),
      updateFn: (id, data)  => updateGeneric(id, data),
      deactivateFn: (id)    => deactivateGeneric(id),
      cols: [
        { key: "name",        label: "Generic Name", type: "text",   required: true },
        { key: "description", label: "Description",  type: "text" },
        /* Manufacturer is deliberately not offered here. A generic is the drug —
           paracetamol is paracetamol whoever makes it — and the maker belongs to
           the product, which already carries its own Company. Nobody had ever
           filled this in: all 27 generics have POS_PID_COMPANY null.

           The column and its mapping are left in place, so any value already
           stored is kept and the API is unchanged. This removes the field from
           the form and the list, not the data. */
        { key: "status",      label: "Status",       type: "status" },
      ],
    },
    {
      id: "productcompany", label: "Product Company", group: "Products", page: "master",
      serviceKey: "productcompany",
      listFn:   (ctx)       => getProductCompanies(ctx?.currentUser?.orgId || null),
      createFn: (data)      => createProductCompany(data),
      updateFn: (id, data)  => updateProductCompany(id, data),
      deactivateFn: (id)    => deactivateProductCompany(id),
      cols: [
        { key: "name",     label: "Company Name", type: "text", required: true },
        { key: "mobileNo", label: "Mobile",       type: "text" },
        { key: "email",    label: "Email",        type: "text" },
        { key: "address",  label: "Address",      type: "text" },
        { key: "status",   label: "Status",       type: "status" },
      ],
    },

    /* ── PACKAGING ──────────────────────────────────────────────────── */
    {
      id: "leaf", label: "Leaf/Strip", group: "Packaging", page: "master",
      serviceKey: "leaf",
      listFn:   (ctx)       => getLeafs(ctx?.currentUser?.orgId || null),
      createFn: (data)      => createLeaf({ type: data.type, noOfPcs: data.noOfPcs }),
      updateFn: (id, data)  => updateLeaf(id, { type: data.type, noOfPcs: data.noOfPcs, status: data.status }),
      deactivateFn: null,
      cols: [
        { key: "type",    label: "Leaf Type",    type: "text",   required: true },
        { key: "noOfPcs", label: "PCS per Leaf", type: "number", required: true },
        { key: "status",  label: "Status",       type: "status" },
      ],
    },
    {
      id: "box", label: "Box", group: "Packaging", page: "master",
      serviceKey: "box",
      listFn:   (ctx)       => getBoxes(ctx?.currentUser?.orgId || null),
      createFn: (data)      => createBox({ leafId: data.leafId, title: data.title, noOfLeaf: data.noOfLeaf }),
      updateFn: (id, data)  => updateBox(id, { leafId: data.leafId, title: data.title, noOfLeaf: data.noOfLeaf, status: data.status }),
      deactivateFn: null,
      cols: [
        { key: "title",    label: "Box Title",      type: "text",   required: true },
        { key: "leafId",   label: "Leaf/Strip",     type: "select", source: leafs, displayKey: "type", required: true },
        { key: "noOfLeaf", label: "Leaves per Box", type: "number", required: true },
        { key: "status",   label: "Status",         type: "status" },
      ],
    },
    {
      id: "unit", label: "Unit", group: "Packaging", page: "master",
      serviceKey: "unit",
      listFn:   (ctx)  => getUnits(ctx?.currentUser?.orgId || null),
      createFn: (data) => createUnit(data),
      updateFn: null,
      deactivateFn: null,
      cols: [
        { key: "name",   label: "Unit Name", type: "text",   required: true },
        { key: "status", label: "Status",    type: "status" },
      ],
    },

    /* ── HR ─────────────────────────────────────────────────────────── */
    {
      id: "department", label: "Department", group: "HR", page: "hrmaster",
      serviceKey: "department",
      listFn:   ()             => getDepartments(),
      createFn: (data)         => createDepartment(data),
      updateFn: (id, data)     => updateDepartment(id, { name: data.name, status: data.status }),
      deactivateFn: null,
      cols: [
        { key: "name",   label: "Department Name", type: "text",   required: true },
        { key: "status", label: "Status",          type: "status" },
      ],
    },
    {
      id: "designation", label: "Designation", group: "HR", page: "hrmaster",
      serviceKey: "designation",
      listFn:   ()             => getDesignations(),
      createFn: (data)         => createDesignation(data),
      updateFn: (id, data)     => updateDesignation(id, { name: data.name, description: data.description, status: data.status }),
      deactivateFn: null,
      cols: [
        { key: "name",        label: "Designation Name", type: "text",   required: true },
        { key: "description", label: "Description",      type: "text" },
        { key: "status",      label: "Status",           type: "status" },
      ],
    },
    /* The employee list left here in V87: the HR Employees screen enters and
       edits a record in full, photograph included, and two forms over one row
       is how the two quietly disagree. Department and Designation moved to
       the HR Master page (page: "hrmaster") the same day. */

    /* ── PARTIES ────────────────────────────────────────────────────── */
    {
      id: "customer", label: "Customer", group: "Parties", page: "master",
      serviceKey: "customer",
      listFn:   (ctx) => getCustomers({ size: 200 }).then(r => r.items ?? []),
      createFn: (data) => createCustomer(data),
      updateFn: (id, data) => updateCustomer(id, data),
      deactivateFn: (id) => toggleCustomerStatus(id),
      // Normalize response fields → form keys for edit pre-fill
      // Response: { id, name, type, email, mobileNo, branchId, areaId, address, openingBal, creditLimit, status }
      // Form keys: customerName, customerType, emailAddress, mobileNo, branchId, ...
      editNormalize: (row) => ({
        ...row,
        customerName: row.customerName ?? row.name,
        customerType: row.customerType ?? row.type,
        emailAddress: row.emailAddress ?? row.email,
      }),
      tableDisplay: r => (
        <div>
          <b>{r.name}</b>
          <div style={{ fontSize: 11, color: "var(--ink-soft)" }}>
            {r.mobileNo && <span className="mono">{r.mobileNo}</span>}
            {r.type && <span style={{ marginLeft: 6 }}>· {r.type}</span>}
          </div>
        </div>
      ),
      cols: [
        { key: "customerName", label: "Customer Name", type: "text",   required: true },
        { key: "mobileNo",     label: "Mobile No *",   type: "text",   required: true },
        // Exactly the values CK_CUSTOMER_TYPE accepts. The list used to offer
        // "Retail" and "Walk-in", which the database rejected on save — a walk-in
        // sale is a Retailer, which is what onboarding already records.
        { key: "customerType", label: "Type",          type: "select", options: ["Retailer", "Wholesaler", "Hospital", "Pharmacy", "Clinic", "Special"] },
        { key: "emailAddress", label: "Email",         type: "text" },
        { key: "address",      label: "Address",       type: "text" },
        { key: "city",         label: "City",          type: "text" },
        { key: "creditLimit",  label: "Credit Limit",  type: "money" },
        { key: "openingBal",   label: "Opening Bal.",  type: "money" },
        { key: "status",       label: "Status",        type: "status" },
      ],
    },
    {
      id: "supplier", label: "Supplier", group: "Parties", page: "master",
      serviceKey: "supplier",
      listFn:   (ctx) => getSuppliers({ size: 200 }).then(r => r.items ?? []),
      createFn: (data) => createSupplier(data),
      updateFn: (id, data) => updateSupplier(id, data),
      deactivateFn: (id) => toggleSupplierStatus(id),
      // Response: { id, name, email, mobileNo, branchId, companyId, address, openingBal, creditLimit, status }
      // Form keys: supplierName, emailAddress, mobileNo, branchId, companyId, address, ...
      editNormalize: (row) => ({
        ...row,
        supplierName: row.supplierName ?? row.name,
        emailAddress: row.emailAddress ?? row.email,
      }),
      tableDisplay: r => (
        <div>
          <b>{r.name}</b>
          <div style={{ fontSize: 11, color: "var(--ink-soft)" }}>
            {r.mobileNo && <span className="mono">{r.mobileNo}</span>}
            {r.branchName && <span style={{ marginLeft: 6 }}>· {r.branchName}</span>}
          </div>
        </div>
      ),
      cols: [
        { key: "supplierName",      label: "Supplier Name",       type: "text",   required: true },
        { key: "mobileNo",          label: "Mobile",              type: "text" },
        { key: "companyId",         label: "Mfr. Company",        type: "select", source: productCompanies, displayKey: "name" },
        { key: "emailAddress",      label: "Email",               type: "text" },
        { key: "address",           label: "Address",             type: "text" },
        { key: "responsiblePerson", label: "Contact Person",      type: "text" },
        { key: "creditLimit",       label: "Credit Limit",        type: "money" },
        { key: "openingBal",        label: "Opening Bal.",        type: "money" },
        { key: "status",            label: "Status",              type: "status" },
      ],
    },
  ];
}


/**
 * Tab order. Which of these a page actually shows is decided by the entities
 * it carries — a page with no HR entity gets no HR tab — so this is the order
 * they appear in, not a promise that all of them exist.
 */
const GROUP_ORDER = ["Org Structure", "Geography", "Branches", "Products", "Packaging", "HR", "Parties"];

/* The heading, per door. One engine, three screens. */
const PAGE_COPY = {
  master:       { title: "Master Data",   subtitle: "Manage all organization, product, packaging and party master records." },
  orgstructure: { title: "Org Structure", subtitle: "Organisations, companies and the geography they trade in." },
  hrmaster:     { title: "HR Master",     subtitle: "Departments and designations — the lists every staff record points at." },
};

/* ═══════════════════════════════════════════════════════════════════════════
   FORM FIELD
═══════════════════════════════════════════════════════════════════════════ */
/**
 * The pharmacy's logo, shown and replaced in place.
 *
 * <p>Uploaded rather than typed, so it does not travel with the rest of the
 * form: the file goes straight to its own endpoint and the row's URL is
 * updated from the reply. That is why it saves on its own and does not wait for
 * the Save button — an image is not a field, and pretending it is would mean
 * carrying a file through a JSON payload.
 *
 * <p><b>Disabled until the branch exists.</b> The endpoint is addressed by
 * branch id, and a branch being created has none yet. Saying so beats a button
 * that fails, and the logo is a second, smaller decision anyway.
 */
/**
 * Latitude and longitude, with a lookup from the address already typed.
 *
 * Customers find a pharmacy by distance, so these two numbers decide whether
 * it appears at all — and typing them by hand from a map is where a digit gets
 * dropped and a Dhaka branch lands in the Bay of Bengal.
 *
 * The lookup asks **OpenStreetMap's public Nominatim service**. Two things
 * follow from that, and both are deliberate:
 *
 *   - It only runs when somebody presses the button. Nothing is sent while
 *     typing, and the address leaves this machine only on that press.
 *   - The answer is a suggestion, not a commitment. The candidates are listed
 *     with what Nominatim thinks each one is, and a person picks. An address
 *     that resolves to the wrong town is obvious in the label and is simply
 *     not chosen.
 *
 * No key, no account, and it is rate-limited to roughly one call a second —
 * fine for the handful of times a pharmacy is set up, and not something to put
 * in a loop.
 */
function CoordsField({ col, value, onChange, allValues, error }) {
  const [busy, setBusy] = useState(false);
  const [hits, setHits] = useState(null);
  const [note, setNote] = useState(null);

  const lat = allValues?.latitude ?? "";
  const lng = allValues?.longitude ?? "";

  /* What we can tell the geocoder: the address, and the branch name, which is
     often the only thing that distinguishes two shops on the same road. */
  const query = [allValues?.address, allValues?.name].filter(Boolean).join(", ").trim();

  const s = {
    border: `1.5px solid ${error ? "var(--clay)" : "var(--line)"}`,
    borderRadius: 9, padding: "9px 12px", fontSize: 13.5,
    background: "var(--paper)", color: "var(--ink)", outline: "none", width: "100%",
  };

  const look = async () => {
    if (!query) { setNote("Type the address above first."); setHits(null); return; }
    setBusy(true); setNote(null); setHits(null);
    try {
      const url = "https://nominatim.openstreetmap.org/search?format=json&limit=5&addressdetails=1&q="
        + encodeURIComponent(query);
      const r = await fetch(url, { headers: { Accept: "application/json" } });
      if (!r.ok) throw new Error(`Lookup failed (${r.status}).`);
      const rows = await r.json();
      if (!Array.isArray(rows) || rows.length === 0) {
        setNote("No match. Try a fuller address — road, area and city.");
      } else {
        setHits(rows);
      }
    } catch (e) {
      /* Offline, blocked, or the service is down. The fields stay typeable. */
      setNote("Could not reach the lookup service. Enter the coordinates by hand.");
    } finally {
      setBusy(false);
    }
  };

  const pick = (h) => {
    onChange({ latitude: Number(h.lat).toFixed(6), longitude: Number(h.lon).toFixed(6) });
    setHits(null);
    setNote(`Set from “${h.display_name}”.`);
  };

  return (
    <div style={{ gridColumn: "1 / -1" }}>
      <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr auto", gap: 10, alignItems: "end" }}>
        <Field label="Latitude" error={error}>
          <input value={lat} placeholder="e.g. 23.8759" style={s}
                 onChange={(e) => onChange({ latitude: e.target.value })} />
        </Field>
        <Field label="Longitude">
          <input value={lng} placeholder="e.g. 90.3795" style={s}
                 onChange={(e) => onChange({ longitude: e.target.value })} />
        </Field>
        <button type="button" className="btn btn-ghost btn-sm" disabled={busy}
                style={{ marginBottom: 2, whiteSpace: "nowrap" }}
                onClick={look}>
          {busy ? "Looking…" : "Find from address"}
        </button>
      </div>

      <div style={{ fontSize: 11.5, color: "var(--ink-soft)", marginTop: 4 }}>
        Customers find this pharmacy by distance, so both are needed before the online
        store can be switched on. The lookup asks OpenStreetMap and only when you press it.
      </div>

      {note && <div style={{ fontSize: 12, color: "var(--ink-soft)", marginTop: 6 }}>{note}</div>}

      {hits && (
        <div style={{ marginTop: 8, border: "1px solid var(--line)", borderRadius: 9, overflow: "hidden" }}>
          {hits.map((h) => (
            <button key={`${h.lat}:${h.lon}`} type="button"
                    onClick={() => pick(h)}
                    style={{ display: "block", width: "100%", textAlign: "left", cursor: "pointer",
                             padding: "9px 12px", fontSize: 12.5, background: "var(--paper)",
                             border: 0, borderTop: "1px solid var(--line)" }}>
              <b style={{ display: "block" }}>{h.display_name}</b>
              <span style={{ color: "var(--ink-soft)" }}>
                {Number(h.lat).toFixed(5)}, {Number(h.lon).toFixed(5)}
                {h.type ? ` · ${h.type.replace(/_/g, " ")}` : ""}
              </span>
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

function LogoField({ col, value, onChange, allValues }) {
  const addToast = useToast();
  const inputRef = React.useRef(null);
  const [busy, setBusy] = React.useState(false);
  const [failed, setFailed] = React.useState(false);

  const branchId = allValues?.id ?? allValues?.pidBranch ?? null;
  const src = !failed && value ? uploadUrl(value) : null;
  const initial = (allValues?.name || "?").trim().charAt(0).toUpperCase() || "?";

  const upload = async (file) => {
    if (!file || !branchId) return;
    setBusy(true);
    try {
      const body = new FormData();
      body.append("file", file);
      const res = await apiClient.upload(`/api/branches/${branchId}/logo`, body);
      setFailed(false);
      onChange(res?.logoUrl ?? null);
      addToast("Logo updated.", "success");
    } catch (e) {
      addToast(e?.message || "Could not upload the logo.", "error");
    } finally {
      setBusy(false);
      if (inputRef.current) inputRef.current.value = "";   // so the same file re-fires
    }
  };

  const remove = async () => {
    if (!branchId) return;
    setBusy(true);
    try {
      await apiClient.delete(`/api/branches/${branchId}/logo`);
      onChange(null);
      addToast("Logo removed.", "success");
    } catch (e) {
      addToast(e?.message || "Could not remove the logo.", "error");
    } finally { setBusy(false); }
  };

  return (
    <Field label={col.label}>
      <div className="md-logo-field">
        <span className="td-logo">
          {src ? <img src={src} alt="" onError={() => setFailed(true)} />
               : <span className="td-logo-initial" aria-hidden="true">{initial}</span>}
        </span>
        <div>
          <input ref={inputRef} type="file" accept="image/png,image/jpeg,image/webp"
                 style={{ display: "none" }}
                 onChange={(e) => upload(e.target.files?.[0])} />
          <div className="td-logo-actions" style={{ marginTop: 0 }}>
            <button type="button" className="btn btn-ghost btn-sm"
                    disabled={busy || !branchId}
                    onClick={() => inputRef.current?.click()}>
              {busy ? "Saving…" : value ? "Change logo" : "Upload logo"}
            </button>
            {value && !busy && (
              <button type="button" className="btn btn-ghost btn-sm" onClick={remove}>Remove</button>
            )}
          </div>
          <div className="td-logo-hint" style={{ marginTop: 6 }}>
            {branchId
              ? "PNG, JPG or WEBP · up to 2 MB · saved immediately"
              : "Save the branch first, then add its logo."}
          </div>
        </div>
      </div>
    </Field>
  );
}

function FormField({ col, value, onChange, error, allValues }) {
  const s = {
    border: `1.5px solid ${error ? "var(--clay)" : "var(--line)"}`,
    borderRadius: 9, padding: "9px 12px", fontSize: 13.5,
    background: "var(--paper)", color: "var(--ink)", outline: "none", width: "100%",
  };
  const v = value ?? "";

  // Not a text input at all: it uploads a file and saves on its own.
  if (col.type === "logo") {
    return <LogoField col={col} value={value} onChange={onChange} allValues={allValues} />;
  }

  // A pair of coordinates, with a lookup from the address typed above.
  if (col.type === "coords") {
    return <CoordsField col={col} value={value} onChange={onChange} allValues={allValues} error={error} />;
  }

  if (col.type === "status") {
    return (
      <Field label={col.label} error={error}>
        <select value={v || "Y"} onChange={e => onChange(e.target.value)} style={s}>
          <option value="Y">Active</option>
          <option value="N">Inactive</option>
        </select>
      </Field>
    );
  }

  if (col.type === "color") {
    return (
      <Field label={col.label} error={error}>
        <div style={{ display: "flex", gap: 8, alignItems: "center" }}>
          <input type="color" value={v || "#2E6A62"} onChange={e => onChange(e.target.value)}
            style={{ width: 44, height: 36, border: "1px solid var(--line)", borderRadius: 8, padding: 2, cursor: "pointer" }} />
          <input type="text" value={v} onChange={e => onChange(e.target.value)} placeholder="#2E6A62" style={{ ...s, width: 110 }} />
          <span style={{ width: 28, height: 28, borderRadius: 6, background: v || "#888", border: "1px solid var(--line)", flexShrink: 0 }} />
        </div>
      </Field>
    );
  }

  if (col.type === "select" && col.source) {
    // Apply cascading filter if declared
    const src = col.filterFn ? col.filterFn(col.source, allValues) : col.source;
    return (
      <Field label={col.label} error={error}>
        <select value={v || ""} onChange={e => onChange(e.target.value === "" ? null : isNaN(e.target.value) ? e.target.value : Number(e.target.value))} style={s}>
          <option value="">— Select —</option>
          {src.map(x => <option key={x.id} value={x.id}>{x[col.displayKey]}</option>)}
        </select>
      </Field>
    );
  }

  if (col.type === "select" && col.options) {
    return (
      <Field label={col.label} error={error}>
        <select value={v || ""} onChange={e => onChange(e.target.value)} style={s}>
          <option value="">— Select —</option>
          {col.options.map(o => <option key={o}>{o}</option>)}
        </select>
      </Field>
    );
  }

  return (
    <Field label={col.label} error={error}>
      <input
        type={col.type === "date" ? "date" : (col.type === "number" || col.type === "money") ? "number" : "text"}
        step={col.type === "money" ? "0.01" : undefined}
        value={v}
        placeholder={col.placeholder || col.label}
        onChange={e => onChange((col.type === "number" || col.type === "money") ? Number(e.target.value) : e.target.value)}
        style={s}
      />
    </Field>
  );
}

/* ═══════════════════════════════════════════════════════════════════════════
   ENTRY MODAL
═══════════════════════════════════════════════════════════════════════════ */
export function EntryModal({ entity, mode, row, onClose, onSave, saving, backendErrors }) {
  const [form, setForm] = useState(mode === "edit" ? { ...row } : { status: "Y" });
  const [errors, setErrors] = useState({});

  // Surface backend validation errors into field-level errors
  useEffect(() => {
    if (backendErrors && typeof backendErrors === "object") {
      setErrors(prev => ({ ...prev, ...backendErrors }));
    }
  }, [backendErrors]);

  /* A field normally sets its own key. One sets two — the coordinates come as
     a pair from a single lookup — so an object patch is merged as given. */
  const set = (key, val) => setForm(f => (
    val && typeof val === "object" && !Array.isArray(val)
      ? { ...f, ...val }
      : { ...f, [key]: val }
  ));

  const validate = () => {
    const err = {};
    entity.cols.forEach(c => {
      if (c.required && (form[c.key] == null || form[c.key] === "")) {
        err[c.key] = `${c.label} is required`;
      }
    });
    setErrors(err);
    return Object.keys(err).length === 0;
  };

  const cols = entity.cols;
  const wide = cols.length > 6;
  const half = Math.ceil(cols.length / 2);

  return (
    <Modal
      title={`${mode === "edit" ? "Edit" : "New"} ${entity.label}`}
      onClose={onClose}
      width={wide ? 860 : 620}
      footer={
        <>
          <button className="btn btn-ghost" onClick={onClose} disabled={saving}>Cancel</button>
          <button className="btn btn-primary" disabled={saving}
            onClick={() => { if (validate()) onSave(form); }}>
            {saving ? "Saving…" : mode === "edit" ? "Save Changes" : `Create ${entity.label}`}
          </button>
        </>
      }
    >
      {wide ? (
        <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: "0 24px" }}>
          <div>{cols.slice(0, half).map(c => (
            <FormField key={c.key} col={c} value={form[c.key]} onChange={v => set(c.key, v)} error={errors[c.key]} allValues={form} />
          ))}</div>
          <div>{cols.slice(half).map(c => (
            <FormField key={c.key} col={c} value={form[c.key]} onChange={v => set(c.key, v)} error={errors[c.key]} allValues={form} />
          ))}</div>
        </div>
      ) : (
        cols.map(c => (
          <FormField key={c.key} col={c} value={form[c.key]} onChange={v => set(c.key, v)} error={errors[c.key]} allValues={form} />
        ))
      )}
    </Modal>
  );
}

/* ═══════════════════════════════════════════════════════════════════════════
   ENTITY TABLE — list + search + action buttons
═══════════════════════════════════════════════════════════════════════════ */
function EntityTable({ entity, perm, onNew, onEdit, onDeactivate }) {
  const [data, setData]       = useState([]);
  const [loading, setLoading] = useState(true);
  const [q, setQ]             = useState("");
  const { currentUser, addToast } = useApp();
  const caps = getEntityCapabilities(entity.serviceKey);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const result = await entity.listFn({ currentUser });
      // employees returns { items } from unwrapPagination; everything else returns array
      setData(Array.isArray(result) ? result : result.items ?? []);
    } catch (err) {
      addToast(`Failed to load ${entity.label}: ${err.message}`, "error");
      setData([]);
    } finally {
      setLoading(false);
    }
  }, [entity, currentUser]);

  useEffect(() => { load(); }, [load]);

  // Expose reload so parent can trigger after save
  entity._reload = load;

  const filtered = useMemo(() => {
    if (!q.trim()) return data;
    const s = q.toLowerCase();
    return data.filter(r => JSON.stringify(r).toLowerCase().includes(s));
  }, [data, q]);

  // Compute table columns: primary name col + up to 3 supporting cols + status
  const tableCols = useMemo(() => {
    const cols = entity.cols;
    const nc = cols.find(c => ["name", "title", "type", "empName", "empId"].includes(c.key));
    const sc = cols.find(c => c.key === "status");
    const rest = cols.filter(c => c !== nc && c !== sc && !["imgColor", "address", "description"].includes(c.key)).slice(0, 3);
    return [nc, ...rest, sc].filter(Boolean);
  }, [entity]);

  const displayVal = (row, col) => {
    const v = row[col.key];
    if (col.type === "status") {
      const active = v === "Y" || v === "Active";
      return <Pill tone={active ? "sage" : "clay"}>{active ? "Active" : "Inactive"}</Pill>;
    }
    if (col.type === "money") return <span className="mono">{money(v)}</span>;
    // A path is not worth a column; the picture is.
    if (col.type === "logo") {
      return v ? <img src={uploadUrl(v)} alt="" className="md-logo-thumb" /> : "—";
    }
    if (col.type === "select" && col.source) {
      return col.source.find(s => s.id === v)?.[col.displayKey] ?? v ?? "—";
    }
    return v ?? "—";
  };

  return (
    <div>
      {entity.gap && <GapBanner message={entity.gap} />}
      <Card
        eyebrow={entity.group}
        title={`${entity.label} List`}
        right={
          <div className="flex-gap">
            <input
              placeholder="Search…"
              value={q}
              onChange={e => setQ(e.target.value)}
              style={{ border: "1px solid var(--line)", borderRadius: 9, padding: "7px 12px", fontSize: 13, width: 220 }}
            />
            {/* `createAllowed` is an extra gate a few entities set, not a
                replacement for the rights above: absent means the permission
                layer already had the last word. */}
            {perm.canCreate && caps.canCreate && !entity.gap
              && (entity.createAllowed?.(currentUser) ?? true) && (
              <button className="btn btn-primary btn-sm" onClick={onNew}>
                + New {entity.label}
              </button>
            )}
          </div>
        }
      >
        {loading ? <Spinner /> : (
          <>
            <div className="table-wrap">
              <table className="data-table">
                <thead>
                  <tr>
                    <th>{tableCols[0]?.label}</th>
                    {tableCols.slice(1).map(c => <th key={c.key}>{c.label}</th>)}
                    <th style={{ width: 96 }}>Actions</th>
                  </tr>
                </thead>
                <tbody>
                  {filtered.slice(0, 80).map(row => (
                    <tr key={row.id ?? row.divisionId ?? row.districtId ?? row.thanaId}>
                      <td>
                        {entity.tableDisplay
                          ? entity.tableDisplay(row)
                          : <span style={{ fontWeight: 600 }}>{row[tableCols[0]?.key] ?? "—"}</span>}
                      </td>
                      {tableCols.slice(1).map(c => (
                        <td key={c.key}>{displayVal(row, c)}</td>
                      ))}
                      <td>
                        <div className="flex-gap" style={{ gap: 4 }}>
                          {perm.canEdit && caps.canUpdate && !entity.gap && (
                            <button className="icon-btn" style={{ width: 30, height: 30 }} title="Edit"
                              onClick={() => onEdit(row)}>
                              <Ic d="M12 20h9 M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4 12.5-12.5z" />
                            </button>
                          )}
                          {perm.canEdit && caps.canDeactivate && entity.deactivateFn && (
                            <button className="icon-btn" style={{ width: 30, height: 30 }}
                              title={row.status === "N" || row.status === "Inactive" ? "Activate" : "Deactivate"}
                              onClick={() => onDeactivate(row)}>
                              <Ic d={row.status === "N" || row.status === "Inactive"
                                ? "M9 12l2 2 4-4"
                                : "M18 6 6 18M6 6l12 12"} />
                            </button>
                          )}
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            {filtered.length === 0 && (
              <div className="empty-state">No {entity.label} records{q ? ` matching "${q}"` : ""} found.</div>
            )}
            {filtered.length > 80 && (
              <div style={{ padding: "8px 0 0", fontSize: 12.5, color: "var(--ink-soft)" }}>
                Showing 80 of {filtered.length} — refine search to narrow results.
              </div>
            )}
          </>
        )}
      </Card>
    </div>
  );
}

/* ═══════════════════════════════════════════════════════════════════════════
   MAIN EXPORT
═══════════════════════════════════════════════════════════════════════════ */
/**
 * @param page       which slice of the master data this screen shows
 * @param permission  the RBAC route key that governs it
 */
export default function MasterData({ page = "master", permission = "master" }) {
  const confirm = useConfirm();
  const { addToast, currentUser } = useApp();
  const perm = usePermission(permission);

  /* ── shared lookups loaded once for cascading selects ── */
  const [orgs, setOrgs]                     = useState([]);
  const [companies, setCompanies]           = useState([]);
  const [branches, setBranches]             = useState([]);
  const [regions, setRegions]               = useState([]);
  const [divisions, setDivisions]           = useState([]);
  const [districts, setDistricts]           = useState([]);
  const [categories, setCategories]         = useState([]);
  const [leafs, setLeafs]                   = useState([]);
  const [boxes, setBoxes]                   = useState([]);
  const [productCompanies, setProdCompanies]= useState([]);
  const [departments, setDepartments]       = useState([]);
  const [designations, setDesignations]     = useState([]);
  const [lookupsLoaded, setLookupsLoaded]   = useState(false);

  useEffect(() => {
    let active = true;
    async function loadLookups() {
      try {
        const orgId = currentUser?.orgId || null;
        const [o, co, br, re, divs, dists, cats, lf, bx, pc, dept, desig] = await Promise.allSettled([
          getOrganizations(),
          getCompanies(orgId),
          getBranches(orgId),
          getRegions(),
          getDivisions(),
          getDistricts(),
          getCategories(orgId),
          getLeafs(orgId),
          getBoxes(orgId),
          getProductCompanies(orgId),
          getDepartments(),
          getDesignations(),
        ]);
        if (!active) return;
        const val = r => r.status === "fulfilled" ? (Array.isArray(r.value) ? r.value : r.value?.items ?? []) : [];
        setOrgs(val(o));
        setCompanies(val(co));
        setBranches(val(br));
        setRegions(val(re));
        setDivisions(val(divs));
        setDistricts(val(dists));
        setCategories(val(cats));
        setLeafs(val(lf));
        setBoxes(val(bx));
        setProdCompanies(val(pc));
        setDepartments(val(dept));
        setDesignations(val(desig));
      } catch (_) { /* silent — individual errors show per-entity */ }
      finally { if (active) setLookupsLoaded(true); }
    }
    loadLookups();
    return () => { active = false; };
  }, [currentUser]);

  /* ── entity config, rebuilt when lookups arrive ── */
  const ALL_ENTITIES = useMemo(() => buildEntities({
    orgs, companies, branches, regions,
    divisions, districts,
    categories, leafs, boxes, productCompanies,
    departments, designations,
  }), [orgs, companies, branches, regions, divisions, districts, categories, leafs, boxes, productCompanies, departments, designations]);

  /* Only this page's entities. One component serves both screens: the entity
     definitions are the same objects either way, and two copies of a thousand
     lines would drift the first time one of them was corrected. */
  const ENTITIES = useMemo(
    () => ALL_ENTITIES.filter(e => (e.page ?? "master") === page), [ALL_ENTITIES, page]);

  /* Tabs this page actually has, in the shared order. */
  const GROUPS = useMemo(
    () => GROUP_ORDER.filter(g => ENTITIES.some(e => e.group === g)), [ENTITIES]);

  /* ── navigation state ── */
  const [activeGroup, setActiveGroup] = useState(null);
  const [entityId,    setEntityId]    = useState(null);

  /* Settled once the entities are known rather than hard-coded, because the
     first group differs per page and a stale selection from the other page
     would show an empty screen. */
  useEffect(() => {
    if (!ENTITIES.length) return;
    const stillValid = ENTITIES.some(e => e.id === entityId);
    if (stillValid) return;
    const first = ENTITIES[0];
    setActiveGroup(first.group);
    setEntityId(first.id);
  }, [ENTITIES, entityId]);

  const entity = ENTITIES.find(e => e.id === entityId);
  const groupEntities = ENTITIES.filter(e => e.group === activeGroup);

  /* ── modal state ── */
  const [modal, setModal]           = useState(null); // { mode: "create"|"edit", row? }
  const [saving, setSaving]         = useState(false);
  const [backendErrors, setBEErrors]= useState(null);

  const openNew  = ()    => { setBEErrors(null); setModal({ mode: "create" }); };
  const openEdit = (row) => {
    setBEErrors(null);
    // Apply entity-level field normalization for edit pre-fill
    const normalizedRow = entity?.editNormalize ? entity.editNormalize(row) : row;
    setModal({ mode: "edit", row: normalizedRow });
  };
  const closeModal = ()  => { setModal(null); setBEErrors(null); };

  const saveRecord = async (form) => {
    if (!entity) return;
    setSaving(true);
    setBEErrors(null);
    try {
      if (modal.mode === "edit") {
        await entity.updateFn(modal.row.id, form, { currentUser });
        addToast(`${entity.label} updated`);
      } else {
        await entity.createFn(form, { currentUser });
        addToast(`${entity.label} created`);
      }
      closeModal();
      // Reload entity list
      if (typeof entity._reload === "function") await entity._reload();
      // Also refresh lookups that feed cascading selects
      refreshLookup(entity.id);
    } catch (err) {
      // Surface backend validation errors (VALIDATION_FAILED sends field-level map in err.data)
      if (err.data && typeof err.data === "object") {
        setBEErrors(err.data);
      }
      addToast(`Failed to save ${entity.label}: ${err.message}`, "error");
    } finally {
      setSaving(false);
    }
  };

  const deactivateRecord = async (row) => {
    if (!entity || !entity.deactivateFn) return;
    const active = row.status !== "N" && row.status !== "Inactive";
    const verb = active ? "deactivate" : "activate";
    if (!(await confirm({
      title: `${active ? "Deactivate" : "Activate"} this ${entity.label}?`,
      body: active
        ? `It stays on file and on past records, but stops being offered when creating anything new.`
        : `It becomes selectable again wherever a ${entity.label.toLowerCase()} can be chosen.`,
      confirmLabel: active ? "Deactivate" : "Activate",
      tone: active ? "danger" : "teal",
    }))) return;
    try {
      await entity.deactivateFn(row.id, row, { currentUser });
      addToast(`${entity.label} ${verb}d`);
      if (typeof entity._reload === "function") await entity._reload();
    } catch (err) {
      addToast(`Failed to ${verb} ${entity.label}: ${err.message}`, "error");
    }
  };

  // Refresh specific lookup bags after a write
  const refreshLookup = async (eid) => {
    const orgId = currentUser?.orgId || null;
    try {
      if (eid === "organization")    getOrganizations().then(setOrgs).catch(() => {});
      if (eid === "company")         getCompanies(orgId).then(setCompanies).catch(() => {});
      if (eid === "branch")          getBranches(orgId).then(setBranches).catch(() => {});
      if (eid === "region")          getRegions().then(setRegions).catch(() => {});
      if (eid === "division")        getDivisions().then(setDivisions).catch(() => {});
      if (eid === "district")        getDistricts().then(setDistricts).catch(() => {});
      if (eid === "category")        getCategories(orgId).then(setCategories).catch(() => {});
      if (eid === "leaf")            getLeafs(orgId).then(setLeafs).catch(() => {});
      if (eid === "box")             getBoxes(orgId).then(setBoxes).catch(() => {});
      if (eid === "productcompany")  getProductCompanies(orgId).then(setProdCompanies).catch(() => {});
      if (eid === "department")      getDepartments().then(setDepartments).catch(() => {});
      if (eid === "designation")     getDesignations().then(setDesignations).catch(() => {});
    } catch (_) {}
  };

  if (!lookupsLoaded) {
    return (
      <div className="page">
        <div className="page-head"><div><h1>Master Data</h1></div></div>
        <Spinner />
      </div>
    );
  }

  return (
    <div className="page">
      <div className="page-head">
        <div>
          <h1>{PAGE_COPY[page]?.title || "Master Data"}</h1>
          <div className="page-sub">{PAGE_COPY[page]?.subtitle || "Manage all organization, product and packaging master records."}</div>
        </div>
      </div>

      {/* ── Group tab bar — not drawn for a page with one group ── */}
      {GROUPS.length > 1 && (
      <div className="tabs" style={{ marginBottom: 0 }}>
        {GROUPS.map(g => (
          <button key={g} className={`tab-btn ${activeGroup === g ? "active" : ""}`}
            onClick={() => {
              setActiveGroup(g);
              const first = ENTITIES.find(e => e.group === g);
              if (first) { setEntityId(first.id); }
            }}>
            {g}
          </button>
        ))}
      </div>
      )}

      {/* ── Entity sidebar + table ── */}
      <div className="split" style={{ marginTop: 16 }}>
        <div className="card card-pad split-list">
          {groupEntities.map(e => (
            <button key={e.id}
              className={`split-item ${entityId === e.id ? "active" : ""}`}
              onClick={() => { setEntityId(e.id); }}>
              <span>{e.label}</span>
              {e.gap && <span style={{ fontSize: 9, background: "var(--amber)", color: "#fff", borderRadius: 3, padding: "1px 4px", marginLeft: 4 }}>GAP</span>}
            </button>
          ))}
        </div>

        <div style={{ flex: 1, minWidth: 0 }}>
          {entity && (
            <EntityTable
              key={entity.id}
              entity={entity}
              perm={perm}
              onNew={openNew}
              onEdit={openEdit}
              onDeactivate={deactivateRecord}
            />
          )}
        </div>
      </div>

      {/* ── Create / Edit modal ── */}
      {modal && entity && !entity.gap && (
        <EntryModal
          entity={entity}
          mode={modal.mode}
          row={modal.row}
          onClose={closeModal}
          onSave={saveRecord}
          saving={saving}
          backendErrors={backendErrors}
        />
      )}
    </div>
  );
}
