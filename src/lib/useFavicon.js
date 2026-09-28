/**
 * Points the browser tab at the pharmacy's own logo.
 *
 * <p>The tab is the one piece of an application a user sees while looking at
 * something else. On a platform serving many pharmacies it should say which
 * pharmacy, in the same way the sidebar does — and a tenant who has uploaded a
 * mark has already said what that should be.
 *
 * <p>The link element is created if the page has none and reused if it has one,
 * so this cooperates with whatever index.html declares rather than stacking a
 * second icon behind the first. Clearing the logo restores that original,
 * because a pharmacy removing its mark should get the default back, not a blank
 * tab.
 */
import { useEffect } from "react";
import uploadUrl from "./uploadUrl.js";

const REL = "icon";

export default function useFavicon(logoPath) {
  useEffect(() => {
    const href = uploadUrl(logoPath);
    if (!href) return;

    let link = document.querySelector(`link[rel~="${REL}"]`);
    const created = !link;
    if (created) {
      link = document.createElement("link");
      link.rel = REL;
      document.head.appendChild(link);
    }

    // Kept so the tab goes back to what the page shipped with, rather than to
    // whichever tenant was looked at last.
    const previous = link.getAttribute("href");
    link.setAttribute("href", href);

    return () => {
      if (created) link.remove();
      else if (previous) link.setAttribute("href", previous);
      else link.removeAttribute("href");
    };
  }, [logoPath]);
}
