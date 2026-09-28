/**
 * Confirmations that look like the rest of the application.
 *
 * `window.confirm` blocks the whole browser, cannot be styled, renders the
 * origin as a header ("localhost:5173 says"), and gives no room to explain what
 * is about to happen — on a dialog whose entire job is to make someone
 * understand a consequence before accepting it. Worse, it is easy to click past
 * precisely because it looks like every unwanted popup on the web.
 *
 * This keeps the one-line call site that made `window.confirm` convenient:
 *
 *   const confirm = useConfirm();
 *   if (!(await confirm({ title: "…", body: "…" }))) return;
 *
 * The promise resolves false for Cancel, Escape and clicking away, so a caller
 * that only checks for true cannot mistake a dismissal for consent.
 */
import React, { createContext, useCallback, useContext, useEffect, useRef, useState } from "react";
import { Modal } from "../components/UI.jsx";

const ConfirmContext = createContext(null);

/** Which button style the confirming action gets. */
const TONE_CLASS = {
  danger: "btn-danger",
  teal: "btn-teal",
  primary: "btn-primary",
};

export function ConfirmProvider({ children }) {
  const [dialog, setDialog] = useState(null);
  // The resolver lives in a ref rather than in state: settling the promise must
  // not depend on a re-render having happened first.
  const resolver = useRef(null);

  const settle = useCallback((answer) => {
    const resolve = resolver.current;
    resolver.current = null;
    setDialog(null);
    if (resolve) resolve(answer);
  }, []);

  const confirm = useCallback((opts) => {
    // A second request while one is open would strand the first promise
    // forever, and an awaited call that never settles is a frozen screen.
    if (resolver.current) resolver.current(false);
    return new Promise((resolve) => {
      resolver.current = resolve;
      setDialog(typeof opts === "string" ? { body: opts } : (opts ?? {}));
    });
  }, []);

  return (
    <ConfirmContext.Provider value={confirm}>
      {children}
      {dialog && <ConfirmDialog dialog={dialog} onSettle={settle} />}
    </ConfirmContext.Provider>
  );
}

function ConfirmDialog({ dialog, onSettle }) {
  const {
    title = "Are you sure?",
    body,
    detail,
    confirmLabel = "Continue",
    cancelLabel = "Cancel",
    tone = "primary",
  } = dialog;

  const confirmRef = useRef(null);

  useEffect(() => {
    // Focused so Enter works and a keyboard user is not hunting for the
    // dialog. Cancel is the safe default, so focusing Confirm is only
    // acceptable because Escape and click-away both mean no.
    confirmRef.current?.focus();

    const onKey = (e) => {
      if (e.key === "Escape") { e.preventDefault(); onSettle(false); }
      if (e.key === "Enter" && e.target?.tagName !== "BUTTON") {
        e.preventDefault(); onSettle(true);
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onSettle]);

  return (
    <Modal
      title={title}
      width={460}
      onClose={() => onSettle(false)}
      footer={
        <>
          <button className="btn btn-ghost" onClick={() => onSettle(false)}>
            {cancelLabel}
          </button>
          <button ref={confirmRef}
            className={`btn ${TONE_CLASS[tone] ?? TONE_CLASS.primary}`}
            onClick={() => onSettle(true)}>
            {confirmLabel}
          </button>
        </>
      }>
      {body && (
        <p style={{ fontSize: 13.5, lineHeight: 1.6, margin: 0 }}>{body}</p>
      )}
      {detail && (
        <p style={{ fontSize: 12.5, color: "var(--ink-soft)", lineHeight: 1.55, marginTop: 10 }}>
          {detail}
        </p>
      )}
    </Modal>
  );
}

/**
 * @returns {(opts: string | {
 *   title?: string, body?: React.ReactNode, detail?: React.ReactNode,
 *   confirmLabel?: string, cancelLabel?: string,
 *   tone?: 'primary' | 'teal' | 'danger',
 * }) => Promise<boolean>}
 */
export function useConfirm() {
  const confirm = useContext(ConfirmContext);
  if (!confirm) {
    // Loud rather than silently returning false: a confirmation that always
    // says no would look like the user cancelling, and nobody would find it.
    throw new Error("useConfirm must be used inside <ConfirmProvider>.");
  }
  return confirm;
}
