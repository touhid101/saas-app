/**
 * ReceiptKit.jsx — receipts on an expense claim: the thumbnail on a line, the
 * gallery under a claim, and the viewer either one opens.
 *
 * A receipt is evidence. The person filing wants to see at a glance which
 * lines still need one; the approver wants to look at the picture while
 * deciding the amount, without leaving the claim. So the receipt is shown as a
 * picture everywhere it appears — never as a link that says "Receipt" — and
 * opens in a viewer that sits over whatever is already open (a drawer, the
 * approval modal), with the line's kind, date and amount beside it.
 *
 * Uploads go through {@link pickReceipt}: the same checks the server makes
 * (JPEG/PNG/WebP, at most 2 MB), said before the round trip so a refusal is a
 * sentence, not a red toast.
 */
import React, { useCallback, useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import uploadUrl from "../lib/uploadUrl.js";
import { Ic, P, HrButton } from "./HrKit.jsx";
import { IMAGE_ACCEPT, money } from "../services/hrService.js";

/** The server's limit (app.upload.max-size-bytes), so the refusal is the same sentence either way. */
export const RECEIPT_MAX_BYTES = 2 * 1024 * 1024;

/** Why a picked file will be refused, or null when it will be accepted. */
export function receiptProblem(file) {
  if (!file) return "Choose a file.";
  if (!/^image\/(jpeg|png|webp)$/.test(file.type)) return "A receipt is a photograph — JPEG, PNG or WebP.";
  if (file.size > RECEIPT_MAX_BYTES) return `That picture is ${(file.size / 1048576).toFixed(1)} MB; the limit is 2 MB. A phone's "medium" size is plenty.`;
  return null;
}

/** What the viewer shows for a line: the picture and the caption beside it. */
export function receiptItems(lines = [], context = {}) {
  return lines
    .filter((l) => l.receiptPath)
    .map((l) => ({
      lineId: l.id,
      src: uploadUrl(l.receiptPath),
      kind: l.typeName || "Expense",
      date: l.expenseDate,
      detail: [l.fromPlace, l.toPlace].filter(Boolean).join(" to ") || l.description || "",
      amount: l.claimedAmount,
      allowed: l.approvedAmount,
      who: context.empName,
    }));
}

const I = {
  camera:   ["M23 19a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4l2-3h6l2 3h4a2 2 0 0 1 2 2z", "M12 17a4 4 0 1 0 0-8 4 4 0 0 0 0 8z"],
  left:     "M15 18l-6-6 6-6",
  right:    "M9 18l6-6-6-6",
  expand:   ["M15 3h6v6", "M9 21H3v-3", "M21 3l-7 7", "M3 21l7-7"],
  shrink:   ["M4 14h6v6", "M20 10h-6V4", "M14 10l7-7", "M3 21l7-7"],
  external: ["M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6", "M15 3h6v6", "M10 14L21 3"],
  broken:   ["M21 15l-5-5L5 21", "M3 3l18 18", "M21 3H8M3 8v13h13", "M3 3v2"],
};

/* ── The picture itself, small ─────────────────────────────────────────── */

/**
 * A thumbnail that is also the button opening the viewer. Never a broken
 * image: a picture that fails to load shows a marked placeholder instead.
 */
export function ReceiptThumb({ src, alt = "Receipt", size = 40, onOpen, title }) {
  const [failed, setFailed] = useState(false);
  useEffect(() => { setFailed(false); }, [src]);
  return (
    <button type="button" className={`hr-receipt-thumb${failed ? " is-broken" : ""}`}
            style={{ width: size, height: size }} onClick={onOpen}
            title={title || "Open the receipt"} aria-label={title || "Open the receipt"}>
      {failed
        ? <Ic path={I.broken} size={Math.round(size * .42)} />
        : <img src={src} alt={alt} loading="lazy" decoding="async" onError={() => setFailed(true)} />}
    </button>
  );
}

/* ── One table cell ────────────────────────────────────────────────────── */

/**
 * The receipt on a line, in a table. A thumbnail when there is one, an
 * "Attach" button when there is not and the claim can still change, and a
 * quiet "none" otherwise — the approver should see at once that a line came
 * without evidence.
 */
export function ReceiptCell({ line, editable, busy, onOpen, onPick, onRemove }) {
  const input = useRef(null);
  const choose = () => input.current?.click();
  return (
    <span className={`hr-receipt-cell${busy ? " is-busy" : ""}`}>
      {editable && (
        <input ref={input} type="file" accept={IMAGE_ACCEPT} hidden
               onChange={(e) => { onPick?.(e.target.files?.[0]); e.target.value = ""; }} />
      )}
      {line.receiptPath ? (
        <>
          <ReceiptThumb src={uploadUrl(line.receiptPath)} size={36} onOpen={onOpen}
                        title={`Open the receipt for ${line.typeName || "this expense"}`} />
          {editable && (
            <span className="hr-receipt-tools">
              <HrButton size="sm" variant="ghost" iconOnly icon={P.upload} title="Replace the receipt" onClick={choose} disabled={busy} />
              <HrButton size="sm" variant="ghost" iconOnly icon={P.trash} title="Remove the receipt" onClick={onRemove} disabled={busy} />
            </span>
          )}
        </>
      ) : editable ? (
        <HrButton size="sm" icon={I.camera} onClick={choose} disabled={busy}>{busy ? "Uploading…" : "Attach"}</HrButton>
      ) : (
        <span className="hr-receipt-none"><Ic path={I.camera} size={13} /> none</span>
      )}
    </span>
  );
}

/* ── Before the claim exists ───────────────────────────────────────────── */

/**
 * A receipt chosen while the claim is still being typed. There is no line to
 * attach it to yet — the file is held here and goes up the moment the claim
 * is saved. A preview so the person can see they picked the right photo, and
 * a way to drop it again.
 */
export function ReceiptPick({ file, onPick, onClear, problem }) {
  const input = useRef(null);
  const [over, setOver] = useState(false);
  const [url, setUrl] = useState(null);
  useEffect(() => {
    if (!file) { setUrl(null); return undefined; }
    const u = URL.createObjectURL(file);
    setUrl(u);
    return () => URL.revokeObjectURL(u);
  }, [file]);
  return (
    <span className={`hr-receipt-pick${over ? " is-over" : ""}${problem ? " is-invalid" : ""}`}
          onDragOver={(e) => { e.preventDefault(); setOver(true); }}
          onDragLeave={() => setOver(false)}
          onDrop={(e) => { e.preventDefault(); setOver(false); onPick(e.dataTransfer.files?.[0]); }}>
      <input ref={input} type="file" accept={IMAGE_ACCEPT} hidden
             onChange={(e) => { onPick(e.target.files?.[0]); e.target.value = ""; }} />
      {file ? (
        <>
          <span className="hr-receipt-thumb" style={{ width: 36, height: 36, cursor: "default" }} title={file.name}>
            {url && <img src={url} alt="" />}
          </span>
          <span className="hr-receipt-pick-name" title={file.name}>{file.name}</span>
          <HrButton size="sm" variant="ghost" iconOnly icon={P.x} title="Drop this receipt" onClick={onClear} />
        </>
      ) : (
        <HrButton size="sm" icon={I.camera} onClick={() => input.current?.click()} title="A photograph of the receipt — JPEG, PNG or WebP, up to 2 MB">
          {over ? "Drop it" : "Attach"}
        </HrButton>
      )}
    </span>
  );
}

/* ── The gallery under a claim ─────────────────────────────────────────── */

/**
 * Every line as a card: the receipt as a picture, or the place to put one.
 * On a desktop a photograph can be dropped straight onto the card for its
 * line; on a phone the card opens the camera or the photo library.
 */
export function ReceiptGallery({ lines = [], editable, busyLine, onOpen, onPick, onRemove, title = "Receipts" }) {
  const withReceipt = lines.filter((l) => l.receiptPath).length;
  if (!lines.length) return null;
  return (
    <section className="hr-receipts" aria-label={title}>
      <header className="hr-receipts-head">
        <div>
          <strong>{title}</strong>
          <span className="hr-receipts-count">
            {withReceipt === lines.length
              ? `all ${lines.length} attached`
              : `${withReceipt} of ${lines.length} attached`}
          </span>
        </div>
        <div className="hr-receipts-meter" aria-hidden="true">
          <i style={{ width: `${lines.length ? (withReceipt / lines.length) * 100 : 0}%` }} />
        </div>
      </header>
      <div className="hr-receipts-grid">
        {lines.map((l, i) => (
          <ReceiptCard key={l.id ?? i} line={l} editable={editable} busy={busyLine === l.id}
                       onOpen={() => onOpen?.(l)} onPick={(f) => onPick?.(l.id, f)} onRemove={() => onRemove?.(l.id)} />
        ))}
      </div>
      {editable && (
        <p className="hr-receipts-hint">
          <Ic path={P.info} size={13} />
          JPEG, PNG or WebP, up to 2 MB. Drop a photograph on a card, or tap it to take one.
        </p>
      )}
      {!editable && withReceipt < lines.length && (
        <p className="hr-receipts-hint">
          <Ic path={P.info} size={13} />
          {lines.length - withReceipt === 1 ? "One expense" : `${lines.length - withReceipt} expenses`} without a receipt.
          A receipt can be attached only while the claim is a draft — once submitted, the evidence stays as it was sent.
        </p>
      )}
    </section>
  );
}

function ReceiptCard({ line, editable, busy, onOpen, onPick, onRemove }) {
  const input = useRef(null);
  const [over, setOver] = useState(false);
  const [failed, setFailed] = useState(false);
  useEffect(() => { setFailed(false); }, [line.receiptPath]);
  const has = Boolean(line.receiptPath);
  const choose = () => input.current?.click();
  const dropProps = editable ? {
    onDragOver: (e) => { e.preventDefault(); setOver(true); },
    onDragLeave: () => setOver(false),
    onDrop: (e) => { e.preventDefault(); setOver(false); onPick(e.dataTransfer.files?.[0]); },
  } : {};
  return (
    <figure className={`hr-receipt-card${has ? " has-receipt" : ""}${over ? " is-over" : ""}${busy ? " is-busy" : ""}`} {...dropProps}>
      {editable && (
        <input ref={input} type="file" accept={IMAGE_ACCEPT} hidden
               onChange={(e) => { onPick(e.target.files?.[0]); e.target.value = ""; }} />
      )}
      {has ? (
        <button type="button" className="hr-receipt-pic" onClick={onOpen} title="Open the receipt" aria-label={`Open the receipt for ${line.typeName || "this expense"}`}>
          {failed
            ? <span className="hr-receipt-placeholder"><Ic path={I.broken} size={22} /><span>Could not load</span></span>
            : <img src={uploadUrl(line.receiptPath)} alt="" loading="lazy" decoding="async" onError={() => setFailed(true)} />}
          <span className="hr-receipt-zoom"><Ic path={I.expand} size={14} /></span>
        </button>
      ) : editable ? (
        <button type="button" className="hr-receipt-pic is-empty" onClick={choose} disabled={busy}>
          <span className="hr-receipt-placeholder">
            <Ic path={busy ? P.clock : P.upload} size={22} />
            <span>{busy ? "Uploading…" : over ? "Drop it here" : "Add a receipt"}</span>
          </span>
        </button>
      ) : (
        <div className="hr-receipt-pic is-empty is-static">
          <span className="hr-receipt-placeholder"><Ic path={I.camera} size={22} /><span>No receipt</span></span>
        </div>
      )}
      <figcaption>
        <span className="hr-receipt-kind">{line.typeName || "Expense"}</span>
        <span className="hr-receipt-meta">
          <span>{line.expenseDate}</span>
          <b>{money(line.claimedAmount)}</b>
        </span>
        {has && editable && (
          <span className="hr-receipt-cardtools">
            <HrButton size="sm" variant="ghost" icon={P.upload} onClick={choose} disabled={busy}>Replace</HrButton>
            <HrButton size="sm" variant="ghost" iconOnly icon={P.trash} title="Remove the receipt" onClick={onRemove} disabled={busy} />
          </span>
        )}
      </figcaption>
      {busy && <span className="hr-receipt-busy" aria-hidden="true" />}
    </figure>
  );
}

/* ── The viewer ────────────────────────────────────────────────────────── */

/**
 * Full-screen viewer for the receipts on a claim. Arrow keys move between
 * lines, Escape closes, a click on the picture toggles actual size. Rendered
 * into the document body so it sits over the drawer or modal that opened it.
 */
export function ReceiptViewer({ items = [], index = 0, onClose, onIndex }) {
  const [zoomed, setZoomed] = useState(false);
  const [state, setState] = useState("loading");   // loading | ok | failed
  const box = useRef(null);
  const item = items[index];
  const many = items.length > 1;

  const go = useCallback((d) => {
    if (!many) return;
    onIndex?.((index + d + items.length) % items.length);
  }, [index, items.length, many, onIndex]);

  useEffect(() => { setZoomed(false); setState("loading"); }, [item?.src]);

  useEffect(() => {
    const was = document.activeElement;
    box.current?.focus();
    const key = (e) => {
      if (e.key === "Escape") { e.stopPropagation(); onClose?.(); }
      else if (e.key === "ArrowRight") go(1);
      else if (e.key === "ArrowLeft") go(-1);
    };
    document.addEventListener("keydown", key, true);
    // Scroll lock on <html>, not <body> — see HrDrawer for why.
    const root = document.documentElement;
    const prevOverflow = root.style.overflow;
    root.style.overflow = "hidden";
    return () => {
      document.removeEventListener("keydown", key, true);
      root.style.overflow = prevOverflow;
      was?.focus?.();
    };
  }, [go, onClose]);

  if (!item) return null;
  const n = index + 1;
  return createPortal(
    <div className="hr-viewer" role="dialog" aria-modal="true" aria-label={`Receipt ${n} of ${items.length}`}
         onMouseDown={(e) => { if (e.target === e.currentTarget) onClose?.(); }}>
      <div className="hr-viewer-top">
        <div className="hr-viewer-title">
          <span className="hr-viewer-n">{n} / {items.length}</span>
          <strong>{item.kind}</strong>
          <span className="hr-viewer-meta">
            {item.date}{item.detail ? ` · ${item.detail}` : ""}{item.who ? ` · ${item.who}` : ""}
          </span>
        </div>
        <div className="hr-viewer-amount">
          <span>Claimed</span><b>{money(item.amount)}</b>
          {item.allowed != null && <><span>Allowed</span><b>{money(item.allowed)}</b></>}
        </div>
        <div className="hr-viewer-tools">
          <button type="button" className="hr-viewer-btn" onClick={() => setZoomed((z) => !z)} title={zoomed ? "Fit to screen" : "Actual size"} disabled={state !== "ok"}>
            <Ic path={zoomed ? I.shrink : I.expand} size={16} />
          </button>
          <a className="hr-viewer-btn" href={item.src} target="_blank" rel="noreferrer" title="Open in a new tab"><Ic path={I.external} size={16} /></a>
          <button type="button" className="hr-viewer-btn" onClick={onClose} title="Close (Esc)"><Ic path={P.x} size={18} /></button>
        </div>
      </div>

      <div ref={box} tabIndex={-1} className={`hr-viewer-stage${zoomed ? " is-zoomed" : ""}`}
           onMouseDown={(e) => { if (e.target === e.currentTarget) onClose?.(); }}>
        {state === "loading" && <span className="hr-viewer-loading" aria-live="polite">Loading the receipt…</span>}
        {state === "failed" ? (
          <div className="hr-viewer-failed">
            <Ic path={I.broken} size={28} />
            <strong>This receipt could not be loaded.</strong>
            <span>The file may have been removed from the upload folder. The record still says one was attached.</span>
          </div>
        ) : (
          <img key={item.src} src={item.src} alt={`Receipt for ${item.kind}`} draggable={false}
               className={state === "ok" ? "is-ready" : ""}
               onLoad={() => setState("ok")} onError={() => setState("failed")}
               onClick={() => setZoomed((z) => !z)} />
        )}
      </div>

      {many && (
        <>
          <button type="button" className="hr-viewer-nav is-prev" onClick={() => go(-1)} title="Previous receipt (←)" aria-label="Previous receipt"><Ic path={I.left} size={22} /></button>
          <button type="button" className="hr-viewer-nav is-next" onClick={() => go(1)} title="Next receipt (→)" aria-label="Next receipt"><Ic path={I.right} size={22} /></button>
          <div className="hr-viewer-strip" role="tablist" aria-label="Receipts on this claim">
            {items.map((it, i) => (
              <button key={it.lineId ?? i} type="button" role="tab" aria-selected={i === index}
                      className={`hr-viewer-dot${i === index ? " is-on" : ""}`} onClick={() => onIndex?.(i)}
                      title={`${it.kind} · ${money(it.amount)}`}>
                <img src={it.src} alt="" loading="lazy" decoding="async" />
              </button>
            ))}
          </div>
        </>
      )}
    </div>,
    document.body,
  );
}
