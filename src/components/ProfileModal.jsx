import React, { useState, useRef, useEffect } from "react";
import { Modal, Field } from "./UI.jsx";
import { useApp } from "../lib/AppContext.jsx";
import * as authService from "../services/authService.js";
import { fmtDateTimeFriendly, initials } from "../utils/format.js";

const Ic = ({ d, size = 16, color }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none"
    stroke={color || "currentColor"} strokeWidth="1.9"
    strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    {Array.isArray(d) ? d.map((p, i) => <path key={i} d={p} />) : <path d={d} />}
  </svg>
);

const ACCEPTED = ["image/jpeg", "image/jpg", "image/png"];
const MAX_BYTES = 2 * 1024 * 1024;

/** Read-only row for identity fields the user cannot self-edit. */
function MetaRow({ icon, label, value }) {
  return (
    <div style={{ display: "flex", gap: 10, alignItems: "flex-start", padding: "9px 0" }}>
      <div style={{ color: "var(--ink-soft)", marginTop: 1, flexShrink: 0 }}>
        <Ic d={icon} size={14} />
      </div>
      <div style={{ minWidth: 0 }}>
        <div style={{ fontSize: 11, color: "var(--ink-soft)", fontWeight: 600, letterSpacing: ".02em" }}>{label}</div>
        <div style={{ fontSize: 13, color: "var(--ink)", marginTop: 1, wordBreak: "break-word" }}>
          {value || "—"}
        </div>
      </div>
    </div>
  );
}

export default function ProfileModal({ onClose, onOpenPassword }) {
  const { currentUser, currentAvatarUrl, patchCurrentUser, refreshCurrentUser, addToast } = useApp();

  const [form, setForm] = useState({
    fullName: currentUser?.fullName || currentUser?.name || "",
    email:    currentUser?.email || "",
    mobileNo: currentUser?.mobileNo || currentUser?.phoneNo || "",
  });
  const [errors, setErrors]     = useState({});
  const [saving, setSaving]     = useState(false);
  const [uploading, setUpload]  = useState(false);
  const [uploadErr, setUpErr]   = useState("");
  const fileRef = useRef(null);

  // Pull a fresh copy on open so the form never shows a stale cached user
  useEffect(() => {
    let cancelled = false;
    refreshCurrentUser()
      .then(fresh => {
        if (cancelled || !fresh) return;
        setForm({
          fullName: fresh.fullName || "",
          email:    fresh.email || "",
          mobileNo: fresh.mobileNo || "",
        });
      })
      .catch(() => { /* keep the cached values already in the form */ });
    return () => { cancelled = true; };
  }, [refreshCurrentUser]);

  const set = (key) => (e) => {
    setForm(f => ({ ...f, [key]: e.target.value }));
    setErrors(er => ({ ...er, [key]: undefined }));
  };

  const validate = () => {
    const er = {};
    if (!form.fullName.trim()) er.fullName = "Full name is required";
    else if (form.fullName.trim().length > 100) er.fullName = "Must be 100 characters or fewer";

    if (form.email.trim() && !/^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/.test(form.email.trim()))
      er.email = "Enter a valid email address";

    if (form.mobileNo.trim() && !/^[0-9+\-\s()]{6,20}$/.test(form.mobileNo.trim()))
      er.mobileNo = "Enter a valid phone number";

    setErrors(er);
    return Object.keys(er).length === 0;
  };

  const dirty =
    form.fullName.trim() !== (currentUser?.fullName || "") ||
    form.email.trim()    !== (currentUser?.email || "") ||
    form.mobileNo.trim() !== (currentUser?.mobileNo || "");

  const save = async () => {
    if (!validate()) return;
    setSaving(true);
    try {
      const updated = await authService.updateMyProfile({
        fullName: form.fullName.trim(),
        email:    form.email.trim(),
        mobileNo: form.mobileNo.trim(),
      });
      patchCurrentUser(updated || {
        fullName: form.fullName.trim(),
        email:    form.email.trim(),
        mobileNo: form.mobileNo.trim(),
      });
      addToast("Profile updated");
      onClose();
    } catch (err) {
      // Surface field-level conflicts where we can, otherwise a general message
      if (err?.errorCode === "EMAIL_TAKEN") setErrors({ email: err.error || err.message });
      else addToast(err?.message || "Could not update profile", "error");
    } finally {
      setSaving(false);
    }
  };

  const pickAvatar = async (e) => {
    const file = e.target.files?.[0];
    if (!file) return;
    setUpErr("");

    if (!ACCEPTED.includes(file.type)) {
      setUpErr("Only JPG, JPEG or PNG images are allowed");
      if (fileRef.current) fileRef.current.value = "";
      return;
    }
    if (file.size > MAX_BYTES) {
      setUpErr("Image must be under 2 MB");
      if (fileRef.current) fileRef.current.value = "";
      return;
    }

    setUpload(true);
    try {
      const updated = await authService.uploadMyAvatar(file);
      if (updated?.avatarUrl) patchCurrentUser({ avatarUrl: updated.avatarUrl });
      addToast("Photo updated");
    } catch (err) {
      setUpErr(err?.message || "Upload failed");
    } finally {
      setUpload(false);
      if (fileRef.current) fileRef.current.value = "";
    }
  };

  const roleColor = currentUser?.roleColor || "var(--teal)";
  const ini = initials(currentUser?.fullName || currentUser?.name || "FP") || "FP";

  return (
    <Modal
      title="My Profile"
      onClose={onClose}
      width={560}
      footer={
        <>
          <button className="btn btn-ghost" onClick={onClose} disabled={saving}>Cancel</button>
          <button className="btn btn-primary" onClick={save} disabled={saving || !dirty}>
            {saving ? "Saving…" : "Save Changes"}
          </button>
        </>
      }
    >
      {/* ── Identity header ── */}
      <div style={{ display: "flex", gap: 16, alignItems: "center", paddingBottom: 18, marginBottom: 18, borderBottom: "1px solid var(--line)" }}>
        <div style={{ position: "relative", flexShrink: 0 }}>
          <div style={{
            width: 72, height: 72, borderRadius: "50%", background: roleColor, color: "#fff",
            display: "flex", alignItems: "center", justifyContent: "center",
            fontWeight: 700, fontSize: 22, overflow: "hidden",
          }}>
            {currentAvatarUrl
              ? <img src={currentAvatarUrl} alt="" style={{ width: "100%", height: "100%", objectFit: "cover" }} />
              : ini}
          </div>
          <button
            type="button"
            onClick={() => !uploading && fileRef.current?.click()}
            aria-label="Change profile photo"
            title="Change profile photo"
            style={{
              position: "absolute", bottom: -2, right: -2, width: 26, height: 26, borderRadius: "50%",
              background: "var(--card)", border: "1.5px solid var(--line)", cursor: uploading ? "wait" : "pointer",
              display: "flex", alignItems: "center", justifyContent: "center", padding: 0,
              boxShadow: "var(--shadow)",
            }}
          >
            <Ic d={uploading
              ? "M21 12a9 9 0 1 1-6.22-8.56"
              : ["M23 19a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4l2-3h6l2 3h4a2 2 0 0 1 2 2z", "M12 17a4 4 0 1 0 0-8 4 4 0 0 0 0 8z"]}
              size={13} />
          </button>
          <input
            ref={fileRef} type="file" accept=".jpg,.jpeg,.png,image/jpeg,image/png"
            onChange={pickAvatar} style={{ display: "none" }}
          />
        </div>

        <div style={{ minWidth: 0, flex: 1 }}>
          <div style={{ fontSize: 17, fontWeight: 700, color: "var(--ink)" }}>
            {currentUser?.fullName || currentUser?.name || "—"}
          </div>
          <div style={{ display: "flex", gap: 6, alignItems: "center", flexWrap: "wrap", marginTop: 6 }}>
            <span style={{ background: roleColor, color: "#fff", fontSize: 11, fontWeight: 700, padding: "2px 9px", borderRadius: 999 }}>
              {currentUser?.roleName || "Staff"}
            </span>
            {currentUser?.superAdmin && (
              <span style={{ background: "var(--amber-soft)", color: "var(--amber-deep)", fontSize: 11, fontWeight: 700, padding: "2px 9px", borderRadius: 999 }}>
                Super Admin
              </span>
            )}
          </div>
          <div style={{ fontSize: 11.5, color: uploadErr ? "var(--clay)" : "var(--ink-soft)", marginTop: 7 }}>
            {uploading ? "Uploading photo…" : uploadErr || "JPG, JPEG or PNG · max 2 MB"}
          </div>
        </div>
      </div>

      {/* ── Editable fields ── */}
      <div style={{ fontSize: 11, fontWeight: 700, color: "var(--ink-soft)", textTransform: "uppercase", letterSpacing: ".08em", marginBottom: 12 }}>
        Personal Details
      </div>

      <Field label="Full Name" error={errors.fullName}>
        <input
          value={form.fullName} onChange={set("fullName")} maxLength={100}
          className={errors.fullName ? "field-error" : ""}
          placeholder="Your full name" autoComplete="name"
        />
      </Field>

      <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 14 }}>
        <Field label="Email" error={errors.email}>
          <input
            type="email" value={form.email} onChange={set("email")} maxLength={100}
            className={errors.email ? "field-error" : ""}
            placeholder="you@example.com" autoComplete="email"
          />
        </Field>
        <Field label="Phone" error={errors.mobileNo}>
          <input
            value={form.mobileNo} onChange={set("mobileNo")} maxLength={20}
            className={errors.mobileNo ? "field-error" : ""}
            placeholder="01XXXXXXXXX" autoComplete="tel"
          />
        </Field>
      </div>

      {/* ── Read-only account info ── */}
      <div style={{ fontSize: 11, fontWeight: 700, color: "var(--ink-soft)", textTransform: "uppercase", letterSpacing: ".08em", margin: "8px 0 4px" }}>
        Account
      </div>
      <div style={{ background: "var(--paper)", border: "1px solid var(--line)", borderRadius: "var(--radius-sm)", padding: "6px 14px" }}>
        <MetaRow
          icon={["M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2", "M12 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8z"]}
          label="Username" value={currentUser?.username}
        />
        <div style={{ height: 1, background: "var(--line)" }} />
        <MetaRow
          icon={["M12 22s7-6.5 7-12a7 7 0 1 0-14 0c0 5.5 7 12 7 12z", "M12 10a2 2 0 1 0 0-4 2 2 0 0 0 0 4z"]}
          label="Branch" value={currentUser?.branchName}
        />
        <div style={{ height: 1, background: "var(--line)" }} />
        <MetaRow
          icon="M12 8v4l3 2M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z"
          label="Last Login" value={fmtDateTimeFriendly(currentUser?.lastLogin)}
        />
      </div>
      <div style={{ fontSize: 11, color: "var(--ink-soft)", marginTop: 8 }}>
        Username, branch and role are managed by an administrator.
      </div>

      {/* ── Security ── */}
      <button
        type="button"
        onClick={() => { onClose(); onOpenPassword?.(); }}
        style={{
          width: "100%", marginTop: 18, padding: "12px 14px", borderRadius: "var(--radius-sm)",
          border: "1px solid var(--line)", background: "var(--card)", cursor: "pointer",
          display: "flex", alignItems: "center", gap: 12, textAlign: "left",
        }}
      >
        <span style={{ width: 32, height: 32, borderRadius: 9, background: "var(--amber-soft)", color: "var(--amber-deep)", display: "flex", alignItems: "center", justifyContent: "center", flexShrink: 0 }}>
          <Ic d={["M19 11H5a2 2 0 0 0-2 2v7a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7a2 2 0 0 0-2-2z", "M7 11V7a5 5 0 0 1 10 0v4"]} size={15} />
        </span>
        <span style={{ flex: 1 }}>
          <span style={{ display: "block", fontSize: 13.5, fontWeight: 600, color: "var(--ink)" }}>Change Password</span>
          <span style={{ display: "block", fontSize: 11.5, color: "var(--ink-soft)", marginTop: 1 }}>
            Requires your current password
          </span>
        </span>
        <Ic d="M9 18l6-6-6-6" size={14} color="var(--ink-soft)" />
      </button>
    </Modal>
  );
}
