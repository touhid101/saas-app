/**
 * productExcel.js — Excel template generation and workbook parsing.
 *
 * Two responsibilities:
 *  1. Build a .xlsx template pre-loaded with the organisation's own master data,
 *     so the person filling it in can see exactly which Generic / Company /
 *     Category / Unit / Box names are valid instead of guessing and failing on
 *     upload.
 *  2. Parse an uploaded workbook into plain rows for POST /api/products/bulk.
 *
 * Parsing here is transport only. Every row is re-validated server-side and the
 * prices are recalculated there — a spreadsheet cannot inject a TP Price any
 * more than the form can.
 */
/**
 * SheetJS is loaded on demand. It is ~450 KB, and Excel import/export is an
 * occasional action on one page — bundling it statically would put that weight
 * on every page load of the whole app.
 */
let _xlsx = null;
async function xlsx() {
  if (!_xlsx) _xlsx = await import('xlsx');
  return _xlsx;
}

/* ═══════════════════════════════════════════════════════════════════════════
   COLUMN CONTRACT
   `key` matches ProductBulkRequest.Row. Order here is the column order in the
   sheet, and is what maps a parsed row back to a field.
   ═══════════════════════════════════════════════════════════════════════════ */

export const IMPORT_COLUMNS = [
  { key: 'productName',          header: 'Product Name *',      width: 30, type: 'text',   required: true },
  { key: 'sku',                  header: 'SKU *',               width: 16, type: 'text',   required: true },
  { key: 'genericName',          header: 'Generic *',           width: 20, type: 'list',   required: true, source: 'generics' },
  { key: 'companyName',          header: 'Company *',           width: 26, type: 'list',   required: true, source: 'companies' },
  { key: 'categoryName',         header: 'Category *',          width: 18, type: 'list',   required: true, source: 'categories' },
  { key: 'subCategoryName',      header: 'Sub-Category',        width: 18, type: 'list',   source: 'subCategories' },
  { key: 'unitName',             header: 'Unit *',              width: 12, type: 'list',   required: true, source: 'units' },
  { key: 'boxName',              header: 'Box',                 width: 16, type: 'list',   source: 'boxes' },
  { key: 'barcode',              header: 'Barcode',             width: 18, type: 'text' },
  { key: 'mrp',                  header: 'MRP *',               width: 12, type: 'number', required: true },
  { key: 'conversionRate',       header: 'Conversion Rate %',   width: 17, type: 'number' },
  { key: 'vatPct',               header: 'VAT %',               width: 10, type: 'number' },
  // Optional since V89, to match the catalogue: a value here is stored as it
  // is; a blank cell is calculated (IGTA = TP x VAT / 100, Buy = TP + IGTA).
  { key: 'igta',                 header: 'IGTA',                width: 10, type: 'number' },
  { key: 'buyPrice',             header: 'Buy Price',           width: 12, type: 'number' },
  { key: 'discountPct',          header: 'Discount %',          width: 12, type: 'number' },
  { key: 'minimumStock',         header: 'Minimum Stock',       width: 15, type: 'number' },
  { key: 'safetyStock',          header: 'Safety Stock',        width: 14, type: 'number' },
  { key: 'reorderLevel',         header: 'Reorder Level',       width: 14, type: 'number' },
  { key: 'maximumStock',         header: 'Maximum Stock',       width: 15, type: 'number' },
  { key: 'batchTracking',        header: 'Batch Tracking',      width: 15, type: 'yesno' },
  { key: 'expiryTracking',       header: 'Expiry Tracking',     width: 15, type: 'yesno' },
  { key: 'prescriptionRequired', header: 'Prescription Req.',   width: 17, type: 'yesno' },
  { key: 'status',               header: 'Status',              width: 11, type: 'yesno' },
];

/**
 * Columns the template deliberately does NOT contain. Named here so the
 * omission reads as intentional rather than forgotten — these are all
 * server-owned, and the parser drops them if a user adds them by hand.
 */
export const SERVER_OWNED_COLUMNS = ['productCode', 'tpPrice', 'noOfPcs'];

/**
 * The demonstration rows the template ships with.
 *
 * Single source of truth for both writing them into the template and
 * recognising them on the way back in, so the two can never drift apart.
 *
 * Detection matches BOTH the SKU and the product name. Matching the `-EX`
 * suffix alone would silently drop a genuine product whose SKU ends that way —
 * an export variant, say — and a bulk import that quietly discards rows is far
 * worse than one that rejects them loudly.
 */
export const TEMPLATE_EXAMPLES = [
  { sku: 'NAPA500-EX', productName: 'Napa 500mg Tablet' },
  { sku: 'SYRUP100-EX', productName: 'Sample Syrup 100ml' },
];

const isTemplateExample = (row) =>
  TEMPLATE_EXAMPLES.some((ex) =>
    String(row.sku ?? '').trim().toUpperCase() === ex.sku.toUpperCase() &&
    String(row.productName ?? '').trim().toUpperCase() === ex.productName.toUpperCase());

/** Header text → field key, tolerant of case, spacing, `*` and punctuation. */
const HEADER_LOOKUP = (() => {
  const map = {};
  const norm = (s) => String(s).toLowerCase().replace(/[^a-z0-9]/g, '');
  IMPORT_COLUMNS.forEach((c) => {
    map[norm(c.header)] = c.key;
    map[norm(c.key)] = c.key;
  });
  // Common alternative spellings people type.
  map[norm('Product')] = 'productName';
  map[norm('Name')] = 'productName';
  map[norm('Manufacturer')] = 'companyName';
  map[norm('Reorder')] = 'reorderLevel';
  map[norm('Min Stock')] = 'minimumStock';
  map[norm('Max Stock')] = 'maximumStock';
  map[norm('VAT')] = 'vatPct';
  map[norm('Prescription')] = 'prescriptionRequired';
  return map;
})();

const normalizeHeader = (h) => HEADER_LOOKUP[String(h).toLowerCase().replace(/[^a-z0-9]/g, '')] || null;

/**
 * Local-time filename stamps. toISOString() is UTC, so a download at 02:00 in
 * Dhaka (UTC+6) would be stamped with the previous day — confusing on a file
 * the user is about to date-sort.
 */
function localStamp(withTime = false) {
  const d = new Date();
  const p = (n) => String(n).padStart(2, '0');
  const date = `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
  return withTime ? `${date}-${p(d.getHours())}${p(d.getMinutes())}` : date;
}

/* ═══════════════════════════════════════════════════════════════════════════
   TEMPLATE DOWNLOAD
   ═══════════════════════════════════════════════════════════════════════════ */

/**
 * Builds and downloads the import template.
 *
 * Three sheets:
 *   Products   — the header row plus two worked example rows
 *   Reference  — every valid master-data value, so names can be copied exactly
 *   Instructions — the rules, including which columns are calculated
 *
 * @param lookups the object returned by getFormLookups()
 */
export async function downloadTemplate(lookups) {
  const XLSX = await xlsx();
  const wb = XLSX.utils.book_new();
  const defaults = lookups?.defaults ?? {};
  const convRate = defaults.conversionRate ?? 25.037;
  const vatPct = defaults.vatPct ?? 17.4;

  /* ── Sheet 1: Products ── */
  const headers = IMPORT_COLUMNS.map((c) => c.header);

  // Worked examples built from the org's real master data, so the sample rows
  // import successfully as-is rather than failing on invented names.
  const g = lookups?.generics?.[0]?.name ?? 'Paracetamol';
  const co = lookups?.companies?.[0]?.name ?? 'Example Pharma';
  const cat = lookups?.categories?.[0]?.name ?? 'Analgesic';
  const unit = lookups?.units?.[0]?.name ?? 'PCS';
  const box = lookups?.boxes?.[0]?.name ?? '';

  // Names and SKUs come from TEMPLATE_EXAMPLES so the parser recognises these
  // rows exactly; editing one without the other would break the round trip.
  const examples = [
    // Positional, one cell per IMPORT_COLUMNS entry. The first example leaves
    // IGTA and Buy Price blank (calculated); the second types both.
    [TEMPLATE_EXAMPLES[0].productName, TEMPLATE_EXAMPLES[0].sku, g, co, cat, '', unit, box, '8801234567001',
      100, convRate, vatPct, '', '', 0, 20, 50, 100, 500, 'Yes', 'Yes', 'No', 'Active'],
    [TEMPLATE_EXAMPLES[1].productName, TEMPLATE_EXAMPLES[1].sku, g, co, cat, '', unit, '', '',
      75, convRate, vatPct, 9.78, 65.97, 0, 5, 10, 25, 200, 'No', 'Yes', 'No', 'Active'],
  ];

  /* ── Sheet 1: Products — headers only, no data ──
     The worked examples live on their own sheet rather than in this one.
     Shipping them here meant a freshly downloaded template looked full of rows
     but imported as empty, because the importer has to discard them — a
     confusing failure that this layout removes rather than explains.
     parseWorkbook still recognises and skips them, so templates downloaded
     before this change keep working. */
  const wsProducts = XLSX.utils.aoa_to_sheet([headers]);
  wsProducts['!cols'] = IMPORT_COLUMNS.map((c) => ({ wch: c.width }));
  wsProducts['!freeze'] = { xSplit: 0, ySplit: 1 };
  // Freeze the header row (SheetJS writes this via !freeze on some targets and
  // via the pane setting on others; both are harmless if ignored).
  wsProducts['!panes'] = [{ ySplit: 1, topLeftCell: 'A2', activePane: 'bottomLeft', state: 'frozen' }];
  XLSX.utils.book_append_sheet(wb, wsProducts, 'Products');

  /* ── Sheet 2: Example — the same columns, filled in, to copy from ── */
  const wsExample = XLSX.utils.aoa_to_sheet([
    ['These rows are for reference only. Type your products into the Products sheet.'],
    [],
    headers,
    ...examples,
  ]);
  wsExample['!cols'] = IMPORT_COLUMNS.map((c) => ({ wch: c.width }));
  XLSX.utils.book_append_sheet(wb, wsExample, 'Example');

  /* ── Sheet 2: Reference ── */
  const col = (arr) => (arr ?? []).map((x) => x.name);
  const generics = col(lookups?.generics);
  const companies = col(lookups?.companies);
  const categories = col(lookups?.categories);
  const units = col(lookups?.units);
  const boxes = (lookups?.boxes ?? []).map((b) =>
    b.noOfPcs ? `${b.name}  (${b.noOfPcs} pcs)` : b.name);
  const subCats = (lookups?.subCategories ?? []).map((s) => {
    const parent = (lookups?.categories ?? []).find((c) => c.id === s.categoryId);
    return parent ? `${s.name}  (under ${parent.name})` : s.name;
  });

  const refHeader = ['Generic', 'Company', 'Category', 'Sub-Category', 'Unit', 'Box'];
  const depth = Math.max(generics.length, companies.length, categories.length,
    subCats.length, units.length, boxes.length, 1);
  const refRows = [];
  for (let i = 0; i < depth; i++) {
    refRows.push([
      generics[i] ?? '', companies[i] ?? '', categories[i] ?? '',
      subCats[i] ?? '', units[i] ?? '', boxes[i] ?? '',
    ]);
  }
  const wsRef = XLSX.utils.aoa_to_sheet([refHeader, ...refRows]);
  wsRef['!cols'] = [{ wch: 24 }, { wch: 28 }, { wch: 20 }, { wch: 30 }, { wch: 14 }, { wch: 24 }];
  XLSX.utils.book_append_sheet(wb, wsRef, 'Reference');

  /* ── Sheet 3: Instructions ── */
  const instructions = [
    ['Product Master — Bulk Import'],
    [],
    ['Type your products into the Products sheet, one per row, below the header.'],
    ['The Products sheet starts empty — that is expected.'],
    [],
    ['Sheets in this file'],
    ['  Products      the sheet you fill in — this is the only one that is imported'],
    ['  Example       the same columns already filled in, to copy the format from'],
    ['  Reference     every valid Generic / Company / Category / Unit / Box name'],
    ['  Instructions  this sheet'],
    [],
    ['Required columns (marked * in the header)'],
    ['  Product Name, SKU, Generic, Company, Category, Unit, MRP'],
    [],
    ['Master-data columns'],
    ['  Generic, Company, Category, Sub-Category, Unit and Box must match a value'],
    ['  on the Reference sheet exactly. Matching ignores case. Only active records'],
    ['  are listed — an inactive one will be rejected.'],
    ['  For Box, enter only the name, e.g. "Box-10x10" and not "Box-10x10  (100 pcs)".'],
    [],
    ['Calculated columns'],
    ['  TP Price is calculated by the server and cannot be supplied. A column'],
    ['  for it has no effect; it is ignored.'],
    ['    TP Price  = MRP - (MRP x Conversion Rate / 100)'],
    ['  IGTA and Buy Price are optional. Leave a cell blank and the server'],
    ['  calculates it; type a value and it is stored exactly as typed.'],
    ['    IGTA      = TP Price x VAT / 100          (when blank)'],
    ['    Buy Price = TP Price + IGTA               (when blank)'],
    ['  A Buy Price left blank beside a typed IGTA is TP Price + that IGTA.'],
    ['  Product Code is generated by the server and cannot be supplied.'],
    ['  No Of Pcs comes from the selected Box and is never stored on the product.'],
    [],
    ['Defaults when a cell is left blank'],
    [`  Conversion Rate %   ${convRate}`],
    [`  VAT %               ${vatPct}`],
    ['  Batch / Expiry / Prescription   No'],
    ['  Status                          Active'],
    ['  Stock levels                    0'],
    [],
    ['Stock level rule'],
    ['  Minimum Stock <= Safety Stock <= Reorder Level <= Maximum Stock'],
    ['  Leave Maximum Stock at 0 for "no ceiling".'],
    [],
    ['Yes/No columns'],
    ['  Accepted: Yes, No, Y, N, TRUE, FALSE, 1, 0'],
    [],
    ['Status column'],
    ['  Accepted: Active, Inactive'],
    [],
    ['Limits'],
    ['  2000 rows per file. Split larger catalogues across several uploads.'],
    [],
    ['Duplicate SKUs'],
    ['  On upload, choose how existing SKUs are handled:'],
    ['    Create only     — a row whose SKU already exists is reported as an error'],
    ['    Update existing — that product is updated from the row instead'],
  ].map((r) => (r.length ? r : ['']));

  const wsInfo = XLSX.utils.aoa_to_sheet(instructions);
  wsInfo['!cols'] = [{ wch: 96 }];
  XLSX.utils.book_append_sheet(wb, wsInfo, 'Instructions');

  const stamp = localStamp();
  XLSX.writeFile(wb, `product-master-template-${stamp}.xlsx`);
}

/* ═══════════════════════════════════════════════════════════════════════════
   PARSE UPLOAD
   ═══════════════════════════════════════════════════════════════════════════ */

/**
 * Reads a .xlsx / .xls / .csv file into rows keyed by IMPORT_COLUMNS.
 *
 * @returns {Promise<{rows: object[], warnings: string[], headers: string[]}>}
 * @throws  {Error} with a message meant for the user
 */
export async function parseWorkbook(file) {
  const XLSX = await xlsx();
  const buffer = await file.arrayBuffer();

  let wb;
  try {
    wb = XLSX.read(buffer, { type: 'array', cellDates: false });
  } catch {
    throw new Error('That file could not be read as a spreadsheet. Save it as .xlsx and try again.');
  }

  // Prefer the "Products" sheet; fall back to the first sheet so a plain
  // one-sheet export still works.
  const sheetName = wb.SheetNames.find((n) => n.toLowerCase() === 'products') || wb.SheetNames[0];
  if (!sheetName) throw new Error('The workbook contains no sheets.');

  const sheet = wb.Sheets[sheetName];
  const matrix = XLSX.utils.sheet_to_json(sheet, { header: 1, blankrows: false, defval: '' });
  if (!matrix.length) throw new Error(`Sheet "${sheetName}" is empty.`);

  const rawHeaders = matrix[0].map((h) => String(h ?? '').trim());
  const mapped = rawHeaders.map(normalizeHeader);

  const warnings = [];
  const unknown = rawHeaders.filter((h, i) => h && !mapped[i]);
  if (unknown.length) {
    const serverOwned = unknown.filter((h) =>
      SERVER_OWNED_COLUMNS.some((s) => h.toLowerCase().replace(/[^a-z]/g, '').includes(s.toLowerCase().replace(/[^a-z]/g, ''))));
    if (serverOwned.length) {
      warnings.push(`Ignored calculated column${serverOwned.length > 1 ? 's' : ''}: ${serverOwned.join(', ')} — the server calculates these.`);
    }
    const other = unknown.filter((h) => !serverOwned.includes(h));
    if (other.length) warnings.push(`Unrecognised column${other.length > 1 ? 's' : ''} ignored: ${other.join(', ')}`);
  }

  const missingRequired = IMPORT_COLUMNS
    .filter((c) => c.required && !mapped.includes(c.key))
    .map((c) => c.header);
  if (missingRequired.length) {
    throw new Error(`The file is missing required column${missingRequired.length > 1 ? 's' : ''}: ${missingRequired.join(', ')}. Download the template to see the expected format.`);
  }

  const byKey = Object.fromEntries(IMPORT_COLUMNS.map((c) => [c.key, c]));
  const rows = [];
  let examplesSkipped = 0;

  for (let r = 1; r < matrix.length; r++) {
    const line = matrix[r];
    if (!line || line.every((c) => String(c ?? '').trim() === '')) continue;

    // +1 for the header row, +1 because spreadsheets are 1-based — so this is
    // the row number the user sees in Excel.
    const row = { rowNumber: r + 1 };
    mapped.forEach((key, c) => {
      if (!key) return;
      row[key] = coerce(line[c], byKey[key].type);
    });

    // Drop the template's demonstration rows rather than importing them as
    // real products. Matching is on SKU *and* name (see TEMPLATE_EXAMPLES), so
    // a genuine product whose SKU merely ends in "-EX" is never discarded.
    if (isTemplateExample(row)) {
      examplesSkipped++;
      warnings.push(`Row ${row.rowNumber}: skipped the template example row (${row.sku}).`);
      continue;
    }

    rows.push(row);
  }

  if (!rows.length) {
    // Distinguish "you sent us the untouched template" from "the sheet is
    // genuinely empty" — telling someone to fill in a sheet that visibly has
    // rows in it is how this reads as a bug rather than as instructions.
    if (examplesSkipped > 0) {
      throw new Error(
        `This is the blank template — the only rows in it are the ${examplesSkipped} ` +
        'example row(s) it ships with. Replace them with your own products (or add ' +
        'yours below them) and upload again.');
    }
    throw new Error('No data rows found. Fill in the Products sheet below the header row.');
  }

  return { rows, warnings, headers: rawHeaders.filter(Boolean), sheetName };
}

/** Cell value → the type the API expects. Blank becomes null, never 0 or "". */
function coerce(value, type) {
  if (value === null || value === undefined) return null;
  const s = String(value).trim();
  if (s === '') return null;

  if (type === 'number') {
    // Tolerate "৳1,250.00" and "1 250" — spreadsheets often carry formatting.
    const n = Number(s.replace(/[^0-9.\-]/g, ''));
    return Number.isFinite(n) ? n : null;
  }
  if (type === 'yesno') return s;      // server normalises Y/Yes/TRUE/1
  if (type === 'list') {
    // "Box-10x10  (100 pcs)" → "Box-10x10", so a value copied straight from the
    // Reference sheet still resolves.
    return s.replace(/\s*\(.*\)\s*$/, '').trim();
  }
  return s;
}

/* ═══════════════════════════════════════════════════════════════════════════
   RESULT EXPORT
   ═══════════════════════════════════════════════════════════════════════════ */

/** Downloads the per-row import outcome so failures can be fixed and re-uploaded. */
export async function downloadImportResult(result) {
  const XLSX = await xlsx();
  const rows = (result?.results ?? []).map((r) => ({
    Row: r.rowNumber ?? '',
    SKU: r.sku ?? '',
    'Product Name': r.productName ?? '',
    Status: r.status ?? '',
    'Product Code': r.productCode ?? '',
    Error: r.error ?? '',
  }));

  const wb = XLSX.utils.book_new();
  const ws = XLSX.utils.json_to_sheet(rows);
  ws['!cols'] = [{ wch: 7 }, { wch: 16 }, { wch: 30 }, { wch: 11 }, { wch: 14 }, { wch: 70 }];
  XLSX.utils.book_append_sheet(wb, ws, 'Import Result');

  const stamp = localStamp(true);
  XLSX.writeFile(wb, `product-import-result-${stamp}.xlsx`);
}

/** Exports the current (filtered) product list as a real .xlsx workbook. */
export async function downloadProductsExcel(products) {
  const XLSX = await xlsx();
  const rows = (products ?? []).map((p) => ({
    'Product Code': p.productCode ?? '',
    'Product Name': p.productName ?? '',
    Generic: p.genericName ?? '',
    Company: p.companyName ?? '',
    Category: p.categoryName ?? '',
    SKU: p.sku ?? '',
    Barcode: p.barcode ?? '',
    Unit: p.unitName ?? '',
    MRP: p.mrp ?? 0,
    'TP Price': p.tpPrice ?? 0,
    IGTA: p.igta ?? 0,
    'Buy Price': p.buyPrice ?? 0,
    'Current Stock': p.currentStock ?? 0,
    'Reorder Level': p.reorderLevel ?? 0,
    'Stock Status': p.stockStatus ?? '',
    'Batch Tracking': p.batchTracking ? 'Yes' : 'No',
    'Expiry Tracking': p.expiryTracking ? 'Yes' : 'No',
    'Prescription Req.': p.prescriptionRequired ? 'Yes' : 'No',
    Status: p.status ?? '',
  }));

  const wb = XLSX.utils.book_new();
  const ws = XLSX.utils.json_to_sheet(rows);
  ws['!cols'] = [
    { wch: 13 }, { wch: 30 }, { wch: 20 }, { wch: 26 }, { wch: 18 }, { wch: 16 },
    { wch: 18 }, { wch: 10 }, { wch: 11 }, { wch: 11 }, { wch: 11 }, { wch: 13 },
    { wch: 13 }, { wch: 12 }, { wch: 14 }, { wch: 14 }, { wch: 16 }, { wch: 10 },
  ];
  XLSX.utils.book_append_sheet(wb, ws, 'Products');

  const stamp = localStamp();
  XLSX.writeFile(wb, `products-${stamp}.xlsx`);
}

export default {
  IMPORT_COLUMNS,
  downloadTemplate,
  parseWorkbook,
  downloadImportResult,
  downloadProductsExcel,
};
