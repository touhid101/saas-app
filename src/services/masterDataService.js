/**
 * masterDataService.js
 *
 * Full CRUD for all 16 master-data entities + supporting lookups.
 * Base paths confirmed from MasterDataController.java.
 * All gaps from master-data-integration-notes.md are now implemented.
 *
 * GAP status after backend implementation:
 *   GAP-1 Division  → ✅ GET/POST/PUT /api/divisions
 *   GAP-2 District  → ✅ GET/POST/PUT /api/districts
 *   GAP-3 Thana     → ✅ GET/POST/PUT /api/thanas
 *   GAP-4 Area      → ✅ GET/POST/PUT /api/areas
 *   GAP-5 Leaf/Box  → ✅ PUT /api/leafs/{id}, PUT /api/boxes/{id}
 *   GAP-6 Dept/Desig→ ✅ PUT /api/departments/{id}, PUT /api/designations/{id}
 *   GAP-7 No DELETE → still soft-delete only (status=N via PUT)
 *
 * Response field notes:
 *   Division:  { id, name, nameBn, regionId, regionName }
 *   District:  { id, name, nameBn, divisionId, divisionName }
 *   Thana:     { id, name, nameBn, districtId, districtName }
 *   Area:      { id, name, branchId, branchName, status }
 */
import apiClient from '../lib/apiClient.js';

// Product CRUD moved to productMasterService.js — see the PRODUCTS section below.
// Imported (not just re-exported) because the default export object at the end
// of this file needs them as local bindings.
import {
  getProducts,
  getProduct,
  getProductByBarcode,
  createProduct,
  updateProduct,
  deleteProduct,
} from './productMasterService.js';

// ─────────────────────────────────────────────────────────────────────────────
// ORGANIZATION  — /api/organizations
// ─────────────────────────────────────────────────────────────────────────────
export async function getOrganizations() {
  return apiClient.get('/api/organizations');
}

export async function createOrganization(data) {
  return apiClient.post('/api/organizations', {
    name: data.name,
    shortName: data.shortName || null,
    email: data.email || null,
    phone: data.phone || null,
    address: data.address || null,
    status: data.status || 'Y',
  });
}

export async function updateOrganization(id, data) {
  return apiClient.put(`/api/organizations/${id}`, {
    name: data.name,
    shortName: data.shortName || null,
    email: data.email || null,
    phone: data.phone || null,
    address: data.address || null,
    status: data.status || 'Y',
  });
}

export async function deactivateOrganization(id) {
  return apiClient.put(`/api/organizations/${id}`, { status: 'N' });
}

// ─────────────────────────────────────────────────────────────────────────────
// COMPANY  — /api/companies
// ─────────────────────────────────────────────────────────────────────────────
export async function getCompanies(orgId) {
  return apiClient.get('/api/companies', orgId ? { orgId } : null);
}

export async function createCompany(data) {
  return apiClient.post('/api/companies', {
    orgId: data.orgId,
    name: data.name,
    shortName: data.shortName || null,
    email: data.email || null,
    phone: data.phone || null,
    address: data.address || null,
    status: data.status || 'Y',
  });
}

export async function updateCompany(id, data) {
  return apiClient.put(`/api/companies/${id}`, {
    orgId: data.orgId,
    name: data.name,
    shortName: data.shortName || null,
    email: data.email || null,
    phone: data.phone || null,
    address: data.address || null,
    status: data.status || 'Y',
  });
}

export async function deactivateCompany(id, orgId) {
  return apiClient.put(`/api/companies/${id}`, { orgId, status: 'N' });
}

// ─────────────────────────────────────────────────────────────────────────────
// BRANCH  — /api/branches
// ─────────────────────────────────────────────────────────────────────────────
export async function getBranches(orgId, regionId = null) {
  const params = {};
  if (orgId)    params.orgId    = orgId;
  if (regionId) params.regionId = regionId;
  return apiClient.get('/api/branches', Object.keys(params).length ? params : null);
}

export async function createBranch(data) {
  return apiClient.post('/api/branches', {
    orgId: data.orgId,
    companyId: data.companyId,
    name: data.name,
    address: data.address || null,
    email: data.email || null,
    phone: data.phone || null,
    status: data.status || 'Y',
    regionId: data.regionId || null,
  });
}

export async function updateBranch(id, data) {
  return apiClient.put(`/api/branches/${id}`, {
    orgId: data.orgId,
    companyId: data.companyId,
    name: data.name,
    address: data.address || null,
    email: data.email || null,
    phone: data.phone || null,
    status: data.status || 'Y',
    regionId: data.regionId || null,
    /* [V103] The online store. Typed as text on the form so an empty box means
       "unchanged" rather than 0 — 0 is a real latitude. */
    ecomEnabled: data.ecomEnabled || null,
    latitude: numOrNull(data.latitude),
    longitude: numOrNull(data.longitude),
    ecomDeliveryRadiusKm: numOrNull(data.ecomDeliveryRadiusKm),
    ecomMinOrderAmount: numOrNull(data.ecomMinOrderAmount),
    ecomOpeningHours: data.ecomOpeningHours ?? null,
    ecomDescription: data.ecomDescription ?? null,
    /* [V108] The delivery charge policy. Same rule as above: an empty box means
       "leave it alone", because 0 is a real fee (free) and null is not. */
    ecomDeliveryFeeType: data.ecomDeliveryFeeType || null,
    ecomDeliveryFlatFee: numOrNull(data.ecomDeliveryFlatFee),
    ecomDeliveryFeePerKm: numOrNull(data.ecomDeliveryFeePerKm),
    ecomDeliveryFreeAbove: numOrNull(data.ecomDeliveryFreeAbove),
  });
}

export async function deactivateBranch(id, data) {
  return apiClient.put(`/api/branches/${id}`, { orgId: data.orgId, companyId: data.companyId, name: data.name, status: 'N' });
}

// ─────────────────────────────────────────────────────────────────────────────
// REGION  — /api/regions  (body is raw { name } map per backend design)
// ─────────────────────────────────────────────────────────────────────────────
export async function getRegions() {
  return apiClient.get('/api/regions');
}

export async function createRegion(name) {
  return apiClient.post('/api/regions', { name });
}

export async function updateRegion(id, name) {
  return apiClient.put(`/api/regions/${id}`, { name });
}

// ─────────────────────────────────────────────────────────────────────────────
// DIVISION  — /api/divisions  ✅ GAP-1 RESOLVED
// Response: { id, name, nameBn, regionId, regionName }
// ─────────────────────────────────────────────────────────────────────────────
export async function getDivisions(regionId = null) {
  return apiClient.get('/api/divisions', regionId ? { regionId } : null);
}

export async function getDivision(id) {
  return apiClient.get(`/api/divisions/${id}`);
}

export async function createDivision(data) {
  return apiClient.post('/api/divisions', {
    divisionName: data.divisionName ?? data.name,
    divisionBn:   data.divisionBn  ?? data.nameBn ?? null,
    pidRegion:    data.pidRegion   ?? data.regionId,
  });
}

export async function updateDivision(id, data) {
  return apiClient.put(`/api/divisions/${id}`, {
    divisionName: data.divisionName ?? data.name,
    divisionBn:   data.divisionBn  ?? data.nameBn ?? null,
    pidRegion:    data.pidRegion   ?? data.regionId,
  });
}

// ─────────────────────────────────────────────────────────────────────────────
// DISTRICT  — /api/districts  ✅ GAP-2 RESOLVED
// Response: { id, name, nameBn, divisionId, divisionName }
// ─────────────────────────────────────────────────────────────────────────────
export async function getDistricts(divisionId = null) {
  return apiClient.get('/api/districts', divisionId ? { divisionId } : null);
}

export async function getDistrict(id) {
  return apiClient.get(`/api/districts/${id}`);
}

export async function createDistrict(data) {
  return apiClient.post('/api/districts', {
    districtName: data.districtName ?? data.name,
    districtBn:   data.districtBn  ?? data.nameBn ?? null,
    divisionId:   data.divisionId,
  });
}

export async function updateDistrict(id, data) {
  return apiClient.put(`/api/districts/${id}`, {
    districtName: data.districtName ?? data.name,
    districtBn:   data.districtBn  ?? data.nameBn ?? null,
    divisionId:   data.divisionId,
  });
}

// ─────────────────────────────────────────────────────────────────────────────
// THANA  — /api/thanas  ✅ GAP-3 RESOLVED
// Response: { id, name, nameBn, districtId, districtName }
// ─────────────────────────────────────────────────────────────────────────────
export async function getThanas(districtId = null) {
  return apiClient.get('/api/thanas', districtId ? { districtId } : null);
}

export async function getThana(id) {
  return apiClient.get(`/api/thanas/${id}`);
}

export async function createThana(data) {
  return apiClient.post('/api/thanas', {
    thanaName:  data.thanaName  ?? data.name,
    thanaBn:    data.thanaBn    ?? data.nameBn ?? null,
    districtId: data.districtId,
  });
}

export async function updateThana(id, data) {
  return apiClient.put(`/api/thanas/${id}`, {
    thanaName:  data.thanaName  ?? data.name,
    thanaBn:    data.thanaBn    ?? data.nameBn ?? null,
    districtId: data.districtId,
  });
}

// ─────────────────────────────────────────────────────────────────────────────
// AREA  — /api/areas  ✅ GAP-4 RESOLVED (POST + PUT now available)
// Response: { id, name, branchId, branchName, status }
// ─────────────────────────────────────────────────────────────────────────────
export async function getAreas(branchId = null) {
  return apiClient.get('/api/areas', branchId ? { branchId } : null);
}

export async function createArea(data) {
  return apiClient.post('/api/areas', {
    areaName:   data.areaName  ?? data.name,
    branchId:   data.branchId,
    thanaId:    data.thanaId   ?? null,
    employeeId: data.employeeId ?? null,
    status:     data.status    || 'Y',
  });
}

export async function updateArea(id, data) {
  return apiClient.put(`/api/areas/${id}`, {
    areaName:   data.areaName  ?? data.name,
    branchId:   data.branchId,
    thanaId:    data.thanaId   ?? null,
    employeeId: data.employeeId ?? null,
    status:     data.status    || 'Y',
  });
}

// ─────────────────────────────────────────────────────────────────────────────
// PRODUCT CATEGORY  — /api/categories
// ─────────────────────────────────────────────────────────────────────────────
export async function getCategories(orgId = null) {
  return apiClient.get('/api/categories', orgId ? { orgId } : null);
}

export async function createCategory(data) {
  return apiClient.post('/api/categories', { name: data.name, status: data.status || 'Y' });
}

export async function updateCategory(id, data) {
  return apiClient.put(`/api/categories/${id}`, { name: data.name, status: data.status || 'Y' });
}

// Its own endpoint, not a PUT carrying only a status: the update endpoint
// requires the record's name, so a status-only PUT failed validation every time.
export async function deactivateCategory(id) {
  return apiClient.post(`/api/categories/${id}/toggle-status`);
}

// ─────────────────────────────────────────────────────────────────────────────
// PRODUCT SUB-CATEGORY  — /api/sub-categories
// ─────────────────────────────────────────────────────────────────────────────
export async function getSubCategories(categoryId = null, orgId = null) {
  const params = {};
  if (categoryId) params.categoryId = categoryId;
  if (orgId)      params.orgId      = orgId;
  return apiClient.get('/api/sub-categories', Object.keys(params).length ? params : null);
}

export async function createSubCategory(data) {
  return apiClient.post('/api/sub-categories', {
    name: data.name, categoryId: data.categoryId, status: data.status || 'Y',
  });
}

export async function updateSubCategory(id, data) {
  return apiClient.put(`/api/sub-categories/${id}`, {
    name: data.name, categoryId: data.categoryId, status: data.status || 'Y',
  });
}

export async function deactivateSubCategory(id) {
  return apiClient.post(`/api/sub-categories/${id}/toggle-status`);
}

// ─────────────────────────────────────────────────────────────────────────────
// GENERIC  — /api/generics
// ─────────────────────────────────────────────────────────────────────────────
export async function getGenerics(orgId = null) {
  return apiClient.get('/api/generics', orgId ? { orgId } : null);
}

export async function createGeneric(data) {
  return apiClient.post('/api/generics', {
    name: data.name, description: data.description || null,
    companyId: data.companyId || null, status: data.status || 'Y',
  });
}

export async function updateGeneric(id, data) {
  return apiClient.put(`/api/generics/${id}`, {
    name: data.name, description: data.description || null,
    companyId: data.companyId || null, status: data.status || 'Y',
  });
}

export async function deactivateGeneric(id) {
  return apiClient.post(`/api/generics/${id}/toggle-status`);
}

// ─────────────────────────────────────────────────────────────────────────────
// PRODUCT COMPANY (Manufacturer)  — /api/product-companies
// ─────────────────────────────────────────────────────────────────────────────
export async function getProductCompanies(orgId = null) {
  return apiClient.get('/api/product-companies', orgId ? { orgId } : null);
}

export async function createProductCompany(data) {
  return apiClient.post('/api/product-companies', {
    name: data.name, mobileNo: data.mobileNo || null,
    email: data.email || null, address: data.address || null, status: data.status || 'Y',
  });
}

export async function updateProductCompany(id, data) {
  return apiClient.put(`/api/product-companies/${id}`, {
    name: data.name, mobileNo: data.mobileNo || null,
    email: data.email || null, address: data.address || null, status: data.status || 'Y',
  });
}

export async function deactivateProductCompany(id) {
  return apiClient.post(`/api/product-companies/${id}/toggle-status`);
}

// ─────────────────────────────────────────────────────────────────────────────
// LEAF  — /api/leafs  ✅ GAP-5 PUT RESOLVED
// ─────────────────────────────────────────────────────────────────────────────
export async function getLeafs(orgId = null) {
  return apiClient.get('/api/leafs', orgId ? { orgId } : null);
}

export async function createLeaf(data) {
  return apiClient.post('/api/leafs', {
    type: data.type, noOfPcs: data.noOfPcs, status: data.status || 'Y',
  });
}

export async function updateLeaf(id, data) {
  return apiClient.put(`/api/leafs/${id}`, {
    type:    data.type    ?? null,
    noOfPcs: data.noOfPcs ?? null,
    status:  data.status  ?? null,
  });
}

// ─────────────────────────────────────────────────────────────────────────────
// BOX  — /api/boxes  ✅ GAP-5 PUT RESOLVED
// ─────────────────────────────────────────────────────────────────────────────
export async function getBoxes(orgId = null, leafId = null) {
  const params = {};
  if (orgId)  params.orgId  = orgId;
  if (leafId) params.leafId = leafId;
  return apiClient.get('/api/boxes', Object.keys(params).length ? params : null);
}

export async function createBox(data) {
  return apiClient.post('/api/boxes', {
    leafId: data.leafId, title: data.title, noOfLeaf: data.noOfLeaf, status: data.status || 'Y',
  });
}

export async function updateBox(id, data) {
  return apiClient.put(`/api/boxes/${id}`, {
    leafId:   data.leafId   ?? null,
    title:    data.title    ?? null,
    noOfLeaf: data.noOfLeaf ?? null,
    status:   data.status   ?? null,
  });
}

// ─────────────────────────────────────────────────────────────────────────────
// UNIT  — /api/units  (POST only, no PUT by design)
// ─────────────────────────────────────────────────────────────────────────────
export async function getUnits(orgId = null) {
  return apiClient.get('/api/units', orgId ? { orgId } : null);
}

export async function createUnit(data) {
  return apiClient.post('/api/units', { name: data.name, status: data.status || 'Y' });
}

// ─────────────────────────────────────────────────────────────────────────────
// DEPARTMENT  — /api/departments  ✅ GAP-6 PUT RESOLVED
// ─────────────────────────────────────────────────────────────────────────────
export async function getDepartments() {
  return apiClient.get('/api/departments');
}

export async function createDepartment(data) {
  return apiClient.post('/api/departments', { name: data.name, status: data.status || 'Y' });
}

export async function updateDepartment(id, data) {
  return apiClient.put(`/api/departments/${id}`, {
    name: data.name ?? null, status: data.status ?? null,
  });
}

// ─────────────────────────────────────────────────────────────────────────────
// DESIGNATION  — /api/designations  ✅ GAP-6 PUT RESOLVED
// ─────────────────────────────────────────────────────────────────────────────
export async function getDesignations() {
  return apiClient.get('/api/designations');
}

export async function createDesignation(data) {
  return apiClient.post('/api/designations', {
    name: data.name, description: data.description || null, status: data.status || 'Y',
  });
}

export async function updateDesignation(id, data) {
  return apiClient.put(`/api/designations/${id}`, {
    name:        data.name        ?? null,
    description: data.description ?? null,
    status:      data.status      ?? null,
  });
}

// ─────────────────────────────────────────────────────────────────────────────
// EMPLOYEE  — /api/employees (paginated)
// ─────────────────────────────────────────────────────────────────────────────
export async function getEmployees({ branchId = null, q = null, page = 0, size = 25 } = {}) {
  const params = { page, size };
  if (branchId) params.branchId = branchId;
  if (q)        params.q        = q;
  const raw = await apiClient.get('/api/employees', params);
  return apiClient.unwrapPagination(raw);
}

/** The employee types this deployment accepts: [{ value, label }]. */
export async function getEmployeeTypes() {
  return apiClient.get('/api/employees/types');
}

export async function getEmployee(id) {
  return apiClient.get(`/api/employees/${id}`);
}

export async function createEmployee(data) {
  return apiClient.post('/api/employees', {
    empId: data.empId, empName: data.empName, branchId: data.branchId,
    email: data.email || null, phone: data.phone || null,
    departmentId: data.departmentId || null, designationId: data.designationId || null,
    gender: data.gender || null, address: data.address || null,
    vehicleNo: data.vehicleNo || null, status: data.status || 'Y',
    empType: data.empType || null,
  });
}

export async function updateEmployee(id, data) {
  return apiClient.put(`/api/employees/${id}`, {
    empId: data.empId, empName: data.empName, branchId: data.branchId,
    email: data.email || null, phone: data.phone || null,
    departmentId: data.departmentId || null, designationId: data.designationId || null,
    gender: data.gender || null, address: data.address || null,
    vehicleNo: data.vehicleNo || null, status: data.status || 'Y',
    empType: data.empType || null,
  });
}

export async function deactivateEmployee(id, data) {
  return apiClient.put(`/api/employees/${id}`, { ...data, status: 'N' });
}

export async function getDeliveryMen(branchId = null) {
  return apiClient.get('/api/employees/delivery-men', branchId ? { branchId } : null);
}

// ─────────────────────────────────────────────────────────────────────────────
// PRODUCTS  — moved to productMasterService.js
//
// /api/products is now served by ProductController, whose contract differs:
//   - no orgId parameter (the tenant comes from the JWT)
//   - `q` is now `search`, plus sku/barcode/genericId/companyId/categoryId/
//     unitId/status/stockStatus filters and sortBy/sortDir
//   - the request body no longer accepts tpPrice/igta/buyPrice (server-calculated)
//     or productCode (server-generated)
//   - DELETE is a real delete that 409s on transaction history; deactivation is
//     PATCH /api/products/{id}/status
//
// Re-exported here so existing imports keep working and land on the correct
// implementation rather than a stale one. The import itself is at the top of
// the file so these are local bindings, which the default export object needs.
// ─────────────────────────────────────────────────────────────────────────────
export {
  getProducts,
  getProduct,
  getProductByBarcode,
  createProduct,
  updateProduct,
  deleteProduct,
};

// ─────────────────────────────────────────────────────────────────────────────
// CUSTOMERS  — /api/customers (paginated)
// ─────────────────────────────────────────────────────────────────────────────
export async function getCustomers({ branchId = null, q = null, page = 0, size = 25 } = {}) {
  const params = { page, size };
  if (branchId) params.branchId = branchId;
  if (q)        params.q        = q;
  const raw = await apiClient.get('/api/customers', params);
  return apiClient.unwrapPagination(raw);
}

export async function getCustomer(id) { return apiClient.get(`/api/customers/${id}`); }

const _customerBody = d => ({
  customerName: d.customerName ?? d.name,
  customerCode: d.customerCode ?? d.code ?? null,
  mobileNo:     d.mobileNo     ?? d.phone,
  emailAddress: d.emailAddress ?? d.email ?? null,
  address: d.address || null, city: d.city || null,
  branchId: d.branchId, areaId: d.areaId || null,
  customerType: d.customerType ?? d.type ?? null,
  openingBal:   d.openingBal   ?? d.openingBalance ?? null,
  creditLimit:  d.creditLimit  ?? null,
  status: d.status || 'Y',
});

export async function createCustomer(data) { return apiClient.post('/api/customers', _customerBody(data)); }
export async function updateCustomer(id, data) { return apiClient.put(`/api/customers/${id}`, _customerBody(data)); }
export async function toggleCustomerStatus(id) { return apiClient.post(`/api/customers/${id}/toggle-status`); }

// ─────────────────────────────────────────────────────────────────────────────
// SUPPLIERS  — /api/suppliers (paginated)
// ─────────────────────────────────────────────────────────────────────────────
export async function getSuppliers({ q = null, page = 0, size = 25 } = {}) {
  const params = { page, size };
  if (q) params.q = q;
  const raw = await apiClient.get('/api/suppliers', params);
  return apiClient.unwrapPagination(raw);
}

export async function getSupplier(id) { return apiClient.get(`/api/suppliers/${id}`); }

const _supplierBody = d => ({
  supplierName:      d.supplierName ?? d.name,
  branchId:          d.branchId,
  companyId:         d.companyId || null,
  mobileNo:          d.mobileNo  ?? d.phone ?? null,
  emailAddress:      d.emailAddress ?? d.email ?? null,
  address:           d.address || null,
  responsiblePerson: d.responsiblePerson || null,
  openingBal:        d.openingBal ?? d.openingBalance ?? null,
  creditLimit:       d.creditLimit ?? null,
  status:            d.status || 'Y',
});

export async function createSupplier(data) { return apiClient.post('/api/suppliers', _supplierBody(data)); }
export async function updateSupplier(id, data) { return apiClient.put(`/api/suppliers/${id}`, _supplierBody(data)); }
export async function toggleSupplierStatus(id) { return apiClient.post(`/api/suppliers/${id}/toggle-status`); }

// ─────────────────────────────────────────────────────────────────────────────
// Capability flags (updated — all gaps resolved)
// ─────────────────────────────────────────────────────────────────────────────
const ENTITY_CAPS = {
  organization:   { canCreate: true,  canUpdate: true,  canDeactivate: true  },
  company:        { canCreate: true,  canUpdate: true,  canDeactivate: true  },
  branch:         { canCreate: true,  canUpdate: true,  canDeactivate: true  },
  region:         { canCreate: true,  canUpdate: true,  canDeactivate: false },
  division:       { canCreate: true,  canUpdate: true,  canDeactivate: false }, // GAP-1 ✅
  district:       { canCreate: true,  canUpdate: true,  canDeactivate: false }, // GAP-2 ✅
  thana:          { canCreate: true,  canUpdate: true,  canDeactivate: false }, // GAP-3 ✅
  area:           { canCreate: true,  canUpdate: true,  canDeactivate: false }, // GAP-4 ✅
  category:       { canCreate: true,  canUpdate: true,  canDeactivate: true  },
  subcategory:    { canCreate: true,  canUpdate: true,  canDeactivate: true  },
  generic:        { canCreate: true,  canUpdate: true,  canDeactivate: true  },
  productcompany: { canCreate: true,  canUpdate: true,  canDeactivate: true  },
  leaf:           { canCreate: true,  canUpdate: true,  canDeactivate: false }, // GAP-5 ✅
  box:            { canCreate: true,  canUpdate: true,  canDeactivate: false }, // GAP-5 ✅
  unit:           { canCreate: true,  canUpdate: false, canDeactivate: false }, // no PUT by design
  department:     { canCreate: true,  canUpdate: true,  canDeactivate: false }, // GAP-6 ✅
  designation:    { canCreate: true,  canUpdate: true,  canDeactivate: false }, // GAP-6 ✅
  employee:       { canCreate: true,  canUpdate: true,  canDeactivate: true  },
  product:        { canCreate: true,  canUpdate: true,  canDeactivate: true  },
  customer:       { canCreate: true,  canUpdate: true,  canDeactivate: true  },
  supplier:       { canCreate: true,  canUpdate: true,  canDeactivate: true  },
};

export function getEntityCapabilities(entityKey) {
  return ENTITY_CAPS[entityKey?.toLowerCase()] ?? { canCreate: false, canUpdate: false, canDeactivate: false };
}

// ─────────────────────────────────────────────────────────────────────────────
// Default export
// ─────────────────────────────────────────────────────────────────────────────
export default {
  // Org
  getOrganizations, createOrganization, updateOrganization, deactivateOrganization,
  getCompanies,     createCompany,      updateCompany,      deactivateCompany,
  getBranches,      createBranch,       updateBranch,       deactivateBranch,
  // Geography
  getRegions,   createRegion,   updateRegion,
  getDivisions, getDivision,    createDivision,  updateDivision,
  getDistricts, getDistrict,    createDistrict,  updateDistrict,
  getThanas,    getThana,       createThana,     updateThana,
  getAreas,     createArea,     updateArea,
  // Products
  getCategories,      createCategory,      updateCategory,      deactivateCategory,
  getSubCategories,   createSubCategory,   updateSubCategory,   deactivateSubCategory,
  getGenerics,        createGeneric,       updateGeneric,       deactivateGeneric,
  getProductCompanies,createProductCompany,updateProductCompany,deactivateProductCompany,
  getLeafs,   createLeaf,   updateLeaf,
  getBoxes,   createBox,    updateBox,
  getUnits,   createUnit,
  // HR
  getDepartments,  createDepartment,  updateDepartment,
  getDesignations, createDesignation, updateDesignation,
  getEmployees, getEmployee, createEmployee, updateEmployee, deactivateEmployee, getDeliveryMen,
  // Products full CRUD
  getProducts, getProduct, getProductByBarcode, createProduct, updateProduct, deleteProduct,
  // Customers & Suppliers
  getCustomers, getCustomer, createCustomer, updateCustomer, toggleCustomerStatus,
  getSuppliers, getSupplier, createSupplier, updateSupplier, toggleSupplierStatus,
  // Utilities
  getEntityCapabilities,
};

/** "" and undefined are "not given"; anything else must be a number. */
function numOrNull(v) {
  if (v === '' || v == null) return null;
  const n = Number(v);
  return Number.isFinite(n) ? n : null;
}
