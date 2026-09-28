/**
 * A purchase order, ready to print and send to the supplier.
 *
 * Companion to invoiceDocumentService: the same arrangement, for the document
 * that goes the other way. The letterhead, the supplier's postal details, the
 * requisition it came from and every figure — including the amount in words —
 * are assembled on the server, so what a supplier receives cannot disagree with
 * what the buyer's copy says.
 */
import apiClient from '../lib/apiClient.js';

/** The printable document for one purchase order. */
export async function getPurchaseOrderDocument(purchaseId) {
  return apiClient.get(`/api/purchases/${purchaseId}/document`);
}

export default { getPurchaseOrderDocument };
