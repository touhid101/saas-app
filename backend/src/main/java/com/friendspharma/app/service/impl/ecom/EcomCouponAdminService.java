package com.friendspharma.app.service.impl.ecom;

import com.friendspharma.app.dto.response.EcomCouponResponses;
import com.friendspharma.app.entity.AllBranch;
import com.friendspharma.app.entity.EcomCoupon;
import com.friendspharma.app.entity.EcomCouponBranch;
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
 * Managing coupons (MOD54 / docs/ecommerce Phase 12).
 *
 * <p><b>A coupon costs somebody money, so who may write one is not a detail.</b>
 * A pharmacy may create coupons for <em>its own</em> branch and no other; only a
 * platform operator may write one that every pharmacy honours. That is enforced
 * here rather than on the screen, because the screen is not the only way in.
 *
 * <p>Codes are stored as typed and matched case-insensitively — SAVE100 and
 * save100 are one coupon, and the unique index says so.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EcomCouponAdminService {

    /** The screen this service is behind, as the RBAC matrix knows it. */
    public static final String MOD_PROMOTIONS = "MOD54";

    private final EcomCouponRepository coupons;
    private final EcomCouponBranchRepository couponBranches;
    private final EcomCouponUsageRepository usages;
    private final AllBranchRepository branches;
    private final BranchScopeService branchScope;
    private final PermissionGuard permissions;
    private final EcomAuditRecorder audit;

    /* ══════════════════════════════════════════════════════════════════════
       READS
       ══════════════════════════════════════════════════════════════════════ */

    @Transactional(readOnly = true)
    public Page<EcomCouponResponses.Row> list(String active, String q, int page, int size) {
        require(PermissionGuard.Right.VIEW, "see coupons");
        var pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
        return coupons.search(blank(active), blank(q), pageable).map(this::row);
    }

    @Transactional(readOnly = true)
    public EcomCouponResponses.Detail detail(Long id) {
        require(PermissionGuard.Right.VIEW, "see coupons");
        EcomCoupon c = load(id);
        return EcomCouponResponses.Detail.builder()
                .coupon(row(c))
                .branches(couponBranches.findByPidCoupon(id).stream()
                        .map(b -> EcomCouponResponses.BranchRef.builder()
                                .branchId(b.getPidBranch())
                                .branchName(branches.findById(b.getPidBranch())
                                        .map(AllBranch::getBranchName).orElse("Branch " + b.getPidBranch()))
                                .build()).toList())
                .build();
    }

    /* ══════════════════════════════════════════════════════════════════════
       WRITES
       ══════════════════════════════════════════════════════════════════════ */

    @Transactional
    public EcomCouponResponses.Row create(EcomCouponResponses.Form form) {
        require(PermissionGuard.Right.CREATE, "create a coupon");

        String code = required(form.getCode(), "code").trim().toUpperCase(Locale.ROOT);
        coupons.findByCode(code).ifPresent(existing -> {
            throw new BusinessException("The code " + existing.getCode() + " is already in use.",
                    HttpStatus.CONFLICT, "ECOM_COUPON_CODE_TAKEN");
        });

        EcomCoupon c = new EcomCoupon();
        c.setCode(code);
        c.setPidOwnerBranch(branchScope.resolve(null));
        c.setPidOrg(SecurityUtils.getCurrentOrgId());
        c.setStatus("Y");
        apply(c, form, true);
        coupons.save(c);
        saveBranches(c, form.getBranchIds());

        audit.record("COUPON_CREATED", null, c.getPidOwnerBranch(), "ECOM_COUPON", c.getPidCoupon(),
                c.getCode() + " · " + describe(c));
        log.info("Coupon {} created by {} ({})", c.getCode(), SecurityUtils.getCurrentUsername(), describe(c));
        return row(c);
    }

    @Transactional
    public EcomCouponResponses.Row update(Long id, EcomCouponResponses.Form form) {
        require(PermissionGuard.Right.EDIT, "change a coupon");
        EcomCoupon c = load(id);

        /* The code is not editable once it exists. Somebody has been given it;
           changing it would silently invalidate whatever is printed on a leaflet
           or sitting in a customer's messages. */
        apply(c, form, false);
        coupons.save(c);
        saveBranches(c, form.getBranchIds());

        audit.record("COUPON_UPDATED", null, c.getPidOwnerBranch(), "ECOM_COUPON", c.getPidCoupon(),
                c.getCode() + " · " + describe(c));
        return row(c);
    }

    /**
     * Switches a coupon on or off.
     *
     * <p>Never deleted: orders that were paid with it point at it, and a
     * campaign's cost is read from those rows.
     */
    @Transactional
    public EcomCouponResponses.Row setActive(Long id, boolean on) {
        require(PermissionGuard.Right.APPROVE, on ? "start a coupon" : "stop a coupon");
        EcomCoupon c = load(id);
        c.setIsActive(on ? "Y" : "N");
        coupons.save(c);
        audit.record(on ? "COUPON_STARTED" : "COUPON_STOPPED", null, c.getPidOwnerBranch(),
                "ECOM_COUPON", c.getPidCoupon(), c.getCode());
        log.info("Coupon {} {} by {}", c.getCode(), on ? "started" : "stopped", SecurityUtils.getCurrentUsername());
        return row(c);
    }

    /* ══════════════════════════════════════════════════════════════════════
       RULES
       ══════════════════════════════════════════════════════════════════════ */

    private void apply(EcomCoupon c, EcomCouponResponses.Form form, boolean creating) {
        c.setCouponName(required(form.getName(), "name"));
        c.setDescription(trim(form.getDescription(), 300));

        String type = required(form.getDiscountType(), "discount type").trim().toUpperCase(Locale.ROOT);
        if (!List.of(EcomCoupon.PERCENT, EcomCoupon.FIXED, EcomCoupon.FREE_DELIVERY).contains(type)) {
            throw new BusinessException("A coupon is a percentage, a fixed amount, or free delivery — '"
                    + form.getDiscountType() + "' is none of them.", HttpStatus.BAD_REQUEST, "ECOM_COUPON_TYPE");
        }
        c.setDiscountType(type);

        if (EcomCoupon.FREE_DELIVERY.equals(type)) {
            c.setDiscountValue(BigDecimal.ZERO);
            c.setMaxDiscountAmount(null);
        } else {
            BigDecimal v = form.getDiscountValue();
            if (v == null || v.signum() <= 0) {
                throw new BusinessException("Say how much the coupon is worth.",
                        HttpStatus.BAD_REQUEST, "ECOM_COUPON_VALUE");
            }
            if (EcomCoupon.PERCENT.equals(type) && v.compareTo(BigDecimal.valueOf(100)) > 0) {
                throw new BusinessException("A percentage coupon cannot be more than 100%.",
                        HttpStatus.BAD_REQUEST, "ECOM_COUPON_VALUE");
            }
            c.setDiscountValue(v);
            c.setMaxDiscountAmount(form.getMaxDiscountAmount());
            /* A percentage without a ceiling is an open cheque: 20% off is
               ৳10,000 on a ৳50,000 order. Warned about rather than refused,
               because a pharmacy may genuinely mean it. */
            if (EcomCoupon.PERCENT.equals(type) && form.getMaxDiscountAmount() == null) {
                log.warn("Coupon {} is {}% with no maximum — a large basket will take a large discount.",
                        c.getCode(), v);
            }
        }

        c.setMinOrderAmount(form.getMinOrderAmount());
        c.setUsageLimitTotal(form.getUsageLimitTotal());
        c.setUsageLimitPerCustomer(form.getUsageLimitPerCustomer());
        c.setFirstOrderOnly("Y".equalsIgnoreCase(form.getFirstOrderOnly()) ? "Y" : "N");
        c.setStartsAt(form.getStartsAt());
        c.setEndsAt(form.getEndsAt());
        if (c.getStartsAt() != null && c.getEndsAt() != null && !c.getEndsAt().isAfter(c.getStartsAt())) {
            throw new BusinessException("The coupon would end before it starts.",
                    HttpStatus.BAD_REQUEST, "ECOM_COUPON_WINDOW");
        }

        String tier = form.getCustomerTypeScope() == null ? EcomCoupon.TIER_ALL
                : form.getCustomerTypeScope().trim().toUpperCase(Locale.ROOT);
        if (!List.of("ALL", "RETAILER", "WHOLESALER", "SPECIAL").contains(tier)) {
            throw new BusinessException("'" + form.getCustomerTypeScope() + "' is not a customer type.",
                    HttpStatus.BAD_REQUEST, "ECOM_COUPON_TIER");
        }
        c.setCustomerTypeScope(tier);

        /* Where it may be spent — the decision with money attached, so it is
           never inferred. A pharmacy may write coupons for itself; only the
           platform may write one that everybody honours. */
        String scope = required(form.getBranchScope(), "branch scope").trim().toUpperCase(Locale.ROOT);
        if (!List.of(EcomCoupon.ALL_BRANCHES, EcomCoupon.SELECTED_BRANCHES).contains(scope)) {
            throw new BusinessException("A coupon applies to all pharmacies or to named ones.",
                    HttpStatus.BAD_REQUEST, "ECOM_COUPON_SCOPE");
        }
        if (EcomCoupon.ALL_BRANCHES.equals(scope) && !branchScope.isPlatformOperator()) {
            throw new BusinessException(
                    "Only a platform administrator can offer a coupon at every pharmacy. "
                    + "Choose the pharmacies it applies to.",
                    HttpStatus.FORBIDDEN, "NOT_PLATFORM_OPERATOR");
        }
        c.setBranchScope(scope);
        if (creating) c.setIsActive("N".equalsIgnoreCase(form.getIsActive()) ? "N" : "Y");
        else if (form.getIsActive() != null) c.setIsActive("N".equalsIgnoreCase(form.getIsActive()) ? "N" : "Y");
    }

    /** Which pharmacies honour it — and a branch may only name its own. */
    private void saveBranches(EcomCoupon c, List<Long> branchIds) {
        if (EcomCoupon.ALL_BRANCHES.equals(c.getBranchScope())) {
            couponBranches.deleteByPidCoupon(c.getPidCoupon());
            return;
        }
        if (branchIds == null || branchIds.isEmpty()) {
            throw new BusinessException("Name at least one pharmacy for this coupon.",
                    HttpStatus.BAD_REQUEST, "ECOM_COUPON_NO_BRANCH");
        }
        Long mine = branchScope.resolve(null);
        boolean platform = branchScope.isPlatformOperator();
        for (Long b : branchIds) {
            if (!platform && !b.equals(mine)) {
                throw new BusinessException(
                        "You can only offer a coupon at your own pharmacy.",
                        HttpStatus.FORBIDDEN, "ECOM_COUPON_OTHER_BRANCH");
            }
            branches.findById(b).orElseThrow(() -> new ResourceNotFoundException("Branch", b));
        }
        couponBranches.deleteByPidCoupon(c.getPidCoupon());
        for (Long b : branchIds.stream().distinct().toList()) {
            EcomCouponBranch link = EcomCouponBranch.builder().pidCoupon(c.getPidCoupon()).pidBranch(b).build();
            link.setStatus("Y");
            couponBranches.save(link);
        }
    }

    private void require(PermissionGuard.Right right, String action) {
        if (!permissions.has(MOD_PROMOTIONS, right)) {
            throw new BusinessException(
                    "You do not have permission to " + action + ".",
                    HttpStatus.FORBIDDEN, "ECOM_NOT_PERMITTED");
        }
    }

    /* ── views ──────────────────────────────────────────────────────────── */

    private EcomCouponResponses.Row row(EcomCoupon c) {
        long used = usages.countLive(c.getPidCoupon());
        LocalDateTime now = LocalDateTime.now();
        String state = !c.isLive() ? "Stopped"
                : c.getEndsAt() != null && now.isAfter(c.getEndsAt()) ? "Expired"
                : c.getStartsAt() != null && now.isBefore(c.getStartsAt()) ? "Scheduled"
                : c.getUsageLimitTotal() != null && used >= c.getUsageLimitTotal() ? "Taken up"
                : "Running";

        return EcomCouponResponses.Row.builder()
                .id(c.getPidCoupon()).code(c.getCode()).name(c.getCouponName())
                .description(c.getDescription())
                .discountType(c.getDiscountType()).discountValue(c.getDiscountValue())
                .maxDiscountAmount(c.getMaxDiscountAmount()).minOrderAmount(c.getMinOrderAmount())
                .branchScope(c.getBranchScope()).customerTypeScope(c.getCustomerTypeScope())
                .firstOrderOnly(c.isFirstOrderOnly())
                .usageLimitTotal(c.getUsageLimitTotal()).usageLimitPerCustomer(c.getUsageLimitPerCustomer())
                .startsAt(c.getStartsAt()).endsAt(c.getEndsAt())
                .active(c.isLive()).state(state)
                .timesUsed(used).spent(usages.spentOn(c.getPidCoupon()))
                .summary(describe(c))
                .build();
    }

    /** The coupon in one line, for a list and for the audit trail. */
    private static String describe(EcomCoupon c) {
        String worth = switch (c.getDiscountType()) {
            case EcomCoupon.PERCENT -> plain(c.getDiscountValue()) + "% off"
                    + (c.getMaxDiscountAmount() == null ? "" : ", up to " + plain(c.getMaxDiscountAmount()));
            case EcomCoupon.FIXED -> plain(c.getDiscountValue()) + " off";
            default -> "free delivery";
        };
        return worth
                + (c.getMinOrderAmount() == null ? "" : " on orders over " + plain(c.getMinOrderAmount()))
                + (EcomCoupon.ALL_BRANCHES.equals(c.getBranchScope()) ? ", every pharmacy" : ", selected pharmacies")
                + (EcomCoupon.TIER_ALL.equals(c.getCustomerTypeScope()) ? "" : ", " + c.getCustomerTypeScope().toLowerCase() + " only")
                + (c.isFirstOrderOnly() ? ", first order only" : "");
    }

    private EcomCoupon load(Long id) {
        return coupons.findById(id).orElseThrow(() -> new ResourceNotFoundException("Coupon", id));
    }

    private static String required(String v, String what) {
        if (v == null || v.isBlank()) {
            throw new BusinessException("A coupon needs a " + what + ".", HttpStatus.BAD_REQUEST, "ECOM_COUPON_INCOMPLETE");
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
