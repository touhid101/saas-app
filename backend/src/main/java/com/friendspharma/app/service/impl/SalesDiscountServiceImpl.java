package com.friendspharma.app.service.impl;

import com.friendspharma.app.dto.request.SalesDiscountRequest;
import com.friendspharma.app.dto.response.SalesDiscountResponse;
import com.friendspharma.app.entity.PosSalesDiscount;
import com.friendspharma.app.exception.custom.ResourceNotFoundException;
import com.friendspharma.app.repository.PosSalesDiscountRepository;
import com.friendspharma.app.service.interfaces.SalesDiscountService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import com.friendspharma.app.exception.custom.BusinessException;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class SalesDiscountServiceImpl implements SalesDiscountService {

    private final PosSalesDiscountRepository discountRepo;
    private final PermissionGuard            permissions;

    /** ACL_MODULE_INFO code for Sales Discount%. */
    private static final String MODULE = "MOD08";

    /**
     * Writing a discount rule is whatever the permission matrix says it is.
     *
     * <p>These three endpoints used to carry
     * {@code @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")} (and {@code hasRole('ADMIN')}
     * on delete), which was wrong twice over. {@code MANAGER} is granted to nobody — no
     * code path ever issues that authority — and {@code ADMIN} is issued only to Super
     * Admin. So a Sales Manager or Administrator (Branch) holding the right on Sales
     * Discount% saw the controls, because the screen reads the matrix, and then got 403,
     * because the server did not.
     *
     * <p>Checked here rather than on the endpoints so it holds for every route into a
     * change, including any added later. A discount percentage is a price: who may set
     * one is a business decision, and it now moves with the matrix rather than with a
     * redeploy.
     */
    private void requireRight(PermissionGuard.Right right, String action) {
        if (!permissions.has(MODULE, right)) {
            throw new BusinessException(
                    "You do not have permission to " + action + " a sales discount rule. "
                    + "Ask somebody whose role holds " + label(right) + " on Sales Discount%.",
                    HttpStatus.FORBIDDEN, "SALES_DISCOUNT_NOT_PERMITTED");
        }
    }

    private static String label(PermissionGuard.Right right) {
        return switch (right) {
            case CREATE -> "Add";
            case EDIT   -> "Edit";
            case DELETE -> "Delete";
            default     -> right.name();
        };
    }

    // ── Create ────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public SalesDiscountResponse.SalesDiscountDto create(
            SalesDiscountRequest.CreateSalesDiscountRequest req) {
        requireRight(PermissionGuard.Right.CREATE, "add");

        PosSalesDiscount disc = PosSalesDiscount.builder()
                .pidOrg(req.getPidOrg())
                .pidCompany(req.getPidCompany())
                .pidProduct(req.getPidProduct())
                .discountTier(req.getDiscountTier())
                .discountPct(req.getDiscountPct())
                .isActive(1)
                .build();

        return toDto(discountRepo.save(disc));
    }

    // ── Update ────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public SalesDiscountResponse.SalesDiscountDto update(Long pidDisc,
            SalesDiscountRequest.UpdateSalesDiscountRequest req) {
        requireRight(PermissionGuard.Right.EDIT, "change");

        PosSalesDiscount disc = discountRepo.findById(pidDisc)
                .orElseThrow(() -> new ResourceNotFoundException("SalesDiscount", pidDisc));

        if (req.getDiscountPct() != null)  disc.setDiscountPct(req.getDiscountPct());
        if (req.getIsActive()   != null)   disc.setIsActive(req.getIsActive());

        return toDto(discountRepo.save(disc));
    }

    // ── Delete ────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public void delete(Long pidDisc) {
        requireRight(PermissionGuard.Right.DELETE, "delete");
        if (!discountRepo.existsById(pidDisc)) {
            throw new ResourceNotFoundException("SalesDiscount", pidDisc);
        }
        discountRepo.deleteById(pidDisc);
    }

    // ── Queries ───────────────────────────────────────────────────────────

    @Override
    public List<SalesDiscountResponse.SalesDiscountDto> listByOrg(Long orgId) {
        return discountRepo.findByPidOrgAndIsActive(orgId, 1)
                .stream().map(this::toDto).collect(Collectors.toList());
    }

    @Override
    public List<SalesDiscountResponse.SalesDiscountDto> listAllByOrg(Long orgId) {
        return discountRepo.findByPidOrgAndIsActive(orgId, 1)    // re-use; adjust if needed
                .stream().map(this::toDto).collect(Collectors.toList());
    }

    @Override
    public List<SalesDiscountResponse.SalesDiscountDto> listByCompany(Long orgId, Long companyId) {
        return discountRepo.findByPidOrgAndPidCompanyAndIsActive(orgId, companyId, 1)
                .stream().map(this::toDto).collect(Collectors.toList());
    }

    @Override
    public List<SalesDiscountResponse.SalesDiscountDto> listByProduct(Long orgId, Long productId) {
        return discountRepo.findByPidOrgAndPidProductAndIsActive(orgId, productId, 1)
                .stream().map(this::toDto).collect(Collectors.toList());
    }

    // ── Effective discount ────────────────────────────────────────────────

    @Override
    public BigDecimal resolveEffectiveDiscount(Long orgId, Long productId,
                                               Long companyId, String tier) {
        List<PosSalesDiscount> matches =
                discountRepo.findEffectiveDiscount(orgId, productId, companyId, tier);
        return matches.isEmpty() ? BigDecimal.ZERO : matches.get(0).getDiscountPct();
    }

    @Override
    public SalesDiscountResponse.EffectiveDiscountDto getEffectiveDiscount(
            SalesDiscountRequest.EffectiveDiscountQuery q) {

        List<PosSalesDiscount> matches =
                discountRepo.findEffectiveDiscount(q.getOrgId(), q.getProductId(),
                                                   q.getCompanyId(), q.getTier());
        if (matches.isEmpty()) {
            return SalesDiscountResponse.EffectiveDiscountDto.builder()
                    .discountTier(q.getTier())
                    .discountPct(BigDecimal.ZERO)
                    .appliedAt("none")
                    .build();
        }
        PosSalesDiscount best = matches.get(0);
        String level = best.getPidProduct() != null ? "product"
                     : best.getPidCompany() != null ? "company"
                     : "org";
        return SalesDiscountResponse.EffectiveDiscountDto.builder()
                .pidDisc(best.getPidDisc())
                .discountTier(best.getDiscountTier())
                .discountPct(best.getDiscountPct())
                .appliedAt(level)
                .build();
    }

    // ── Mapper ────────────────────────────────────────────────────────────

    private SalesDiscountResponse.SalesDiscountDto toDto(PosSalesDiscount d) {
        return SalesDiscountResponse.SalesDiscountDto.builder()
                .pidDisc(d.getPidDisc())
                .pidOrg(d.getPidOrg())
                .pidCompany(d.getPidCompany())
                .companyName(d.getCompany() != null ? d.getCompany().getCompanyName() : null)
                .pidProduct(d.getPidProduct())
                .productName(d.getProduct() != null ? d.getProduct().getProductName() : null)
                .discountTier(d.getDiscountTier())
                .discountPct(d.getDiscountPct())
                .isActive(d.getIsActive())
                .createdAt(d.getCreatedAt())
                .updatedAt(d.getUpdatedAt())
                .build();
    }
}
