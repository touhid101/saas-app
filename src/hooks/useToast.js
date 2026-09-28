import { useApp } from "../lib/AppContext.jsx";

/** Convenience hook — returns addToast from global context */
export function useToast() {
  const { addToast } = useApp();
  return addToast;
}
