const NAV = [
  { id: "exec", label: "Executive Summary", n: "1" },
  { id: "scope", label: "Business Scope", n: "2" },
  { id: "master", label: "Master Data & Packaging", n: "3" },
  { id: "auth", label: "Authentication & Authorization", n: "4" },
  { id: "numbering", label: "Transaction & Invoice Numbering", n: "5" },
  { id: "stock", label: "Stock Calculation Rules", n: "6" },
  {
    id: "flows", label: "Transaction Rules & Process Flows", n: "7",
    children: [
      { id: "flow-purchase", label: "Purchase & Purchase Return" },
      { id: "flow-sales", label: "Sales & Stock Validation" },
      { id: "flow-return", label: "Sales Return & Status" },
      { id: "flow-req", label: "Smart Requisition" },
      { id: "flow-merge", label: "Invoice Merge" },
    ],
  },
  { id: "cal", label: "Calculation Register", n: "8" },
  { id: "val", label: "Validation Register", n: "9" },
  { id: "fr", label: "Functional Requirements", n: "10" },
  { id: "nfr", label: "Non-Functional Requirements", n: "11" },
  { id: "datamodel", label: "Data Model Notes", n: "12" },
  { id: "risks", label: "Risks & Improvement Priorities", n: "13" },
  { id: "control", label: "Document Control", n: "14" },
];

const SCOPE = [
  ["Organization & access", "Organization, company, branch, users, groups, menus, role-based access control administration.", "Organization, Company, Branch, User, Group, User-Group, Module, Menu, Menu-Group Assignment"],
  ["Master data", "Category, sub-category, unit, generic, medicine, leaf, box, product, customer, supplier, area.", "Product Category, Product Sub-Category, Generic, Medicine, Leaf, Box, Product, Customer, Supplier, Area"],
  ["Purchase", "Purchase entry, purchase return, supplier payment, purchase invoice printing.", "Transaction Master, Transaction Detail, Payment Master"],
  ["Sales", "POS sale, sales invoice, quotation, challan, customer payment, delivery/collection.", "Transaction Master, Transaction Detail, Product Stock View, Delivery Collection"],
  ["Returns", "Sales return against original invoice lines.", "Transaction Master, Transaction Detail (return columns)"],
  ["Payments & ledger", "Customer/supplier payment entry, ledger, accounts reports.", "Payment Master, Transaction Master/Detail"],
  ["Inventory / stock", "Available stock, stock ledger, alerts, stock analysis, out-of-stock report.", "Product Stock View"],
  ["Requisition", "Smart requisition generation, edit/delete.", "Requisition Master, Requisition Detail, temporary requisition working set"],
  ["Invoice merge", "Merge multiple sales invoices into one invoice.", "Transaction Master, Transaction Detail"],
  ["Pricing & reporting", "Sales percentage, dashboards, ledgers, analytical reports.", "Product, Product Stock View, transaction tables"],
];

const PACKAGING_FLOW = [
  { k: "n", t: "Leaf", d: "Leaf Type, No Of PCS (must be > 0), Status" },
  { k: "n", t: "Box", d: "Leaf Code (must reference an active Leaf), Box Size Title, No Of Leaf (must be > 0), Status" },
  { k: "n", t: "Product", d: "Product Name, Company, Unit, Box, MRP, TP, VAT, Opening Qty, Alert Qty, Status" },
  { k: "ok", t: "Derived", d: "PCS per Box = No Of Leaf x No Of PCS" },
];

const MASTER_VALIDATIONS = [
  ["Leaf", "Leaf Type, No Of PCS, Status", "No Of PCS must be greater than zero."],
  ["Box", "Leaf Code, Box Size Title, No Of Leaf, Status", "Box must reference an active leaf. No Of Leaf must be greater than zero."],
  ["Product", "Product Name, Company, Unit, Box, MRP, TP, VAT, Opening Qty, Alert Qty, Status", "Product must have a valid, active package (box/leaf) before it can be sold by box, leaf or PCS. Barcode/SKU must be unique per branch when supplied."],
  ["Category / Sub-category", "Category Name, Sub Category Name, Status", "Sub-category must reference an active parent category."],
  ["Customer / Supplier", "Name, Area, Contact, Status", "Must be active and within the active organization/branch scope before being used in a transaction."],
];

const PRICING_CALCS = [
  "PCS per Box = No Of Leaf x No Of PCS",
  "TP Price = round(MRP Price − (MRP Price x Conversion Percentage / 100), 2)",
  "TP Price With VAT = round(TP Price + (TP Price x VAT Percentage / 100), 2)",
  "IGTA (VAT amount) = round(TP Price x VAT Percentage / 100, 2)",
  "Unit Sales Price = MRP − (MRP x Sales Percentage / 100), maintained separately per BOX, LEAF (STRIP) and PCS",
];

const AUTH_BULLETS = [
  "Internal (staff) login is handled by a dedicated authentication service (Spring Security) backed by the user/credential store.",
  "Active users are authenticated by USER_NAME, PIN/password credential, and an active STATUS flag.",
  "Mobile / customer-facing authentication uses a separate authentication flow against the customer master data.",
  "Super-admin access is decided centrally by a role/permission check in the authentication service.",
  "Non-admin menu and feature visibility is generated from group-to-menu (role-to-permission) assignments.",
];

const AUTH_FLOW = [
  { k: "n", t: "Open login screen", d: "" },
  { k: "n", t: "Enter username / PIN or password", d: "" },
  { k: "n", t: "Validate credentials", d: "Via the authentication service; issue session token / JWT on success" },
  { k: "warn", t: "Invalid credentials", d: "Show login error, stop" },
  { k: "ok", t: "Valid", d: "Initialize session context — organization / company / branch, role" },
  { k: "ok", t: "Super admin", d: "Render full active menu / feature set" },
  { k: "ok", t: "Normal user", d: "Render only role-assigned menu items" },
];

const NUMBERING_BULLETS = [
  "FN_INVOICE_NO_GEN(pTRANSACTION_TYPE) generates invoice numbers as: Transaction Type + Year-Month (RRMM) + 5-digit zero-padded sequence.",
  "The backing sequence SEQ_INVOICE_NO is tracked in SEQUENCE_TRACKER and is reset automatically the first time the year-month changes.",
  "INVOICE_NO together with TRANSACTION_TYPE is enforced unique at the database level (UK_POS_TRANS_MST_INVOICE on POS_TRANSACTION_MST).",
  "Primary keys for transaction and requisition tables are generated automatically by row-level triggers, drawing from their respective sequences.",
];

const STOCK_BULLETS = [
  "VW_PRODUCT_STOCK is the single application stock authority; nothing else should be treated as the source of truth for available quantity.",
  "Stock is derived from POS_TRANSACTION_DTL / POS_TRANSACTION_MST grouped by branch, company, product and expiry date, restricted to finalized (STATUS = 'Y') documents.",
  "Sales entry performs a server-side stock check against VW_PRODUCT_STOCK before any detail row is inserted.",
  "PROC_EXPIRY_WISE_SALES_QTY_DIS allocates the sold quantity across expiry lots strictly in ascending expiry-date order (earliest expiry first, FEFO), splitting a sale across more than one lot when a single lot cannot cover the requested quantity.",
  "Global notification logic flags low-stock and zero-stock products by comparing STOCK_QTY against each product's ALERT_QTY.",
];

const SALES_UNIT_TABLE = [
  ["PCS", "Entered Quantity x 1", "PCS_SALES_PRICE"],
  ["STRIP / LEAF", "Entered Quantity x PCS per Leaf", "STRIP_SALES_PRICE or LEAF_SALES_PRICE"],
  ["BOX", "Entered Quantity x PCS per Box", "SALES_PRICE"],
];

const PURCHASE_FLOW = [
  { k: "n", t: "Open Purchase Entry", d: "Select PU or PR, supplier and date" },
  { k: "n", t: "Select product", d: "Load package price, enter quantity, discount and cost" },
  { k: "n", t: "Calculate PCS", d: "PCS = Box Quantity x PCS per Box, and line total" },
  { k: "n", t: "Create / update transaction", d: "Master created on first valid line; detail inserted or updated for subsequent lines" },
  { k: "n", t: "Recalculate header total", d: "TOTAL_AMOUNT recalculated from all detail rows" },
  { k: "n", t: "Apply invoice discount", d: "Record given amount and due, remarks and status" },
  { k: "ok", t: "Save pending or complete", d: "PU adds / PR reduces PCS stock on completion" },
  { k: "ok", t: "Open Purchase Invoice", d: "For print / reference" },
];

const PURCHASE_CALC = [
  ["Purchase PCS", "Box Quantity x PCS per Box"],
  ["MNP Gross", "Quantity x (TP Price + IGTA) − Quantity x TP Price x TP Percentage / 100"],
  ["MNP Line Total", "round(Gross − Gross x Discount % / 100 − Quantity x Flat Discount, 2)"],
  ["MRP Line Total", "round(Quantity x (MRP − (MRP x Discount % / 100 + Flat Discount)), 2)"],
  ["Purchase Total", "round(Subtotal − Subtotal x Invoice Discount % / 100 − Invoice Discount Amount, 2)"],
  ["Due", "round(Grand Total − Given Amount, 2)"],
];

const PURCHASE_VALIDATIONS = [
  "Supplier, product, transaction date and PU/PR type are mandatory.",
  "Quantity and line total must be greater than zero (matches VAL-03).",
  "Header TOTAL_AMOUNT must be recalculated from detail rows before finalization, never trusted from the client alone.",
  "Only documents with STATUS = 'N' (Pending) can be edited or deleted.",
  "Completed (STATUS = 'Y') documents require a linked purchase return or reversal document, never a direct delete (matches VAL-06 / VAL-08).",
];

const SALES_FLOW = [
  { k: "n", t: "Open New Sales", d: "Select customer and date" },
  { k: "n", t: "Select product with visible stock", d: "Choose sales unit (PCS / STRIP / BOX), enter quantity" },
  { k: "n", t: "Read available stock", d: "From VW_PRODUCT_STOCK; normalize requested quantity to PCS" },
  { k: "warn", t: "Insufficient PCS stock", d: "Reject the line with a stock error, stop" },
  { k: "n", t: "Create sales master", d: "TRANSACTION_TYPE = 'SL', if this is the first line" },
  { k: "n", t: "Allocate quantity", d: "Across expiry lots earliest-first via PROC_EXPIRY_WISE_SALES_QTY_DIS" },
  { k: "n", t: "Insert detail rows", d: "Recalculate Subtotal / Grand Total / Due" },
  { k: "ok", t: "Save", d: "Payment type, discount, received amount, remarks and status" },
  { k: "ok", t: "Open Invoice Report", d: "" },
];

const SALES_TOTALS = [
  "Subtotal = sum of stored line totals.",
  "Grand Total = Subtotal − percentage discount − flat discount + tax + service charge.",
  "Due = Grand Total − Received (Given) Amount; any change/overpayment must be recorded as a positive value only, never a negative due.",
];

const SALES_VALIDATIONS = [
  "Sales requested PCS must not exceed available unexpired PCS (matches VAL-04).",
  "Expiry allocation must total exactly the requested PCS and must execute in the same database transaction as the sale itself, so a failure rolls back both together (matches VAL-05).",
  "Sales price must be defined per BOX, STRIP/LEAF and PCS before the product can be sold in that unit.",
  "Cart service adds, updates and removes cart products for the POS/mobile cart via JSON responses; a scheduled job deletes app carts left unsubmitted for more than one day.",
];

const RETURN_FLOW = [
  { k: "n", t: "Select the completed sales invoice", d: "To return against" },
  { k: "n", t: "Load original detail lines", d: "And remaining returnable PCS per line" },
  { k: "n", t: "Enter return quantity and reason", d: "" },
  { k: "warn", t: "Return exceeds returnable PCS", d: "Reject, stop" },
  { k: "n", t: "Create SR document", d: "Referencing original transaction, detail, customer and lot" },
  { k: "ok", t: "Restore PCS stock", d: "By the same lot, on completion" },
];

const STOCK_POSTING = [
  ["PU (Purchase) and SR (Sales Return)", "Increases PCS stock"],
  ["SL (Sale) and PR (Purchase Return)", "Decreases PCS stock"],
];

const STATUS_TABLE = [
  ["N", "Pending", "Editable, deletable. No stock posting."],
  ["Y", "Complete / received", "Finalized. Stock posting occurs. Not directly deletable."],
  ["D", "Delivered", "Delivery state only — should not overload document status."],
  ["F", "Paid (in some pages)", "Payment state only — should not overload document status."],
  ["C", "Recommended, not consistently implemented", "Cancelled. Never a direct delete of a posted document."],
];

const REQ_FLOW = [
  { k: "n", t: "Open Smart Requisition", d: "" },
  { k: "n", t: "Filter and select products", d: "" },
  { k: "n", t: "Maintain selections", d: "In a temporary requisition working set (SMART_REQ)" },
  { k: "warn", t: "Validate selection", d: "At least one selected product and a supplier is present" },
  { k: "n", t: "Calculate total", d: "By purchase type (MNP or MRP)" },
  { k: "n", t: "Insert requisition master", d: "" },
  { k: "n", t: "Insert requisition detail rows", d: "" },
  { k: "ok", t: "Clear working set", d: "Open Requisition List" },
];

const REQ_BULLETS = [
  "At least one product and a supplier are mandatory before a requisition can be generated.",
  "Total is calculated by the selected purchase type, MNP or MRP, consistent with the purchase calculation rules in section 7.1.",
  "The Requisition List provides requisition edit/delete; the same pending-only edit rule applies.",
];

const MERGE_FLOW = [
  { k: "n", t: "Open Invoice Merge", d: "" },
  { k: "n", t: "Filter customer invoices", d: "" },
  { k: "n", t: "Select multiple unmerged invoices", d: "" },
  { k: "warn", t: "Validate selection", d: "A multi-invoice selection was made" },
  { k: "n", t: "Create new merged sales master", d: "" },
  { k: "n", t: "Copy selected detail rows", d: "Into the new master" },
  { k: "n", t: "Flag source invoices", d: "MERGE_PID_TRAN_MST and IS_MERGE_INVOICE" },
  { k: "ok", t: "Open Invoice Report", d: "" },
];

const MERGE_BULLETS = [
  "Requires two or more selected, unmerged invoices for the same customer.",
  "Original invoices are retained and flagged (MERGE_PID_TRAN_MST) rather than deleted, preserving audit trail.",
];

const CAL_REGISTER = [
  ["CAL-01", "Packaging conversion", "PCS per Box = No Of Leaf x No Of PCS. Reject zero or missing conversion factors."],
  ["CAL-02", "TP price", "TP Price = round(MRP − MRP x Conversion % / 100, 2)."],
  ["CAL-03", "Unit sales price", "Unit Sales Price = MRP − MRP x Sales % / 100; must be defined per BOX, STRIP/LEAF and PCS."],
  ["CAL-04", "Purchase PCS", "Purchase PCS = Box Quantity x PCS per Box; store normalized PCS on the transaction detail row."],
  ["CAL-05", "Sales line total", "Sales Line Total = Input Quantity x Selected Unit Price; store a price snapshot and the line total, not only a reference to current price."],
  ["CAL-06", "Invoice total", "Invoice Total = Subtotal − % discount − flat discount + tax + service charge; calculated on the backend server, never trusted from the client."],
  ["CAL-07", "Due", "Due = Grand Total − Received Amount; overpayment recorded as a positive change amount only."],
  ["CAL-08", "Closing stock", "Closing PCS = Opening + Purchase (PU) + Sales Return (SR) − Sales (SL) − Purchase Return (PR); finalized (STATUS='Y') movements only."],
  ["CAL-09", "Box-equivalent sold", "Box Equivalent Sold = Sold PCS / PCS per Box; uses finalized SL lines only."],
  ["CAL-10", "Return ratio", "Return Ratio = Return Amount / Sales Amount x 100; return zero when sales amount is zero."],
  ["CAL-11", "VAT / IGTA", "IGTA = round(TP Price x VAT % / 100, 2); TP Price With VAT = round(TP Price + IGTA, 2)."],
  ["CAL-12", "MNP gross & line total", "MNP Gross = Qty x (TP Price + IGTA) − Qty x TP Price x TP % / 100; Line Total = round(Gross − Gross x Discount % / 100 − Qty x Flat Discount, 2)."],
  ["CAL-13", "MRP line total", "MRP Line Total = round(Qty x (MRP − (MRP x Discount % / 100 + Flat Discount)), 2)."],
  ["CAL-14", "Invoice number", "Invoice No = Transaction Type + RRMM (year-month) + 5-digit zero-padded sequence, reset each new month via SEQUENCE_TRACKER."],
];

const VAL_REGISTER = [
  ["VAL-01", "Product, leaf, box, supplier, customer and price records must be active and within the active organization/company/branch scope."],
  ["VAL-02", "Product barcode and SKU must be unique per branch when supplied."],
  ["VAL-03", "Quantity must be greater than zero on every transaction line; price and totals must never be negative."],
  ["VAL-04", "Sales requested PCS must not exceed available unexpired PCS, checked against VW_PRODUCT_STOCK in normalized PCS terms regardless of entry unit."],
  ["VAL-05", "Expiry-lot allocation must total exactly the requested PCS and must run in the same database transaction as the sale."],
  ["VAL-06", "Only documents with STATUS = Pending (N) can be edited or deleted."],
  ["VAL-07", "Return PCS must not exceed net returnable PCS (original sold PCS minus prior completed returns) from the original document."],
  ["VAL-08", "Completed / finalized documents must be reversed through a linked return or reversal document, never deleted directly."],
  ["VAL-09", "All totals and stock values must be recalculated on the server before finalization, even if a total was passed from the client/session item."],
  ["VAL-10", "Role, organization, company and branch access must be checked in every data query and process, not only at login."],
  ["VAL-11", "Purchase entry requires supplier, product, transaction date and a valid PU/PR type."],
  ["VAL-12", "Header TOTAL_AMOUNT / Grand Total is recalculated from detail rows on every save, not accumulated incrementally on the client."],
  ["VAL-13", "Smart requisition requires at least one selected product and a supplier before it can be generated."],
  ["VAL-14", "Invoice merge requires two or more selected, unmerged invoices for the same customer before merging."],
  ["VAL-15", "INVOICE_NO must remain unique per TRANSACTION_TYPE (enforced by database unique constraint)."],
  ["VAL-16", "API / server processes must return specific status and message values to the UI; generic catch-all handlers that mask the underlying error must be avoided in new development."],
];

const FR_REGISTER = [
  ["FR-01", "The system shall authenticate internal users by username, PIN/password credential and an active status flag."],
  ["FR-02", "The system shall restrict menu visibility by super-admin flag or by role/group menu assignment."],
  ["FR-03", "The system shall maintain organization, company and branch setup."],
  ["FR-04", "The system shall maintain medicine/product master data including generic, category, unit, leaf and box packaging."],
  ["FR-05", "The system shall maintain customers, suppliers and areas."],
  ["FR-06", "The system shall support purchase invoice entry with discount, payment and printing."],
  ["FR-07", "The system shall support sales invoice entry with server-side stock validation and expiry-wise (FEFO) allocation."],
  ["FR-08", "The system shall prevent sales line insertion when available PCS stock is insufficient, regardless of the entry unit used."],
  ["FR-09", "The system shall support customer/supplier payment entry and ledger reporting."],
  ["FR-10", "The system shall support quotation, challan, invoice and printable report generation."],
  ["FR-11", "The system shall support sales returns linked to original transactions with returnable-quantity enforcement."],
  ["FR-12", "The system shall support stock ledger, stock alert, available stock and out-of-stock reporting from the product stock view."],
  ["FR-13", "The system shall support smart requisition generation and requisition edit/delete workflows."],
  ["FR-14", "The system shall support merging multiple unmerged sales invoices into one invoice while preserving an audit trail on the source invoices."],
  ["FR-15", "The system shall provide sales performance dashboards and analytical reports."],
  ["FR-16", "The system shall support delivery and collection tracking distinct from document completion status."],
  ["FR-17", "The system shall generate unique, sequential, month-scoped invoice numbers per transaction type."],
];

const NFR_BULLETS = [
  "The application shall be implemented using Java Spring Boot for backend services, React for the frontend/UI, and Oracle or MySQL as the relational database.",
  "Transaction invoice numbers shall remain unique per transaction type at the database constraint level.",
  "All stock-sensitive operations shall be validated server-side, independent of any client-supplied total.",
  "Menu access shall be generated dynamically from role/permission tables, never hard-coded per user.",
  "API processes shall return structured JSON status and message values to the UI.",
  "Reports shall use organization and branch context carried in the authenticated session/security context.",
  "Credential comparison shall use a salted hash rather than plain-text comparison.",
  "Error handling shall surface specific, actionable messages instead of generic catch-all handlers.",
  "Price and package changes shall be versioned with effective dates rather than creating disconnected new product records.",
];

const DATAMODEL_BULLETS = [
  "POS_TRANSACTION_MST is the shared master table for purchase, sales, quotation, challan, return and merge workflows, distinguished by TRANSACTION_TYPE (PU, PR, SL, SR, and related quotation/challan types).",
  "POS_TRANSACTION_DTL stores product lines and expiry-level movement detail, including RETURN_QTY and a return linkage column (PID_TRAN_DTL_RETURN).",
  "POS_TRANSACTION_MST enforces a unique constraint on (INVOICE_NO, TRANSACTION_TYPE).",
  "The Requisition Master and Requisition Detail tables are dedicated to smart requisition and are populated through a temporary requisition working set (SMART_REQ) during entry.",
  "VW_PRODUCT_STOCK is the canonical stock projection used by sales entry and all stock reporting screens.",
  "Audit columns (inserted/updated date & by) and primary keys are populated through row-level triggers across virtually every master and transaction table.",
  "MERGE_PID_TRAN_MST and IS_MERGE_INVOICE on POS_TRANSACTION_MST implement invoice merge traceability.",
];

const RISK_FINDINGS = [
  "Credential comparison in the existing implementation compares PIN/password values directly; hashing logic exists in the code but is disabled.",
  "Several server processes use generic catch-all error handling, which can hide underlying data errors from support staff and users.",
  "Some totals and stock values are passed through page/session items, so mandatory server-side re-validation (VAL-09) must never be bypassed.",
  "Legacy export metadata from the current implementation should be reviewed and discarded during migration; behavior must be re-verified after each module is reimplemented on the target platform.",
];

const RISK_CRITICAL = [
  "Normalize all transaction quantity to PCS. The current UI supports PCS, STRIP and BOX entry while the stock view aggregates a single quantity field — this is the root cause of the stock-comparison risk in section 6.",
  "Define precisely what each stored sales price represents (PCS, STRIP or BOX) and ensure package conversion is never applied twice across entry, calculation and printing.",
];

const RISK_HIGH = [
  "Separate document status, delivery status and payment status. The current implementation overloads a single STATUS column with values Y, N, P, D, F and S with overlapping meanings.",
  "Wrap price validation, stock validation, expiry-lot allocation, detail insert, stock-movement posting and finalization in a single database transaction (e.g. a Spring @Transactional service method) so a failure at any step rolls back the whole sale.",
  "Replace plain-text credential comparison with salted-hash comparison.",
];

const RISK_MEDIUM = [
  "Move product price/package changes to an effective-dated history model instead of creating a new product record per change.",
  "Replace generic catch-all exception handling with specific, loggable error handling.",
];

const DOC_CONTROL = [
  ["Title", "Friends Pharma — Consolidated Business SRS"],
  ["Prepared by", "Md Touhidur Rahman"],
  ["Date", "09 August 2026"],
  ["Target platform", "Java Spring Boot (backend), React (frontend), Oracle / MySQL (database)"],
  ["Source documents", "Visual SRS Process Flow document, F110 Visual SRS Process Flow document, database schema export, working prototype"],
  ["Basis", "Static analysis of the existing implementation's business logic and database schema (tables, sequences, triggers, functions and procedures), restated for a Java Spring Boot / React / Oracle-MySQL target platform."],
  ["Status", "Consolidated draft — supersedes the two individual source SRS drafts for business-rule reference."],
];

/* ---------------------------------------------------------------------- */
/* PRIMITIVES                                                              */
/* ---------------------------------------------------------------------- */

const esc = v => String(v ?? "").replace(/[&<>"']/g, c => ({
  "&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"
}[c]));

function chip(text,tone="amber"){return `<span class="chip ${tone}">${esc(text)}</span>`}
function bullets(items){return `<ul class="bullets">${items.map(x=>`<li>${esc(x)}</li>`).join("")}</ul>`}
function prose(x){return `<p class="prose">${esc(x)}</p>`}
function sub(x){return `<h3 class="sub">${esc(x)}</h3>`}
function note(title,items){
  const a=Array.isArray(items)?items:[items];
  return `<div class="note"><div class="box-title">${esc(title)}</div>${a.map(x=>`<p>${esc(x)}</p>`).join("")}</div>`;
}
function warn(x){return `<div class="warn"><div class="box-title">Known implementation risk</div><p>${esc(x)}</p></div>`}
function flow(steps){
  return `<div class="flow">${steps.map(s=>`
    <div class="step"><div class="dot ${s.k}"></div><div class="flow-card ${s.k}">
      <div class="flow-title ${s.k}">${esc(s.t)}</div>
      ${s.d?`<div class="flow-desc">${esc(s.d)}</div>`:""}
    </div></div>`).join("")}</div>`;
}
function table(head,rows,widths=[]){
  return `<div class="table-wrap"><table><thead><tr>${head.map((h,i)=>`<th ${widths[i]?`style="width:${widths[i]}"`:""}>${esc(h)}</th>`).join("")}</tr></thead>
  <tbody>${rows.map(r=>`<tr>${r.map(c=>`<td>${esc(c)}</td>`).join("")}</tr>`).join("")}</tbody></table></div>`;
}
function register(rows,columns,key,label){
  return `<div class="register"><div class="search">
    <input id="${key}-search" placeholder="Search ${label.toLowerCase()} register…">
    <span class="count" id="${key}-count">${rows.length} / ${rows.length} entries</span>
  </div><div id="${key}-table"></div></div>`;
}
function registerRows(rows,columns){
  return table(columns,rows);
}
function section(id,n,title,body){
  return `<section id="${id}"><div class="section-head"><div class="section-num">${n}</div><h2 class="section-title">${esc(title)}</h2></div><div class="section-rule"></div>${body}</section>`;
}

const sections = [];

sections.push(section("exec","01","Executive Summary",
  prose("Friends Pharma is a branch-aware pharmacy point-of-sale and inventory management application.")+
  prose("This document consolidates two prior SRS drafts — the Visual SRS Process Flow document and the F110 Visual SRS Process Flow document — the existing database schema export, and the working prototype into a single standardized Business SRS, restated for a target implementation on Java Spring Boot (backend), React (frontend) and Oracle/MySQL (database). It restates every business rule under one numbering scheme, resolves overlaps between the two drafts, and adds the conditions and validations identified from the existing database triggers, functions and views but only partially described in the original drafts.")+
  note("Core business rule",[
    "PCS (piece) is the stock-keeping unit. Leaf (strip) and Box are entry and display units only.",
    "Every purchase, sale, purchase return and sales return must be converted to PCS before stock is checked or posted.",
    "Closing PCS = Opening PCS + Purchase PCS + Sales Return PCS − Sales PCS − Purchase Return PCS.",
    "The product stock view is the single authoritative source; it aggregates transaction records by branch, company, product and expiry for finalized (STATUS = 'Y') transactions only."
  ])
));

sections.push(section("scope","02","Business Scope",
  prose("Consolidated module list from both source SRS drafts, restated as functional modules for the target platform together with the core data entities identified from schema analysis.")+
  table(["Business area","Function","Core data entities"],SCOPE,["18%","42%","40%"])
));

sections.push(section("master","03","Master Data & Packaging",
  prose("Packaging is a strict three-level hierarchy: Leaf defines pieces (PCS) per leaf; Box defines leaves per box; Product references one active box and inherits both conversion factors. This hierarchy is what every purchase, sales and stock calculation converts through.")+
  sub("Packaging hierarchy")+flow(PACKAGING_FLOW)+
  sub("Master data validations")+table(["Master","Key fields","Business rule"],MASTER_VALIDATIONS,["16%","34%","50%"])+
  sub("Implemented pricing calculations")+bullets(PRICING_CALCS)+
  note("Data-quality control gap","The current implementation creates a new product record whenever MRP or the box/package changes, rather than versioning price. A standardized implementation should keep one stable product identifier and maintain effective-dated price and package history so historical invoices remain accurate.")
));

sections.push(section("auth","04","Authentication & Authorization",
  bullets(AUTH_BULLETS)+sub("Login and menu access flow")+flow(AUTH_FLOW)+
  warn("Security gap confirmed by static analysis of the existing implementation: credentials were compared as plain text, with hashing logic present but disabled. The target Java Spring Boot implementation must use salted password hashing (e.g. BCrypt) and issue signed session tokens; this must be verified before production hardening sign-off.")+
  note("VAL-SEC-01","Role, organization, company and branch access must be checked in every API call and data query, not only at login.")
));

sections.push(section("numbering","05","Transaction & Invoice Numbering",bullets(NUMBERING_BULLETS)));

sections.push(section("stock","06","Stock Calculation Rules",
  bullets(STOCK_BULLETS)+sub("Stock equation")+
  note("Closing balance","Closing PCS = Opening PCS + Purchase PCS + Sales Return PCS − Sales PCS − Purchase Return PCS")+
  sub("Sales unit conversion")+table(["Sales unit","PCS conversion","Price field used"],SALES_UNIT_TABLE,["20%","40%","40%"])+
  warn("Critical implementation issue confirmed in both source drafts: the current sales screen can compare displayed stock against entered quantity even when the entry unit (PCS/STRIP/BOX) differs from the unit the displayed stock is expressed in. The authoritative server-side check must always normalize the requested quantity to PCS and compare PCS to PCS before allowing the sale.")
));

sections.push(section("flows","07","Transaction Rules & Process Flows",
  prose("Five core workflows, each shown as a dispensing sequence with its calculation and validation rules.")+
`<div id="flow-purchase" class="flow-section">${sub("7.1 Purchase & Purchase Return (PU / PR)")}
${prose("Purchase (PU) increases PCS stock; Purchase Return (PR) decreases it. Both share the same transaction master/detail tables, distinguished by transaction type.")}
${flow(PURCHASE_FLOW)}${sub("Purchase calculations")}${table(["Calculation","Formula"],PURCHASE_CALC,["26%","74%"])}${sub("Purchase validations")}${bullets(PURCHASE_VALIDATIONS)}</div>
<div id="flow-sales" class="flow-section">${sub("7.2 Sales (SL) & Stock Validation")}${flow(SALES_FLOW)}${sub("Sales totals")}${bullets(SALES_TOTALS)}${sub("Sales validations")}${bullets(SALES_VALIDATIONS)}</div>
<div id="flow-return" class="flow-section">${sub("7.3 Sales Return (SR) & Status Handling")}${flow(RETURN_FLOW)}${note("Return arithmetic",[
"Returnable PCS = Original Sold PCS − Total Completed Prior Return PCS.",
"New return PCS must be ≤ Returnable PCS (matches VAL-07). The return must link to the original sales invoice, customer, product and expiry lot wherever possible.",
"Return Ratio = Return Amount / Sales Amount x 100; return zero when sales amount is zero."
])}${sub("Stock posting direction")}${table(["Transaction type","Stock effect"],STOCK_POSTING,["50%","50%"])}${sub("Status standardization")}${prose("Both source drafts and the schema use a single status column for multiple purposes. This SRS recommends separating document status from delivery status and payment status.")}${table(["Status code","Current usage","Required standardized meaning"],STATUS_TABLE,["14%","30%","56%"])}</div>
<div id="flow-req" class="flow-section">${sub("7.4 Smart Requisition")}${flow(REQ_FLOW)}${bullets(REQ_BULLETS)}</div>
<div id="flow-merge" class="flow-section">${sub("7.5 Invoice Merge")}${flow(MERGE_FLOW)}${bullets(MERGE_BULLETS)}</div>`
));

sections.push(section("cal","08","Consolidated Calculation Register",
  prose("Merges the calculation rules from both source SRS drafts under one identifier scheme (CAL-01 to CAL-14).")+
  register(CAL_REGISTER,["ID","Rule","Formula / control"],"cal","Calculation")
));
sections.push(section("val","09","Consolidated Validation Register",
  prose("Merges the mandatory validations from both source SRS drafts and the transaction-rule tables (VAL-01 to VAL-16).")+
  register(VAL_REGISTER,["ID","Validation"],"val","Validation")
));
sections.push(section("fr","10","Functional Requirements",register(FR_REGISTER,["ID","Requirement"],"fr","Requirement")));
sections.push(section("nfr","11","Non-Functional Requirements",bullets(NFR_BULLETS)));
sections.push(section("datamodel","12","Data Model Notes",bullets(DATAMODEL_BULLETS)));
sections.push(section("risks","13","Risks & Improvement Priorities",
  sub("Findings from static analysis")+note("Static analysis findings",RISK_FINDINGS)+
  sub("Critical priority")+bullets(RISK_CRITICAL)+
  sub("High priority")+bullets(RISK_HIGH)+
  sub("Medium priority")+bullets(RISK_MEDIUM)
));
sections.push(section("control","14","Document Control",table(["Field","Value"],DOC_CONTROL,["22%","78%"])));

document.getElementById("content").innerHTML = sections.join("")+
`<footer>Friends Pharma — Consolidated Business SRS · Prepared by Md Touhidur Rahman · v1.0</footer>`;

const sidebar=document.getElementById("sidebar");
sidebar.innerHTML=`<div class="brand"><div class="k">Consolidated Business SRS</div><div class="t">Friends Pharma</div><div class="s">Java Spring Boot · React · Oracle/MySQL</div></div><nav>${
NAV.map(n=>`<div class="nav-main" data-target="${n.id}"><span class="nav-num">${n.n}</span><span>${esc(n.label)}</span></div>${
n.children?`<div>${n.children.map(c=>`<div class="nav-child" data-target="${c.id}">${esc(c.label)}</div>`).join("")}</div>`:""
}`).join("")}</nav>`;

document.querySelectorAll("[data-target]").forEach(el=>{
  el.addEventListener("click",()=>document.getElementById(el.dataset.target)?.scrollIntoView({behavior:"smooth",block:"start"}));
});

function renderRegister(rows,columns,key){
  const holder=document.getElementById(key+"-table");
  holder.innerHTML=registerRows(rows,columns);
  const input=document.getElementById(key+"-search"), count=document.getElementById(key+"-count");
  input.addEventListener("input",()=>{
    const q=input.value.trim().toLowerCase();
    const filtered=q?rows.filter(r=>r.join(" ").toLowerCase().includes(q)):rows;
    holder.innerHTML=registerRows(filtered,columns);
    count.textContent=`${filtered.length} / ${rows.length} entries`;
  });
}
renderRegister(CAL_REGISTER,["ID","Rule","Formula / control"],"cal");
renderRegister(VAL_REGISTER,["ID","Validation"],"val");
renderRegister(FR_REGISTER,["ID","Requirement"],"fr");

const ids=[...NAV.map(n=>n.id),...NAV.flatMap(n=>(n.children||[]).map(c=>c.id))];
const observer=new IntersectionObserver(entries=>{
  entries.forEach(e=>{
    if(!e.isIntersecting)return;
    document.querySelectorAll("[data-target]").forEach(x=>{
      const active=x.dataset.target===e.target.id;
      if(x.classList.contains("nav-main")){
        x.style.color=active?"#fff":"var(--sidebarSoft)";
        x.style.background=active?"rgba(184,117,43,.28)":"transparent";
        x.style.fontWeight=active?"600":"400";
      }else{
        x.style.color=active?"var(--amber)":"rgba(159,176,165,.78)";
        x.style.fontWeight=active?"600":"400";
      }
    });
  });
},{rootMargin:"-14% 0px -70% 0px",threshold:0});
ids.forEach(id=>{const e=document.getElementById(id);if(e)observer.observe(e)});
