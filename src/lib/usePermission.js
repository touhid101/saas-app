/**
 * usePermission — RBAC permission hook
 *
 * Reads the userMenu loaded by AppContext (from GET /api/v1/rbac/menu) and
 * returns the 6 action flags for the given page/module.
 *
 * Usage:
 *   const perm = usePermission('sales');
 *   // perm.canView, perm.canCreate, perm.canEdit,
 *   // perm.canDelete, perm.canApprove, perm.canExport
 *   // perm.isSuperAdmin  — true when user has all permissions globally
 *
 * The routeKey matches the routePath value stored in ACL_MODULE_INFO
 * (the ROUTE_KEY column), which is also used as the nav item ID in Sidebar.jsx.
 *
 * Route keys (from seed data):
 *   dashboard | master | purchase | sales | quicksales | activity |
 *   salesperformance | salesdiscount | delivery | collection |
 *   requisition | merge | inventory | reports | ai | rbac
 *
 * Super Admin: backend already pre-filters menu to only modules with canView=true,
 * so Super Admins who have all 6 flags = true on every module will naturally
 * receive full permissions. isSuperAdmin is a convenience flag — true when the
 * current user's roleCode is ROLE01 or when they have all flags on every module.
 */
import { useCallback } from "react";
import { useApp } from "./AppContext.jsx";

const FULL_PERM = {
  canView: true,
  canCreate: true,
  canEdit: true,
  canDelete: true,
  canApprove: true,
  canExport: true,
  isSuperAdmin: true,
};

const NO_PERM = {
  canView: false,
  canCreate: false,
  canEdit: false,
  canDelete: false,
  canApprove: false,
  canExport: false,
  isSuperAdmin: false,
};

/**
 * Hook — call inside any page component.
 * @param {string} routeKey  e.g. "sales", "purchase", "rbac"
 * @returns {object} permission flags
 */
export function usePermission(routeKey) {
  const { userMenu, currentUser } = useApp();

  // Super Admin shortcut — roleCode ROLE01 means full access everywhere
  const isSuperAdmin =
    currentUser?.roleCode === "ROLE01" ||
    currentUser?.superAdmin === true;

  if (isSuperAdmin) {
    return { ...FULL_PERM, isSuperAdmin: true };
  }

  // Menu not loaded yet — return no permissions (UI will show loading state)
  if (!userMenu || userMenu.length === 0) {
    return { ...NO_PERM, isSuperAdmin: false };
  }

  // Walk the flat+tree structure to find the menu item matching this route
  const findItem = (items, key) => {
    for (const item of items) {
      // Match by routePath (e.g. "/sales" or "sales") or moduleCode (e.g. "MOD04")
      const path = (item.routePath || "").replace(/^\//, "");
      if (path === key || item.moduleCode?.toLowerCase() === key?.toLowerCase()) {
        return item;
      }
      // Search children
      if (item.children?.length) {
        const found = findItem(item.children, key);
        if (found) return found;
      }
    }
    return null;
  };

  const item = findItem(userMenu, routeKey);

  if (!item) {
    // Module not in menu = user has no canView permission for it
    return { ...NO_PERM, isSuperAdmin: false };
  }

  return {
    canView:    item.canView    === true || item.canView    === 1,
    canCreate:  item.canCreate  === true || item.canCreate  === 1,
    canEdit:    item.canEdit    === true || item.canEdit    === 1,
    canDelete:  item.canDelete  === true || item.canDelete  === 1,
    canApprove: item.canApprove === true || item.canApprove === 1,
    canExport:  item.canExport  === true || item.canExport  === 1,
    isSuperAdmin: false,
  };
}

/**
 * useAllPermissions — returns a lookup function for any route key.
 * Useful in shared components that need to check multiple modules.
 *
 * Usage:
 *   const getPerms = useAllPermissions();
 *   const salesPerms = getPerms('sales');
 *   const purchasePerms = getPerms('purchase');
 */
export function useAllPermissions() {
  const { userMenu, currentUser } = useApp();

  const isSuperAdmin =
    currentUser?.roleCode === "ROLE01" ||
    currentUser?.superAdmin === true;

  return useCallback((routeKey) => {
    if (isSuperAdmin) return { ...FULL_PERM, isSuperAdmin: true };
    if (!userMenu || userMenu.length === 0) return { ...NO_PERM };

    const findItem = (items, key) => {
      for (const item of items) {
        const path = (item.routePath || "").replace(/^\//, "");
        if (path === key || item.moduleCode?.toLowerCase() === key?.toLowerCase()) return item;
        if (item.children?.length) {
          const found = findItem(item.children, key);
          if (found) return found;
        }
      }
      return null;
    };

    const item = findItem(userMenu, routeKey);
    if (!item) return { ...NO_PERM };

    return {
      canView:    item.canView    === true || item.canView    === 1,
      canCreate:  item.canCreate  === true || item.canCreate  === 1,
      canEdit:    item.canEdit    === true || item.canEdit    === 1,
      canDelete:  item.canDelete  === true || item.canDelete  === 1,
      canApprove: item.canApprove === true || item.canApprove === 1,
      canExport:  item.canExport  === true || item.canExport  === 1,
      isSuperAdmin: false,
    };
  }, [userMenu, isSuperAdmin]); // eslint-disable-line react-hooks/exhaustive-deps
}

export default usePermission;
