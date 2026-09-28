package com.friendspharma.app.service.impl.ecom;

import com.friendspharma.app.entity.AllBranch;
import com.friendspharma.app.entity.EcomCustomer;
import com.friendspharma.app.entity.EcomCustomerBranchLink;
import com.friendspharma.app.entity.PosCustomerInfo;
import com.friendspharma.app.exception.custom.BusinessException;
import com.friendspharma.app.exception.custom.ResourceNotFoundException;
import com.friendspharma.app.repository.AllBranchRepository;
import com.friendspharma.app.repository.EcomCustomerBranchLinkRepository;
import com.friendspharma.app.repository.EcomCustomerRepository;
import com.friendspharma.app.repository.PosCustomerInfoRepository;
import com.friendspharma.app.util.PhoneNumbers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * The bridge between a global customer and a pharmacy's own customer list.
 *
 * <p>{@code POS_TRANSACTION_MST.PID_SUP_CUST} must point at a
 * {@link PosCustomerInfo} row of the sale's branch for every invoice, return,
 * collection, ledger posting and report to keep working unchanged. A global
 * customer has no such row — until the first time they order from that
 * pharmacy, when this service makes one (the "shadow") and remembers the
 * pairing in {@link EcomCustomerBranchLink}.
 *
 * <p>Rules:
 * <ul>
 *   <li>The shadow is an ordinary branch customer: org, company and branch of
 *       the pharmacy; {@code CUSTOMER_TYPE} in the spelling the ERP check
 *       constraint accepts; {@code MOBILE_NO} in the local form the pharmacy's
 *       own rows use; {@code ECOM_MANAGED='Y'} so the Customers screen refuses
 *       to edit the type.</li>
 *   <li>If the pharmacy already has a customer with that phone number
 *       ({@code uq_customer_branch_mobile}), that row is <b>adopted</b> rather
 *       than duplicated — it is the same person — and marked
 *       {@code LINK_MODE=LINKED}. Its type becomes the global tier at that
 *       moment, which is the customer's actual tier; the previous value is
 *       logged. (BUSINESS CONFIRMATION was requested on auto-link vs manual in
 *       Phase 0; auto-link is implemented, with the audit trail to review.)</li>
 *   <li>A global tier change is pushed to every shadow in one transaction
 *       ({@link #propagateType}). Historical invoices are unaffected: the
 *       price and the tier are frozen on the transaction lines.</li>
 * </ul>
 *
 * <p>Built in Phase 1 so it exists and is tested before checkout (Phase 6)
 * needs it; nothing calls {@link #ensureShadow} in production yet.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EcomCustomerBridgeService {

    private final EcomCustomerRepository customers;
    private final EcomCustomerBranchLinkRepository links;
    private final PosCustomerInfoRepository posCustomers;
    private final AllBranchRepository branches;
    private final EcomAuditRecorder audit;

    /**
     * The pharmacy-side customer id for this global customer at this branch,
     * creating or adopting the row on first use.
     */
    @Transactional
    public Long ensureShadow(Long ecomCustomerId, Long branchId) {
        Optional<EcomCustomerBranchLink> existing = links.findByPidEcomCustomerAndPidBranch(ecomCustomerId, branchId);
        if (existing.isPresent()) return existing.get().getPidCustomer();

        EcomCustomer c = customers.findById(ecomCustomerId)
                .orElseThrow(() -> new ResourceNotFoundException("Customer", ecomCustomerId));
        if (!c.isApproved()) {
            throw new BusinessException(
                    "This customer account is " + c.getApprovalStatus().toLowerCase()
                    + " and cannot be linked to a pharmacy.",
                    HttpStatus.CONFLICT, "ECOM_CUSTOMER_NOT_APPROVED");
        }
        AllBranch branch = branches.findById(branchId)
                .orElseThrow(() -> new ResourceNotFoundException("Branch", branchId));

        String posType = EcomCustomer.posCustomerTypeOf(c.getCustomerType());
        String localMobile = PhoneNumbers.localForm(c.getMobileNo());

        Optional<PosCustomerInfo> adopted = posCustomers.findFirstByPidBranchAndMobileNo(branchId, localMobile);
        PosCustomerInfo row;
        String mode;
        if (adopted.isPresent()) {
            row = adopted.get();
            // Already the shadow of somebody else? Then two identities share a
            // phone at this pharmacy, which the global unique index makes
            // impossible unless the data was edited by hand. Refuse rather
            // than silently re-point an invoice history.
            if (links.findByPidCustomer(row.getPidCustomer()).isPresent()) {
                throw new BusinessException(
                        "The pharmacy customer with mobile " + localMobile
                        + " is already linked to a different online account.",
                        HttpStatus.CONFLICT, "ECOM_SHADOW_TAKEN");
            }
            String previousType = row.getCustomerType();
            row.setCustomerType(posType);
            row.setEcomManaged("Y");
            if ("N".equals(row.getStatus())) row.setStatus("Y");
            posCustomers.save(row);
            mode = EcomCustomerBranchLink.MODE_LINKED;
            audit.record(EcomAuditRecorder.BRANCH_LINKED, ecomCustomerId, branchId, "POS_CUSTOMER", row.getPidCustomer(),
                    "Adopted existing customer " + row.getPidCustomer() + " at " + branch.getBranchName()
                    + " (type " + previousType + " -> " + posType + ")");
        } else {
            row = PosCustomerInfo.builder()
                    .pidOrg(branch.getPidOrg()).pidCompany(branch.getPidCompany()).pidBranch(branchId)
                    .customerName(c.getFullName())
                    .customerCode("EC-" + c.getPidEcomCustomer())
                    .mobileNo(localMobile)
                    .emailAddress(c.getEmail())
                    .address(c.getRegAddress()).city(c.getRegCity())
                    .customerType(posType)
                    .ecomManaged("Y")
                    .build();
            row.setStatus("Y");
            row = posCustomers.save(row);
            mode = EcomCustomerBranchLink.MODE_CREATED;
            audit.record(EcomAuditRecorder.BRANCH_LINKED, ecomCustomerId, branchId, "POS_CUSTOMER", row.getPidCustomer(),
                    "Created pharmacy customer " + row.getPidCustomer() + " at " + branch.getBranchName());
        }

        links.save(EcomCustomerBranchLink.builder()
                .pidEcomCustomer(ecomCustomerId).pidBranch(branchId).pidCustomer(row.getPidCustomer())
                .linkMode(mode).linkedBy("ECOM")
                .build());
        log.info("E-commerce customer {} linked to branch {} as pharmacy customer {} ({})",
                ecomCustomerId, branchId, row.getPidCustomer(), mode);
        return row.getPidCustomer();
    }

    /** Copies the global tier onto every shadow row. Called inside the type-change transaction. */
    @Transactional
    public int propagateType(Long ecomCustomerId, String ecomType) {
        String posType = EcomCustomer.posCustomerTypeOf(ecomType);
        List<EcomCustomerBranchLink> all = links.findByPidEcomCustomer(ecomCustomerId);
        int changed = 0;
        for (EcomCustomerBranchLink l : all) {
            PosCustomerInfo row = posCustomers.findById(l.getPidCustomer()).orElse(null);
            if (row == null) continue;
            if (!posType.equals(row.getCustomerType())) {
                row.setCustomerType(posType);
                changed++;
            }
            if (!"Y".equals(row.getEcomManaged())) row.setEcomManaged("Y");
            posCustomers.save(row);
        }
        return changed;
    }

    /** Keeps the shadow's display fields in step with the profile (name, e-mail). Type is not touched here. */
    @Transactional
    public void propagateProfile(EcomCustomer c) {
        for (EcomCustomerBranchLink l : links.findByPidEcomCustomer(c.getPidEcomCustomer())) {
            posCustomers.findById(l.getPidCustomer()).ifPresent(row -> {
                if (EcomCustomerBranchLink.MODE_CREATED.equals(l.getLinkMode())) {
                    row.setCustomerName(c.getFullName());
                    row.setEmailAddress(c.getEmail());
                    posCustomers.save(row);
                }
            });
        }
    }

    @Transactional(readOnly = true)
    public List<EcomCustomerBranchLink> linksOf(Long ecomCustomerId) {
        return links.findByPidEcomCustomer(ecomCustomerId);
    }
}
