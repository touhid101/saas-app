/**
 * The page the app is on, mirrored in the address bar.
 *
 * <b>Why this exists.</b> Navigation was a single `useState` and every menu item
 * was a `<button>`. That meant two things a user reasonably expects simply did
 * not work: there was no URL to open in a new tab, and no address to bookmark,
 * send to a colleague, or reload onto. Everything lived at `/`, so even a link
 * that did open a tab would have landed on the dashboard.
 *
 * <b>Why a path and not a hash.</b> This used `#purchase`, which needs no server
 * configuration — any host that serves `index.html` serves every page. The cost
 * is an address bar reading `/#dashboard`, which looks like a workaround because
 * it is one. Paths are the plain form, and the deployment already supports them:
 * nginx answers unknown paths with `index.html`
 *
 *     location / { try_files $uri $uri/ /index.html; }
 *
 * so a reload or a deep link cannot 404. Anywhere this is hosted needs that one
 * rule, or every refresh outside `/` returns the web server's own 404.
 *
 * <b>Old links still work.</b> A bookmark or message holding `/#purchase` is
 * read once on entry and rewritten to `/purchase`, so nothing anybody saved
 * breaks. The rewrite replaces rather than pushes: a redirect the user did not
 * ask for should not be a place the back button returns to.
 *
 * <b>Why not react-router.</b> It is in package.json and unused. Adopting it
 * means rewriting the page switch in App.jsx and every `onNavigate` callback
 * across thirty screens — a large change to deliver an address bar. This does
 * the same job for the way the app is already written, and does not stand in
 * the way of that migration later.
 */
import { useCallback, useEffect, useState } from "react";

/** `/purchase` → `purchase`. Empty path means the default page. */
function pageFromPath(fallback) {
  if (typeof window === "undefined") return fallback;
  const raw = window.location.pathname.replace(/^\/+/, "").replace(/\/+$/, "").trim();
  return raw || fallback;
}

/** `#purchase` → `purchase`, for links saved before the move to paths. */
function pageFromLegacyHash() {
  if (typeof window === "undefined") return "";
  return window.location.hash.replace(/^#\/?/, "").trim();
}

/**
 * @param fallback  the page shown when the URL names none
 * @param isValid   optional guard, so a hand-typed or stale URL cannot leave
 *                  the app on a page that does not exist
 */
export default function usePageRoute(fallback = "dashboard", isValid) {
  const [page, setPageState] = useState(() => {
    const p = pageFromLegacyHash() || pageFromPath(fallback);
    return !isValid || isValid(p) ? p : fallback;
  });

  /* Back and forward, and an address edited by hand, are the same event.
     popstate rather than hashchange, since the page now lives in the path. */
  useEffect(() => {
    const onPop = () => {
      const p = pageFromPath(fallback);
      setPageState(!isValid || isValid(p) ? p : fallback);
    };
    window.addEventListener("popstate", onPop);
    return () => window.removeEventListener("popstate", onPop);
  }, [fallback, isValid]);

  /* The address bar is written on entry too, so the first page has a URL worth
     copying rather than a bare `/` that means "wherever you happened to be".
     This is also where a legacy `#page` link loses its hash. */
  useEffect(() => {
    const want = `/${page}`;
    if (window.location.pathname !== want || window.location.hash) {
      window.history.replaceState(null, "", want + window.location.search);
    }
    // Only on mount: after this, navigate() writes the path itself and pushes a
    // history entry, which replaceState here would immediately flatten.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  /**
   * Go to a page. Accepts the updater form so existing `setPage(p => ...)`
   * callers keep working.
   */
  const navigate = useCallback((next) => {
    setPageState((prev) => {
      const target = typeof next === "function" ? next(prev) : next;
      if (target !== prev) {
        // pushState, not replaceState: each screen the user chose is somewhere
        // the back button should return them to.
        window.history.pushState(null, "", `/${target}`);
      }
      return target;
    });
  }, []);

  return [page, navigate];
}
