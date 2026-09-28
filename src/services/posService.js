/**
 * POS (Point of Sale) Service
 * Handles POS operations: cart management, sales transactions
 */
import apiClient from '../lib/apiClient.js';

/**
 * Get or create cart for current session
 * @param {number} orgId - Organization ID
 * @param {number} branchId - Branch ID
 * @param {number} userId - User ID
 * @returns {Promise<object>} Cart with items
 */
export async function getOrCreateCart(orgId, branchId, userId) {
  return await apiClient.get('/api/pos/cart', {
    params: { orgId, branchId, userId }
  });
}

/**
 * Lookup product by barcode or SKU
 * @param {string} barcode - Barcode or SKU
 * @param {number} orgId - Organization ID
 * @returns {Promise<object>} Product details
 */
export async function lookupProductByBarcode(barcode, orgId) {
  return await apiClient.get(`/api/pos/barcode/${barcode}`, {
    params: { orgId }
  });
}

/**
 * Add item to cart
 * @param {number} cartId - Cart ID
 * @param {object} item - {pidProduct, salesUnit, qty, unitPriceOverride}
 * @returns {Promise<object>} Updated cart
 */
export async function addItemToCart(cartId, item) {
  return await apiClient.post(`/api/pos/cart/${cartId}/items`, item);
}

/**
 * Update cart item
 * @param {number} cartId - Cart ID
 * @param {number} itemId - Cart item ID
 * @param {object} updates - {qty, discountPct}
 * @returns {Promise<object>} Updated cart
 */
export async function updateCartItem(cartId, itemId, updates) {
  return await apiClient.put(`/api/pos/cart/${cartId}/items/${itemId}`, updates);
}

/**
 * Remove item from cart
 * @param {number} cartId - Cart ID
 * @param {number} itemId - Cart item ID
 * @returns {Promise<object>} Updated cart
 */
export async function removeCartItem(cartId, itemId) {
  return await apiClient.delete(`/api/pos/cart/${cartId}/items/${itemId}`);
}

/**
 * Clear all items from cart
 * @param {number} cartId - Cart ID
 * @returns {Promise<object>} Empty cart
 */
export async function clearCart(cartId) {
  return await apiClient.delete(`/api/pos/cart/${cartId}/clear`);
}

/**
 * Hold cart for later
 * @param {number} cartId - Cart ID
 * @param {string} [holdNote] - Optional note
 * @returns {Promise<object>} Held cart with reference number
 */
export async function holdCart(cartId, holdNote = '') {
  return await apiClient.post(`/api/pos/cart/${cartId}/hold`, { holdNote });
}

/**
 * Recall held cart by reference
 * @param {string} cartRef - Cart reference (e.g., "C12345678")
 * @returns {Promise<object>} Recalled cart
 */
export async function recallCart(cartRef) {
  return await apiClient.post('/api/pos/cart/recall', { cartRef });
}

/**
 * List all held carts for a branch
 * @param {number} branchId - Branch ID
 * @returns {Promise<Array>} List of held carts
 */
export async function listHeldCarts(branchId) {
  return await apiClient.get('/api/pos/cart/held', {
    params: { branchId }
  });
}

/**
 * Assign customer to cart
 * @param {number} cartId - Cart ID
 * @param {number} customerId - Customer ID
 * @returns {Promise<object>} Updated cart
 */
export async function assignCustomerToCart(cartId, customerId) {
  return await apiClient.put(`/api/pos/cart/${cartId}/customer/${customerId}`);
}

/**
 * Finalize cart and complete sale
 * @param {object} saleData - {pidCart, pidCustomer, paymentType, additionalDiscountPct, remarks}
 * @returns {Promise<object>} Invoice details
 */
export async function finalizeCart(saleData) {
  return await apiClient.post('/api/pos/cart/finalize', saleData);
}

/**
 * Quick sale (single transaction without cart)
 * @param {object} saleData - {pidOrg, pidBranch, pidCustomer, paymentType, additionalDiscountPct, remarks, items}
 * @returns {Promise<object>} Invoice details
 */
export async function quickSale(saleData) {
  return await apiClient.post('/api/pos/quick-sale', saleData);
}

/**
 * Get receipt for transaction
 * @param {number} transactionId - Transaction/Invoice ID
 * @returns {Promise<object>} Receipt data
 */
export async function getReceipt(transactionId) {
  return await apiClient.get(`/api/pos/receipt/${transactionId}`);
}

export default {
  getOrCreateCart,
  lookupProductByBarcode,
  addItemToCart,
  updateCartItem,
  removeCartItem,
  clearCart,
  holdCart,
  recallCart,
  listHeldCarts,
  assignCustomerToCart,
  finalizeCart,
  quickSale,
  getReceipt
};
