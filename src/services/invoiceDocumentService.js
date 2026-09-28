/**
 * A sale, ready to print.
 *
 * One document behind both layouts — the A4 invoice and the till receipt — so
 * the customer's copy and the office copy of the same invoice cannot disagree
 * about a discount or a balance. Every derived figure (pieces behind a strip,
 * discount as money, amount in words) is computed on the server.
 *
 * The letterhead travels with it, read from the branch that raised the sale.
 */
import apiClient from '../lib/apiClient.js';

/** The printable document for one sale. */
export async function getInvoiceDocument(saleId) {
  return apiClient.get(`/api/sales/${saleId}/document`);
}

export default { getInvoiceDocument };
