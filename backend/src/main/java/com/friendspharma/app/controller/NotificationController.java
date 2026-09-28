package com.friendspharma.app.controller;

import com.friendspharma.app.api.ApiResponse;
import com.friendspharma.app.dto.response.NotificationResponse;
import com.friendspharma.app.service.impl.NotificationService;
import com.friendspharma.app.service.impl.PermissionGuard;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * The topbar alert bell.
 *
 * <p>No role is named here, on purpose. The gate is the View right on
 * <b>Notifications</b> in the RBAC screen, so who gets a bell is changed by
 * ticking a box rather than by editing this file. Administrator (Branch) holds
 * it from V54; every other role is one tick away.
 */
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
@Tag(name = "Notifications", description = "Operational alerts for the signed-in user's branch")
public class NotificationController {

    private static final String MODULE = "MOD28";

    private final NotificationService svc;
    private final PermissionGuard permissions;

    /**
     * Alerts for this user, in this branch.
     *
     * <p>{@code permitted} is sent separately from the list because an empty
     * list means two different things — the role has no bell, or it has one and
     * there is nothing wrong today — and the topbar draws them differently. A
     * user entitled to alerts with none outstanding should see an empty bell
     * saying so, not no bell at all.
     *
     * <p>Answered 200 either way rather than 403 for the unentitled: a refusal
     * would be logged as an error on every page load for every role that simply
     * does not have the feature, burying the refusals that matter.
     */
    @GetMapping
    @Operation(summary = "Alerts for the signed-in user",
               description = "permitted reflects the View right on Notifications in RBAC; items "
                           + "is empty without it. Each alert is additionally gated by the View "
                           + "right on the module it links to, so this can never show a figure "
                           + "the caller could not already look up. Alerts counting zero are "
                           + "not returned.")
    public ResponseEntity<ApiResponse<Map<String, Object>>> list() {
        boolean permitted = permissions.has(MODULE, PermissionGuard.Right.VIEW);
        List<NotificationResponse> items = permitted ? svc.forCurrentUser() : List.of();
        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "permitted", permitted,
                "items", items)));
    }
}
