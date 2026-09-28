package com.friendspharma.app.service.impl.ecom;

import com.friendspharma.app.dto.request.EcomCustomerRequests;
import com.friendspharma.app.dto.response.EcomCustomerResponses;
import com.friendspharma.app.entity.EcomCustomer;
import com.friendspharma.app.entity.EcomCustomerAddress;
import com.friendspharma.app.entity.EcomCustomerAuth;
import com.friendspharma.app.exception.custom.BusinessException;
import com.friendspharma.app.exception.custom.ResourceNotFoundException;
import com.friendspharma.app.repository.EcomCustomerAddressRepository;
import com.friendspharma.app.repository.EcomCustomerAuthRepository;
import com.friendspharma.app.repository.EcomCustomerRepository;
import com.friendspharma.app.security.ecom.EcomCustomerPrincipal;
import com.friendspharma.app.security.ecom.EcomSecurity;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * The signed-in customer's own account: profile and address book.
 *
 * <p>Every method starts from {@link EcomSecurity#require()} and never takes a
 * customer id from the request — a customer can only ever read and change
 * themselves. Ownership of an address is part of the lookup key
 * ({@code findByPidAddressAndPidEcomCustomerAndStatus}), so somebody else's
 * address id is a 404, not a 403.
 */
@Service
@RequiredArgsConstructor
public class EcomCustomerService {

    private final EcomCustomerRepository customers;
    private final EcomCustomerAuthRepository auths;
    private final EcomCustomerAddressRepository addresses;
    private final EcomCustomerBridgeService bridge;
    private final EcomAuditRecorder audit;

    @Transactional(readOnly = true)
    public EcomCustomerResponses.Profile me() {
        EcomCustomerPrincipal p = EcomSecurity.require();
        return profileOf(load(p.customerId()));
    }

    @Transactional
    public EcomCustomerResponses.Profile updateMe(EcomCustomerRequests.UpdateProfile req) {
        EcomCustomerPrincipal p = EcomSecurity.require();
        EcomCustomer c = load(p.customerId());
        String email = EcomCustomerAuthService.blankToNull(req.getEmail());
        if (email != null && customers.existsByEmailIgnoreCaseAndIdNot(email, c.getPidEcomCustomer())) {
            throw new BusinessException("Another account already uses this e-mail address.",
                    HttpStatus.CONFLICT, "ECOM_EMAIL_TAKEN");
        }
        c.setFullName(req.getFullName().trim());
        c.setEmail(email);
        c.setBusinessName(EcomCustomerAuthService.blankToNull(req.getBusinessName()));
        c.setTradeLicenseNo(EcomCustomerAuthService.blankToNull(req.getTradeLicenseNo()));
        c.setDrugLicenseNo(EcomCustomerAuthService.blankToNull(req.getDrugLicenseNo()));
        c.setRegAddress(EcomCustomerAuthService.blankToNull(req.getRegAddress()));
        c.setRegArea(EcomCustomerAuthService.blankToNull(req.getRegArea()));
        c.setRegCity(EcomCustomerAuthService.blankToNull(req.getRegCity()));
        c.setRegLatitude(req.getRegLatitude());
        c.setRegLongitude(req.getRegLongitude());
        customers.save(c);
        bridge.propagateProfile(c);
        audit.record(EcomAuditRecorder.PROFILE_UPDATED, c.getPidEcomCustomer(), null);
        return profileOf(c);
    }

    /* ── Addresses ────────────────────────────────────────────────────── */

    @Transactional(readOnly = true)
    public List<EcomCustomerResponses.Address> addresses() {
        EcomCustomerPrincipal p = EcomSecurity.require();
        return addresses.findByPidEcomCustomerAndStatusOrderByIsDefaultDescPidAddressAsc(p.customerId(), "Y")
                .stream().map(EcomCustomerResponses.Address::of).toList();
    }

    @Transactional
    public EcomCustomerResponses.Address addAddress(EcomCustomerRequests.Address req) {
        EcomCustomerPrincipal p = EcomSecurity.require();
        boolean first = addresses.countByPidEcomCustomerAndStatus(p.customerId(), "Y") == 0;
        boolean makeDefault = first || Boolean.TRUE.equals(req.getIsDefault());
        if (makeDefault) addresses.clearDefault(p.customerId());
        EcomCustomerAddress a = EcomCustomerAddress.builder().pidEcomCustomer(p.customerId()).build();
        apply(a, req);
        a.setIsDefault(makeDefault ? "Y" : "N");
        a.setStatus("Y");
        a = addresses.save(a);
        audit.record(EcomAuditRecorder.ADDRESS_ADDED, p.customerId(), null, "ADDRESS", a.getPidAddress(), a.getLine1());
        return EcomCustomerResponses.Address.of(a);
    }

    @Transactional
    public EcomCustomerResponses.Address updateAddress(Long addressId, EcomCustomerRequests.Address req) {
        EcomCustomerPrincipal p = EcomSecurity.require();
        EcomCustomerAddress a = own(addressId, p.customerId());
        if (Boolean.TRUE.equals(req.getIsDefault()) && !"Y".equals(a.getIsDefault())) {
            addresses.clearDefault(p.customerId());
            a.setIsDefault("Y");
        }
        apply(a, req);
        a = addresses.save(a);
        audit.record(EcomAuditRecorder.ADDRESS_UPDATED, p.customerId(), null, "ADDRESS", a.getPidAddress(), a.getLine1());
        return EcomCustomerResponses.Address.of(a);
    }

    @Transactional
    public void removeAddress(Long addressId) {
        EcomCustomerPrincipal p = EcomSecurity.require();
        EcomCustomerAddress a = own(addressId, p.customerId());
        // Retired, not deleted: an order placed to it (Phase 6) will still point at a row.
        a.setStatus("N");
        a.setIsDefault("N");
        addresses.save(a);
        addresses.findByPidEcomCustomerAndStatusOrderByIsDefaultDescPidAddressAsc(p.customerId(), "Y")
                .stream().findFirst().ifPresent(next -> {
                    if (!"Y".equals(next.getIsDefault())) { next.setIsDefault("Y"); addresses.save(next); }
                });
        audit.record(EcomAuditRecorder.ADDRESS_REMOVED, p.customerId(), null, "ADDRESS", addressId, a.getLine1());
    }

    @Transactional
    public EcomCustomerResponses.Address makeDefault(Long addressId) {
        EcomCustomerPrincipal p = EcomSecurity.require();
        EcomCustomerAddress a = own(addressId, p.customerId());
        addresses.clearDefault(p.customerId());
        a.setIsDefault("Y");
        return EcomCustomerResponses.Address.of(addresses.save(a));
    }

    /* ── Helpers ──────────────────────────────────────────────────────── */

    EcomCustomer load(Long id) {
        return customers.findById(id).orElseThrow(() -> new ResourceNotFoundException("Customer", id));
    }

    EcomCustomerResponses.Profile profileOf(EcomCustomer c) {
        EcomCustomerAuth a = auths.findByPidEcomCustomer(c.getPidEcomCustomer()).orElse(null);
        return EcomCustomerResponses.Profile.of(c,
                a != null && "Y".equals(a.getPhoneVerified()),
                a != null && "Y".equals(a.getEmailVerified()));
    }

    private EcomCustomerAddress own(Long addressId, Long customerId) {
        return addresses.findByPidAddressAndPidEcomCustomerAndStatus(addressId, customerId, "Y")
                .orElseThrow(() -> new ResourceNotFoundException("Address", addressId));
    }

    private static void apply(EcomCustomerAddress a, EcomCustomerRequests.Address req) {
        a.setLabel(EcomCustomerAuthService.blankToNull(req.getLabel()));
        a.setRecipientName(EcomCustomerAuthService.blankToNull(req.getRecipientName()));
        a.setPhone(EcomCustomerAuthService.blankToNull(req.getPhone()));
        a.setLine1(req.getLine1().trim());
        a.setLine2(EcomCustomerAuthService.blankToNull(req.getLine2()));
        a.setArea(EcomCustomerAuthService.blankToNull(req.getArea()));
        a.setCity(EcomCustomerAuthService.blankToNull(req.getCity()));
        a.setPostcode(EcomCustomerAuthService.blankToNull(req.getPostcode()));
        a.setLatitude(req.getLatitude());
        a.setLongitude(req.getLongitude());
    }
}
