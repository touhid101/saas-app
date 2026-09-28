/**
 * View is the floor every other right stands on.
 *
 * <b>Why the two move together.</b> Approve on a page nobody may open is a right
 * that cannot be exercised — the menu hides the page, so the tick is a promise
 * the app does not keep. It is also worse than useless: someone auditing the
 * matrix reads it as access that was deliberately granted, and it is not.
 *
 * So the rule runs in both directions:
 *   · ticking any action right turns View on with it
 *   · unticking View turns the action rights off with it
 *
 * <b>Why it lives here.</b> There are three permission editors — the role matrix
 * in RBAC, what a pharmacy has bought, and what one person may do — and a rule
 * enforced in two of them is the rule the third one quietly breaks.
 */

export const VIEW = "canView";

/** Everything that needs a page open in front of it to mean anything. */
export const ACTION_RIGHTS = [
  "canCreate", "canEdit", "canDelete", "canApprove", "canExport", "canPrint",
];

/**
 * Which rights have to change when `key` is set to `next`.
 *
 * Returns a plain `{ rightKey: boolean }` patch rather than a finished row,
 * because the three editors store a right three different ways — a 0/1 integer,
 * a boolean, and a `{ effective, role, ceiling, override }` cell. Each writes
 * the patch into its own shape; only the decision is shared.
 *
 * @param {string}   key    the right being clicked
 * @param {boolean}  next   what the click sets it to
 * @param {Function} isOn   (rightKey) => boolean — is that right on right now
 * @param {Function} [canSet] (rightKey) => boolean — false for a right this
 *        editor may not turn on, such as one a role never granted. Defaults to
 *        allowing everything.
 * @returns {Object|null} the patch, or null when the change cannot be made
 *        because View cannot be turned on to support it.
 */
export function viewRulePatch(key, next, isOn, canSet = () => true) {
  const patch = { [key]: next };

  if (key === VIEW) {
    // Taking View away takes the rights that stood on it away too, rather than
    // leaving them set against a page that can no longer be reached.
    if (!next) {
      for (const k of ACTION_RIGHTS) if (isOn(k)) patch[k] = false;
    }
    return patch;
  }

  if (next && !isOn(VIEW)) {
    // Refuse rather than half-apply: granting Approve while View stays off is
    // the exact state this rule exists to prevent.
    if (!canSet(VIEW)) return null;
    patch[VIEW] = true;
  }

  return patch;
}
