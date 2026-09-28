package com.friendspharma.app.controller.ecom;

import com.friendspharma.app.api.ApiResponse;
import com.friendspharma.app.dto.response.EcomTrackingResponses;
import com.friendspharma.app.service.impl.ecom.EcomDeliveryTrackingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;

/**
 * The driver's end of a delivery (MOD53 / docs/ecommerce Phase 10).
 *
 * <p>Staff JWT, under the {@code /api/ecom/admin/} prefix like the rest of the
 * staff side. Every rule that matters — only the assigned driver, only while the
 * order is out — is enforced in the service, because a rule written in a
 * controller is a rule the next entry point does not have.
 */
@RestController
@RequestMapping("/api/ecom/admin/delivery")
@RequiredArgsConstructor
@Tag(name = "E-Commerce · Delivery", description = "Where the order is, and what happened at the door")
public class EcomDeliveryTrackingController {

    private final EcomDeliveryTrackingService delivery;

    @Data
    public static class Position {
        private BigDecimal latitude;
        private BigDecimal longitude;
        /** What the device said about the reading, in metres. */
        private BigDecimal accuracyM;
    }

    @Operation(summary = "Report where an order is now",
               description = "Accepted only from the driver it is assigned to, and only while it is ON_THE_WAY.")
    @PostMapping("/{orderNo}/position")
    public ResponseEntity<ApiResponse<EcomTrackingResponses.Ping>> ping(
            @PathVariable String orderNo, @RequestBody Position req) {
        return ResponseEntity.ok(ApiResponse.ok(
                delivery.ping(orderNo, req.getLatitude(), req.getLongitude(), req.getAccuracyM())));
    }

    @Operation(summary = "Record what happened at the door",
               description = "A photograph, a name, or a note — one of the three. Recording it again replaces it.")
    @PostMapping(value = "/{orderNo}/proof", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<EcomTrackingResponses.Proof>> proof(
            @PathVariable String orderNo,
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) String receivedByName,
            @RequestParam(required = false) String note,
            @RequestParam(required = false) BigDecimal latitude,
            @RequestParam(required = false) BigDecimal longitude,
            @RequestPart(required = false) MultipartFile photo) {
        return ResponseEntity.ok(ApiResponse.ok("Proof of delivery recorded",
                delivery.recordProof(orderNo, kind, receivedByName, note, latitude, longitude, photo)));
    }
}
