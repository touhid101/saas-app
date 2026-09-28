/**
 * EmployeeAvatar.jsx — a staff photograph, or the initials behind it.
 *
 * <b>It never shows a broken image.</b> Three things can go wrong with a photo
 * — there isn't one, the file was deleted from under the row, or the request
 * fails — and all three land in the same place here: the initials the server
 * already sent. A component that renders `<img src={maybeNull}>` shows the
 * browser's broken-image glyph for two of those, which looks like a bug in the
 * application rather than an employee who has not uploaded a picture.
 *
 * The initials come from the server (`HrEmployee.getInitials()`), so the list,
 * the profile, the approval queue and the payslip all draw the same two letters
 * for the same person.
 */
import React, { useState, useEffect } from "react";
import uploadUrl from "../lib/uploadUrl.js";

/**
 * @param src       public URL from the server, or null
 * @param initials  fallback letters, also from the server
 * @param size      pixel diameter — 28 in a table, 40 in a list, 96 on a profile
 * @param title     tooltip, usually the person's name
 */
export default function EmployeeAvatar({ src, initials, name, size = 32, title }) {
  /* Reset on a new src, or replacing a photo leaves the failed flag set from
     the previous one and the new image never gets a chance to load. */
  const [failed, setFailed] = useState(false);
  useEffect(() => { setFailed(false); }, [src]);

  const letters = (initials || fallbackInitials(name) || "?").slice(0, 2);
  const showImage = src && !failed;

  const box = {
    width: size, height: size, borderRadius: "50%", flexShrink: 0,
    display: "inline-flex", alignItems: "center", justifyContent: "center",
    overflow: "hidden", background: "var(--paper-deep, var(--paper))",
    border: "1px solid var(--line)", color: "var(--ink-soft)",
    fontSize: Math.max(10, Math.round(size * 0.38)), fontWeight: 700,
    letterSpacing: ".02em", lineHeight: 1, userSelect: "none",
  };

  return (
    <span style={box} title={title || name || undefined}
          aria-label={name ? `Photograph of ${name}` : "Employee photograph"}>
      {showImage ? (
        <img src={uploadUrl(src)} alt=""
             onError={() => setFailed(true)}
             /* decoding async and lazy loading: a staff list of forty draws
                forty of these, and none of them is what the page is about. */
             loading="lazy" decoding="async"
             style={{ width: "100%", height: "100%", objectFit: "cover", display: "block" }} />
      ) : letters}
    </span>
  );
}

/** Only used when the server sent none — a client-side list built from a picker. */
function fallbackInitials(name) {
  if (!name || !name.trim()) return null;
  const parts = name.trim().split(/\s+/);
  return parts.length === 1
    ? parts[0].slice(0, 2).toUpperCase()
    : (parts[0][0] + parts[parts.length - 1][0]).toUpperCase();
}
