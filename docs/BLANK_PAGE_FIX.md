# Blank Page Debug & Fix

**Issue:** `http://localhost:5173/` showing blank page  
**Date:** August 14, 2026  
**Status:** FIXED ✅

---

## Root Cause

**Error in Browser Console:**
```
Uncaught SyntaxError: The requested module '/src/services/inventoryService.js' 
does not provide an export named 'allocateFEFO' (at QuickSales.jsx:16:10)
```

### What Happened

During Phase 2 implementation, we replaced the old `inventoryService.js` (which had mock data and utility functions) with a new API-based service. The old service had a function `allocateFEFO()` for batch allocation logic, which `QuickSales.jsx` was importing but never actually using.

---

## Fixes Applied

### 1. Fixed localStorage Key Mismatch in `apiClient.js`

**File:** `src/lib/apiClient.js`

**Issue:** TokenManager was using generic keys (`accessToken`, `refreshToken`, `user`)

**Fixed:** Changed to namespaced keys to avoid conflicts:
```javascript
// Before
getAccessToken() {
  return localStorage.getItem('accessToken');
}

// After
getAccessToken() {
  return localStorage.getItem('pharma_access_token');
}
```

**All Keys Changed:**
- `accessToken` → `pharma_access_token`
- `refreshToken` → `pharma_refresh_token`
- `user` → `pharma_user`

---

### 2. Removed Unused Import in `QuickSales.jsx`

**File:** `src/pages/QuickSales.jsx`

**Issue:** Importing non-existent `allocateFEFO` function

**Fixed:**
```javascript
// Before
import { allocateFEFO } from "../services/inventoryService.js";

// After
// TODO: allocateFEFO will be implemented when backend batch allocation API is ready
// (removed the import)
```

**Note:** The `allocateFEFO` function was imported but never actually used in the component. The POS currently uses mock batch data from `mockData.js`. When backend implements batch allocation API, this logic will be handled server-side.

---

### 3. Added Error Handling in `AppContext.jsx`

**File:** `src/lib/AppContext.jsx`

**Issue:** If `authService` throws an error during initial load, the entire app would crash

**Fixed:** Wrapped initial state calls in try-catch:
```javascript
const [isLoggedIn, setIsLoggedIn] = useState(() => {
  try {
    return authService.isAuthenticated();
  } catch (error) {
    console.error('[AppContext] Error checking auth state:', error);
    return false;
  }
});
```

---

## How to Test the Fix

### 1. Clear Browser Storage
Since we changed localStorage keys, clear old data:
1. Open DevTools (F12)
2. Go to **Application** tab → **Local Storage** → `http://localhost:5173`
3. Delete these old keys if they exist:
   - `accessToken`
   - `refreshToken`
   - `user`
4. Refresh the page

### 2. Verify Login Page Loads
1. Navigate to `http://localhost:5173`
2. **Expected:** Login page appears (not blank)
3. Should see:
   - Friends Pharma branding
   - Username/Password fields
   - "Sign In" and "Demo Users" tabs

### 3. Test Login
1. Enter credentials: `admin` / `admin123`
2. Click "Sign In"
3. **Expected:** 
   - If backend is running → Login succeeds, redirect to dashboard
   - If backend is NOT running → Error message: "Network error" or "Cannot connect to server"
4. Check localStorage → Should see new keys:
   - `pharma_access_token`
   - `pharma_refresh_token`
   - `pharma_user`

---

## Additional Notes

### Mock Data Still Used
The following pages still use `mockData.js` and will be converted in later phases:

- **QuickSales.jsx** - Uses `PRODUCTS`, `CATEGORIES`, `CUSTOMERS`, `batchesForProduct()`
- **Inventory.jsx** - Uses `PRODUCTS`, `BATCHES`, `stockForProduct()`
- **Sales.jsx** - Uses mock data
- **Purchase.jsx** - Uses mock data

These will be converted when their respective API services are integrated.

### Calculation Functions Preserved
These files contain pure calculation logic (no API calls) and remain unchanged:

- `src/services/salesService.js` - `calcSalesLine()`, `calcInvoiceTotals()`, etc.
- `src/services/purchaseService.js` - Purchase calculation functions

---

## Verification Checklist

- [x] `apiClient.js` - localStorage keys updated to `pharma_*` prefix
- [x] `AppContext.jsx` - Added try-catch for initial auth state
- [x] `QuickSales.jsx` - Removed unused `allocateFEFO` import
- [x] Page loads without JavaScript errors
- [x] Login page is visible (not blank)
- [ ] Backend running - test actual login
- [ ] Clear old localStorage keys

---

## What's Next

1. **Start Backend** (if not running):
   ```bash
   cd backend
   ./mvnw spring-boot:run
   ```

2. **Test Login Flow**:
   - Use credentials from backend seed data
   - Verify JWT tokens are stored correctly
   - Check Network tab for API calls

3. **Test POS Page**:
   - Navigate to Quick Sales
   - Currently uses mock data (POS API integration coming in Phase 3)

4. **Continue Phase 2 Testing**:
   - Follow `TESTING_GUIDE.md` for comprehensive test scenarios

---

**Status:** Page now loads successfully ✅  
**Next:** Test login flow with backend running

