/**
 * productMasterService.js
 *
 * Product Master API layer — talks to ProductController (/api/products).
 *
 * Two things worth knowing before editing this file:
 *
 * 1. No orgId is sent anywhere. The backend resolves the tenant from the JWT.
 *    Passing one would be ignored at best; the old endpoints that accepted
 *    ?orgId let a client read another organisation's catalogue.
 *
 * 2. tpPrice, igta, buyPrice and productCode are never sent. The server
 *    calculates or generates all four and the request DTO has no fields for
 *    them. The frontend still computes them live for display, but that value
 *    is a preview only — see calculatePrices() below.
 */
import apiClient from '../lib/apiClient.js';

/* ═══════════════════════════════════════════════════════════════════════════
   PRICING PREVIEW
   Mirrors backend ProductPricingCalculator so the form updates instantly as
   the user types. The server recalculates independently on save — if these
   two ever disagree, the server wins and this function is the one that is wrong.
   ═══════════════════════════════════════════════════════════════════════════ */

export const DEFAULT_CONVERSION_RATE = 25.037;
export const DEFAULT_VAT_PCT = 17.4;

/** Round half-up to 2 decimals, matching the backend's ROUND(x, 2). */
function round2(value) {
  if (!Number.isFinite(value)) return 0;
  // Number.EPSILON compensates for float representations like 1.005 -> 1.00499…
  return Math.round((value + Number.EPSILON) * 100) / 100;
}

function num(value, fallback = 0) {
  if (value === null || value === undefined || value === '') return fallback;
  const n = Number(value);
  return Number.isFinite(n) ? n : fallback;
}

/**
 * The full dependency chain: MRP + conversionRate -> tpPrice, then
 * tpPrice + vatPct -> igta and buyPrice.
 */
export function calculatePrices({ mrp, conversionRate, vatPct }) {
  const m = num(mrp);
  const rate = conversionRate === null || conversionRate === undefined || conversionRate === ''
    ? DEFAULT_CONVERSION_RATE : num(conversionRate);
  const vat = vatPct === null || vatPct === undefined || vatPct === ''
    ? DEFAULT_VAT_PCT : num(vatPct);

  const tpPrice = round2(m - (m * rate) / 100);
  const igta = round2((tpPrice * vat) / 100);
  const buyPrice = round2(tpPrice + (tpPrice * vat) / 100);

  return { tpPrice, igta, buyPrice };
}

/* ═══════════════════════════════════════════════════════════════════════════
   LIST
   ═══════════════════════════════════════════════════════════════════════════ */

/**
 * Paginated, filtered, sorted product list.
 *
 * Returns the PageResponse shape the backend actually sends
 * ({ content, page, size, totalElements, totalPages, first, last }) normalised
 * to what the grid uses. Note this does NOT use apiClient.unwrapPagination —
 * that helper expects currentPage/pageSize/hasNext/hasPrevious, which
 * PageResponse.java does not emit, so it would silently return zeros.
 */
export async function getProducts({
  search = null,
  sku = null,
  barcode = null,
  genericId = null,
  companyId = null,
  categoryId = null,
  unitId = null,
  status = null,
  stockStatus = null,
  batchTracking = null,
  expiryTracking = null,
  prescriptionRequired = null,
  page = 0,
  size = 25,
  sortBy = 'productName',
  sortDir = 'asc',
} = {}) {
  const params = { page, size, sortBy, sortDir };

  if (search) params.search = search;
  if (sku) params.sku = sku;
  if (barcode) params.barcode = barcode;
  if (genericId) params.genericId = genericId;
  if (companyId) params.companyId = companyId;
  if (categoryId) params.categoryId = categoryId;
  if (unitId) params.unitId = unitId;
  if (status && status !== 'ALL') params.status = status;
  if (stockStatus && stockStatus !== 'ALL') params.stockStatus = stockStatus;
  if (batchTracking !== null) params.batchTracking = batchTracking;
  if (expiryTracking !== null) params.expiryTracking = expiryTracking;
  if (prescriptionRequired !== null) params.prescriptionRequired = prescriptionRequired;

  const raw = await apiClient.get('/api/products', params);

  return {
    items: raw?.content ?? [],
    total: raw?.totalElements ?? 0,
    page: raw?.page ?? 0,
    pageSize: raw?.size ?? size,
    totalPages: raw?.totalPages ?? 0,
    first: raw?.first ?? true,
    last: raw?.last ?? true,
  };
}

/* ═══════════════════════════════════════════════════════════════════════════
   CRUD
   ═══════════════════════════════════════════════════════════════════════════ */

export async function getProduct(id) {
  return apiClient.get(`/api/products/${id}`);
}

export async function getProductByBarcode(barcode) {
  return apiClient.get(`/api/products/barcode/${encodeURIComponent(barcode)}`);
}

export async function createProduct(formValues) {
  return apiClient.post('/api/products', toRequestBody(formValues));
}

/**
 * Saves an edit — which writes a NEW version and retires the one given.
 *
 * The product in the reply has a DIFFERENT id from the one passed in: the row
 * named by `id` is now inactive and keeps the history raised against it, and
 * everything from here on belongs to the new row. A caller that holds on to the
 * old id will be editing a retired version and gets a 409
 * (PRODUCT_VERSION_SUPERSEDED) next time. Read the id back from the response.
 */
export async function updateProduct(id, formValues) {
  return apiClient.put(`/api/products/${id}`, toRequestBody(formValues));
}

/**
 * Is this product name still free on this branch?
 *
 * Asked as the cursor leaves the Product Name field. Never throws: a check that
 * cannot reach the server must not stop somebody filling in a form — Save asks
 * the same question again and is the one that actually refuses.
 */
export async function checkProductName(name, excludeId) {
  try {
    const r = await apiClient.get('/api/products/name-available', {
      name, excludeId: excludeId ?? null,
    });
    return { available: r?.available !== false, message: r?.message ?? null };
  } catch {
    return { available: true, message: null };
  }
}

/** Every version of a product, newest first. Any version's id gives the same list. */
export async function getProductVersions(id) {
  const list = await apiClient.get(`/api/products/${id}/versions`);
  return Array.isArray(list) ? list : [];
}

/** Hard delete. Rejects with errorCode PRODUCT_HAS_TRANSACTIONS (409) if in use. */
export async function deleteProduct(id) {
  return apiClient.delete(`/api/products/${id}`);
}

export async function setProductStatus(id, status) {
  return apiClient.patch(`/api/products/${id}/status`, { status });
}

export async function deactivateProduct(id) {
  return setProductStatus(id, 'INACTIVE');
}

export async function activateProduct(id) {
  return setProductStatus(id, 'ACTIVE');
}

/* ═══════════════════════════════════════════════════════════════════════════
   IMAGE
   ═══════════════════════════════════════════════════════════════════════════ */

export async function uploadProductImage(id, file) {
  const formData = new FormData();
  formData.append('file', file);
  return apiClient.upload(`/api/products/${id}/image`, formData);
}

export async function deleteProductImage(id) {
  return apiClient.delete(`/api/products/${id}/image`);
}

/**
 * Product images are served as a path relative to the API host, not the Vite
 * dev server, so a bare "/uploads/..." would 404 in development. Absolute URLs
 * and data: URLs are passed through untouched.
 */
export function resolveImageUrl(productImage) {
  if (!productImage) return null;
  if (/^(https?:)?\/\//i.test(productImage) || productImage.startsWith('data:')) {
    return productImage;
  }
  const base = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080';
  return `${base}${productImage.startsWith('/') ? '' : '/'}${productImage}`;
}

/* ═══════════════════════════════════════════════════════════════════════════
   BULK IMPORT
   ═══════════════════════════════════════════════════════════════════════════ */

/**
 * Posts parsed spreadsheet rows for import.
 *
 * The rows are sent as data, not as trusted values: the server resolves every
 * master-data name, re-runs validation and recalculates all prices. Rows are
 * independent, so the response reports each one's outcome separately rather
 * than failing the batch.
 *
 * @param rows  output of parseWorkbook()
 * @param mode  'CREATE' (existing SKU is an error) or 'UPSERT' (update it)
 */
export async function bulkImportProducts(rows, mode = 'CREATE') {
  return apiClient.post('/api/products/bulk', { mode, rows });
}

/* ═══════════════════════════════════════════════════════════════════════════
   LOOKUPS
   ═══════════════════════════════════════════════════════════════════════════ */

/**
 * Every dropdown the form needs in one request: generics, companies,
 * categories, subCategories, units, boxes (each with its derived noOfPcs) and
 * the pricing defaults.
 */
export async function getFormLookups() {
  const raw = await apiClient.get('/api/products/lookups');
  const defaults = raw?.defaults?.[0] ?? {};

  return {
    generics: raw?.generics ?? [],
    companies: raw?.companies ?? [],
    categories: raw?.categories ?? [],
    subCategories: raw?.subCategories ?? [],
    units: raw?.units ?? [],
    boxes: raw?.boxes ?? [],
    defaults: {
      conversionRate: num(defaults.conversionRate, DEFAULT_CONVERSION_RATE),
      vatPct: num(defaults.vatPct, DEFAULT_VAT_PCT),
    },
  };
}

/* ═══════════════════════════════════════════════════════════════════════════
   FORM <-> API MAPPING
   ═══════════════════════════════════════════════════════════════════════════ */

/** Blank select values arrive as "" — the API wants null or a number. */
function toId(value) {
  if (value === null || value === undefined || value === '') return null;
  const n = Number(value);
  return Number.isFinite(n) ? n : null;
}

function toDecimal(value) {
  if (value === null || value === undefined || value === '') return null;
  const n = Number(value);
  return Number.isFinite(n) ? n : null;
}

function toText(value) {
  if (value === null || value === undefined) return null;
  const t = String(value).trim();
  return t === '' ? null : t;
}

/**
 * Form state -> POST/PUT body.
 *
 * Deliberately omits tpPrice, igta, buyPrice and productCode. They are
 * displayed in the form but never submitted; the server owns them.
 */
export function toRequestBody(v) {
  const general = v.productType === 'GENERAL';
  return {
    productName: toText(v.productName),
    productType: general ? 'GENERAL' : 'PHARMA',

    // Cleared rather than carried when the type is general. Someone who typed a
    // SKU, then switched to General, should not silently save the SKU they can
    // no longer see — the form hides those fields, and a hidden field that still
    // submits is the oldest way to save a value nobody agreed to.
    sku: general ? null : toText(v.sku),
    barcode: toText(v.barcode),

    genericId: general ? null : toId(v.genericId),
    companyId: toId(v.companyId),
    categoryId: toId(v.categoryId),
    subCategoryId: toId(v.subCategoryId),
    unitId: toId(v.unitId),
    boxId: toId(v.boxId),

    mrp: toDecimal(v.mrp),
    conversionRate: toDecimal(v.conversionRate),
    vatPct: toDecimal(v.vatPct),
    discountPct: toDecimal(v.discountPct),

    reorderLevel: toDecimal(v.reorderLevel),
    safetyStock: toDecimal(v.safetyStock),
    minimumStock: toDecimal(v.minimumStock),
    maximumStock: toDecimal(v.maximumStock),

    batchTracking: !!v.batchTracking,
    expiryTracking: !!v.expiryTracking,
    prescriptionRequired: !!v.prescriptionRequired,

    status: v.status || 'ACTIVE',
    imgColor: toText(v.imgColor),
  };
}

/** API detail response -> form state. Nested refs flatten to their ids. */
export function toFormValues(product, defaults = {}) {
  if (!product) {
    return {
      productName: '',
      // Medicine is what a pharmacy mostly enters, so it is the default and the
      // fuller form. General hides two fields rather than revealing them.
      productType: 'PHARMA',
      sku: '',
      barcode: '',
      genericId: '',
      companyId: '',
      categoryId: '',
      subCategoryId: '',
      unitId: '',
      boxId: '',
      mrp: '',
      // A new product starts at the business defaults; an edit keeps whatever
      // the product already has, so an existing rate is never overwritten.
      conversionRate: defaults.conversionRate ?? DEFAULT_CONVERSION_RATE,
      vatPct: defaults.vatPct ?? DEFAULT_VAT_PCT,
      discountPct: '',
      reorderLevel: '',
      safetyStock: '',
      minimumStock: '',
      maximumStock: '',
      batchTracking: false,
      expiryTracking: false,
      prescriptionRequired: false,
      status: 'ACTIVE',
      imgColor: '',
    };
  }

  return {
    productType: product.productType === 'GENERAL' ? 'GENERAL' : 'PHARMA',
    productName: product.productName ?? '',
    sku: product.sku ?? '',
    barcode: product.barcode ?? '',
    genericId: product.generic?.id ?? '',
    companyId: product.company?.id ?? '',
    categoryId: product.category?.id ?? '',
    subCategoryId: product.subCategory?.id ?? '',
    unitId: product.unit?.id ?? '',
    boxId: product.box?.id ?? '',
    mrp: product.mrp ?? '',
    conversionRate: product.conversionRate ?? DEFAULT_CONVERSION_RATE,
    vatPct: product.vatPct ?? DEFAULT_VAT_PCT,
    discountPct: product.discountPct ?? '',
    reorderLevel: product.reorderLevel ?? '',
    safetyStock: product.safetyStock ?? '',
    minimumStock: product.minimumStock ?? '',
    maximumStock: product.maximumStock ?? '',
    batchTracking: !!product.batchTracking,
    expiryTracking: !!product.expiryTracking,
    prescriptionRequired: !!product.prescriptionRequired,
    status: product.status ?? 'ACTIVE',
    imgColor: product.imgColor ?? '',
  };
}

/* ═══════════════════════════════════════════════════════════════════════════
   CLIENT-SIDE VALIDATION
   Mirrors the server rules so the user gets immediate feedback. The server
   validates independently — this is UX, not enforcement.
   ═══════════════════════════════════════════════════════════════════════════ */

export function validateForm(v) {
  const errors = {};

  if (!toText(v.productName)) errors.productName = 'Product name is required.';
  else if (v.productName.trim().length > 150) errors.productName = 'Maximum 150 characters.';

  // SKU and generic identify a medicine. A general product — soap, razors,
  // baby food — has neither, and the old unconditional rule made people invent
  // them, which put noise into the two fields most used to find a drug.
  const general = v.productType === 'GENERAL';

  // SKU is optional for every product type now. The shape rules below still
  // apply to one that was supplied — an optional field is not an unchecked one.
  if (toText(v.sku)) {
    // The shape rules still apply to a SKU that was supplied, whatever the type.
    if (v.sku.trim().length > 50) errors.sku = 'Maximum 50 characters.';
    else if (!/^[A-Za-z0-9._/-]+$/.test(v.sku.trim())) {
      errors.sku = 'Only letters, digits and . _ / - are allowed.';
    }
  }

  if (!general && !toId(v.genericId)) errors.genericId = 'Generic is required.';
  if (!toId(v.companyId)) errors.companyId = 'Company is required.';
  if (!toId(v.categoryId)) errors.categoryId = 'Category is required.';
  if (!toId(v.unitId)) errors.unitId = 'Unit is required.';

  if (v.barcode && v.barcode.trim() && !/^[A-Za-z0-9-]+$/.test(v.barcode.trim())) {
    errors.barcode = 'Only letters, digits and hyphens are allowed.';
  }
  if (v.barcode && v.barcode.trim().length > 30) errors.barcode = 'Maximum 30 characters.';

  /* ── Pricing ── */
  if (v.mrp === '' || v.mrp === null || v.mrp === undefined) {
    errors.mrp = 'MRP is required.';
  } else if (num(v.mrp, -1) < 0) {
    errors.mrp = 'MRP must be greater than or equal to zero.';
  }

  const rate = num(v.conversionRate, -1);
  if (rate < 0) errors.conversionRate = 'Conversion rate must be greater than or equal to zero.';
  else if (rate >= 100) errors.conversionRate = 'A rate of 100% or more reduces the trade price to zero.';

  const vat = num(v.vatPct, -1);
  if (vat < 0) errors.vatPct = 'VAT % must be greater than or equal to zero.';
  else if (vat > 100) errors.vatPct = 'VAT % cannot exceed 100.';

  if (v.discountPct !== '' && v.discountPct !== null && v.discountPct !== undefined) {
    const disc = num(v.discountPct, -1);
    if (disc < 0 || disc > 100) errors.discountPct = 'Discount % must be between 0 and 100.';
  }

  /* ── Stock ladder: minimum <= safety <= reorder <= maximum ── */
  const min = num(v.minimumStock);
  const safety = num(v.safetyStock);
  const reorder = num(v.reorderLevel);
  const max = num(v.maximumStock);

  if (min < 0) errors.minimumStock = 'Cannot be negative.';
  if (safety < 0) errors.safetyStock = 'Cannot be negative.';
  if (reorder < 0) errors.reorderLevel = 'Cannot be negative.';
  if (max < 0) errors.maximumStock = 'Cannot be negative.';

  if (!errors.minimumStock && !errors.safetyStock && min > safety) {
    errors.minimumStock = 'Minimum stock cannot exceed safety stock.';
  }
  if (!errors.safetyStock && !errors.reorderLevel && safety > reorder) {
    errors.safetyStock = 'Safety stock cannot exceed the reorder level.';
  }
  // A maximum of 0 means "no ceiling set", matching the backend rule.
  if (!errors.reorderLevel && !errors.maximumStock && max > 0 && reorder > max) {
    errors.reorderLevel = 'Reorder level cannot exceed maximum stock.';
  }

  return errors;
}

export default {
  getProducts,
  getProduct,
  getProductByBarcode,
  createProduct,
  updateProduct,
  getProductVersions,
  checkProductName,
  deleteProduct,
  setProductStatus,
  activateProduct,
  deactivateProduct,
  uploadProductImage,
  deleteProductImage,
  resolveImageUrl,
  getFormLookups,
  bulkImportProducts,
  calculatePrices,
  toRequestBody,
  toFormValues,
  validateForm,
};
