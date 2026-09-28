package com.friendspharma.app.controller;

import com.friendspharma.app.api.ApiResponse;
import com.friendspharma.app.service.impl.GlobalSearchServiceImpl;
import com.friendspharma.app.service.impl.PermissionGuard;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * The topbar search box.
 *
 * <p>No role is named here. The gate is the View right on <b>Global Search</b>
 * in RBAC, so who gets a search box changes by a tick rather than an edit.
 * Administrator (Branch) holds it from V55; every other role is one tick away.
 *
 * <p>Results are scoped to the caller's own branch and to the modules they may
 * already view — see {@link GlobalSearchServiceImpl}.
 */
@RestController
@RequestMapping("/api/v1/search")
@RequiredArgsConstructor
@Tag(name = "Global Search",
     description = "Products, customers, suppliers, invoices and requisitions in your own branch")
public class GlobalSearchController {

    private static final String MODULE = "MOD29";

    private final GlobalSearchServiceImpl searchService;
    private final PermissionGuard permissions;

    /**
     * Search this branch.
     *
     * <p>{@code permitted} is sent beside the results for the same reason the
     * bell sends it: an empty list means either "no search box for this role" or
     * "nothing matched", and the screen has to draw those differently.
     *
     * <p>The {@code orgId} parameter the old signature accepted is gone. It was
     * overwritten from the session on the first line of the method, so it did
     * nothing but suggest to a caller that it might.
     */
    @GetMapping
    @Operation(summary = "Search across records in your branch",
               description = "Up to 5 hits per kind, minimum 2 characters. permitted reflects the "
                           + "View right on Global Search in RBAC. Each kind of record is further "
                           + "gated by the View right on the module that owns it, so this reaches "
                           + "nothing the caller could not already open.")
    public ApiResponse<Map<String, Object>> search(@RequestParam String q) {
        boolean permitted = permissions.has(MODULE, PermissionGuard.Right.VIEW);
        List<Map<String, Object>> items = permitted ? searchService.search(q) : List.of();
        return ApiResponse.success(Map.of(
                "permitted", permitted,
                "items", items));
    }
}
