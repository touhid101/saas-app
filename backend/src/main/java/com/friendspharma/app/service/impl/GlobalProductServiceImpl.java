package com.friendspharma.app.service.impl;

import org.springframework.web.multipart.MultipartFile;
import com.friendspharma.app.repository.PosBoxInfoRepository;
import com.friendspharma.app.repository.PosLeafInfoRepository;
import com.friendspharma.app.repository.PosProductSubCategoryRepository;
import com.friendspharma.app.entity.PosBoxInfo;
import com.friendspharma.app.entity.PosLeafInfo;
import com.friendspharma.app.entity.PosProductSubCategory;
import com.friendspharma.app.entity.*;
import com.friendspharma.app.exception.custom.ResourceNotFoundException;
import com.friendspharma.app.exception.custom.ValidationException;
import com.friendspharma.app.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The catalogue, and copying from it into a pharmacy.
 *
 * <p><b>Import copies, it does not share.</b> A pharmacy ends up with its own
 * {@link PosProductInfo} row carrying its own branch — its price, its stock, its
 * reorder level, its supplier. Editing the catalogue afterwards changes nothing
 * on anybody's shelf, which is the point: a Super Admin correcting a spelling
 * must not silently reprice three hundred pharmacies.
 *
 * <p><b>Importing twice adds nothing the second time.</b> Checked in this class
 * against what the branch already holds, and enforced underneath by a unique
 * index on (branch, catalogue row) — because two administrators importing the
 * same catalogue at the same moment would both pass the check here and only one
 * of them can be allowed to win.
 *
 * <p><b>Master data is matched by name, then created.</b> A catalogue row names
 * its manufacturer and unit as text, and the receiving pharmacy keeps those as
 * its own rows. Importing Napa into a pharmacy that has never heard of Beximco
 * creates Beximco <em>for that pharmacy</em> — not a reference to somebody
 * else's.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GlobalProductServiceImpl {

    private final GlobalProductRepository      catalogueRepo;
    private final PosProductInfoRepository     productRepo;
    private final PosCompanyInfoRepository     companyRepo;
    private final PosProductCategoryRepository categoryRepo;
    private final PosGenericInfoRepository     genericRepo;
    private final PosProductUnitRepository     unitRepo;
    private final PosProductSubCategoryRepository subCategoryRepo;
    private final PosLeafInfoRepository        leafRepo;
    private final PosBoxInfoRepository         boxRepo;
    private final ProductImageStorageService   imageStorage;
    private final AllBranchRepository          branchRepo;

    /* The catalogue's own master lists. Curated by the Super Admin, referenced by
       catalogue entries, and copied — never shared — into a pharmacy on import. */
    private final GlobalCompanyRepository      globalCompanyRepo;
    private final GlobalGenericRepository      globalGenericRepo;
    private final GlobalCategoryRepository     globalCategoryRepo;
    private final GlobalSubCategoryRepository  globalSubCategoryRepo;
    private final GlobalUnitRepository         globalUnitRepo;
    private final GlobalLeafRepository         globalLeafRepo;
    private final GlobalBoxRepository          globalBoxRepo;

    /** What an import did, in the terms the person who ran it cares about. */
    public record ImportResult(long branchId, String branchName,
                               int imported, int alreadyHeld, List<String> names) {}

    /* ══════════════════════════════════════════════════════════════════════
       THE CATALOGUE
       ══════════════════════════════════════════════════════════════════════ */

    @Transactional(readOnly = true)
    public Page<GlobalProduct> search(String q, String company, Pageable pageable) {
        return catalogueRepo.search(q == null ? "" : q.trim(),
                                    company == null ? "" : company.trim(), pageable);
    }

    @Transactional(readOnly = true)
    public List<String> companies() { return catalogueRepo.companies(); }

    @Transactional
    public GlobalProduct save(GlobalProduct incoming) {
        if (incoming.getProductName() == null || incoming.getProductName().isBlank()) {
            throw new ValidationException("A catalogue entry needs a product name.");
        }

        /* ── carry over what this request does not own ──
           The controller binds a fresh entity from the request body, so anything
           the form does not send arrives null — and saving that over the stored
           row blanks it. The picture is the one that hurt: it is managed by its
           own endpoints and never travels with the form, so every edit was
           silently deleting the image reference while leaving the file on disk.

           Editing an entry is not the way to change its picture or to bring a
           retired one back, so neither is taken from the body. */
        if (incoming.getPidGlobalProduct() != null) {
            catalogueRepo.findById(incoming.getPidGlobalProduct()).ifPresent(stored -> {
                incoming.setProductImage(stored.getProductImage());
                incoming.setFileName(stored.getFileName());
                incoming.setFileMimeType(stored.getFileMimeType());
                if (incoming.getIsActive() == null) incoming.setIsActive(stored.getIsActive());
            });
        }

        if (incoming.getIsActive() == null) incoming.setIsActive(1);
        incoming.setStatus("Y");

        // PHARMA unless told otherwise: everything in this catalogue was medicine
        // before the column existed, and an older client that omits the field is
        // describing medicine too.
        boolean general = "GENERAL".equalsIgnoreCase(incoming.getProductType());
        incoming.setProductType(general ? "GENERAL" : "PHARMA");

        // A medicine is identified by its generic; a razor has none to give. The
        // catalogue is what every pharmacy imports from, so an entry with no
        // generic propagates that gap to each of them — worth refusing here for
        // medicine, and worth allowing for everything else.
        if (!general && incoming.getPidGlobalGeneric() == null
                && (incoming.getGenericName() == null || incoming.getGenericName().isBlank())) {
            throw new ValidationException("A generic is required for a pharma product.");
        }

        // Leaving a stale generic on an entry reclassified as general would keep
        // it searchable as a drug under a name nobody chose for it.
        if (general) {
            incoming.setPidGlobalGeneric(null);
            incoming.setGenericName(null);
        }

        resolveMasters(incoming);
        return catalogueRepo.save(incoming);
    }

    /**
     * Writes the catalogue's master names from the lists they point at.
     *
     * <p>The names used to be typed, which is how one manufacturer became three:
     * "Square Pharmaceuticals", "Square Pharma" and a trailing space are all
     * different strings, and every pharmacy that imported them inherited all
     * three as separate rows.
     *
     * <p>The *_NAME columns still exist and are still what the import reads —
     * that path is proven and tenancy-critical, and this change deliberately does
     * not touch it. What changed is that a name is now derived from a chosen list
     * entry rather than typed, so it cannot name something that does not exist.
     *
     * <p>An entry with no list chosen keeps whatever name it already had. The
     * catalogue predates these lists and an entry that has not been revisited is
     * not an error.
     */
    private void resolveMasters(GlobalProduct g) {
        if (g.getPidGlobalCompany() != null) {
            g.setCompanyName(globalCompanyRepo.findById(g.getPidGlobalCompany())
                    .orElseThrow(() -> new ValidationException("That manufacturer is not in the list."))
                    .getCompanyName());
        }
        if (g.getPidGlobalGeneric() != null) {
            g.setGenericName(globalGenericRepo.findById(g.getPidGlobalGeneric())
                    .orElseThrow(() -> new ValidationException("That generic is not in the list."))
                    .getGenericName());
        }
        if (g.getPidGlobalCategory() != null) {
            g.setCategoryName(globalCategoryRepo.findById(g.getPidGlobalCategory())
                    .orElseThrow(() -> new ValidationException("That category is not in the list."))
                    .getCategoryName());
        }
        if (g.getPidGlobalSubCategory() != null) {
            GlobalSubCategory sub = globalSubCategoryRepo.findById(g.getPidGlobalSubCategory())
                    .orElseThrow(() -> new ValidationException("That sub-category is not in the list."));
            // A sub-category belongs to exactly one category, so choosing one that
            // sits under a different category than the entry claims is a mistake
            // worth refusing rather than silently reconciling.
            if (g.getPidGlobalCategory() != null
                    && !sub.getPidGlobalCategory().equals(g.getPidGlobalCategory())) {
                throw new ValidationException(
                        "That sub-category belongs to a different category. Choose one under "
                        + g.getCategoryName() + ".");
            }
            g.setSubCategoryName(sub.getSubCategoryName());
        }
        if (g.getPidGlobalUnit() != null) {
            g.setUnitName(globalUnitRepo.findById(g.getPidGlobalUnit())
                    .orElseThrow(() -> new ValidationException("That unit is not in the list."))
                    .getUnitName());
        }
        /* The box names its leaf, so choosing a box settles both numbers. A leaf
           on its own is allowed: not every medicine comes boxed. */
        if (g.getPidGlobalBox() != null) {
            GlobalBox box = globalBoxRepo.findById(g.getPidGlobalBox())
                    .orElseThrow(() -> new ValidationException("That box is not in the list."));
            GlobalLeaf leaf = globalLeafRepo.findById(box.getPidGlobalLeaf())
                    .orElseThrow(() -> new ValidationException("That box names a strip that is not in the list."));
            if (g.getPidGlobalLeaf() != null && !g.getPidGlobalLeaf().equals(leaf.getPidGlobalLeaf())) {
                throw new ValidationException(
                        "That box is built from " + leaf.getLeafType() + ", not the strip chosen.");
            }
            g.setPidGlobalLeaf(leaf.getPidGlobalLeaf());
            g.setPcsPerLeaf(leaf.getNoOfPcs());
            g.setLeafPerBox(box.getNoOfLeaf());
        } else if (g.getPidGlobalLeaf() != null) {
            GlobalLeaf leaf = globalLeafRepo.findById(g.getPidGlobalLeaf())
                    .orElseThrow(() -> new ValidationException("That strip is not in the list."));
            g.setPcsPerLeaf(leaf.getNoOfPcs());
            // No box means no box count. The import creates neither rather than
            // half a packaging description.
            g.setLeafPerBox(null);
        }
    }

    /**
     * Retires a catalogue entry.
     *
     * <p>Deactivated rather than deleted. Pharmacies that already imported it hold
     * real products pointing back at this row, and deleting it would either break
     * that link or, worse, silently succeed and leave the link dangling.
     */
    @Transactional
    public void retire(Long id) {
        GlobalProduct g = catalogueRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Catalogue product", id));
        g.setIsActive(0);
        catalogueRepo.save(g);
    }

    /**
     * Sets the catalogue picture for a medicine.
     *
     * <p>The old file is removed only once the new reference is saved, and only
     * when it is genuinely a different file — the same order the pharmacy-side
     * upload uses, so a failure part-way leaves a row pointing at a file that
     * still exists rather than at one that does not.
     *
     * <p>Pharmacies that already imported this medicine are untouched. They hold
     * their own copy of the old picture, and the catalogue does not reach onto a
     * shelf it has already stocked.
     */
    @Transactional
    public GlobalProduct setImage(Long id, MultipartFile file) {
        GlobalProduct g = catalogueRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Catalogue product", id));

        String previous = g.getProductImage();
        String stored   = imageStorage.store(file, id);

        g.setProductImage(stored);
        g.setFileName(file.getOriginalFilename());
        g.setFileMimeType(file.getContentType());
        GlobalProduct saved = catalogueRepo.save(g);

        if (previous != null && !previous.equals(stored)) imageStorage.delete(previous);
        return saved;
    }

    /** Removes the catalogue picture. Pharmacies keep the copies they took. */
    @Transactional
    public GlobalProduct clearImage(Long id) {
        GlobalProduct g = catalogueRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Catalogue product", id));

        String previous = g.getProductImage();
        g.setProductImage(null);
        g.setFileName(null);
        g.setFileMimeType(null);
        GlobalProduct saved = catalogueRepo.save(g);

        imageStorage.delete(previous);
        return saved;
    }

    /* ══════════════════════════════════════════════════════════════════════
       IMPORT
       ══════════════════════════════════════════════════════════════════════ */

    /**
     * Copies catalogue entries into one pharmacy.
     *
     * <p>{@code productIds} null or empty means the whole active catalogue, which
     * is what activating a new pharmacy wants.
     */
    @Transactional
    public ImportResult importInto(Long branchId, List<Long> productIds) {
        return importInto(branchId, productIds, null, null);
    }

    /**
     * As above, but "everything" means everything the caller is looking at.
     *
     * <p><b>Why the filter comes too.</b> The screen searches, then offers to
     * import. With no ids the old method took the entire catalogue regardless —
     * so filtering to one manufacturer, reading "Import 120 medicines" and
     * pressing it copied all three thousand. The button and the act have to
     * agree, and the only way to be sure they do is for both to be the same
     * query: this uses {@link #search}, the one the list itself is drawn from.
     *
     * <p>Blank {@code q} and {@code company} match everything, so the onboarding
     * path — which passes neither — still takes the whole catalogue exactly as
     * it did.
     */
    @Transactional
    public ImportResult importInto(Long branchId, List<Long> productIds, String q, String company) {
        AllBranch branch = branchRepo.findById(branchId)
                .orElseThrow(() -> new ResourceNotFoundException("Branch", branchId));

        List<GlobalProduct> wanted = (productIds == null || productIds.isEmpty())
                ? search(q, company, org.springframework.data.domain.Pageable.unpaged()).getContent()
                : catalogueRepo.findAllById(productIds);

        if (wanted.isEmpty()) {
            boolean filtered = (q != null && !q.isBlank()) || (company != null && !company.isBlank());
            throw new ValidationException(filtered
                    ? "Nothing in the catalogue matches that search, so there is nothing to import."
                    : "There is nothing in the catalogue to import.");
        }

        // Two questions, not two per product. Importing two thousand medicines
        // should not be four thousand round trips.
        Set<Long> held = new HashSet<>(catalogueRepo.alreadyHeldBy(branchId));

        // Matched by name and code as well as by the catalogue link, because a
        // pharmacy that typed "Napa 500mg" in by hand has no link to the
        // catalogue row of the same name. Without this the import tried to create
        // a second one and the unique index on (branch, product code) rejected
        // the whole batch with a constraint error nobody could act on.
        Set<String> heldByName = catalogueRepo.namesAndCodesHeldBy(branchId).stream()
                .filter(java.util.Objects::nonNull)
                .map(String::trim)
                .collect(Collectors.toSet());

        MasterData master = new MasterData(branch, companyRepo, categoryRepo, genericRepo, unitRepo,
                                           subCategoryRepo, leafRepo, boxRepo);

        List<String> imported = new ArrayList<>();
        int skipped = 0;
        for (GlobalProduct g : wanted) {
            if (held.contains(g.getPidGlobalProduct()) || alreadyStocked(heldByName, g)) {
                skipped++;
                continue;
            }

            PosProductInfo p = PosProductInfo.builder()
                    .pidOrg(branch.getPidOrg())
                    .pidBranch(branch.getPidBranch())
                    .pidGlobalProduct(g.getPidGlobalProduct())
                    .productName(g.getProductName())
                    .productCode(g.getProductCode())
                    .barcode(g.getBarcode())
                    // Carried from the catalogue, not defaulted. A GENERAL
                    // catalogue entry — a bar of soap — must not arrive on a
                    // pharmacy's shelf as PHARMA, because the type decides
                    // whether a generic and an SKU are demanded of it later.
                    // Falls back to the entity default only if the catalogue
                    // row somehow has none.
                    .productType(g.getProductType() == null ? "PHARMA" : g.getProductType())
                    // Suggested, not imposed. The pharmacy owns these the moment
                    // the row lands and the catalogue never touches them again.
                    .mrpPrice(g.getSuggestedMrp())
                    .tpPrice(g.getSuggestedTp())
                    // V89: the two figures a purchase line is valued at, so an
                    // imported medicine can be bought without somebody typing
                    // its VAT amount first. Owned by the pharmacy from here on.
                    .igta(g.getIgta())
                    .buyPrice(g.getBuyPrice())
                    .prescriptionFlag(g.getPrescriptionFlag())
                    .pidCompany(master.company(g.getCompanyName()))
                    .pidGeneric(master.generic(g.getGenericName()))
                    .pidUnitId(master.unit(g.getUnitName()))
                    .pidUnit(g.getUnitName())
                    .pidBox(master.box(g.getPcsPerLeaf(), g.getLeafPerBox()))
                    // Its own copy of the picture, in its own folder, not a
                    // reference to the catalogue's — see ProductImageStorageService.copyOf.
                    .productImage(imageStorage.copyOf(g.getProductImage(), branch.getPidBranch()))
                    .fileName(g.getFileName())
                    .fileMimeType(g.getFileMimeType())
                    .build();

            // After the builder because the sub-category needs the category's id,
            // and the category is resolved in the same expression.
            Long categoryId = master.category(g.getCategoryName());
            p.setPidCategory(categoryId);
            p.setPidSubCategory(master.subCategory(categoryId, g.getSubCategoryName()));
            p.setStatus("Y");
            productRepo.save(p);
            imported.add(g.getProductName());
        }

        log.info("Imported {} catalogue product(s) into branch {}; {} were already held.",
                 imported.size(), branchId, skipped);
        return new ImportResult(branchId, branch.getBranchName(), imported.size(), skipped, imported);
    }

    /** True where the branch already stocks this medicine under any name it knows. */
    private static boolean alreadyStocked(Set<String> held, GlobalProduct g) {
        return (g.getProductName() != null && held.contains(g.getProductName().trim().toLowerCase()))
            || (g.getProductCode() != null && held.contains(g.getProductCode().trim().toLowerCase()));
    }

    /**
     * The receiving pharmacy's own manufacturers, categories, generics and units.
     *
     * <p>Looked up by name and created where missing, once per import rather than
     * once per product. Importing into a pharmacy that has never heard of Beximco
     * creates Beximco <em>for that pharmacy</em>; it must not reach for another
     * pharmacy's row, which is exactly the leak Phase 1 closed.
     */
    private static final class MasterData {
        private final AllBranch branch;
        private final PosCompanyInfoRepository     companyRepo;
        private final PosProductCategoryRepository categoryRepo;
        private final PosGenericInfoRepository     genericRepo;
        private final PosProductUnitRepository     unitRepo;
        private final PosProductSubCategoryRepository subCatRepo;
        private final PosLeafInfoRepository        leafRepo;
        private final PosBoxInfoRepository         boxRepo;

        private final Map<String, Long> companies  = new HashMap<>();
        private final Map<String, Long> categories = new HashMap<>();
        private final Map<String, Long> generics   = new HashMap<>();
        private final Map<String, Long> units      = new HashMap<>();
        /* Keyed by category as well as name: two categories may each have a
           "Tablet", and they are different rows. */
        private final Map<String, Long> subCategories = new HashMap<>();
        private final Map<Integer, Long> leaves    = new HashMap<>();
        private final Map<String, Long> boxes      = new HashMap<>();

        MasterData(AllBranch branch, PosCompanyInfoRepository c, PosProductCategoryRepository cat,
                   PosGenericInfoRepository g, PosProductUnitRepository u,
                   PosProductSubCategoryRepository sc, PosLeafInfoRepository l,
                   PosBoxInfoRepository b) {
            this.branch = branch;
            this.companyRepo = c; this.categoryRepo = cat; this.genericRepo = g; this.unitRepo = u;
            this.subCatRepo = sc; this.leafRepo = l; this.boxRepo = b;
            for (PosProductSubCategory x : sc.findByPidOrgAndPidBranchAndStatusOrderBySubCategoryName(
                    branch.getPidOrg(), branch.getPidBranch(), "Y")) {
                subCategories.put(x.getPidCategory() + "|" + key(x.getSubCategoryName()), x.getPidSubCategory());
            }
            for (PosLeafInfo x : l.findByPidOrgAndPidBranchAndStatusOrderByLeafType(
                    branch.getPidOrg(), branch.getPidBranch(), "Y")) {
                if (x.getNoOfPcs() != null) leaves.putIfAbsent(x.getNoOfPcs(), x.getPidLeaf());
            }
            for (PosBoxInfo x : b.findByPidOrgAndPidBranchAndStatusOrderByBoxSizeTitle(
                    branch.getPidOrg(), branch.getPidBranch(), "Y")) {
                boxes.putIfAbsent(x.getPidLeaf() + "|" + x.getNoOfLeaf(), x.getPidBox());
            }
            for (PosCompanyInfo x : c.findByPidOrgAndPidBranchAndStatusOrderByCompanyName(
                    branch.getPidOrg(), branch.getPidBranch(), "Y")) {
                companies.put(key(x.getCompanyName()), x.getPidCompany());
            }
            for (PosProductCategory x : cat.findByPidOrgAndPidBranchAndStatusOrderByCategoryName(
                    branch.getPidOrg(), branch.getPidBranch(), "Y")) {
                categories.put(key(x.getCategoryName()), x.getPidCategory());
            }
            for (PosGenericInfo x : g.findByPidOrgAndPidBranchAndStatusOrderByGenericName(
                    branch.getPidOrg(), branch.getPidBranch(), "Y")) {
                generics.put(key(x.getGenericName()), x.getPidGeneric());
            }
            for (PosProductUnit x : u.findByPidOrgAndPidBranchAndStatusOrderByUnitName(
                    branch.getPidOrg(), branch.getPidBranch(), "Y")) {
                units.put(key(x.getUnitName()), x.getPidUnit());
            }
        }

        private static String key(String s) { return s == null ? "" : s.trim().toLowerCase(); }

        Long company(String name) {
            if (name == null || name.isBlank()) return null;
            return companies.computeIfAbsent(key(name), k -> {
                PosCompanyInfo e = PosCompanyInfo.builder()
                        .pidOrg(branch.getPidOrg()).pidBranch(branch.getPidBranch())
                        .companyName(name.trim()).build();
                e.setStatus("Y");
                return companyRepo.save(e).getPidCompany();
            });
        }

        Long category(String name) {
            if (name == null || name.isBlank()) return null;
            return categories.computeIfAbsent(key(name), k -> {
                PosProductCategory e = PosProductCategory.builder()
                        .pidOrg(branch.getPidOrg()).pidBranch(branch.getPidBranch())
                        .pidCompany(branch.getPidCompany())
                        .categoryName(name.trim()).build();
                e.setStatus("Y");
                return categoryRepo.save(e).getPidCategory();
            });
        }

        Long generic(String name) {
            if (name == null || name.isBlank()) return null;
            return generics.computeIfAbsent(key(name), k -> {
                PosGenericInfo e = PosGenericInfo.builder()
                        .pidOrg(branch.getPidOrg()).pidBranch(branch.getPidBranch())
                        .pidCompany(branch.getPidCompany())
                        .genericName(name.trim()).build();
                e.setStatus("Y");
                return genericRepo.save(e).getPidGeneric();
            });
        }

        /**
         * The pharmacy's sub-category of this name under this category.
         *
         * <p>Needs the category first — a sub-category with no parent is not a
         * thing the rest of the application can navigate to.
         */
        Long subCategory(Long categoryId, String name) {
            if (categoryId == null || name == null || name.isBlank()) return null;
            return subCategories.computeIfAbsent(categoryId + "|" + key(name), k -> {
                PosProductSubCategory e = PosProductSubCategory.builder()
                        .pidOrg(branch.getPidOrg()).pidBranch(branch.getPidBranch())
                        .pidCompany(branch.getPidCompany())
                        .pidCategory(categoryId).subCategoryName(name.trim()).build();
                e.setStatus("Y");
                return subCatRepo.save(e).getPidSubCategory();
            });
        }

        /**
         * The box this packaging describes, creating the leaf/strip under it first.
         *
         * <p>Both numbers are needed: a box is a count of leaves, and a leaf is a
         * count of pieces. With only one of them there is nothing coherent to
         * create, so the product imports without a box rather than with half of one.
         */
        Long box(Integer pcsPerLeaf, Integer leafPerBox) {
            if (pcsPerLeaf == null || pcsPerLeaf < 1 || leafPerBox == null || leafPerBox < 1) return null;

            Long leafId = leaves.computeIfAbsent(pcsPerLeaf, pcs -> {
                PosLeafInfo e = PosLeafInfo.builder()
                        .pidOrg(branch.getPidOrg()).pidBranch(branch.getPidBranch())
                        .pidCompany(branch.getPidCompany())
                        .leafType("Strip-" + pcs).noOfPcs(pcs).build();
                e.setStatus("Y");
                return leafRepo.save(e).getPidLeaf();
            });

            return boxes.computeIfAbsent(leafId + "|" + leafPerBox, k -> {
                PosBoxInfo e = PosBoxInfo.builder()
                        .pidOrg(branch.getPidOrg()).pidBranch(branch.getPidBranch())
                        .pidCompany(branch.getPidCompany())
                        .pidLeaf(leafId).noOfLeaf(leafPerBox)
                        .boxSizeTitle(leafPerBox + "x" + pcsPerLeaf).build();
                e.setStatus("Y");
                return boxRepo.save(e).getPidBox();
            });
        }

        Long unit(String name) {
            if (name == null || name.isBlank()) return null;
            return units.computeIfAbsent(key(name), k -> {
                PosProductUnit e = PosProductUnit.builder()
                        .pidOrg(branch.getPidOrg()).pidBranch(branch.getPidBranch())
                        .pidCompany(branch.getPidCompany())
                        .unitName(name.trim()).build();
                e.setStatus("Y");
                return unitRepo.save(e).getPidUnit();
            });
        }
    }
}
