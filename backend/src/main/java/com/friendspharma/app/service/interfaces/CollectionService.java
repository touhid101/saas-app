package com.friendspharma.app.service.interfaces;

import com.friendspharma.app.dto.request.CollectionRequest;
import com.friendspharma.app.dto.response.CollectionResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.List;

public interface CollectionService {

    CollectionResponse.CollectionDto record(CollectionRequest.RecordCollectionRequest req);

    CollectionResponse.CollectionDto update(Long pidCollection,
                                             CollectionRequest.UpdateCollectionRequest req);

    void cancel(Long pidCollection, String reason);

    CollectionResponse.CollectionDto getById(Long pidCollection);

    /** All collections against a single invoice */
    List<CollectionResponse.CollectionDto> getByTransaction(Long transactionId);

    /** Summary: total collected vs outstanding for an invoice */
    CollectionResponse.CollectionSummaryDto getSummary(Long transactionId);

    /** Paginated list for a branch within a date range */
    Page<CollectionResponse.CollectionDto> listByBranch(Long orgId, Long branchId,
                                                         LocalDate from, LocalDate to,
                                                         Pageable pageable);

    /** Collections by delivery man */
    Page<CollectionResponse.CollectionDto> listByDeliveryMan(Long deliveryManId,
                                                              LocalDate from, LocalDate to,
                                                              Pageable pageable);
}
