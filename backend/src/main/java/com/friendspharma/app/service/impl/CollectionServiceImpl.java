package com.friendspharma.app.service.impl;

import com.friendspharma.app.dto.request.CollectionRequest;
import com.friendspharma.app.dto.response.CollectionResponse;
import com.friendspharma.app.entity.PosDeliveryCollection;
import com.friendspharma.app.entity.PosTransactionMst;
import com.friendspharma.app.exception.custom.BusinessException;
import com.friendspharma.app.exception.custom.ResourceNotFoundException;
import com.friendspharma.app.repository.PosDeliveryCollectionRepository;
import com.friendspharma.app.repository.PosTransactionMstRepository;
import org.springframework.http.HttpStatus;
import com.friendspharma.app.service.interfaces.CollectionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.friendspharma.app.service.impl.accounting.AccountingHooks;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class CollectionServiceImpl implements CollectionService {

    private final PosDeliveryCollectionRepository collectionRepo;
    private final PosTransactionMstRepository     tranMstRepo;
    private final PermissionGuard                 permissions;
    /** Accounts: every financial event this service commits is booked through here, in the same transaction. */
    private final AccountingHooks accounting;

    /** ACL_MODULE_INFO code for Collection. */
    private static final String MODULE = "MOD10";

    @Override
    @Transactional
    public CollectionResponse.CollectionDto record(CollectionRequest.RecordCollectionRequest req) {
        PosTransactionMst mst = tranMstRepo.findById(req.getPidTransaction())
                .orElseThrow(() -> new ResourceNotFoundException("Transaction", req.getPidTransaction()));

        BigDecimal alreadyCollected = collectionRepo
                .sumCollectedByTransaction(req.getPidTransaction());
        if (alreadyCollected == null) alreadyCollected = BigDecimal.ZERO;

        BigDecimal outstanding = mst.getGrandTotal().subtract(alreadyCollected);
        if (req.getCollectedAmount().compareTo(outstanding) > 0) {
            throw new BusinessException(
                    String.format("Collection amount %.2f exceeds outstanding balance %.2f",
                            req.getCollectedAmount(), outstanding));
        }

        BigDecimal newBalance = outstanding.subtract(req.getCollectedAmount());

        PosDeliveryCollection col = PosDeliveryCollection.builder()
                .pidOrg(req.getPidOrg())
                .pidBranch(req.getPidBranch())
                .pidTransaction(req.getPidTransaction())
                .invoiceNo(mst.getInvoiceNo())
                .pidDeliveryMan(req.getPidDeliveryMan())
                .pidCustomer(mst.getPidCustomer())
                .customerName(mst.getCustomerName())
                .collectionDate(req.getCollectionDate() != null ? req.getCollectionDate() : LocalDate.now())
                .invoiceAmount(mst.getGrandTotal())
                .collectedAmount(req.getCollectedAmount())
                .balanceAmount(newBalance)
                .paymentMode(req.getPaymentMode() != null ? req.getPaymentMode() : "CASH")
                .chequeNo(req.getChequeNo())
                .bankName(req.getBankName())
                .transactionRef(req.getTransactionRef())
                .remarks(req.getRemarks())
                .status("COLLECTED")
                .build();

        PosDeliveryCollection saved = collectionRepo.save(col);
        accounting.deliveryCashIn(saved);
        return toDto(saved);
    }

    @Override
    @Transactional
    public CollectionResponse.CollectionDto update(Long pidCollection,
            CollectionRequest.UpdateCollectionRequest req) {
        PosDeliveryCollection col = getActive(pidCollection);
        if (req.getCollectedAmount() != null) col.setCollectedAmount(req.getCollectedAmount());
        if (req.getPaymentMode()     != null) col.setPaymentMode(req.getPaymentMode());
        if (req.getChequeNo()        != null) col.setChequeNo(req.getChequeNo());
        if (req.getBankName()        != null) col.setBankName(req.getBankName());
        if (req.getTransactionRef()  != null) col.setTransactionRef(req.getTransactionRef());
        if (req.getRemarks()         != null) col.setRemarks(req.getRemarks());
        PosDeliveryCollection saved = collectionRepo.save(col);
        if (req.getCollectedAmount() != null || req.getPaymentMode() != null) {
            // The figures changed: the earlier booking is reversed and the collection booked afresh.
            accounting.deliveryCashInAmended(saved);
        }
        return toDto(saved);
    }

    /**
     * Cancelling is whatever the permission matrix says it is.
     *
     * <p>This used to be {@code @PreAuthorize("hasAnyRole('ADMIN','MANAGER')")}
     * on the endpoint, which was wrong twice over. {@code MANAGER} is granted to
     * nobody — no code path ever issues that authority — and {@code ADMIN} is
     * issued only to Super Admin. So an Accounts or Administrator (Branch) user
     * holding Delete on Collection saw the button, because the screen reads the
     * matrix, and then got 403, because the server did not.
     *
     * <p>Checked here rather than on the endpoint so it holds for every route
     * into a cancellation, including any added later.
     */
    @Override
    @Transactional
    public void cancel(Long pidCollection, String reason) {
        if (!permissions.has(MODULE, PermissionGuard.Right.DELETE)) {
            throw new BusinessException(
                    "You do not have permission to cancel a collection. "
                    + "Ask somebody whose role holds Delete on Collection.",
                    HttpStatus.FORBIDDEN, "COLLECTION_CANCEL_NOT_PERMITTED");
        }
        PosDeliveryCollection col = getActive(pidCollection);
        col.setStatus("CANCELLED");
        col.setRemarks(reason);
        collectionRepo.save(col);
        accounting.deliveryCashInCancelled(col, reason);
    }

    @Override
    public CollectionResponse.CollectionDto getById(Long pidCollection) {
        return toDto(collectionRepo.findById(pidCollection)
                .orElseThrow(() -> new ResourceNotFoundException("Collection", pidCollection)));
    }

    @Override
    public List<CollectionResponse.CollectionDto> getByTransaction(Long transactionId) {
        return collectionRepo.findByPidTransactionOrderByCollectionDateDesc(transactionId)
                .stream().map(this::toDto).collect(Collectors.toList());
    }

    @Override
    public CollectionResponse.CollectionSummaryDto getSummary(Long transactionId) {
        PosTransactionMst mst = tranMstRepo.findById(transactionId)
                .orElseThrow(() -> new ResourceNotFoundException("Transaction", transactionId));

        BigDecimal collected = collectionRepo.sumCollectedByTransaction(transactionId);
        if (collected == null) collected = BigDecimal.ZERO;
        BigDecimal outstanding = mst.getGrandTotal().subtract(collected);

        return CollectionResponse.CollectionSummaryDto.builder()
                .pidTransaction(transactionId)
                .invoiceNo(mst.getInvoiceNo())
                .invoiceAmount(mst.getGrandTotal())
                .totalCollected(collected)
                .outstanding(outstanding)
                .fullyCollected(outstanding.compareTo(BigDecimal.ZERO) <= 0)
                .build();
    }

    @Override
    public Page<CollectionResponse.CollectionDto> listByBranch(Long orgId, Long branchId,
            LocalDate from, LocalDate to, Pageable pageable) {
        return collectionRepo
                .findByPidOrgAndPidBranchAndCollectionDateBetween(orgId, branchId, from, to, pageable)
                .map(this::toDto);
    }

    @Override
    public Page<CollectionResponse.CollectionDto> listByDeliveryMan(Long deliveryManId,
            LocalDate from, LocalDate to, Pageable pageable) {
        return collectionRepo
                .findByPidDeliveryManAndCollectionDateBetween(deliveryManId, from, to, pageable)
                .map(this::toDto);
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private PosDeliveryCollection getActive(Long id) {
        PosDeliveryCollection col = collectionRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Collection", id));
        if ("CANCELLED".equals(col.getStatus())) {
            throw new BusinessException("Collection " + id + " is already cancelled");
        }
        return col;
    }

    private CollectionResponse.CollectionDto toDto(PosDeliveryCollection c) {
        return CollectionResponse.CollectionDto.builder()
                .pidCollection(c.getPidCollection())
                .pidOrg(c.getPidOrg())
                .pidBranch(c.getPidBranch())
                .pidTransaction(c.getPidTransaction())
                .invoiceNo(c.getInvoiceNo())
                .pidDeliveryMan(c.getPidDeliveryMan())
                .deliveryManName(c.getDeliveryManName())
                .pidCustomer(c.getPidCustomer())
                .customerName(c.getCustomerName())
                .collectionDate(c.getCollectionDate())
                .invoiceAmount(c.getInvoiceAmount())
                .collectedAmount(c.getCollectedAmount())
                .balanceAmount(c.getBalanceAmount())
                .paymentMode(c.getPaymentMode())
                .chequeNo(c.getChequeNo())
                .bankName(c.getBankName())
                .transactionRef(c.getTransactionRef())
                .remarks(c.getRemarks())
                .status(c.getStatus())
                .createdAt(c.getCreatedAt())
                .build();
    }
}
