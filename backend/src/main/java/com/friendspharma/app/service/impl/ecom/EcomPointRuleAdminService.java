package com.friendspharma.app.service.impl.ecom;

import com.friendspharma.app.dto.response.EcomPointsResponses;
import com.friendspharma.app.entity.AllBranch;
import com.friendspharma.app.entity.EcomPointRule;
import com.friendspharma.app.entity.EcomPointRuleBranch;
import com.friendspharma.app.exception.custom.BusinessException;
import com.friendspharma.app.exception.custom.ResourceNotFoundException;
import com.friendspharma.app.repository.*;
import com.friendspharma.app.security.SecurityUtils;
import com.friendspharma.app.service.impl.BranchScopeService;
import com.friendspharma.app.service.impl.PermissionGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

/**
 * Writing the loyalty rules (MOD54 / docs/ecommerce Phase 13).
 *
 * <p>Same shape as coupons, and for the same reason: a rule gives money away,
 * so a pharmacy may write one for <em>its own</em> branch and only a platform
 * operator may write one every pharmacy honours.
 *
 * <p>There is no default rule and nothing is seeded. Every number — what a
 * point is worth, how many are earned, what fraction of a bill they may pay —
 * is somebody's decision, made here.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EcomPointRuleAdminService {

    /** The screen this sits behind, beside the coupons. */
    public static final String MOD_PROMOTIONS = "MOD54";

    private final EcomPointRuleRepository rules;
    private final EcomPointRuleBranchRepository ruleBranches;
    private final EcomPointLedgerRepository ledger;
    private final AllBranchRepository branches;
    private final BranchScopeService branchScope;
    private final PermissionGuard permissions;
    private final EcomAuditRecorder audit;

    @Transactional(readOnly = true)
    public Page<EcomPointsResponses.Rule> list(String active, String q, int page, int size) {
        require(PermissionGuard.Right.VIEW, "see the points rules");
        var pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
        return rules.search(blank(active), blank(q), pageable).map(this::view);
    }

    @Transactional(readOnly = true)
    public EcomPointsResponses.Rule detail(Long id) {
        require(PermissionGuard.Right.VIEW, "see the points rules");
        return view(load(id));
    }

    @Transactional
    public EcomPointsResponses.Rule create(EcomPointsResponses.Form form) {
        require(PermissionGuard.Right.CREATE, "write a points rule");
        EcomPointRule r = new EcomPointRule();
        r.setPidOwnerBranch(branchScope.resolve(null));
        r.setPidOrg(SecurityUtils.getCurrentOrgId());
        r.setStatus("Y");
        apply(r, form);
        rules.save(r);
        saveBranches(r, form.getBranchIds());
        audit.record("POINT_RULE_CREATED", null, r.getPidOwnerBranch(), "ECOM_POINT_RULE", r.getPidPointRule(),
                r.getRuleName() + " · " + describe(r));
        log.info("Points rule '{}' written by {} ({})", r.getRuleName(), SecurityUtils.getCurrentUsername(), describe(r));
        return view(r);
    }

    @Transactional
    public EcomPointsResponses.Rule update(Long id, EcomPointsResponses.Form form) {
        require(PermissionGuard.Right.EDIT, "change a points rule");
        EcomPointRule r = load(id);
        apply(r, form);
        rules.save(r);
        saveBranches(r, form.getBranchIds());
        audit.record("POINT_RULE_UPDATED", null, r.getPidOwnerBranch(), "ECOM_POINT_RULE", r.getPidPointRule(),
                r.getRuleName() + " · " + describe(r));
        return view(r);
    }

    /**
     * Switches a rule on or off.
     *
     * <p>Never deleted: points already earned under it point at it, and a
     * balance has to be explicable years later.
     */
    @Transactional
    public EcomPointsResponses.Rule setActive(Long id, boolean on) {
        require(PermissionGuard.Right.APPROVE, on ? "start a points rule" : "stop a points rule");
        EcomPointRule r = load(id);
        r.setIsActive(on ? "Y" : "N");
        rules.save(r);
        audit.record(on ? "POINT_RULE_STARTED" : "POINT_RULE_STOPPED", null, r.getPidOwnerBranch(),
                "ECOM_POINT_RULE", r.getPidPointRule(), r.getRuleName());
        return view(r);
    }

    /* ── rules ──────────────────────────────────────────────────────────── */

    private void apply(EcomPointRule r, EcomPointsResponses.Form form) {
        r.setRuleName(required(form.getName(), "name"));
        r.setDescription(trim(form.getDescription(), 300));

        if (form.getEarnPointsPer100() == null || form.getEarnPointsPer100().signum() < 0) {
            throw new BusinessException("Say how many points 100 spent should earn.",
                    HttpStatus.BAD_REQUEST, "ECOM_POINTS_EARN_RATE");
        }
        if (form.getPointValue() == null || form.getPointValue().signum() <= 0) {
            throw new BusinessException("Say what one point is worth. There is no default — it is your money.",
                    HttpStatus.BAD_REQUEST, "ECOM_POINTS_VALUE");
        }
        if (form.getMaxRedeemPercent() != null
                && (form.getMaxRedeemPercent().signum() <= 0
                    || form.getMaxRedeemPercent().compareTo(BigDecimal.valueOf(100)) > 0)) {
            throw new BusinessException("Points may pay for between 1% and 100% of an order.",
                    HttpStatus.BAD_REQUEST, "ECOM_POINTS_CAP");
        }
        r.setEarnPointsPer100(form.getEarnPointsPer100());
        r.setMinOrderToEarn(form.getMinOrderToEarn());
        r.setPointValue(form.getPointValue());
        r.setMinPointsToRedeem(form.getMinPointsToRedeem());
        r.setMaxRedeemPercent(form.getMaxRedeemPercent());
        r.setExpiryMonths(form.getExpiryMonths());
        r.setStartsAt(form.getStartsAt());
        r.setEndsAt(form.getEndsAt());
        if (r.getStartsAt() != null && r.getEndsAt() != null && !r.getEndsAt().isAfter(r.getStartsAt())) {
            throw new BusinessException("The rule would end before it starts.",
                    HttpStatus.BAD_REQUEST, "ECOM_POINTS_WINDOW");
        }

        String tier = form.getCustomerTypeScope() == null ? EcomPointRule.TIER_ALL
                : form.getCustomerTypeScope().trim().toUpperCase(Locale.ROOT);
        if (!List.of("ALL", "RETAILER", "WHOLESALER", "SPECIAL").contains(tier)) {
            throw new BusinessException("'" + form.getCustomerTypeScope() + "' is not a customer type.",
                    HttpStatus.BAD_REQUEST, "ECOM_POINTS_TIER");
        }
        r.setCustomerTypeScope(tier);

        String scope = required(form.getBranchScope(), "branch scope").trim().toUpperCase(Locale.ROOT);
        if (!List.of(EcomPointRule.ALL_BRANCHES, EcomPointRule.SELECTED_BRANCHES).contains(scope)) {
            throw new BusinessException("A rule applies at all pharmacies or at named ones.",
                    HttpStatus.BAD_REQUEST, "ECOM_POINTS_SCOPE");
        }
        if (EcomPointRule.ALL_BRANCHES.equals(scope) && !branchScope.isPlatformOperator()) {
            throw new BusinessException(
                    "Only a platform administrator can run a points scheme across every pharmacy. "
                    + "Choose the pharmacies this rule applies to.",
                    HttpStatus.FORBIDDEN, "NOT_PLATFORM_OPERATOR");
        }
        r.setBranchScope(scope);
        r.setIsActive("N".equalsIgnoreCase(form.getIsActive()) ? "N" : "Y");
    }

    private void saveBranches(EcomPointRule r, List<Long> branchIds) {
        if (EcomPointRule.ALL_BRANCHES.equals(r.getBranchScope())) {
            ruleBranches.deleteByPidPointRule(r.getPidPointRule());
            return;
        }
        if (branchIds == null || branchIds.isEmpty()) {
            throw new BusinessException("Name at least one pharmacy for this rule.",
                    HttpStatus.BAD_REQUEST, "ECOM_POINTS_NO_BRANCH");
        }
        Long mine = branchScope.resolve(null);
        boolean platform = branchScope.isPlatformOperator();
        for (Long b : branchIds) {
            if (!platform && !b.equals(mine)) {
                throw new BusinessException("You can only run a points scheme at your own pharmacy.",
                        HttpStatus.FORBIDDEN, "ECOM_POINTS_OTHER_BRANCH");
            }
            branches.findById(b).orElseThrow(() -> new ResourceNotFoundException("Branch", b));
        }
        ruleBranches.deleteByPidPointRule(r.getPidPointRule());
        for (Long b : branchIds.stream().distinct().toList()) {
            EcomPointRuleBranch link = EcomPointRuleBranch.builder()
                    .pidPointRule(r.getPidPointRule()).pidBranch(b).build();
            link.setStatus("Y");
            ruleBranches.save(link);
        }
    }

    /**
     * Reading a customer's balance is promotions work, and moving one by hand
     * gives money away, so the two are guarded here rather than at the entity.
     * EcomPointsService itself cannot carry these checks: checkout calls it on
     * behalf of a customer, who holds no ERP permission at all.
     */
    public void requireCustomerPointsView() {
        require(PermissionGuard.Right.VIEW, "see a customer's points");
    }

    public void requireCustomerPointsAdjust() {
        require(PermissionGuard.Right.APPROVE, "move a customer's points by hand");
    }

    private void require(PermissionGuard.Right right, String action) {
        if (!permissions.has(MOD_PROMOTIONS, right)) {
            throw new BusinessException("You do not have permission to " + action + ".",
                    HttpStatus.FORBIDDEN, "ECOM_NOT_PERMITTED");
        }
    }

    /* ── views ──────────────────────────────────────────────────────────── */

    private EcomPointsResponses.Rule view(EcomPointRule r) {
        LocalDateTime now = LocalDateTime.now();
        String state = !r.isLive() ? "Stopped"
                : r.getEndsAt() != null && now.isAfter(r.getEndsAt()) ? "Ended"
                : r.getStartsAt() != null && now.isBefore(r.getStartsAt()) ? "Scheduled"
                : "Running";
        List<EcomPointRuleBranch> links = ruleBranches.findByPidPointRule(r.getPidPointRule());
        return EcomPointsResponses.Rule.builder()
                .id(r.getPidPointRule()).name(r.getRuleName()).description(r.getDescription())
                .earnPointsPer100(r.getEarnPointsPer100()).minOrderToEarn(r.getMinOrderToEarn())
                .pointValue(r.getPointValue()).minPointsToRedeem(r.getMinPointsToRedeem())
                .maxRedeemPercent(r.getMaxRedeemPercent()).expiryMonths(r.getExpiryMonths())
                .branchScope(r.getBranchScope()).customerTypeScope(r.getCustomerTypeScope())
                .startsAt(r.getStartsAt()).endsAt(r.getEndsAt())
                .active(r.isLive()).state(state).summary(describe(r))
                .branchIds(links.stream().map(EcomPointRuleBranch::getPidBranch).toList())
                .branchNames(links.stream()
                        .map(l -> branches.findById(l.getPidBranch()).map(AllBranch::getBranchName)
                                .orElse("Branch " + l.getPidBranch())).toList())
                .build();
    }

    /** The rule in one sentence, as a pharmacist would say it. */
    private static String describe(EcomPointRule r) {
        return plain(r.getEarnPointsPer100()) + " point" + (one(r.getEarnPointsPer100()) ? "" : "s") + " per 100 spent"
                + (r.getMinOrderToEarn() == null ? "" : " on orders over " + plain(r.getMinOrderToEarn()))
                + "; 1 point = " + plain(r.getPointValue())
                + (r.getMaxRedeemPercent() == null ? "" : ", up to " + plain(r.getMaxRedeemPercent()) + "% of a bill")
                + (r.getMinPointsToRedeem() == null ? "" : ", from " + r.getMinPointsToRedeem() + " points")
                + (EcomPointRule.ALL_BRANCHES.equals(r.getBranchScope()) ? ", every pharmacy" : ", selected pharmacies")
                + (EcomPointRule.TIER_ALL.equals(r.getCustomerTypeScope()) ? "" : ", " + r.getCustomerTypeScope().toLowerCase() + " only");
    }

    private EcomPointRule load(Long id) {
        return rules.findById(id).orElseThrow(() -> new ResourceNotFoundException("Points rule", id));
    }

    private static boolean one(BigDecimal v) { return v != null && v.compareTo(BigDecimal.ONE) == 0; }

    private static String required(String v, String what) {
        if (v == null || v.isBlank()) {
            throw new BusinessException("A points rule needs a " + what + ".",
                    HttpStatus.BAD_REQUEST, "ECOM_POINTS_INCOMPLETE");
        }
        return v.trim();
    }

    private static String trim(String s, int max) {
        if (s == null || s.isBlank()) return null;
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }

    private static String blank(String s) { return s == null ? "" : s.trim(); }

    private static String plain(BigDecimal v) {
        return v == null ? "0" : v.stripTrailingZeros().toPlainString();
    }
}
