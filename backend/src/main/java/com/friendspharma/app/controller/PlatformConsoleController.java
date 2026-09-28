package com.friendspharma.app.controller;

import com.friendspharma.app.api.ApiResponse;
import com.friendspharma.app.service.impl.PlatformConsoleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * How the platform is doing: revenue, renewals, and who has stopped using it.
 *
 * <p>The one place in the application where looking across tenants is legitimate,
 * and it is guarded in one place rather than thirty. {@code ROLE01} only, and the
 * queries behind it are written for this purpose — no ordinary endpoint was
 * loosened to serve both.
 *
 * <p>What it returns is deliberately narrow: counts, totals and dates about
 * pharmacies. Not one invoice, customer or line of anybody's trade. Running a
 * platform requires knowing that a pharmacy has gone quiet; it does not require
 * knowing what they sold.
 */
@RestController
@RequestMapping("/api/admin/console")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ROLE01')")
@Tag(name = "Platform console", description = "Revenue, renewals and usage across every pharmacy — Super Admin only")
public class PlatformConsoleController {

    private final PlatformConsoleService svc;

    @GetMapping
    @Operation(summary = "Everything the console draws, in one call",
               description = "Assembled server-side rather than as eight endpoints, so the "
                           + "screen cannot render half-finished.")
    public ResponseEntity<ApiResponse<PlatformConsoleService.Console>> load() {
        return ResponseEntity.ok(ApiResponse.ok(svc.load()));
    }
}
