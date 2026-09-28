import React from "react";

const base = (props) => ({
  width: props.size || 17, height: props.size || 17, viewBox: "0 0 24 24",
  fill: "none", stroke: "currentColor", strokeWidth: 1.9, strokeLinecap: "round", strokeLinejoin: "round",
});

export const IconDashboard = (p) => (
  <svg {...base(p)}><rect x="3" y="3" width="7" height="9" rx="1.5" /><rect x="14" y="3" width="7" height="5" rx="1.5" /><rect x="14" y="12" width="7" height="9" rx="1.5" /><rect x="3" y="16" width="7" height="5" rx="1.5" /></svg>
);
export const IconLayers = (p) => (
  <svg {...base(p)}><path d="M12 3 2 8l10 5 10-5-10-5Z" /><path d="M2 13l10 5 10-5" /><path d="M2 18l10 5 10-5" /></svg>
);
export const IconCart = (p) => (
  <svg {...base(p)}><circle cx="9" cy="20" r="1.4" /><circle cx="18" cy="20" r="1.4" /><path d="M2 3h2l2.4 12.2a2 2 0 0 0 2 1.6h8.6a2 2 0 0 0 2-1.6L21 7H6" /></svg>
);
export const IconReceipt = (p) => (
  <svg {...base(p)}><path d="M6 2h12v20l-2.5-1.5L13 22l-2.5-1.5L8 22l-2-1.5V2Z" /><path d="M9 7h6M9 11h6M9 15h4" /></svg>
);
export const IconTablet = (p) => (
  <svg {...base(p)}><rect x="4" y="2" width="16" height="20" rx="2.4" /><path d="M11 18h2" /></svg>
);
export const IconTruck = (p) => (
  <svg {...base(p)}><rect x="1" y="6" width="14" height="11" rx="1.4" /><path d="M15 10h4l3 3v4h-7z" /><circle cx="6" cy="19" r="1.7" /><circle cx="17.5" cy="19" r="1.7" /></svg>
);
export const IconClipboard = (p) => (
  <svg {...base(p)}><rect x="5" y="4" width="14" height="17" rx="2" /><rect x="9" y="2" width="6" height="4" rx="1" /><path d="M9 11h6M9 15h6" /></svg>
);
export const IconMerge = (p) => (
  <svg {...base(p)}><path d="M6 3v6a4 4 0 0 0 4 4h4" /><path d="M18 3v6a4 4 0 0 1-4 4" /><path d="M12 13v8" /><path d="m9 18 3 3 3-3" /></svg>
);
export const IconChart = (p) => (
  <svg {...base(p)}><path d="M4 20V10M12 20V4M20 20v-7" /><path d="M2 20h20" /></svg>
);
export const IconShield = (p) => (
  <svg {...base(p)}><path d="M12 2 4 5v6c0 5 3.4 8.4 8 11 4.6-2.6 8-6 8-11V5l-8-3Z" /><path d="m9 12 2 2 4-4" /></svg>
);
export const IconBot = (p) => (
  <svg {...base(p)}><rect x="4" y="8" width="16" height="12" rx="3" /><circle cx="9" cy="14" r="1.3" fill="currentColor" /><circle cx="15" cy="14" r="1.3" fill="currentColor" /><path d="M12 8V4" /><circle cx="12" cy="3" r="1.2" fill="currentColor" /><path d="M2 13h2M20 13h2" /></svg>
);
export const IconSearch = (p) => (
  <svg {...base(p)}><circle cx="11" cy="11" r="7" /><path d="m21 21-4.3-4.3" /></svg>
);
export const IconBell = (p) => (
  <svg {...base(p)}><path d="M6 8a6 6 0 0 1 12 0c0 5 2 6 2 6H4s2-1 2-6Z" /><path d="M10 21a2 2 0 0 0 4 0" /></svg>
);
export const IconPlus = (p) => (
  <svg {...base(p)}><path d="M12 5v14M5 12h14" /></svg>
);
export const IconMinus = (p) => (
  <svg {...base(p)}><path d="M5 12h14" /></svg>
);
export const IconX = (p) => (
  <svg {...base(p)}><path d="M18 6 6 18M6 6l12 12" /></svg>
);
export const IconCheck = (p) => (
  <svg {...base(p)}><path d="m5 13 4 4L19 7" /></svg>
);
export const IconChevronDown = (p) => (
  <svg {...base(p)}><path d="m6 9 6 6 6-6" /></svg>
);
export const IconEdit = (p) => (
  <svg {...base(p)}><path d="M12 20h9" /><path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4 12.5-12.5Z" /></svg>
);
export const IconTrash = (p) => (
  <svg {...base(p)}><path d="M3 6h18" /><path d="M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2" /><path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6" /><path d="M10 11v6M14 11v6" /></svg>
);
export const IconAlert = (p) => (
  <svg {...base(p)}><path d="M12 9v4" /><circle cx="12" cy="16.5" r=".6" fill="currentColor" /><path d="M10.3 3.9 1.8 18a1.8 1.8 0 0 0 1.5 2.7h17.4a1.8 1.8 0 0 0 1.5-2.7L13.7 3.9a1.8 1.8 0 0 0-3.4 0Z" /></svg>
);
export const IconBox = (p) => (
  <svg {...base(p)}><path d="M21 8 12 3 3 8l9 5 9-5Z" /><path d="M3 8v9l9 5 9-5V8" /><path d="M12 13v9" /></svg>
);
export const IconUsers = (p) => (
  <svg {...base(p)}><circle cx="9" cy="8" r="3.4" /><path d="M2.5 20a6.5 6.5 0 0 1 13 0" /><circle cx="17.5" cy="9" r="2.6" /><path d="M15.5 20a5.2 5.2 0 0 1 6-4.8" /></svg>
);
export const IconMapPin = (p) => (
  <svg {...base(p)}><path d="M12 22s7-6.5 7-12a7 7 0 1 0-14 0c0 5.5 7 12 7 12Z" /><circle cx="12" cy="10" r="2.4" /></svg>
);
export const IconDownload = (p) => (
  <svg {...base(p)}><path d="M12 3v13" /><path d="m6 11 6 6 6-6" /><path d="M4 20h16" /></svg>
);
export const IconFilter = (p) => (
  <svg {...base(p)}><path d="M4 5h16l-6 8v6l-4 2v-8L4 5Z" /></svg>
);
export const IconArrowRight = (p) => (
  <svg {...base(p)}><path d="M5 12h14" /><path d="m13 6 6 6-6 6" /></svg>
);
export const IconClock = (p) => (
  <svg {...base(p)}><circle cx="12" cy="12" r="9" /><path d="M12 7v5l3.5 2" /></svg>
);
export const IconWallet = (p) => (
  <svg {...base(p)}><rect x="2.5" y="6" width="19" height="14" rx="2.4" /><path d="M2.5 10h19" /><circle cx="17" cy="15" r="1.2" fill="currentColor" /></svg>
);
export const IconFlask = (p) => (
  <svg {...base(p)}><path d="M9 2h6" /><path d="M10 2v6.5L4.5 18a2 2 0 0 0 1.7 3h11.6a2 2 0 0 0 1.7-3L14 8.5V2" /><path d="M7.5 15h9" /></svg>
);
