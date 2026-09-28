package com.friendspharma.app.service.impl.ecom;

import com.friendspharma.app.entity.AllBranch;
import com.friendspharma.app.entity.AllRegion;
import com.friendspharma.app.entity.EcomCustomer;
import com.friendspharma.app.exception.custom.BusinessException;
import com.friendspharma.app.repository.AllBranchRepository;
import com.friendspharma.app.repository.AllRegionRepository;
import com.friendspharma.app.repository.EcomCustomerRepository;
import com.friendspharma.app.security.ecom.EcomCustomerPrincipal;
import com.friendspharma.app.security.ecom.EcomSecurity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Which pharmacies this customer may buy from — the one place that decides it.
 *
 * <p>Two journeys, and they do not overlap (docs/ecommerce 23):
 *
 * <pre>
 *   RETAILER             whichever retail-trading pharmacy is near them.
 *                        No region, no selection step, nothing to approve.
 *
 *   WHOLESALER/SPECIAL   the wholesale-trading pharmacies in the ONE region
 *                        somebody approved for them, and nothing else. They
 *                        must choose one before they see a price.
 * </pre>
 *
 * <p><b>Nothing here is read from the request.</b> The customer, their type and
 * their approved region all come from the authenticated record. A wholesaler in
 * Rangpur who edits a branch id into a URL gets the same refusal as one who
 * types a branch that does not exist, because the question this service asks is
 * never "is this branch allowed?" on its own — it is "is this branch in the set
 * this customer's own record permits?".
 *
 * <p><b>A missing region is not Dhaka.</b> It is a refusal with a sentence
 * telling the customer what to complete. Substituting a default here would give
 * away wholesale prices in a region nobody approved, which is the whole reason
 * the region exists.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EcomEligibilityService {

    /**
     * Whether the region rule is armed.
     *
     * <p><b>Off by default, and that is deliberate.</b> The rule is built and
     * tested, but arming it on a database whose region master holds one row and
     * whose 122 approved wholesalers have no approved region would stop all of
     * them ordering the moment it deployed (docs/ecommerce 23 3.2). Switching a
     * rule on is a business decision with a date attached; shipping the code is
     * not.
     *
     * <p>While it is off, eligibility behaves exactly as the platform did
     * before V112 — any pharmacy open to the marketplace — so nothing that
     * works today stops working. Set
     * {@code ECOM_WHOLESALE_REGIONS_ENFORCED=true} once the regions are
     * populated, the branches are marked and the customers are approved.
     */
    @Value("${app.ecom.wholesale-regions-enforced:false}")
    private boolean enforced;

    private final AllBranchRepository branches;
    private final AllRegionRepository regions;
    private final EcomCustomerRepository customers;

    /** What a pharmacy list is allowed to contain, and why, for one customer. */
    public record Scope(EcomCustomer customer,
                        boolean wholesale,
                        Long regionId,
                        String regionName) {}

    /* ══════════════════════════════════════════════════════════════════════
       WHO IS ASKING
       ══════════════════════════════════════════════════════════════════════ */

    @Transactional(readOnly = true)
    public Scope scopeOf() {
        EcomCustomerPrincipal p = EcomSecurity.require();
        EcomCustomer c = customers.findById(p.customerId())
                .orElseThrow(() -> new BusinessException("Your account could not be read.",
                        HttpStatus.UNAUTHORIZED, "ECOM_NO_CUSTOMER"));
        return scopeOf(c);
    }

    /**
     * The same scope, for deciding what somebody may *look at*.
     *
     * <p>Approval is not asked for here. A customer waiting for approval has
     * always been able to browse and fill a cart, and is refused at checkout
     * with a sentence that explains why — taking that away would turn a clear
     * refusal at the end into a baffling one at the beginning.
     */
    @Transactional(readOnly = true)
    public Scope browsingScopeOf(EcomCustomer c) {
        if (!c.isWholesaleBuyer()) return new Scope(c, false, null, null);
        Long regionId = c.getPidApprovedRegion();
        if (regionId == null && !enforced) return new Scope(c, true, null, null);
        if (regionId == null) {
            throw new BusinessException(
                    "Your wholesale region has not been approved yet. Add or update your business address, "
                  + "and the pharmacy will confirm which region you buy in.",
                    HttpStatus.FORBIDDEN, "ECOM_NO_APPROVED_REGION");
        }
        return new Scope(c, true, regionId, regions.findById(regionId).map(AllRegion::getRegionName).orElse(null));
    }

    @Transactional(readOnly = true)
    public Scope scopeOf(EcomCustomer c) {
        if (!c.isApproved()) {
            throw new BusinessException("Your account is waiting for approval. You can order once it is approved.",
                    HttpStatus.FORBIDDEN, "ECOM_CUSTOMER_NOT_APPROVED");
        }
        if (!c.isWholesaleBuyer()) return new Scope(c, false, null, null);

        Long regionId = c.getPidApprovedRegion();
        if (regionId == null && !enforced) {
            /* Unarmed: the customer shops as they did before V112. Reported as
               a wholesale scope with no region so callers can still tell what
               kind of customer this is. */
            return new Scope(c, true, null, null);
        }
        if (regionId == null) {
            /* Deliberately not a generic "not allowed": the customer can fix
               this, and the message says how. */
            throw new BusinessException(
                    "Your wholesale region has not been approved yet. Add or update your business address, "
                  + "and the pharmacy will confirm which region you buy in.",
                    HttpStatus.FORBIDDEN, "ECOM_NO_APPROVED_REGION");
        }
        String name = regions.findById(regionId).map(AllRegion::getRegionName).orElse(null);
        return new Scope(c, true, regionId, name);
    }

    /* ══════════════════════════════════════════════════════════════════════
       WHAT THEY MAY SEE
       ══════════════════════════════════════════════════════════════════════ */

    /**
     * The wholesale pharmacies this customer may choose between.
     *
     * <p>Region first, trading type second, and the platform's own rules third
     * — a branch that is not trading, or not open to the marketplace, is not a
     * choice however right its region is.
     */
    @Transactional(readOnly = true)
    public List<AllBranch> eligibleWholesalePharmacies() {
        Scope s = scopeOf();
        if (!s.wholesale()) {
            throw new BusinessException("Wholesale pharmacies are for wholesale accounts. "
                  + "Your account buys from the pharmacies near you.",
                    HttpStatus.FORBIDDEN, "ECOM_NOT_WHOLESALE");
        }
        return branches.findAll().stream()
                .filter(this::tradeable)
                .filter(AllBranch::sellsWholesale)
                .filter(b -> s.regionId() == null || s.regionId().equals(b.getPidRegion()))
                .sorted(Comparator.comparing(AllBranch::getBranchName, Comparator.nullsLast(String::compareToIgnoreCase)))
                .toList();
    }

    /**
     * May this customer shop at this branch at all?
     *
     * <p>Called wherever a branch id arrives from outside — the catalogue, the
     * cart, the checkout — because a rule enforced only at the screen that
     * offers the choice is a rule anybody with a terminal can skip.
     */
    @Transactional(readOnly = true)
    public AllBranch requireShoppable(Long branchId) {
        if (branchId == null) {
            throw new BusinessException("Choose a pharmacy first.", HttpStatus.BAD_REQUEST, "ECOM_NO_BRANCH");
        }
        EcomCustomerPrincipal p = EcomSecurity.require();
        EcomCustomer c = customers.findById(p.customerId())
                .orElseThrow(() -> new BusinessException("Your account could not be read.",
                        HttpStatus.UNAUTHORIZED, "ECOM_NO_CUSTOMER"));
        Scope s = browsingScopeOf(c);
        AllBranch b = branches.findById(branchId)
                .filter(this::tradeable)
                .orElseThrow(() -> new BusinessException("That pharmacy is not open for online orders.",
                        HttpStatus.NOT_FOUND, "ECOM_BRANCH_NOT_AVAILABLE"));

        if (!enforced) return b;   // pre-V112 behaviour: any pharmacy open to the marketplace

        if (s.wholesale()) {
            if (!b.sellsWholesale()) {
                throw new BusinessException(b.getBranchName() + " does not sell wholesale.",
                        HttpStatus.FORBIDDEN, "ECOM_BRANCH_NOT_WHOLESALE");
            }
            if (s.regionId() != null && !s.regionId().equals(b.getPidRegion())) {
                /* Named, not silent: the customer is not doing anything wrong
                   by having moved, and the pharmacy staff need to hear about it
                   if the region on the record is the thing that is wrong. */
                log.info("Customer {} ({}) refused branch {} — approved region {} does not cover it",
                        s.customer().getPidEcomCustomer(), s.customer().getCustomerType(), branchId, s.regionId());
                throw new BusinessException(b.getBranchName() + " is not in "
                        + (s.regionName() == null ? "your approved region" : s.regionName())
                        + ", which is where your account is approved to buy wholesale.",
                        HttpStatus.FORBIDDEN, "ECOM_BRANCH_WRONG_REGION");
            }
        } else if (!b.sellsRetail()) {
            throw new BusinessException(b.getBranchName() + " is a wholesale pharmacy and does not sell to retail accounts.",
                    HttpStatus.FORBIDDEN, "ECOM_BRANCH_WHOLESALE_ONLY");
        }
        return b;
    }

    /**
     * The same question, asked where a refusal would be the wrong answer —
     * a list that should simply leave the branch out rather than fail.
     */
    @Transactional(readOnly = true)
    public boolean mayShopAt(AllBranch b) {
        if (b == null || !tradeable(b)) return false;
        if (!enforced) return true;
        Optional<Scope> s = quietScope();
        if (s.isEmpty()) return b.sellsRetail();          // signed out: the retail shelf
        if (!s.get().wholesale()) return b.sellsRetail();
        return b.sellsWholesale() && s.get().regionId() != null && s.get().regionId().equals(b.getPidRegion());
    }

    /** The scope, or nothing, for callers that also serve guests. */
    private Optional<Scope> quietScope() {
        try { return Optional.of(scopeOf()); }
        catch (RuntimeException e) { return Optional.empty(); }
    }

    /** Open for business at all: active row, tenant trading, listed online. */
    private boolean tradeable(AllBranch b) {
        return b != null
            && !"N".equals(b.getStatus())
            && b.isEcomEnabled()
            && !"SUSPENDED".equalsIgnoreCase(String.valueOf(b.getTenantStatus()));
    }
}
