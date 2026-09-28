# Frontend-Backend Integration Testing Guide

**Last Updated:** August 14, 2026  
**Phase:** 2 - Core Services Testing

---

## Prerequisites

### 1. Backend Running
```bash
cd backend

# Verify PostgreSQL is running
# Host: localhost:5432
# Database: minipos_db
# Schema: PHARMA
# User: pharma
# Password: pharma

# Start Spring Boot
./mvnw spring-boot:run

# OR if already compiled
java -jar target/pharma-app-backend.jar

# Verify backend is up
# Open browser: http://localhost:8080/swagger-ui.html
# Should see Swagger API documentation
```

### 2. Frontend Running
```bash
# From project root
npm install

# Start Vite dev server
npm run dev

# Should see:
# VITE v5.x.x  ready in X ms
# ➜  Local:   http://localhost:5173/
# ➜  Network: http://192.168.x.x:5173/
```

### 3. Environment Setup
Verify `.env` file exists in project root:
```bash
cat .env
```

Should contain:
```
VITE_API_BASE_URL=http://localhost:8080
```

---

## Test Scenarios

### ✅ Test 1: Login Flow

#### Test 1.1: Invalid Credentials
1. Open http://localhost:5173
2. Should redirect to `/login`
3. Enter username: `wronguser`, password: `wrongpass`
4. Click "Sign In"
5. **Expected:** 
   - Loading spinner shows briefly
   - Error message appears (red banner)
   - Message from backend: "Invalid username or password" (or similar)
   - Network tab shows `POST /api/auth/login` with 401 status

#### Test 1.2: Valid Login
1. Clear error, enter username: `admin`, password: `admin123`
2. Click "Sign In"
3. **Expected:**
   - Loading spinner shows
   - Success → redirect to `/dashboard`
   - Check localStorage: `pharma_access_token` and `pharma_refresh_token` keys exist
   - Check localStorage: `pharma_user` contains user object with `pidUser`, `username`, `fullName`, `roles`
   - Network tab shows `POST /api/auth/login` with 200 status

#### Test 1.3: Demo Users Tab
1. Logout (click user menu → Logout)
2. Navigate to `/login`
3. Click "Demo Users" tab
4. **Expected:**
   - Shows 3 demo users: Admin, Manager, Cashier
   - Each has colored avatar, name, role, username badge
5. Click on "System Admin" card
6. Click "Sign in as System"
7. **Expected:** Same as Test 1.2 (successful login)

#### Test 1.4: Session Persistence
1. Login as admin
2. Navigate to `/inventory`
3. Refresh page (F5)
4. **Expected:**
   - User stays logged in
   - Page reloads and shows inventory data
   - No redirect to login
5. Close browser tab, open new tab, navigate to http://localhost:5173
6. **Expected:** Still logged in

#### Test 1.5: Logout
1. While logged in, click user avatar in top-right
2. Click "Logout" from dropdown
3. **Expected:**
   - Redirect to `/login`
   - localStorage cleared (no tokens or user object)
   - Network tab shows `POST /api/auth/logout` (may be 200 or 401, both OK)
   - Trying to access `/dashboard` directly redirects to `/login`

---

### ✅ Test 2: Inventory Page

#### Test 2.1: Load Inventory Summary
1. Login as admin
2. Navigate to `/inventory`
3. **Expected:**
   - Network tab shows `GET /api/inventory/summary?orgId=1`
   - Top cards show:
     - Total Products
     - Total Stock Value
     - Low Stock Items
     - Out of Stock Items
     - Expiring Soon
   - Values from backend (not hardcoded 0)

#### Test 2.2: Low Stock Tab
1. Click "Low Stock" tab
2. **Expected:**
   - Network tab shows `GET /api/inventory/low-stock?orgId=1`
   - Table shows products with stock below alert quantity
   - Columns: Product Name, Current Stock, Alert Quantity, Last Updated

#### Test 2.3: Expiring Products
1. Click "Expiring Soon" tab
2. **Expected:**
   - Network tab shows `GET /api/inventory/expiring?orgId=1&days=90`
   - Table shows products expiring within 90 days
   - Columns: Product Name, Batch No, Expiry Date, Stock Qty

#### Test 2.4: Search Inventory
1. Type "para" in search box
2. **Expected:**
   - Network tab shows `GET /api/products/search?orgId=1&query=para`
   - Results show products matching "para" (e.g., Paracetamol)

---

### ✅ Test 3: POS Page (Quick Sales)

#### Test 3.1: Load Empty Cart
1. Navigate to `/quick-sales` or `/pos`
2. **Expected:**
   - Network tab shows `GET /api/pos/cart?orgId=1&branchId=1&userId=1`
   - Cart shows "No items in cart"
   - Total: 0.00

#### Test 3.2: Barcode Scan
1. Click barcode input field
2. Type: `1234567890123` (sample barcode)
3. Press Enter
4. **Expected:**
   - Network tab shows `GET /api/pos/barcode/1234567890123?orgId=1`
   - If product found:
     - Product details modal appears
     - Shows: Name, MRP, Company, Stock
     - "Add to Cart" button enabled
   - If not found:
     - Error toast: "Product not found"

#### Test 3.3: Add Item to Cart
1. After barcode scan (or product search)
2. Select quantity, unit (PCS/STRIP/BOX)
3. Click "Add to Cart"
4. **Expected:**
   - Network tab shows `POST /api/pos/cart/{cartId}/items`
   - Cart updates with new item
   - Subtotal, Grand Total calculate correctly
   - Item shows: Product name, qty, unit, price, line total

#### Test 3.4: Update Cart Item
1. With items in cart, click quantity field
2. Change quantity from 10 to 20
3. Tab out or press Enter
4. **Expected:**
   - Network tab shows `PUT /api/pos/cart/{cartId}/items/{itemId}`
   - Line total updates
   - Grand total recalculates

#### Test 3.5: Remove Item
1. Click "X" or "Remove" button on cart item
2. **Expected:**
   - Network tab shows `DELETE /api/pos/cart/{cartId}/items/{itemId}`
   - Item removed from cart
   - Totals recalculate

#### Test 3.6: Hold Cart
1. With items in cart, click "Hold" button
2. Enter hold note: "Customer will return later"
3. Click "Save"
4. **Expected:**
   - Network tab shows `POST /api/pos/cart/{cartId}/hold`
   - Cart clears
   - Toast: "Cart held successfully"
   - Cart reference shown (e.g., "C12345678")

#### Test 3.7: Recall Cart
1. Click "Recall Cart" button
2. Enter cart reference from Test 3.6
3. Click "Recall"
4. **Expected:**
   - Network tab shows `POST /api/pos/cart/recall`
   - Cart loads with previous items
   - All quantities and totals preserved

#### Test 3.8: Finalize Sale
1. With items in cart
2. (Optional) Assign customer
3. Select payment type: CASH
4. Click "Complete Sale"
5. **Expected:**
   - Network tab shows `POST /api/pos/cart/finalize`
   - Success modal shows invoice number
   - Invoice details: items, subtotal, discount, grand total
   - Cart clears
   - "Print Receipt" button available

---

### ✅ Test 4: Products Page

#### Test 4.1: Load Products List
1. Navigate to `/master-data` → Products tab
2. **Expected:**
   - Network tab shows `GET /api/products?orgId=1&page=0&size=20`
   - Products table shows: Name, Code, Barcode, MRP, Stock, Status
   - Pagination controls at bottom

#### Test 4.2: Search Products
1. Type "napa" in search box
2. **Expected:**
   - Network tab shows `GET /api/products/search?orgId=1&query=napa`
   - Results filter to matching products

#### Test 4.3: Create New Product
1. Click "Add Product" button
2. Fill form:
   - Product Name: "Test Medicine 500mg"
   - Barcode: "9999999999999"
   - Category: Select from dropdown
   - MRP: 150.00
   - Purchase Price: 100.00
   - Alert Qty: 10
3. Click "Save"
4. **Expected:**
   - Network tab shows `POST /api/products`
   - Success toast: "Product created successfully"
   - New product appears in table

#### Test 4.4: Edit Product
1. Click "Edit" icon on any product
2. Change MRP from 150.00 to 180.00
3. Click "Update"
4. **Expected:**
   - Network tab shows `PUT /api/products/{id}`
   - Success toast: "Product updated successfully"
   - Table updates with new MRP

#### Test 4.5: Delete Product
1. Click "Delete" icon on a product
2. Confirm deletion in modal
3. **Expected:**
   - Network tab shows `DELETE /api/products/{id}`
   - Success toast: "Product deleted successfully"
   - Product removed from table

---

### ✅ Test 5: Master Data Pages

#### Test 5.1: Categories
1. Navigate to `/master-data` → Categories tab
2. **Expected:**
   - Network tab shows `GET /api/master/categories`
   - Table shows: Category Name, Description, Status
3. Click "Add Category"
4. Fill: Name: "Test Category", Description: "For testing"
5. Click "Save"
6. **Expected:**
   - Network tab shows `POST /api/master/categories`
   - New category appears in table

#### Test 5.2: Customers
1. Navigate to `/master-data` → Customers tab
2. **Expected:**
   - Network tab shows `GET /api/master/customers?orgId=1&page=0&size=20`
   - Table shows: Name, Phone, Email, Type, Status
3. Click "Add Customer"
4. Fill customer details
5. Click "Save"
6. **Expected:**
   - Network tab shows `POST /api/master/customers`
   - New customer appears

#### Test 5.3: Edit/Delete (Backend Gap)
1. Click "Edit" icon on any master data entity
2. **Expected:**
   - Tooltip or modal: "Edit functionality coming soon"
   - OR: Form opens but shows message about backend limitation
3. Click "Delete" icon
4. **Expected:**
   - Same message about coming soon

---

### ✅ Test 6: Error Handling

#### Test 6.1: Network Error
1. Stop backend (Ctrl+C in backend terminal)
2. In frontend, try to load `/inventory`
3. **Expected:**
   - Error toast: "Network error" or "Cannot connect to server"
   - Page shows error state (not blank/spinning forever)
4. Restart backend
5. Refresh page
6. **Expected:** Data loads successfully

#### Test 6.2: 401 Unauthorized
1. Login successfully
2. Open browser DevTools → Application → Local Storage
3. Delete `pharma_access_token` key
4. Try to navigate to `/inventory`
5. **Expected:**
   - Network tab shows 401 response
   - Auto-refresh attempts with `POST /api/auth/refresh`
   - If refresh fails → redirect to `/login`

#### Test 6.3: Validation Error
1. Navigate to Products → Add Product
2. Leave required fields empty
3. Click "Save"
4. **Expected:**
   - Frontend validation: "This field is required"
   - OR Backend validation: Network shows 400 error
   - Error toast shows backend message

#### Test 6.4: 500 Server Error
1. (Simulate by temporarily breaking backend endpoint)
2. Make API call
3. **Expected:**
   - Error toast: Backend error message
   - Page doesn't crash
   - User can retry or navigate away

---

### ✅ Test 7: Token Refresh (Advanced)

#### Test 7.1: Automatic Token Refresh
1. Login successfully
2. Check backend `application.yml` for `jwt.expiration` (e.g., 3600 seconds = 1 hour)
3. Wait until token is close to expiry
4. Make any API call (e.g., load inventory)
5. **Expected:**
   - If token expired:
     - Network tab shows original request with 401
     - Automatically calls `POST /api/auth/refresh`
     - Retries original request with new token
     - Page shows data successfully (no redirect to login)

#### Test 7.2: Refresh Token Expired
1. Login successfully
2. Wait until both access token AND refresh token expire
3. Try to make API call
4. **Expected:**
   - `POST /api/auth/refresh` fails with 401
   - Redirect to `/login`
   - Toast: "Session expired. Please login again."

---

## 🐛 Common Issues & Fixes

### Issue 1: CORS Error
**Symptom:** Browser console shows `CORS policy` error  
**Fix:** Check backend `CorsConfig.java` allows `http://localhost:5173`

### Issue 2: 404 Not Found
**Symptom:** API calls return 404  
**Fix:** 
- Verify backend is running on port 8080
- Check `.env` has correct `VITE_API_BASE_URL`
- Restart Vite dev server after changing `.env`

### Issue 3: Blank Page After Login
**Symptom:** Login succeeds but dashboard is blank  
**Fix:**
- Check browser console for JavaScript errors
- Verify `user` object in localStorage has expected shape
- Check if dashboard widgets are trying to load data

### Issue 4: Tokens Not Persisting
**Symptom:** Refreshing page logs user out  
**Fix:**
- Check if localStorage is disabled in browser
- Verify `apiClient.js` uses `localStorage` (not `sessionStorage`)
- Check if browser is in Incognito/Private mode

### Issue 5: Mock Data Still Showing
**Symptom:** Old mock data appears instead of API data  
**Fix:**
- Clear browser cache and localStorage
- Hard refresh (Ctrl+Shift+R)
- Check if component is importing from `mockData.js` instead of service

---

## 📊 Test Results Template

```
=== FRONTEND-BACKEND INTEGRATION TEST RESULTS ===

Date: August 14, 2026
Tester: [Your Name]
Environment:
- Backend: http://localhost:8080 (Spring Boot v2.7.x)
- Frontend: http://localhost:5173 (Vite + React)
- Database: PostgreSQL minipos_db
- Browser: Chrome 123 / Firefox 122

Test 1: Login Flow
- [✅/❌] 1.1 Invalid Credentials
- [✅/❌] 1.2 Valid Login
- [✅/❌] 1.3 Demo Users Tab
- [✅/❌] 1.4 Session Persistence
- [✅/❌] 1.5 Logout

Test 2: Inventory Page
- [✅/❌] 2.1 Load Summary
- [✅/❌] 2.2 Low Stock Tab
- [✅/❌] 2.3 Expiring Products
- [✅/❌] 2.4 Search

Test 3: POS Page
- [✅/❌] 3.1 Load Cart
- [✅/❌] 3.2 Barcode Scan
- [✅/❌] 3.3 Add Item
- [✅/❌] 3.4 Update Item
- [✅/❌] 3.5 Remove Item
- [✅/❌] 3.6 Hold Cart
- [✅/❌] 3.7 Recall Cart
- [✅/❌] 3.8 Finalize Sale

Test 4: Products Page
- [✅/❌] 4.1 Load Products
- [✅/❌] 4.2 Search
- [✅/❌] 4.3 Create
- [✅/❌] 4.4 Edit
- [✅/❌] 4.5 Delete

Test 5: Master Data
- [✅/❌] 5.1 Categories
- [✅/❌] 5.2 Customers
- [✅/❌] 5.3 Edit/Delete (Backend Gap)

Test 6: Error Handling
- [✅/❌] 6.1 Network Error
- [✅/❌] 6.2 401 Unauthorized
- [✅/❌] 6.3 Validation Error
- [✅/❌] 6.4 500 Server Error

Test 7: Token Refresh
- [✅/❌] 7.1 Auto Refresh
- [✅/❌] 7.2 Refresh Expired

ISSUES FOUND:
1. [Description]
2. [Description]

OVERALL STATUS: [PASS / PARTIAL / FAIL]
```

---

## ✅ Next Steps After Testing

1. **If tests pass:** Proceed to Phase 3 (Sales & Purchase services)
2. **If tests fail:** 
   - Document specific failures
   - Check backend logs for errors
   - Verify API documentation matches actual responses
   - Update service files if needed
3. **Performance testing:**
   - Check page load times
   - Monitor API response times
   - Test with larger datasets (100+ products, customers, etc.)

---

**Happy Testing! 🚀**

