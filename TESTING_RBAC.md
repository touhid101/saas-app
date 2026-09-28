# RBAC Testing Guide

## Prerequisites ✅
- [x] Database has roles, modules, permissions inserted
- [x] Backend has RbacController, PermissionService, DTOs
- [x] Frontend has rbacService, AppContext menu loading, Sidebar using backend

## Step 1: Restart Backend

```bash
cd backend
mvn spring-boot:run
```

**Expected**: Backend starts without errors

## Step 2: Test Backend API Directly

### Test Menu Endpoint (after login)
```bash
# First, login to get token
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}'

# Copy the accessToken from response, then:
curl -X GET http://localhost:8080/api/v1/rbac/menu \
  -H "Authorization: Bearer YOUR_ACCESS_TOKEN_HERE"
```

**Expected Response**:
```json
{
  "success": true,
  "data": [
    {
      "pidModule": 1,
      "moduleCode": "MOD01",
      "moduleName": "Dashboard",
      "routePath": "/dashboard",
      "iconKey": "dashboard",
      "canView": true,
      "canCreate": true,
      "canEdit": true,
      "canDelete": true,
      "canApprove": true,
      "canExport": true,
      "children": []
    },
    ...16 modules total for Super Admin
  ]
}
```

## Step 3: Test Frontend

```bash
cd ..  # back to root
npm run dev
```

Navigate to http://localhost:5173

### Test Cases:

#### TC1: Super Admin Login
```
Username: admin
Password: admin123
```
**Expected**:
- Login successful
- Console shows: `[AppContext] Menu loaded from backend: Array(16)`
- Sidebar shows ALL 16 menus:
  - Dashboard
  - Master Data
  - Purchase
  - Sales
  - Quick Sales / POS
  - Sales Activity
  - Sales Performance
  - Sales Discount%
  - Delivery
  - Collection
  - Requisition
  - Invoice Merge
  - Inventory
  - Reports
  - AI Copilot
  - RBAC / Admin

#### TC2: Pharmacist Login
```
Username: pharmacist
Password: pharma123
```
**Expected**:
- Login successful
- Console shows: `[AppContext] Menu loaded from backend: Array(7)`
- Sidebar shows ONLY 7 menus:
  - Dashboard (View only)
  - Master Data (View only)
  - Sales (Can create/edit)
  - Quick Sales / POS (Can create/edit)
  - Sales Activity (View only)
  - Inventory (View only)
  - Reports (View + Export)
- **NOT showing**: Purchase, Delivery, Collection, RBAC, etc.

#### TC3: Branch Manager Login
```
Username: manager
Password: manager123
```
**Expected**:
- Login successful
- Sidebar shows 15 menus (everything except RBAC)
- Role badge shows "Branch Manager"

#### TC4: Accounts Login
```
Username: accounts
Password: accounts123
```
**Expected**:
- Login successful
- Sidebar shows ONLY 6 menus:
  - Dashboard
  - Sales (View only)
  - Sales Activity
  - Collection (Full access)
  - Invoice Merge (Full access)
  - Reports

## Common Issues & Solutions

### Issue 1: Backend fails to start
**Error**: `Could not autowire. No beans of type 'PosPermissionsRepository' found.`

**Solution**: Verify all repository files exist in `backend/src/main/java/com/friendspharma/app/repository/`

### Issue 2: 404 on /api/v1/rbac/menu
**Error**: `{"status":404,"error":"Not Found"}`

**Solution**: 
- Check backend logs
- Verify RbacController is annotated with `@RestController` and `@RequestMapping("/api/v1/rbac")`
- Verify SecurityConfig allows `/api/v1/rbac/menu` endpoint

### Issue 3: Menu returns empty array
**Error**: `[AppContext] Menu loaded from backend: []`

**Possible Causes**:
1. User has no role assigned: `SELECT pid_user, user_name, pid_role FROM acl_user;`
2. No permissions for that role: `SELECT COUNT(*) FROM pos_permissions WHERE pid_role = 3;`
3. All modules are inactive: `SELECT COUNT(*) FROM acl_module_info WHERE is_active = 1;`

**Solution**:
```sql
-- Check user's role
SELECT u.user_name, u.pid_role, g.group_name 
FROM acl_user u 
LEFT JOIN acl_group g ON u.pid_role = g.pid_group;

-- Check permissions for role
SELECT COUNT(*) FROM pos_permissions WHERE pid_role = 3;

-- If zero, run: backend/db/fix-permissions-sequence.sql again
```

### Issue 4: Frontend shows "Loading menu..." forever
**Cause**: API call failing

**Solution**:
1. Open browser DevTools → Network tab
2. Check for failed API calls to `/api/v1/rbac/menu`
3. Check error response
4. Common fixes:
   - 401: Token expired → Log out and log back in
   - 403: User not authorized → Check user's role in database
   - 500: Backend error → Check backend logs

### Issue 5: Sidebar still shows mock data
**Cause**: Old code cached

**Solution**:
1. Hard refresh browser: `Ctrl+Shift+R` (Windows) or `Cmd+Shift+R` (Mac)
2. Clear browser cache
3. Restart Vite dev server: `Ctrl+C` then `npm run dev`

## Debugging Tips

### Check Backend Logs
```bash
# In backend terminal, look for:
# - "buildMenuForUser called for user: 1"
# - "Found 16 permissions for role: 1"
```

### Check Frontend Console
```javascript
// Should see in browser console:
[AppContext] Menu loaded from backend: Array(X)

// If you see error:
[AppContext] Failed to load menu: Error: Network Error
// → Backend is not running or CORS issue
```

### Manual Database Verification
```sql
-- Check if permissions exist
SELECT 
    g.group_name,
    COUNT(*) as menu_count
FROM pos_permissions p
JOIN acl_group g ON p.pid_role = g.pid_group
GROUP BY g.group_name
ORDER BY g.pid_group;

-- Should show:
-- Super Admin: 16
-- Branch Manager: 15
-- Pharmacist: 7
-- Accounts: 6
-- etc.
```

## Success Criteria ✅

- [ ] Backend starts without errors
- [ ] GET /api/v1/rbac/menu returns menu JSON
- [ ] Admin login shows 16 menus in sidebar
- [ ] Pharmacist login shows 7 menus in sidebar
- [ ] Different users see different menus
- [ ] Console logs show menu loading from backend
- [ ] No errors in browser console
- [ ] No errors in backend logs

## Next Steps After Success

1. **Add Route Guards**: Prevent users from accessing pages they can't view
2. **Add Permission Checks**: Hide/disable buttons based on can_create, can_edit, can_delete
3. **Build RBAC Admin Page**: UI to manage roles, modules, permissions
4. **Re-enable BCrypt**: Change SecurityConfig back to BCryptPasswordEncoder for production

