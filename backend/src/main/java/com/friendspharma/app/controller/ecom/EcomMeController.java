package com.friendspharma.app.controller.ecom;

import com.friendspharma.app.api.ApiResponse;
import com.friendspharma.app.dto.request.EcomCustomerRequests;
import com.friendspharma.app.dto.response.EcomCustomerResponses;
import com.friendspharma.app.service.impl.ecom.EcomCustomerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** The signed-in customer's own account. No id in any path: you are only ever yourself. */
@RestController
@RequestMapping("/api/ecom/me")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ECOM_CUSTOMER')")
@Tag(name = "E-Commerce · My account", description = "Profile and address book of the signed-in customer")
public class EcomMeController {

    private final EcomCustomerService customers;
    /** The customer's own points statement. [Phase 13] */
    private final com.friendspharma.app.service.impl.ecom.EcomCustomerPointsService myPoints;

    @Operation(summary = "My profile, tier and approval status")
    @GetMapping
    public ResponseEntity<ApiResponse<EcomCustomerResponses.Profile>> me() {
        return ResponseEntity.ok(ApiResponse.ok(customers.me()));
    }

    @Operation(summary = "Update my profile (name, e-mail, business details, registration location)")
    @PutMapping
    public ResponseEntity<ApiResponse<EcomCustomerResponses.Profile>> update(
            @Valid @RequestBody EcomCustomerRequests.UpdateProfile req) {
        return ResponseEntity.ok(ApiResponse.ok("Profile updated", customers.updateMe(req)));
    }

    @Operation(summary = "My delivery addresses, default first")
    @GetMapping("/addresses")
    public ResponseEntity<ApiResponse<List<EcomCustomerResponses.Address>>> addresses() {
        return ResponseEntity.ok(ApiResponse.ok(customers.addresses()));
    }

    @Operation(summary = "Add a delivery address (the first one becomes the default)")
    @PostMapping("/addresses")
    public ResponseEntity<ApiResponse<EcomCustomerResponses.Address>> addAddress(
            @Valid @RequestBody EcomCustomerRequests.Address req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Address added", customers.addAddress(req)));
    }

    @Operation(summary = "Change a delivery address")
    @PutMapping("/addresses/{id}")
    public ResponseEntity<ApiResponse<EcomCustomerResponses.Address>> updateAddress(
            @PathVariable Long id, @Valid @RequestBody EcomCustomerRequests.Address req) {
        return ResponseEntity.ok(ApiResponse.ok("Address updated", customers.updateAddress(id, req)));
    }

    @Operation(summary = "Make an address the default")
    @PostMapping("/addresses/{id}/default")
    public ResponseEntity<ApiResponse<EcomCustomerResponses.Address>> makeDefault(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok("Default address set", customers.makeDefault(id)));
    }

    @Operation(summary = "Remove a delivery address")
    @DeleteMapping("/addresses/{id}")
    public ResponseEntity<ApiResponse<Void>> removeAddress(@PathVariable Long id) {
        customers.removeAddress(id);
        return ResponseEntity.ok(ApiResponse.noContent("Address removed"));
    }
    @Operation(summary = "My loyalty points: the balance and how it got there")
    @GetMapping("/points")
    public ResponseEntity<ApiResponse<com.friendspharma.app.dto.response.EcomPointsResponses.Statement>> points(
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.ok(myPoints.statement(size)));
    }
}
