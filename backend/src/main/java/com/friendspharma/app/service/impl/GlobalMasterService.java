package com.friendspharma.app.service.impl;

import com.friendspharma.app.entity.*;
import com.friendspharma.app.exception.custom.ResourceNotFoundException;
import com.friendspharma.app.exception.custom.ValidationException;
import com.friendspharma.app.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The catalogue's master lists — manufacturers, generics, categories,
 * sub-categories, units and packaging.
 *
 * <p><b>These belong to the catalogue, not to a pharmacy.</b> They carry no
 * branch. A pharmacy never reads them: importing a catalogue entry creates that
 * pharmacy's own manufacturer, generic, category, sub-category, unit, leaf and box
 * rows, stamped with its branch and company. Renaming something here changes the
 * catalogue and nobody's shelf — the same separation that lets a Super Admin
 * correct a spelling without repricing three hundred pharmacies.
 *
 * <p><b>Why they exist at all.</b> These were free-typed strings on the catalogue
 * row. Nothing stopped "Square Pharmaceuticals", "Square Pharma" and a trailing
 * space being three manufacturers, and every pharmacy that imported them would
 * have inherited all three as separate rows. A list cannot be misspelt.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GlobalMasterService {

    private final GlobalCompanyRepository     companyRepo;
    private final GlobalGenericRepository     genericRepo;
    private final GlobalCategoryRepository    categoryRepo;
    private final GlobalSubCategoryRepository subCategoryRepo;
    private final GlobalUnitRepository        unitRepo;
    private final GlobalLeafRepository        leafRepo;
    private final GlobalBoxRepository         boxRepo;
    private final GlobalProductRepository     catalogueRepo;

    /* ══════════════════════════════════════════════════════════════════════
       READING — everything the catalogue screen needs for its dropdowns
       ══════════════════════════════════════════════════════════════════════ */

    /**
     * Every list in one call.
     *
     * <p>One request rather than six: the catalogue form needs all of them before
     * it can render, and six round trips to fill six dropdowns is six chances for
     * the form to appear half-populated.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> all() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("companies",     companies());
        m.put("generics",      generics());
        m.put("categories",    categories());
        m.put("subCategories", subCategories());
        m.put("units",         units());
        m.put("leafs",         leafs());
        m.put("boxes",         boxes());
        return m;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> companies() {
        return companyRepo.findByStatusOrderByCompanyName("Y").stream()
                .map(c -> row(c.getPidGlobalCompany(), c.getCompanyName())).toList();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> generics() {
        return genericRepo.findByStatusOrderByGenericName("Y").stream()
                .map(g -> {
                    Map<String, Object> m = row(g.getPidGlobalGeneric(), g.getGenericName());
                    m.put("description", g.getDescription());
                    return m;
                }).toList();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> categories() {
        return categoryRepo.findByStatusOrderByCategoryName("Y").stream()
                .map(c -> row(c.getPidGlobalCategory(), c.getCategoryName())).toList();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> subCategories() {
        return subCategoryRepo.findByStatusOrderBySubCategoryName("Y").stream()
                .map(s -> {
                    Map<String, Object> m = row(s.getPidGlobalSubCategory(), s.getSubCategoryName());
                    // The screen filters its sub-category list by the chosen
                    // category, so it needs the parent on every row.
                    m.put("categoryId", s.getPidGlobalCategory());
                    return m;
                }).toList();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> units() {
        return unitRepo.findByStatusOrderByUnitName("Y").stream()
                .map(u -> row(u.getPidGlobalUnit(), u.getUnitName())).toList();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> leafs() {
        return leafRepo.findByStatusOrderByLeafType("Y").stream()
                .map(l -> {
                    Map<String, Object> m = row(l.getPidGlobalLeaf(), l.getLeafType());
                    m.put("noOfPcs", l.getNoOfPcs());
                    return m;
                }).toList();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> boxes() {
        return boxRepo.findByStatusOrderByBoxSizeTitle("Y").stream()
                .map(b -> {
                    Map<String, Object> m = row(b.getPidGlobalBox(), b.getBoxSizeTitle());
                    // The screen shows which strip a box is built from, and the
                    // catalogue form needs it to settle both numbers.
                    m.put("leafId", b.getPidGlobalLeaf());
                    m.put("noOfLeaf", b.getNoOfLeaf());
                    return m;
                }).toList();
    }

    private static Map<String, Object> row(Long id, String name) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        return m;
    }

    /* ══════════════════════════════════════════════════════════════════════
       WRITING
       ══════════════════════════════════════════════════════════════════════ */

    @Transactional
    public Map<String, Object> saveCompany(Long id, String name) {
        String clean = require(name, "A manufacturer needs a name.");
        GlobalCompany e = id == null ? new GlobalCompany()
                : companyRepo.findById(id).orElseThrow(() -> new ResourceNotFoundException("Manufacturer", id));
        refuseDuplicate(companyRepo.findByCompanyNameIgnoreCase(clean)
                .map(GlobalCompany::getPidGlobalCompany).orElse(null), id, clean);
        e.setCompanyName(clean);
        e.setStatus("Y");
        Long savedId = companyRepo.save(e).getPidGlobalCompany();
        // The catalogue stores the name beside the id and the import reads the
        // name, so a rename has to reach the entries pointing here.
        catalogueRepo.renameCompany(savedId, clean);
        return row(savedId, clean);
    }

    @Transactional
    public Map<String, Object> saveGeneric(Long id, String name, String description) {
        String clean = require(name, "A generic needs a name.");
        GlobalGeneric e = id == null ? new GlobalGeneric()
                : genericRepo.findById(id).orElseThrow(() -> new ResourceNotFoundException("Generic", id));
        refuseDuplicate(genericRepo.findByGenericNameIgnoreCase(clean)
                .map(GlobalGeneric::getPidGlobalGeneric).orElse(null), id, clean);
        e.setGenericName(clean);
        e.setDescription(description);
        e.setStatus("Y");
        Long savedId = genericRepo.save(e).getPidGlobalGeneric();
        catalogueRepo.renameGeneric(savedId, clean);
        return row(savedId, clean);
    }

    @Transactional
    public Map<String, Object> saveCategory(Long id, String name) {
        String clean = require(name, "A category needs a name.");
        GlobalCategory e = id == null ? new GlobalCategory()
                : categoryRepo.findById(id).orElseThrow(() -> new ResourceNotFoundException("Category", id));
        refuseDuplicate(categoryRepo.findByCategoryNameIgnoreCase(clean)
                .map(GlobalCategory::getPidGlobalCategory).orElse(null), id, clean);
        e.setCategoryName(clean);
        e.setStatus("Y");
        Long savedId = categoryRepo.save(e).getPidGlobalCategory();
        catalogueRepo.renameCategory(savedId, clean);
        return row(savedId, clean);
    }

    @Transactional
    public Map<String, Object> saveSubCategory(Long id, Long categoryId, String name) {
        String clean = require(name, "A sub-category needs a name.");
        if (categoryId == null) throw new ValidationException("A sub-category needs a category.");
        if (!categoryRepo.existsById(categoryId)) {
            throw new ValidationException("That category is not in the list.");
        }
        GlobalSubCategory e = id == null ? new GlobalSubCategory()
                : subCategoryRepo.findById(id).orElseThrow(() -> new ResourceNotFoundException("Sub-category", id));
        e.setPidGlobalCategory(categoryId);
        e.setSubCategoryName(clean);
        e.setStatus("Y");
        Long savedId = subCategoryRepo.save(e).getPidGlobalSubCategory();
        catalogueRepo.renameSubCategory(savedId, clean);
        Map<String, Object> m = row(savedId, clean);
        m.put("categoryId", categoryId);
        return m;
    }

    @Transactional
    public Map<String, Object> saveUnit(Long id, String name) {
        String clean = require(name, "A unit needs a name.");
        GlobalUnit e = id == null ? new GlobalUnit()
                : unitRepo.findById(id).orElseThrow(() -> new ResourceNotFoundException("Unit", id));
        refuseDuplicate(unitRepo.findByUnitNameIgnoreCase(clean)
                .map(GlobalUnit::getPidGlobalUnit).orElse(null), id, clean);
        e.setUnitName(clean);
        e.setStatus("Y");
        Long savedId = unitRepo.save(e).getPidGlobalUnit();
        catalogueRepo.renameUnit(savedId, clean);
        return row(savedId, clean);
    }

    /**
     * Packaging is named for the person choosing it, not by them.
     *
     * <p>"10 x 10" is the two numbers, so letting somebody type a title invites a
     * row called "10 x 10" holding 12 and 8.
     */
    @Transactional
    public Map<String, Object> saveLeaf(Long id, String leafType, Integer noOfPcs) {
        String clean = require(leafType, "A leaf/strip needs a name.");
        if (noOfPcs == null || noOfPcs < 1) throw new ValidationException("Pieces per strip must be at least 1.");

        GlobalLeaf e = id == null ? new GlobalLeaf()
                : leafRepo.findById(id).orElseThrow(() -> new ResourceNotFoundException("Leaf/Strip", id));
        refuseDuplicate(leafRepo.findByLeafTypeIgnoreCase(clean)
                .map(GlobalLeaf::getPidGlobalLeaf).orElse(null), id, clean);
        e.setLeafType(clean);
        e.setNoOfPcs(noOfPcs);
        e.setStatus("Y");

        Long savedId = leafRepo.save(e).getPidGlobalLeaf();
        // Catalogue entries store the piece count beside the link, and the import
        // reads the count — so resizing a strip has to reach them.
        catalogueRepo.resizeLeaf(savedId, noOfPcs);
        Map<String, Object> m = row(savedId, clean);
        m.put("noOfPcs", noOfPcs);
        return m;
    }

    /**
     * A box, and the strip it is built from.
     *
     * <p>The strip is required: a box with no strip cannot say how many pieces it
     * holds, which is the one thing the import needs it for.
     */
    @Transactional
    public Map<String, Object> saveBox(Long id, String title, Long leafId, Integer noOfLeaf) {
        String clean = require(title, "A box needs a title.");
        if (leafId == null) throw new ValidationException("A box needs the leaf/strip it is built from.");
        if (noOfLeaf == null || noOfLeaf < 1) throw new ValidationException("Strips per box must be at least 1.");
        if (!leafRepo.existsById(leafId)) throw new ValidationException("That leaf/strip is not in the list.");

        GlobalBox e = id == null ? new GlobalBox()
                : boxRepo.findById(id).orElseThrow(() -> new ResourceNotFoundException("Box", id));
        refuseDuplicate(boxRepo.findByBoxSizeTitleIgnoreCase(clean)
                .map(GlobalBox::getPidGlobalBox).orElse(null), id, clean);
        e.setBoxSizeTitle(clean);
        e.setPidGlobalLeaf(leafId);
        e.setNoOfLeaf(noOfLeaf);
        e.setStatus("Y");

        Long savedId = boxRepo.save(e).getPidGlobalBox();
        catalogueRepo.resizeBox(savedId, noOfLeaf);
        Map<String, Object> m = row(savedId, clean);
        m.put("leafId", leafId);
        m.put("noOfLeaf", noOfLeaf);
        return m;
    }


    /* ══════════════════════════════════════════════════════════════════════
       RETIRING
       ══════════════════════════════════════════════════════════════════════ */

    /**
     * Retires a list entry.
     *
     * <p>Deactivated rather than deleted, and refused outright while catalogue
     * entries still point at it — the alternative is a catalogue row naming a
     * manufacturer that no longer exists, which is exactly the inconsistency
     * these lists were introduced to prevent.
     */
    @Transactional
    public void retire(String list, Long id) {
        long inUse = switch (list) {
            case "companies"     -> catalogueRepo.countByPidGlobalCompany(id);
            case "generics"      -> catalogueRepo.countByPidGlobalGeneric(id);
            case "categories"    -> catalogueRepo.countByPidGlobalCategory(id);
            case "subCategories" -> catalogueRepo.countByPidGlobalSubCategory(id);
            case "units"         -> catalogueRepo.countByPidGlobalUnit(id);
            case "leafs"         -> catalogueRepo.countByPidGlobalLeaf(id) + boxRepo.countByPidGlobalLeaf(id);
            case "boxes"         -> catalogueRepo.countByPidGlobalBox(id);
            default -> throw new ValidationException("There is no such list.");
        };
        if (inUse > 0) {
            throw new ValidationException(
                    inUse + " catalogue " + (inUse == 1 ? "entry uses" : "entries use")
                    + " this. Change them first, then retire it.");
        }

        switch (list) {
            case "companies"     -> companyRepo.findById(id).ifPresent(e -> { e.setStatus("N"); companyRepo.save(e); });
            case "generics"      -> genericRepo.findById(id).ifPresent(e -> { e.setStatus("N"); genericRepo.save(e); });
            case "categories"    -> categoryRepo.findById(id).ifPresent(e -> { e.setStatus("N"); categoryRepo.save(e); });
            case "subCategories" -> subCategoryRepo.findById(id).ifPresent(e -> { e.setStatus("N"); subCategoryRepo.save(e); });
            case "units"         -> unitRepo.findById(id).ifPresent(e -> { e.setStatus("N"); unitRepo.save(e); });
            case "leafs"         -> leafRepo.findById(id).ifPresent(e -> { e.setStatus("N"); leafRepo.save(e); });
            case "boxes"         -> boxRepo.findById(id).ifPresent(e -> { e.setStatus("N"); boxRepo.save(e); });
            default -> throw new ValidationException("There is no such list.");
        }
    }

    /* ── helpers ── */

    private static String require(String name, String complaint) {
        if (name == null || name.isBlank()) throw new ValidationException(complaint);
        return name.trim();
    }

    /** Refuses a name already taken by a different row. */
    private static void refuseDuplicate(Long existingId, Long thisId, String name) {
        if (existingId != null && !existingId.equals(thisId)) {
            throw new ValidationException("\"" + name + "\" is already in the list.");
        }
    }
}
