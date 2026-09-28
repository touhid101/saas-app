package com.friendspharma.app.service.impl;

import com.friendspharma.app.entity.EmployeeType;
import com.friendspharma.app.entity.HrEmployee;
import com.friendspharma.app.exception.custom.ResourceNotFoundException;
import com.friendspharma.app.exception.custom.ValidationException;
import com.friendspharma.app.repository.HrEmployeeRepository;
import com.friendspharma.app.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single rule deciding who may be handed an invoice to deliver.
 *
 * <p>Employee CRUD lives in {@code MasterDataServiceImpl}; this exists only
 * because the rule has to hold at four separate entry points — creating a sale
 * with a delivery man, editing one, assigning one, and bulk-assigning — and a
 * check copied four times is a check that will eventually differ in one of them.
 *
 * <p>Before {@code EMP_TYPE} existed there was no rule to apply: the delivery-man
 * list was every active employee, and the assignment path did
 * {@code findById(...).ifPresent(...)}, so an id that matched nobody was written
 * to the invoice anyway and the name silently kept its previous value.
 */
@Service
@RequiredArgsConstructor
public class DeliveryManService {

    private final HrEmployeeRepository repo;

    /**
     * Resolves an employee id to a delivery man, or explains why it is not one.
     *
     * <p>Three distinct failures, each with its own message, because "could not
     * assign" tells whoever is dispatching nothing about what to do next:
     *
     * <ul>
     *   <li>no such employee — typically a stale id from a cached picker;
     *   <li>not a delivery man — the case that was silently allowed before;
     *   <li>inactive — someone who has left.
     * </ul>
     */
    @Transactional(readOnly = true)
    public HrEmployee requireDeliveryMan(Long employeeId) {
        HrEmployee e = repo.findById(employeeId)
                .orElseThrow(() -> new ResourceNotFoundException("Delivery man", employeeId));

        Long orgId = SecurityUtils.getCurrentOrgId();
        if (orgId != null && e.getPidOrg() != null && !orgId.equals(e.getPidOrg())) {
            // Not found rather than forbidden: whether another organisation
            // employs this id is not this caller's business.
            throw new ResourceNotFoundException("Delivery man", employeeId);
        }

        if (!e.isDeliveryMan()) {
            throw new ValidationException(
                    e.getEmpName() + " is a " + EmployeeType.label(e.getEmpType())
                    + ", not a delivery man. Change their type in Master Data › Employees, "
                    + "or pick someone else.");
        }

        if (!e.isActive()) {
            throw new ValidationException(
                    e.getEmpName() + " is inactive and cannot be assigned a delivery.");
        }

        return e;
    }
}
