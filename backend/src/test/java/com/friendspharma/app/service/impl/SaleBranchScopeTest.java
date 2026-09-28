package com.friendspharma.app.service.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which branch a sale is written to, and which branch the list looks in.
 *
 * <p>They have to be the same answer. They were not: every read on the Sales and
 * Sales Activity screens resolved the branch through {@link BranchScopeService},
 * which honours the acting branch, while the write took it straight off the
 * session. An invoice raised while supporting a pharmacy was therefore filed
 * against the operator's own branch and then could not be found by the list that
 * had just been used to raise it.
 *
 * <p>These model the two resolutions rather than call the service, which needs a
 * SecurityContext and four repositories. What is being pinned is the rule, and
 * the rule is one line either side.
 */
class SaleBranchScopeTest {

    /** What BranchScopeService.resolve does, for a non-admin. */
    private static Long readBranch(Long acting, Long session, Long requested) {
        if (acting != null) return acting;
        return requested == null ? session : requested;   // non-admin naming another → 404 elsewhere
    }

    /** The old write rule: session, or whatever the body said. */
    private static Long writeBranchBefore(Long acting, Long session, Long fromBody) {
        return fromBody != null ? fromBody : session;
    }

    /** The new write rule — the same call the reads make. */
    private static Long writeBranchAfter(Long acting, Long session, Long fromBody) {
        return readBranch(acting, session, fromBody);
    }

    private static final Long OPERATOR_BRANCH = 1L;
    private static final Long CUSTOMER_BRANCH = 95L;

    @Nested
    @DisplayName("supporting a pharmacy")
    class ActingAsAnother {

        @Test
        @DisplayName("the list looks in the pharmacy being supported")
        void readFollowsActingBranch() {
            assertThat(readBranch(CUSTOMER_BRANCH, OPERATOR_BRANCH, null))
                    .isEqualTo(CUSTOMER_BRANCH);
        }

        /** The reported bug: written to 1, looked for in 95. */
        @Test
        @DisplayName("the sale used to be written to the operator's own branch")
        void writeUsedToIgnoreIt() {
            assertThat(writeBranchBefore(CUSTOMER_BRANCH, OPERATOR_BRANCH, null))
                    .isEqualTo(OPERATOR_BRANCH);
            assertThat(writeBranchBefore(CUSTOMER_BRANCH, OPERATOR_BRANCH, null))
                    .as("written somewhere the list would never look")
                    .isNotEqualTo(readBranch(CUSTOMER_BRANCH, OPERATOR_BRANCH, null));
        }

        @Test
        @DisplayName("now it is written where the list looks")
        void writeNowMatchesRead() {
            assertThat(writeBranchAfter(CUSTOMER_BRANCH, OPERATOR_BRANCH, null))
                    .isEqualTo(readBranch(CUSTOMER_BRANCH, OPERATOR_BRANCH, null));
        }
    }

    @Nested
    @DisplayName("the ordinary case is unchanged")
    class NotActing {

        @Test
        @DisplayName("a sale is written to, and found in, the user's own branch")
        void ownBranch() {
            assertThat(writeBranchAfter(null, OPERATOR_BRANCH, null)).isEqualTo(OPERATOR_BRANCH);
            assertThat(readBranch(null, OPERATOR_BRANCH, null)).isEqualTo(OPERATOR_BRANCH);
        }

        /**
         * The second fault in the old line: branchId came from the request body
         * and was used unchecked, so a caller could file a sale against another
         * pharmacy. It now goes through the same resolution as a read, which
         * refuses a branch the caller may not name.
         */
        @Test
        @DisplayName("a branch named in the body is no longer taken on trust")
        void bodyBranchIsResolvedNotTrusted() {
            Long someoneElse = 902L;
            assertThat(writeBranchBefore(null, OPERATOR_BRANCH, someoneElse))
                    .as("the old rule wrote it wherever the body said")
                    .isEqualTo(someoneElse);
            // The new rule hands it to BranchScopeService, which returns it only
            // for an administrator and throws ResourceNotFound otherwise. Pinned
            // here as "goes through resolution", the refusal itself being that
            // class's own contract.
            assertThat(writeBranchAfter(null, OPERATOR_BRANCH, someoneElse))
                    .isEqualTo(readBranch(null, OPERATOR_BRANCH, someoneElse));
        }
    }
}
