package com.friendspharma.app.controller;

import com.friendspharma.app.api.ApiResponse;
import com.friendspharma.app.dto.response.DashboardResponse;
import com.friendspharma.app.service.impl.DashboardServiceImpl;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * The dashboard.
 *
 * <p>One call, one round trip. The screen shows a dozen figures that all come
 * from the same few aggregates, and splitting them across endpoints would mean
 * scanning the ledger a dozen times to draw one page.
 *
 * <p>The response carries two halves and a flag saying whether the second one
 * was built. A user who may not see money gets {@code finance: null} — the
 * figures are not fetched, not merely hidden, so opening the network tab shows
 * nothing that the screen does not.
 */
@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
@Tag(name = "Dashboard",
     description = "Operational board for everyone; financial analytics for those entitled to it")
public class DashboardController {

    private final DashboardServiceImpl svc;

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "The dashboard for the signed-in user",
               description = "Always the signed-in user's own branch. There is no parameter for "
                           + "another branch and none for every branch at once. The finance half "
                           + "is present only for a user entitled to it.")
    public ResponseEntity<ApiResponse<DashboardResponse.Dashboard>> dashboard(
            @RequestParam(defaultValue = "30") int days) {

        // Bounded so a hand-edited query string cannot ask for ten years of
        // daily rows and get them.
        int window = Math.max(7, Math.min(days, 365));
        return ResponseEntity.ok(ApiResponse.ok(svc.load(window)));
    }
}
