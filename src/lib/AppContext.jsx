import React, { createContext, useContext, useState, useCallback, useEffect } from "react";
import { ROLES, REQUISITIONS as SEED_REQS } from "./mockData.js";
import notificationService from "../services/notificationService.js";

/* Capability module codes. Named here so no screen carries a bare "MOD28". */
export const CAP_NOTIFICATIONS = "MOD28";
export const CAP_GLOBAL_SEARCH = "MOD29";
export const CAP_BRANCH_CATALOGUE = "MOD30";

/**
 * The routes that make a user an HR user.
 *
 * <p>Route paths rather than module codes: the menu is keyed by route, and
 * these seven are the same seven `App.jsx` renders. A user with none of them in
 * their menu is never asked for the HR summary.
 */
const HR_ROUTES = new Set([
  "hr", "attendance", "leave", "advances", "payroll", "expenses", "approvals",
  "hrmaster", "hrreports",
]);
import * as authService from "../services/authService.js";
import * as rbacService from "../services/rbacService.js";
import { getBranches, getOrganizations, getCompanies } from "../services/masterDataService.js";

const AppContext = createContext(null);

export function AppProvider({ children }) {
  /* ── Auth ── */
  const [isLoggedIn,  setIsLoggedIn]  = useState(() => {
    try {
      return authService.isAuthenticated();
    } catch (error) {
      console.error('[AppContext] Error checking auth state:', error);
      return false;
    }
  });
  
  const [currentUser, setCurrentUser] = useState(() => {
    try {
      return authService.getCurrentUser();
    } catch (error) {
      console.error('[AppContext] Error getting current user:', error);
      return null;
    }
  });

  /* ── RBAC user menu from backend ── */
  const [userMenu, setUserMenu] = useState(null); // null = not loaded yet

  /* [V71] True while this account holds a password an administrator issued.
     Session state only, never persisted: it is re-established by the login
     response every time, and a stale copy in localStorage would either trap a
     user on the change screen after they had complied or, worse, let them past
     it. The server refuses everything but the change either way. */
  const [mustChangePassword, setMustChangePassword] = useState(false);
  const [menuLoading, setMenuLoading] = useState(false);

  const login = async (username, password) => {
    try {
      const response = await authService.login(username, password);
      const user = response.user;
      
      setCurrentUser(user);
      setIsLoggedIn(true);
      setMustChangePassword(response.mustChangePassword === true);

      // Clear previous menu to trigger reload
      setUserMenu(null);
      
      return response;
    } catch (error) {
      setCurrentUser(null);
      setIsLoggedIn(false);
      setMustChangePassword(false);
      throw error;
    }
  };
  
  const logout = async () => {
    try {
      await authService.logout();
    } finally {
      setCurrentUser(null);
      setIsLoggedIn(false);
      setUserMenu(null); // Clear menu on logout
    }
  };

  /* ── User avatar ── */
  const [userAvatars, setUserAvatarsState] = useState({});
  const setUserAvatar = (userId, dataUrl) => {
    setUserAvatarsState(prev => ({ ...prev, [userId]: dataUrl }));
  };

  /** Merge fields into the current user in context + localStorage.
   *  Used after profile edits / avatar uploads so the UI updates without a reload. */
  const patchCurrentUser = useCallback((changes) => {
    setCurrentUser(prev => {
      if (!prev) return prev;
      const updated = { ...prev, ...changes };
      // Persist so the change survives a page refresh
      try { localStorage.setItem('pharma_user', JSON.stringify(updated)); } catch (_) {}
      return updated;
    });
  }, []);

  /** Update the current user's avatarUrl in context + localStorage after a server upload */
  const updateCurrentUserAvatar = useCallback((newAvatarUrl) => {
    patchCurrentUser({ avatarUrl: newAvatarUrl });
  }, [patchCurrentUser]);

  /** Re-fetch the authenticated user from GET /api/auth/me. */
  const refreshCurrentUser = useCallback(async () => {
    const fresh = await authService.getMe();
    if (fresh) setCurrentUser(fresh);
    return fresh;
  }, []);

  /**
   * Bring the cached session up to date once, on load.
   *
   * <p>currentUser is read from localStorage, where it was written at sign-in
   * and then left alone. Anything about the user or their branch that changes
   * server-side afterwards stays wrong in that copy until they sign out — and
   * the branch logo is the case where it shows: replacing a pharmacy's logo
   * writes a new filename and deletes the old file, so every session still
   * holding the previous URL asks for a file that is gone, gets a 404, and
   * falls back to the initial. The logo looks broken for everyone already
   * signed in, and correct for anyone who signs in after.
   *
   * <p>One request on load, and the name, role, avatar and logo are all the
   * server's current answer. Failure is ignored on purpose: the cached copy is
   * still good enough to run the app, and an expired token is the refresh
   * interceptor's business, not this effect's.
   */
  useEffect(() => {
    // Nobody signed in: there is nothing to refresh, and asking would be a
    // guaranteed 401 on every load of the login screen.
    if (!currentUser) return;
    refreshCurrentUser().catch(() => {});
    // Once per mount. Re-running it whenever currentUser changes would loop:
    // the refresh sets currentUser, which would schedule another refresh.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Resolve relative backend avatar paths to absolute URLs (stored as /uploads/avatars/...)
  const _API_BASE = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080';
  const _resolveUrl = (url) => {
    if (!url) return null;
    if (url.startsWith('http://') || url.startsWith('https://')) return url;
    if (url.startsWith('/')) return `${_API_BASE}${url}`;
    return url;
  };
  // Backend users are keyed by userId; mock/demo users by id — accept either.
  const currentUserKey = currentUser?.userId ?? currentUser?.id ?? null;
  const rawAvatarUrl = currentUser
    ? (currentUserKey != null ? userAvatars[currentUserKey] : null) || currentUser.avatarUrl
    : null;
  const currentAvatarUrl = _resolveUrl(rawAvatarUrl);

  /* ── Branch / Org ── */
  // Seeded with empty arrays — populated from real API after login
  const [branches,     setBranches]     = useState([]);
  const [organizations,setOrganizations]= useState([]);
  const [orgCompanies, setOrgCompanies] = useState([]);
  const [currentBranch, setCurrentBranch] = useState(null);
  const [currentOrg,    setCurrentOrg]    = useState(null);

  /* ── Notifications ──
     Counted from the ledger for this user's branch, not seeded from mockData.
     `notificationsReady` separates "not fetched yet" from "fetched, nothing to
     report": the topbar must not flash a bell that is about to disappear for a
     role that has no right to it. */
  const [notifications, setNotifications] = useState([]);

  const refreshNotifications = useCallback(async () => {
    const { items } = await notificationService.getNotifications();
    setNotifications(notificationService.applyReadState(items));
  }, []);

  const markNotificationRead = useCallback((id) => {
    setNotifications(n => n.map(x => {
      if (x.id !== id) return x;
      notificationService.markRead(x.id, x.count);
      return { ...x, read: true };
    }));
  }, []);

  const markAllRead = useCallback(() => {
    setNotifications(n => {
      notificationService.markAllRead(n);
      return n.map(x => ({ ...x, read: true }));
    });
  }, []);

  const unreadCount = notifications.filter(n => !n.read).length;

  /* ── Toasts ── */
  const [toasts, setToasts] = useState([]);
  const addToast = useCallback((message, type = "success") => {
    const id = Date.now();
    setToasts(t => [...t, { id, message, type }]);
    setTimeout(() => setToasts(t => t.filter(x => x.id !== id)), 3500);
  }, []);

  /* ── UI state ── */
  const [searchOpen, setSearchOpen] = useState(false);
  const [sidebarCollapsed, setSidebarCollapsed] = useState(false);

  /* ── Product images ── */
  const [productImages, setProductImages] = useState({});
  const setProductImage = (productId, dataUrl) => {
    setProductImages(prev => ({ ...prev, [productId]: dataUrl }));
  };

  /* ── Theme ── */
  const [theme, setThemeState] = useState(() => localStorage.getItem("pharma-theme") || "light");
  const setTheme = (t) => {
    setThemeState(t);
    localStorage.setItem("pharma-theme", t);
  };
  useEffect(() => {
    const root = document.documentElement;
    if (theme === "light") root.removeAttribute("data-theme");
    else root.setAttribute("data-theme", theme);
  }, [theme]);

  /* ── Sales Discount rules ── */
  const [discountRules, setDiscountRulesState] = useState({});
  const setDiscountRule = (productId, rule) => {
    setDiscountRulesState(prev => ({ ...prev, [productId]: rule }));
  };

  /* ── Global Requisitions (shared between AIAgent → Requisition page) ── */
  const [globalReqs, setGlobalReqs] = useState(() => SEED_REQS.map(r => ({ ...r })));
  const addRequisition = useCallback((req) => {
    setGlobalReqs(prev => [req, ...prev]);
  }, []);
  const updateRequisition = useCallback((id, changes) => {
    setGlobalReqs(prev => prev.map(r => r.id === id ? { ...r, ...changes } : r));
  }, []);
  
  /* ── Listen for session-expired event fired by apiClient ── */
  useEffect(() => {
    const handleSessionExpired = () => {
      console.warn('[AppContext] Session expired — logging out');
      setCurrentUser(null);
      setIsLoggedIn(false);
      setUserMenu(null);
      setBranches([]);
      setOrganizations([]);
      setOrgCompanies([]);
      setCurrentBranch(null);
      setCurrentOrg(null);
    };
    window.addEventListener('pharma:session-expired', handleSessionExpired);
    return () => window.removeEventListener('pharma:session-expired', handleSessionExpired);
  }, []);

  /* ── Load menu after login ── */
  useEffect(() => {
    if (isLoggedIn && !userMenu && !menuLoading) {
      console.log('[AppContext] Loading menu for logged in user...');
      setMenuLoading(true);
      rbacService.getMyMenu()
        .then(menu => {
          console.log('[AppContext] Menu loaded from backend:', menu);
          setUserMenu(menu);
        })
        .catch(error => {
          console.error('[AppContext] Failed to load menu:', error);
          // Fallback: set empty menu so sidebar doesn't break
          setUserMenu([]);
        })
        .finally(() => {
          setMenuLoading(false);
        });
    }
  }, [isLoggedIn]); // Only depend on isLoggedIn, not userMenu or menuLoading

  /* ── Load branches / orgs / companies from real API after login ── */
  useEffect(() => {
    if (!isLoggedIn) {
      // Clear on logout
      setBranches([]);
      setOrganizations([]);
      setOrgCompanies([]);
      setCurrentBranch(null);
      setCurrentOrg(null);
      return;
    }
    const orgId = currentUser?.orgId ?? null;
    // Load in parallel, silently ignore failures (each consumer has its own error handling)
    Promise.allSettled([
      getOrganizations(),
      getBranches(orgId),
      orgId ? getCompanies(orgId) : Promise.resolve([]),
    ]).then(([orgsRes, branchRes, compRes]) => {
      const orgs  = orgsRes.status  === 'fulfilled' ? (orgsRes.value  ?? []) : [];
      const brs   = branchRes.status === 'fulfilled' ? (branchRes.value ?? []) : [];
      const cos   = compRes.status  === 'fulfilled' ? (compRes.value  ?? []) : [];

      setOrganizations(orgs);
      setBranches(brs);
      setOrgCompanies(cos);

      // Set current org from the JWT claim
      if (orgId) {
        const org = orgs.find(o => o.id === orgId);
        if (org) setCurrentOrg(org);
        else if (orgs.length > 0) setCurrentOrg(orgs[0]);
      } else if (orgs.length > 0) {
        setCurrentOrg(orgs[0]);
      }

      // Set current branch from the JWT claim (branchId stored as pidBranch)
      const userBranchId = currentUser?.branchId ?? currentUser?.pidBranch ?? null;
      if (userBranchId) {
        const br = brs.find(b => b.id === userBranchId);
        if (br) { setCurrentBranch(br); return; }
      }
      if (brs.length > 0) setCurrentBranch(brs[0]);
    });
  }, [isLoggedIn, currentUser?.orgId]); // eslint-disable-line react-hooks/exhaustive-deps
  
  /* ── Capabilities: permissions with no page behind them ──
     Notifications and Global Search are granted in RBAC like any module, but
     they are deliberately absent from the menu because there is nowhere to
     navigate to. The topbar reads them from here to decide which buttons to
     draw, instead of calling each feature to find out whether it exists. */
  const [capabilities, setCapabilities] = useState([]);
  const can = useCallback((code) => capabilities.includes(code), [capabilities]);

  useEffect(() => {
    if (!isLoggedIn) { setCapabilities([]); return; }
    rbacService.getMyCapabilities().then(setCapabilities);
  }, [isLoggedIn]);

  /* ── Alerts, for those entitled to them ──
     Gated on the capability rather than fetched and thrown away: a role with no
     bell has no reason to ask what would be in it. The server enforces the same
     right regardless — this only avoids the pointless call. */
  useEffect(() => {
    if (!isLoggedIn || !can(CAP_NOTIFICATIONS)) {
      setNotifications([]);
      return;
    }
    refreshNotifications();
  }, [isLoggedIn, can, refreshNotifications]);

  /* ══════════════════════════════════════════════════════════════════════
     HR — what is waiting, counted once
     ══════════════════════════════════════════════════════════════════════

     The sidebar badges and the dashboard's People tab both want the same
     figures, and both are drawn on every page. Counting them here means one
     request rather than two components each asking, and — more importantly —
     one set of numbers: a badge saying 3 beside a tile saying 4 is worse than
     neither being there.

     Gated on the menu, not attempted and caught. `summary()` needs the employee
     module's View right, so a cashier fetching it earns a 403 on every page
     load to be told a number they may not see. If the server did not put an HR
     page in this user's menu, there is nothing to badge. */
  const [hrCounts, setHrCounts] = useState(null);   // null = not fetched yet

  /** Whether this user's menu contains any HR page at all. */
  const hasHrPage = React.useMemo(() => {
    const walk = (items) => (items ?? []).some((m) => {
      const route = (m.routePath || "").replace(/^\//, "");
      return HR_ROUTES.has(route) || walk(m.children);
    });
    return walk(userMenu);
  }, [userMenu]);

  const refreshHrCounts = useCallback(async () => {
    if (!hasHrPage) { setHrCounts(null); return; }
    try {
      // Imported here rather than at the top: this file loads on every page,
      // and the HR service has no business in the bundle a cashier downloads.
      // It also means the envelope is unwrapped by the one function that knows
      // how, instead of a second copy of that logic living here.
      const { getSummary } = await import("../services/hrService.js");
      setHrCounts(await getSummary());
    } catch {
      // A badge is not worth an error message. Absent beats wrong, and the
      // pages themselves report their own failures perfectly well.
      setHrCounts(null);
    }
  }, [hasHrPage]);

  useEffect(() => {
    if (!isLoggedIn || !hasHrPage) { setHrCounts(null); return; }
    refreshHrCounts();
  }, [isLoggedIn, hasHrPage, refreshHrCounts]);

  /* ── RBAC custom menus (for RBAC page editing) ── */
  const [customMenus, setCustomMenus] = useState(null); // null = use defaults from mockData

  const currentRole = ROLES.find(r => r.id === currentUser?.roleId);

  return (
    <AppContext.Provider value={{
      /* auth */
      isLoggedIn, login, logout,
      mustChangePassword, setMustChangePassword,
      currentUser, setCurrentUser,
      userAvatars, setUserAvatar, currentAvatarUrl, updateCurrentUserAvatar,
      patchCurrentUser, refreshCurrentUser,
      /* branch/org — now loaded from real API */
      currentBranch, setCurrentBranch,
      currentOrg, setCurrentOrg,
      branches, organizations, orgCompanies,
      /* capabilities — permissions with no page */
      capabilities, can,
      /* notifications */
      notifications, unreadCount,
      markNotificationRead, markAllRead, refreshNotifications,
      /* toasts */
      toasts, addToast,
      /* ui */
      searchOpen, setSearchOpen,
      sidebarCollapsed, setSidebarCollapsed,
      /* products */
      productImages, setProductImage,
      /* theme */
      theme, setTheme,
      /* discounts */
      discountRules, setDiscountRule,
      /* requisitions (global) */
      globalReqs, setGlobalReqs, addRequisition, updateRequisition,
      /* hr — the counts the sidebar badges and the dashboard both read */
      hrCounts, hasHrPage, refreshHrCounts,
      /* rbac */
      userMenu, menuLoading, setUserMenu,
      customMenus, setCustomMenus,
      currentRole,
    }}>
      {children}
    </AppContext.Provider>
  );
}

export function useApp() {
  const ctx = useContext(AppContext);
  if (!ctx) throw new Error("useApp must be used within AppProvider");
  return ctx;
}
