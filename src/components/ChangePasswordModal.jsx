import React, { useState } from "react";
import { Modal, Field } from "./UI.jsx";
import { useApp } from "../lib/AppContext.jsx";
import * as authService from "../services/authService.js";

const Ic = ({ d, size = 16, color }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none"
    stroke={color || "currentColor"} strokeWidth="1.9"
    strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    {Array.isArray(d) ? d.map((p, i) => <path key={i} d={p} />) : <path d={d} />}
  </svg>
);

const EYE      = ["M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7-10-7-10-7z", "M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6z"];
const EYE_OFF  = ["M17.94 17.94A10.1 10.1 0 0 1 12 20c-6.4 0-10-7-10-7a17.6 17.6 0 0 1 4.06-5.06", "M9.9 4.24A9.1 9.1 0 0 1 12 4c6.4 0 10 7 10 7a17.7 17.7 0 0 1-2.16 3.19", "M1 1l22 22", "M9.88 9.88a3 3 0 1 0 4.24 4.24"];

const MIN_LEN = 6;

/** Server error codes mapped to the field that should show the message. */
const ERROR_FIELD = {
  CURRENT_PASSWORD_MISMATCH: "current",
  CURRENT_PASSWORD_REQUIRED: "current",
  PASSWORD_REUSED:           "next",
};

function strengthOf(pw) {
  if (!pw) return null;
  let score = 0;
  if (pw.length >= MIN_LEN) score++;
  if (pw.length >= 10) score++;
  if (/[A-Z]/.test(pw) && /[a-z]/.test(pw)) score++;
  if (/\d/.test(pw)) score++;
  if (/[^A-Za-z0-9]/.test(pw)) score++;

  if (score <= 2) return { label: "Weak",   tone: "var(--clay)",  fill: 33 };
  if (score === 3) return { label: "Fair",   tone: "var(--amber)", fill: 55 };
  if (score === 4) return { label: "Good",   tone: "var(--teal)",  fill: 78 };
  return { label: "Strong", tone: "var(--sage)", fill: 100 };
}

function PasswordInput({ value, onChange, placeholder, autoComplete, invalid, id }) {
  const [show, setShow] = useState(false);
  return (
    <div style={{ position: "relative" }}>
      <input
        id={id}
        type={show ? "text" : "password"}
        value={value}
        onChange={onChange}
        placeholder={placeholder}
        autoComplete={autoComplete}
        className={invalid ? "field-error" : ""}
        style={{ paddingRight: 40, width: "100%" }}
      />
      <button
        type="button"
        onClick={() => setShow(s => !s)}
        aria-label={show ? "Hide password" : "Show password"}
        title={show ? "Hide password" : "Show password"}
        style={{
          position: "absolute", right: 6, top: "50%", transform: "translateY(-50%)",
          background: "none", border: "none", cursor: "pointer", padding: 6,
          color: "var(--ink-soft)", display: "flex", alignItems: "center",
        }}
      >
        <Ic d={show ? EYE_OFF : EYE} size={15} />
      </button>
    </div>
  );
}

export default function ChangePasswordModal({ onClose }) {
  const { addToast } = useApp();
  const [current, setCurrent] = useState("");
  const [next,    setNext]    = useState("");
  const [confirm, setConfirm] = useState("");
  const [errors,  setErrors]  = useState({});
  const [saving,  setSaving]  = useState(false);

  const strength = strengthOf(next);

  const clearErr = (key) => setErrors(e => ({ ...e, [key]: undefined, form: undefined }));

  const validate = () => {
    const er = {};
    if (!current)                 er.current = "Enter your current password";
    if (!next)                    er.next    = "Enter a new password";
    else if (next.length < MIN_LEN) er.next  = `Must be at least ${MIN_LEN} characters`;
    else if (next === current)     er.next   = "New password must be different from your current password";
    if (!confirm)                 er.confirm = "Re-enter the new password";
    else if (next && confirm !== next) er.confirm = "Passwords do not match";
    setErrors(er);
    return Object.keys(er).length === 0;
  };

  const submit = async (e) => {
    e?.preventDefault();
    if (!validate()) return;
    setSaving(true);
    try {
      // The server independently verifies the current password and rejects a
      // reused one — the checks above are only to save a round trip.
      await authService.changeMyPassword(current, next);
      addToast("Password changed successfully");
      onClose();
    } catch (err) {
      const msg   = err?.error || err?.message || "Could not change password";
      const field = ERROR_FIELD[err?.errorCode];
      setErrors(field ? { [field]: msg } : { form: msg });
    } finally {
      setSaving(false);
    }
  };

  return (
    <Modal
      title="Change Password"
      onClose={onClose}
      width={460}
      footer={
        <>
          <button className="btn btn-ghost" onClick={onClose} disabled={saving}>Cancel</button>
          <button className="btn btn-primary" onClick={submit} disabled={saving}>
            {saving ? "Updating…" : "Update Password"}
          </button>
        </>
      }
    >
      <form onSubmit={submit}>
        <div style={{
          display: "flex", gap: 10, padding: "11px 13px", marginBottom: 18,
          background: "var(--amber-soft)", borderRadius: "var(--radius-sm)",
          fontSize: 12.2, color: "var(--ink)", lineHeight: 1.5,
        }}>
          <span style={{ color: "var(--amber-deep)", flexShrink: 0, marginTop: 1 }}>
            <Ic d={["M19 11H5a2 2 0 0 0-2 2v7a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7a2 2 0 0 0-2-2z", "M7 11V7a5 5 0 0 1 10 0v4"]} size={15} />
          </span>
          <span>
            Confirm your current password to set a new one. The new password must be
            at least {MIN_LEN} characters and different from your current password.
          </span>
        </div>

        {errors.form && (
          <div style={{
            padding: "10px 13px", marginBottom: 14, borderRadius: "var(--radius-sm)",
            background: "var(--clay-soft)", color: "var(--clay)", fontSize: 12.5, fontWeight: 500,
          }} role="alert">
            {errors.form}
          </div>
        )}

        <Field label="Current Password" error={errors.current}>
          <PasswordInput
            id="pw-current"
            value={current}
            onChange={e => { setCurrent(e.target.value); clearErr("current"); }}
            placeholder="Your current password"
            autoComplete="current-password"
            invalid={!!errors.current}
          />
        </Field>

        <Field label="New Password" error={errors.next}>
          <PasswordInput
            id="pw-next"
            value={next}
            onChange={e => { setNext(e.target.value); clearErr("next"); }}
            placeholder={`At least ${MIN_LEN} characters`}
            autoComplete="new-password"
            invalid={!!errors.next}
          />
        </Field>

        {strength && (
          <div style={{ margin: "-6px 0 14px", display: "flex", alignItems: "center", gap: 10 }}>
            <div style={{ flex: 1, height: 4, borderRadius: 999, background: "var(--line)", overflow: "hidden" }}>
              <div style={{
                width: `${strength.fill}%`, height: "100%", background: strength.tone,
                borderRadius: 999, transition: "width .2s ease",
              }} />
            </div>
            <span style={{ fontSize: 11, fontWeight: 700, color: strength.tone, minWidth: 44 }}>
              {strength.label}
            </span>
          </div>
        )}

        <Field label="Confirm New Password" error={errors.confirm}>
          <PasswordInput
            id="pw-confirm"
            value={confirm}
            onChange={e => { setConfirm(e.target.value); clearErr("confirm"); }}
            placeholder="Re-enter the new password"
            autoComplete="new-password"
            invalid={!!errors.confirm}
          />
        </Field>

        {/* Lets Enter submit the form without a visible duplicate button */}
        <button type="submit" style={{ display: "none" }} aria-hidden="true" tabIndex={-1} />
      </form>
    </Modal>
  );
}
