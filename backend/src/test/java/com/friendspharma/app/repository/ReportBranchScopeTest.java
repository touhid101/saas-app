package com.friendspharma.app.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every report query must narrow to a branch.
 *
 * <p>A branch is a pharmacy. Filtering a report on the organisation alone shows
 * one pharmacy the takings, debts and returns of the others sharing that
 * organisation — which is the single thing the tenancy model promises cannot
 * happen.
 *
 * <p>Two reports were doing exactly that. Supplier Outstanding and Supplier
 * Ageing read V_SUPPLIER_ACCOUNT, grained per organisation, filtered on PID_ORG
 * with no branch anywhere in the query. The Sales Return Register did the same
 * through {@code findSalesReturns}. In the live database that meant a user at a
 * branch holding 4 sales returns was shown 14,004, and the combined supplier
 * debt of three pharmacies.
 *
 * <p>This reads the {@code @Query} text off the annotations rather than naming
 * the reports, so a report added later is covered the day it is written. A query
 * that genuinely has no branch to filter on has to be listed below with a reason,
 * which is the point: it makes the exception a decision somebody made rather than
 * a line nobody noticed.
 */
class ReportBranchScopeTest {

    /**
     * Queries that legitimately have no branch predicate.
     *
     * <p>Empty. It is kept so that adding one requires writing down why.
     */
    private static final List<String> EXEMPT = List.of();

    @Test
    @DisplayName("every report query filters on a branch")
    void everyReportQueryIsBranchScoped() {
        List<String> unscoped = unscopedQueriesIn(SalesPurchaseReportRepository.class);
        assertThat(unscoped)
                .as("report queries filtering on the organisation but not the branch — "
                    + "these show one pharmacy another's figures")
                .isEmpty();
    }

    @Test
    @DisplayName("every report query that filters on a branch also accepts one")
    void everyBranchPredicateHasAParameter() {
        for (Method m : SalesPurchaseReportRepository.class.getDeclaredMethods()) {
            Query q = m.getAnnotation(Query.class);
            if (q == null || !mentionsBranch(q.value())) continue;
            assertThat(hasBranchParameter(m))
                    .as("%s filters on a branch but takes no branchId", m.getName())
                    .isTrue();
        }
    }

    /**
     * The sales-return register lives on the transaction repository rather than
     * the report one, which is how it was missed.
     */
    @Test
    @DisplayName("the sales return register filters on a branch")
    void salesReturnRegisterIsBranchScoped() throws Exception {
        Method m = find(PosTransactionMstRepository.class, "findSalesReturns");
        Query q = m.getAnnotation(Query.class);

        assertThat(q).as("findSalesReturns has no @Query to inspect").isNotNull();
        assertThat(mentionsBranch(q.value()))
                .as("the sales return register still filters on the organisation alone")
                .isTrue();
        assertThat(hasBranchParameter(m))
                .as("findSalesReturns cannot be branch-scoped without a branchId")
                .isTrue();
    }

    /* ── helpers ─────────────────────────────────────────────────────────── */

    private static List<String> unscopedQueriesIn(Class<?> repository) {
        List<String> out = new ArrayList<>();
        for (Method m : repository.getDeclaredMethods()) {
            Query q = m.getAnnotation(Query.class);
            if (q == null || EXEMPT.contains(m.getName())) continue;
            if (!mentionsBranch(q.value())) out.add(m.getName());
        }
        return out;
    }

    /** True where the query text narrows by branch at all, in either dialect. */
    private static boolean mentionsBranch(String sql) {
        String s = sql.toLowerCase(Locale.ROOT);
        return s.contains("pid_branch") || s.contains("pidbranch");
    }

    private static boolean hasBranchParameter(Method m) {
        for (Parameter p : m.getParameters()) {
            if (p.getName().toLowerCase(Locale.ROOT).contains("branch")) return true;
        }
        // Parameter names are not always retained; the @Param annotation is.
        for (var anns : m.getParameterAnnotations()) {
            for (var a : anns) {
                if (a instanceof org.springframework.data.repository.query.Param param
                        && param.value().toLowerCase(Locale.ROOT).contains("branch")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Method find(Class<?> type, String name) {
        for (Method m : type.getDeclaredMethods()) if (m.getName().equals(name)) return m;
        throw new AssertionError(name + " no longer exists on " + type.getSimpleName());
    }
}
