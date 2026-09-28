package com.friendspharma.app.security;

/**
 * The pharmacy a platform operator has chosen to act as, for this request.
 *
 * <p><b>Why a header and not a parameter on every endpoint.</b> Support means
 * looking at what the customer is looking at — their products, their dashboard,
 * their reports. Threading a branch parameter through two hundred and sixty
 * endpoints to achieve that would be two hundred and sixty chances to forget one,
 * and the one forgotten is the screen the operator needed. One header applies to
 * all of them at once.
 *
 * <p><b>Why a ThreadLocal and not a field on the principal.</b> The choice lasts
 * one request. Putting it on the session would make it sticky — an operator who
 * looked at a customer's stock in the morning would still be inside that pharmacy
 * in the afternoon, and would eventually change something believing it was their
 * own. Every request states it again or does not get it.
 *
 * <p>Set only by {@link ActingBranchFilter}, which refuses the header for anyone
 * who is not a platform operator and records every use of it.
 */
public final class ActingBranch {

    private static final ThreadLocal<Long> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<Long> CURRENT_ORG = new ThreadLocal<>();

    private ActingBranch() {}

    /** The header a client sets to look at one pharmacy's data. */
    public static final String HEADER = "X-Acting-Branch";

    /**
     * The organisation travels with the branch.
     *
     * <p>Switching the branch alone is not enough and fails in the way that is
     * hardest to notice: every query filters on the organisation as well, so the
     * operator gets HTTP 200 and an empty pharmacy. It looks like it works.
     */
    static void set(Long branchId, Long orgId) { CURRENT.set(branchId); CURRENT_ORG.set(orgId); }

    /** Cleared in a finally block by the filter: a leaked value would follow the
     *  thread into the next request, which could belong to anybody. */
    static void clear() { CURRENT.remove(); CURRENT_ORG.remove(); }

    /** The branch being acted as, or null when the caller is simply themselves. */
    public static Long get() { return CURRENT.get(); }

    /** The organisation of the pharmacy being acted as, or null. */
    public static Long getOrg() { return CURRENT_ORG.get(); }

    public static boolean isActing() { return CURRENT.get() != null; }
}
