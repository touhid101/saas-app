/** Application-wide constants — single source of truth */

export const APP_NAME = "Friends Pharma";
export const APP_VERSION = "2.0.0";
export const CURRENCY_SYMBOL = "৳";
export const DATE_FORMAT = "DD/MM/YYYY";

export const PAYMENT_METHODS = ["Cash", "Card", "bKash", "Nagad", "Bank Transfer", "Credit"];

export const PURCHASE_TYPES = ["Regular", "Emergency", "Seasonal"];

export const ORDER_STAGES = [
  "Submitted", "Confirmed", "Delivery Assigned", "On The Way", "Delivered", "Collected",
];

export const SALE_STATUS_TONE = {
  Submitted: "amber", Confirmed: "teal", "Delivery Assigned": "sage",
  "On The Way": "teal", Delivered: "sage", Collected: "sage",
};

export const PURCHASE_STATUS_TONE = {
  Completed: "sage", Pending: "amber", Cancelled: "clay",
};

export const REQUISITION_STATUS_TONE = {
  Draft: "grey", Submitted: "amber", "Pending Approval": "amber",
  Approved: "sage", Rejected: "clay", Converted: "teal", Cancelled: "clay",
};

export const DELIVERY_STATUS_TONE = {
  Assigned: "amber", "On The Way": "teal", Delivered: "sage", Collected: "sage",
};

export const PERMISSION_KEYS = [
  "view", "create", "edit", "delete", "approve", "print", "export", "finalize", "cancel",
];

export const STAGE_NEXT_LABEL = {
  Submitted: "Confirm Order",
  Confirmed: "Assign Delivery",
  "Delivery Assigned": "Start Delivery",
  "On The Way": "Mark Delivered",
  Delivered: "Record Collection",
};

export const STOCK_STATUS = {
  OUT: "Out of Stock",
  LOW: "Low Stock",
  OK: "In Stock",
  NEAR_EXPIRY: "Near Expiry",
  EXPIRED: "Expired",
};
