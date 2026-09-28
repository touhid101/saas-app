package com.friendspharma.app.service.interfaces;

import com.friendspharma.app.dto.request.RequisitionRequest;
import com.friendspharma.app.dto.response.RequisitionResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.List;

public interface RequisitionService {

    RequisitionResponse.RequisitionDto create(RequisitionRequest.CreateRequisitionRequest req, Long userId);

    RequisitionResponse.RequisitionDto update(Long pidRequisition,
                                               RequisitionRequest.UpdateRequisitionRequest req);

    /** Update a single line qty from the approval modal */
    RequisitionResponse.RequisitionDto updateLineQty(Long pidRequisition, Long pidReqDtl,
                                                      RequisitionRequest.UpdateLineQtyRequest req);

    /**
     * Takes a line off a requisition that has not been approved yet.
     *
     * <p>Same stages as a quantity change: both decide what the requisition
     * asks for, and after approval that is settled. Refuses the last line —
     * cancel the requisition rather than empty it.
     */
    RequisitionResponse.RequisitionDto removeLine(Long pidRequisition, Long pidReqDtl);

    /** DRAFT → SUBMITTED */
    RequisitionResponse.RequisitionDto submit(Long pidRequisition);

    /** SUBMITTED → PENDING_APPROVAL (manager review) */
    RequisitionResponse.RequisitionDto pendingApproval(Long pidRequisition);

    /** PENDING_APPROVAL → APPROVED */
    RequisitionResponse.RequisitionDto approve(Long pidRequisition,
                                                RequisitionRequest.ApproveRequest req,
                                                Long approverId);

    /** → REJECTED */
    RequisitionResponse.RequisitionDto reject(Long pidRequisition,
                                               RequisitionRequest.RejectRequest req,
                                               Long approverId);

    /** APPROVED → CONVERTED (linked to a purchase order) */
    RequisitionResponse.RequisitionDto convert(Long pidRequisition, Long pidPurchaseOrder);

    void cancel(Long pidRequisition, String reason);

    RequisitionResponse.RequisitionDto getById(Long pidRequisition);

    Page<RequisitionResponse.RequisitionSummaryDto> list(Long orgId, Long branchId, String stage,
                                                          LocalDate from, LocalDate to,
                                                          Pageable pageable);

    /** Create an AI-sourced requisition draft (called by AiService) */
    RequisitionResponse.RequisitionDto createFromAi(
            RequisitionRequest.CreateRequisitionRequest req, Long userId);

    /** Count pending-approval requisitions — used by dashboard / notifications */
    /** Approvals waiting in one branch; a null branch means every branch. */
    long countPendingApproval(Long branchId);
}
