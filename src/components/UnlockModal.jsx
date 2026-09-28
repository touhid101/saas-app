/**
 * A supervisor standing at the till, permitting one correction.
 *
 * Shared by the POS and the Sales page, because the same rule applies wherever
 * a saved invoice is reopened — and a second copy of a security dialog is a
 * second place for it to drift.
 *
 * The password is never held beyond the request: it is exchanged immediately
 * for a token good for this invoice, this user, and one save. This dialog is
 * not the guard — the server verifies the credentials, checks that person is
 * allowed to authorise anything, and checks again when the edit is saved.
 */
import React, { useState } from "react";
import { Modal, Field } from "./UI.jsx";
import { useToast } from "../hooks/useToast.js";
import salesService from "../services/salesService.js";

export default function UnlockModal({ sale, onClose, onUnlocked }) {
  const addToast = useToast();
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);

  const saleId = sale.id ?? sale.pidTranMst;

  const submit = async (e) => {
    e?.preventDefault?.();
    if (!username.trim() || !password || busy) return;
    setBusy(true);
    setError(null);
    try {
      const { token, validMinutes } = await salesService.unlockInvoice(saleId, {
        username: username.trim(), password, reason,
      });
      // Cleared the moment it has been sent.
      setPassword("");
      addToast(`${sale.invoiceNo} unlocked for ${validMinutes} minutes.`);
      onUnlocked(token);
    } catch (err) {
      setError(err?.message || "That could not be authorised.");
      setPassword("");
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal title="Administrator authorisation" onClose={onClose} width={480}
      footer={
        <>
          <button className="btn btn-ghost" onClick={onClose}>Cancel</button>
          <button className="btn btn-primary" onClick={submit}
            disabled={busy || !username.trim() || !password}>
            {busy ? "Checking…" : "Unlock for editing"}
          </button>
        </>
      }>
      <form onSubmit={submit}>
        <p style={{ fontSize: 13.5, lineHeight: 1.6, marginTop: 0 }}>
          <b className="mono">{sale.invoiceNo}</b> has already been saved. Changing a saved
          invoice needs an administrator.
        </p>
        <p style={{ fontSize: 12.5, color: "var(--ink-soft)", lineHeight: 1.55 }}>
          An administrator can authorise this one correction. Their name, yours, and the reason
          are recorded against the invoice either way.
        </p>

        {error && (
          <div role="alert" style={{ margin: "12px 0", padding: "9px 11px", borderRadius: 8,
                                     background: "var(--clay-soft)", border: "1px solid var(--clay)",
                                     color: "var(--clay)", fontSize: 12.5 }}>
            {error}
          </div>
        )}

        <div style={{ display: "grid", gap: 10, marginTop: 12 }}>
          <Field label="Administrator username">
            <input value={username} onChange={(e) => setUsername(e.target.value)}
              autoFocus autoComplete="off" className="input" />
          </Field>
          <Field label="Administrator password">
            {/* new-password stops the browser offering the operator's own saved
                credentials — the one login guaranteed not to have authority. */}
            <input type="password" value={password} onChange={(e) => setPassword(e.target.value)}
              autoComplete="new-password" className="input" />
          </Field>
          <Field label="Reason">
            <input value={reason} onChange={(e) => setReason(e.target.value)}
              placeholder="What is being corrected?" className="input" />
          </Field>
        </div>
        <button type="submit" style={{ display: "none" }} aria-hidden="true" />
      </form>
    </Modal>
  );
}
