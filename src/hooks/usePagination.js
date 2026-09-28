import { useState } from "react";

export function usePagination(items, pageSize = 25) {
  const [page, setPage] = useState(0);
  const totalPages = Math.max(1, Math.ceil(items.length / pageSize));
  const safeP = Math.min(page, totalPages - 1);
  const pageItems = items.slice(safeP * pageSize, (safeP + 1) * pageSize);
  return {
    page: safeP, setPage,
    totalPages, pageItems,
    totalItems: items.length,
    hasNext: safeP < totalPages - 1,
    hasPrev: safeP > 0,
    next: () => setPage(p => Math.min(p + 1, totalPages - 1)),
    prev: () => setPage(p => Math.max(p - 1, 0)),
  };
}
