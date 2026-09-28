/**
 * ProductImage — shows uploaded product image or falls back to imgColor swatch.
 * Also exports ImageUploadField for use in forms.
 */
import React, { useRef } from "react";
import { useApp } from "../lib/AppContext.jsx";
import { useToast } from "../hooks/useToast.js";

/** Display component — shows img if available, else color swatch with initials */
export function ProductImage({ product, size = 72, borderRadius = 10, fontSize = 20, showBadges = false, expiring = false, outOfStock = false }) {
  const { productImages } = useApp();
  const url = productImages[product?.id] || product?.imageUrl;
  const initials = (product?.name || "?").slice(0, 2).toUpperCase();
  const bg = product?.imgColor || "#2E6A62";

  return (
    <div style={{
      width: size === "100%" ? "100%" : size,
      height: size,
      borderRadius,
      background: bg,
      overflow: "hidden",
      position: "relative",
      flexShrink: 0,
      display: "flex",
      alignItems: "center",
      justifyContent: "center",
    }}>
      {url ? (
        <img
          src={url}
          alt={product?.name}
          style={{ width: "100%", height: "100%", objectFit: "cover", display: "block" }}
        />
      ) : (
        <span style={{
          color: "#fff", fontWeight: 800, fontSize,
          fontFamily: "monospace", textShadow: "0 1px 4px rgba(0,0,0,0.30)",
          userSelect: "none",
        }}>
          {initials}
        </span>
      )}
      {/* Overlay badges */}
      {showBadges && expiring && !outOfStock && (
        <span style={{
          position: "absolute", top: 5, right: 5,
          background: "#F39C12", color: "#fff",
          fontSize: 9, fontWeight: 700, padding: "2px 5px", borderRadius: 4,
        }}>
          NEAR EXP
        </span>
      )}
      {showBadges && outOfStock && (
        <span style={{
          position: "absolute", inset: 0,
          background: "rgba(0,0,0,0.48)",
          display: "flex", alignItems: "center", justifyContent: "center",
        }}>
          <span style={{ color: "#fff", fontWeight: 700, fontSize: 10 }}>OUT OF STOCK</span>
        </span>
      )}
    </div>
  );
}

/** Thumbnail with upload button overlay (used in MasterData product form) */
export function ImageUploadField({ productId, product, onChange }) {
  const { productImages, setProductImage } = useApp();
  const addToast = useToast();
  const inputRef = useRef(null);
  const url = productImages[productId] || product?.imageUrl;

  const handleFile = (e) => {
    const file = e.target.files?.[0];
    if (!file) return;
    if (file.size > 2 * 1024 * 1024) {
      addToast("That image is over 2 MB. Please choose a smaller one.", "error");
      return;
    }
    const reader = new FileReader();
    reader.onload = (ev) => {
      const dataUrl = ev.target.result;
      setProductImage(productId, dataUrl);
      if (onChange) onChange(dataUrl);
    };
    reader.readAsDataURL(file);
  };

  const removeImage = (e) => {
    e.stopPropagation();
    setProductImage(productId, null);
    if (onChange) onChange(null);
  };

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 8 }}>
      {/* Preview area */}
      <div
        onClick={() => inputRef.current?.click()}
        style={{
          width: 120, height: 120, borderRadius: 12,
          background: product?.imgColor || "#2E6A62",
          overflow: "hidden", position: "relative",
          cursor: "pointer", border: "2px dashed var(--line)",
          flexShrink: 0,
        }}
      >
        {url ? (
          <img src={url} alt="product" style={{ width: "100%", height: "100%", objectFit: "cover" }} />
        ) : (
          <div style={{ width: "100%", height: "100%", display: "flex", flexDirection: "column", alignItems: "center", justifyContent: "center", gap: 6 }}>
            <span style={{ color: "#fff", fontWeight: 800, fontSize: 22, fontFamily: "monospace" }}>
              {(product?.name || "?").slice(0, 2).toUpperCase()}
            </span>
            <span style={{ color: "rgba(255,255,255,0.75)", fontSize: 10 }}>Click to upload</span>
          </div>
        )}
        {/* Upload overlay on hover */}
        <div style={{
          position: "absolute", inset: 0,
          background: "rgba(0,0,0,0.4)",
          display: "flex", alignItems: "center", justifyContent: "center",
          opacity: 0, transition: "opacity .15s",
        }}
          onMouseEnter={e => e.currentTarget.style.opacity = "1"}
          onMouseLeave={e => e.currentTarget.style.opacity = "0"}
        >
          <div style={{ display: "flex", flexDirection: "column", alignItems: "center", gap: 4 }}>
            <svg width={20} height={20} viewBox="0 0 24 24" fill="none" stroke="#fff" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4M17 8l-5-5-5 5M12 3v12" />
            </svg>
            <span style={{ color: "#fff", fontSize: 10, fontWeight: 700 }}>Upload</span>
          </div>
        </div>
      </div>
      <input ref={inputRef} type="file" accept="image/*" onChange={handleFile} style={{ display: "none" }} />
      <div style={{ display: "flex", gap: 6 }}>
        <button
          type="button"
          className="btn btn-ghost btn-sm"
          onClick={() => inputRef.current?.click()}
          style={{ flex: 1, fontSize: 11 }}
        >
          📷 Upload Image
        </button>
        {url && (
          <button type="button" className="btn btn-sm" onClick={removeImage}
            style={{ background: "var(--clay-soft)", color: "var(--clay)", border: "none", fontSize: 11 }}>
            ✕
          </button>
        )}
      </div>
      <div style={{ fontSize: 10.5, color: "var(--ink-soft)" }}>
        JPG, PNG, WEBP · max 2 MB
      </div>
    </div>
  );
}
