package com.friendspharma.app.controller;

import com.friendspharma.app.api.ApiResponse;
import com.friendspharma.app.service.impl.GlobalMasterService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * The catalogue's master lists — Super Admin only.
 *
 * <p>These describe the catalogue, not a pharmacy. Nothing here carries a branch,
 * and no pharmacy reads it: importing a catalogue entry creates that pharmacy's
 * own copies. Editing a name here changes the catalogue and nobody's shelf.
 */
@RestController
@RequestMapping("/api/admin/global-masters")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ROLE01')")
@Tag(name = "Global catalogue masters",
     description = "Manufacturers, generics, categories, sub-categories, units and packaging")
public class GlobalMasterController {

    private final GlobalMasterService svc;

    /**
     * Every list at once.
     *
     * <p>The catalogue form needs all six before it can render, and six round
     * trips is six chances for it to appear half-populated.
     */
    @GetMapping
    @Operation(summary = "All catalogue master lists")
    public ResponseEntity<ApiResponse<Map<String, Object>>> all() {
        return ResponseEntity.ok(ApiResponse.ok(svc.all()));
    }

    @GetMapping("/{list}")
    @Operation(summary = "One list")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> one(@PathVariable String list) {
        return ResponseEntity.ok(ApiResponse.ok(switch (list) {
            case "companies"     -> svc.companies();
            case "generics"      -> svc.generics();
            case "categories"    -> svc.categories();
            case "subCategories" -> svc.subCategories();
            case "units"         -> svc.units();
            case "leafs"         -> svc.leafs();
            case "boxes"         -> svc.boxes();
            default -> throw new com.friendspharma.app.exception.custom.ValidationException(
                    "There is no such list.");
        }));
    }

    @PostMapping("/companies")
    @Operation(summary = "Add a manufacturer")
    public ResponseEntity<ApiResponse<Map<String, Object>>> addCompany(@RequestBody Map<String, Object> b) {
        return ResponseEntity.ok(ApiResponse.ok("Manufacturer added.", svc.saveCompany(null, str(b.get("name")))));
    }

    @PutMapping("/companies/{id}")
    @Operation(summary = "Rename a manufacturer",
               description = "Changes the catalogue. Pharmacies keep the copies they took.")
    public ResponseEntity<ApiResponse<Map<String, Object>>> editCompany(
            @PathVariable Long id, @RequestBody Map<String, Object> b) {
        return ResponseEntity.ok(ApiResponse.ok("Manufacturer updated.", svc.saveCompany(id, str(b.get("name")))));
    }

    @PostMapping("/generics")
    @Operation(summary = "Add a generic")
    public ResponseEntity<ApiResponse<Map<String, Object>>> addGeneric(@RequestBody Map<String, Object> b) {
        return ResponseEntity.ok(ApiResponse.ok("Generic added.",
                svc.saveGeneric(null, str(b.get("name")), str(b.get("description")))));
    }

    @PutMapping("/generics/{id}")
    @Operation(summary = "Edit a generic")
    public ResponseEntity<ApiResponse<Map<String, Object>>> editGeneric(
            @PathVariable Long id, @RequestBody Map<String, Object> b) {
        return ResponseEntity.ok(ApiResponse.ok("Generic updated.",
                svc.saveGeneric(id, str(b.get("name")), str(b.get("description")))));
    }

    @PostMapping("/categories")
    @Operation(summary = "Add a category")
    public ResponseEntity<ApiResponse<Map<String, Object>>> addCategory(@RequestBody Map<String, Object> b) {
        return ResponseEntity.ok(ApiResponse.ok("Category added.", svc.saveCategory(null, str(b.get("name")))));
    }

    @PutMapping("/categories/{id}")
    @Operation(summary = "Rename a category")
    public ResponseEntity<ApiResponse<Map<String, Object>>> editCategory(
            @PathVariable Long id, @RequestBody Map<String, Object> b) {
        return ResponseEntity.ok(ApiResponse.ok("Category updated.", svc.saveCategory(id, str(b.get("name")))));
    }

    @PostMapping("/sub-categories")
    @Operation(summary = "Add a sub-category")
    public ResponseEntity<ApiResponse<Map<String, Object>>> addSubCategory(@RequestBody Map<String, Object> b) {
        return ResponseEntity.ok(ApiResponse.ok("Sub-category added.",
                svc.saveSubCategory(null, lng(b.get("categoryId")), str(b.get("name")))));
    }

    @PutMapping("/sub-categories/{id}")
    @Operation(summary = "Edit a sub-category")
    public ResponseEntity<ApiResponse<Map<String, Object>>> editSubCategory(
            @PathVariable Long id, @RequestBody Map<String, Object> b) {
        return ResponseEntity.ok(ApiResponse.ok("Sub-category updated.",
                svc.saveSubCategory(id, lng(b.get("categoryId")), str(b.get("name")))));
    }

    @PostMapping("/units")
    @Operation(summary = "Add a unit")
    public ResponseEntity<ApiResponse<Map<String, Object>>> addUnit(@RequestBody Map<String, Object> b) {
        return ResponseEntity.ok(ApiResponse.ok("Unit added.", svc.saveUnit(null, str(b.get("name")))));
    }

    @PutMapping("/units/{id}")
    @Operation(summary = "Rename a unit")
    public ResponseEntity<ApiResponse<Map<String, Object>>> editUnit(
            @PathVariable Long id, @RequestBody Map<String, Object> b) {
        return ResponseEntity.ok(ApiResponse.ok("Unit updated.", svc.saveUnit(id, str(b.get("name")))));
    }

    @PostMapping("/leafs")
    @Operation(summary = "Add a leaf/strip")
    public ResponseEntity<ApiResponse<Map<String, Object>>> addLeaf(@RequestBody Map<String, Object> b) {
        return ResponseEntity.ok(ApiResponse.ok("Leaf/strip added.",
                svc.saveLeaf(null, str(b.get("name")), intOf(b.get("noOfPcs")))));
    }

    @PutMapping("/leafs/{id}")
    @Operation(summary = "Edit a leaf/strip")
    public ResponseEntity<ApiResponse<Map<String, Object>>> editLeaf(
            @PathVariable Long id, @RequestBody Map<String, Object> b) {
        return ResponseEntity.ok(ApiResponse.ok("Leaf/strip updated.",
                svc.saveLeaf(id, str(b.get("name")), intOf(b.get("noOfPcs")))));
    }

    @PostMapping("/boxes")
    @Operation(summary = "Add a box")
    public ResponseEntity<ApiResponse<Map<String, Object>>> addBox(@RequestBody Map<String, Object> b) {
        return ResponseEntity.ok(ApiResponse.ok("Box added.",
                svc.saveBox(null, str(b.get("name")), lng(b.get("leafId")), intOf(b.get("noOfLeaf")))));
    }

    @PutMapping("/boxes/{id}")
    @Operation(summary = "Edit a box")
    public ResponseEntity<ApiResponse<Map<String, Object>>> editBox(
            @PathVariable Long id, @RequestBody Map<String, Object> b) {
        return ResponseEntity.ok(ApiResponse.ok("Box updated.",
                svc.saveBox(id, str(b.get("name")), lng(b.get("leafId")), intOf(b.get("noOfLeaf")))));
    }

    @DeleteMapping("/{list}/{id}")
    @Operation(summary = "Retire a list entry",
               description = "Refused while catalogue entries still use it.")
    public ResponseEntity<ApiResponse<Void>> retire(@PathVariable String list, @PathVariable Long id) {
        svc.retire(list, id);
        return ResponseEntity.ok(ApiResponse.noContent("Retired from the list."));
    }

    /* ── reading a loose JSON body ──
       Blank is treated as absent: an empty text box means "nothing here", and
       storing "" as a name produces a list entry nobody can see or select. */
    private static String str(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }

    private static Long lng(Object o) {
        String s = str(o);
        if (s == null) return null;
        try { return Long.valueOf(s); }
        catch (NumberFormatException e) { return null; }
    }

    private static Integer intOf(Object o) {
        String s = str(o);
        if (s == null) return null;
        try { return Integer.valueOf(s); }
        catch (NumberFormatException e) { return null; }
    }
}
