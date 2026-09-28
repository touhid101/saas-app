/**
 * Absolute URL for a file this application uploaded.
 *
 * <p>Uploads are stored on disk and served by the backend — avatars, product
 * images, branch logos. In development the app runs on Vite's port and the
 * backend on its own, so a stored path like {@code /uploads/logo/branch_1_ab.png}
 * resolves against the wrong origin and the image silently fails to load. In
 * production, where both are behind one origin, the same path is already
 * correct and is returned untouched.
 *
 * <p>An absolute URL is passed through, so a stored value that already names a
 * host — a CDN, say — keeps working without this having to know about it.
 */
const API_BASE = import.meta.env.VITE_API_BASE_URL ?? "http://localhost:8080";

export default function uploadUrl(path) {
  if (!path) return null;
  if (/^https?:\/\//i.test(path)) return path;
  return path.startsWith("/") ? `${API_BASE}${path}` : `${API_BASE}/${path}`;
}
