package com.friendspharma.app.service.impl;

import com.friendspharma.app.entity.GlobalProduct;
import com.friendspharma.app.entity.PosProductInfo;
import com.friendspharma.app.repository.GlobalProductRepository;
import com.friendspharma.app.repository.PosProductInfoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Connects images that are already on disk to the rows that should point at them.
 *
 * <p>Migrating a folder of pictures onto a server does not make them appear in
 * the application: a picture is shown because a row names it, and the two are
 * loaded by different means. This reconciles them in one pass — after which the
 * ordinary upload path takes over again and this never needs running twice.
 *
 * <p>Three faults are repaired, in the order a picture goes missing:
 *
 * <ol>
 *   <li><b>Nothing points at the file.</b> A catalogue entry whose picture was
 *       never recorded, but where the folder holds a file named after that
 *       entry, is pointed at it.</li>
 *   <li><b>The name is right but the case is not.</b> Windows treats
 *       {@code 201.PNG} and {@code 201.png} as one file and Linux does not, so a
 *       folder that worked on a laptop can go blank on a server. The column is
 *       rewritten to the name the filesystem actually uses.</li>
 *   <li><b>A pharmacy imported the medicine before the pictures arrived.</b>
 *       {@code copyOf} logged the missing file and imported the product without
 *       one, so the shelf has no picture even though the catalogue now does. The
 *       catalogue's file is copied for that product, exactly as an import would
 *       have done at the time.</li>
 * </ol>
 *
 * <p>A pharmacy gets its own copy rather than a reference to the catalogue's
 * file, for the reason {@link ProductImageStorageService#copyOf} gives: both
 * uploading and deleting remove the previous file, so a shared one would be
 * destroyed by the first pharmacy to change its picture, taking every other
 * pharmacy's with it.
 *
 * <p>This deliberately crosses every branch — it is a platform-wide repair run
 * by the Super Admin, not a tenant reading its own shelf, which is why it lives
 * behind {@code ROLE01} and nowhere near {@code BranchScopeService}.
 *
 * <p>Nothing is deleted and no picture already in place is replaced, so the
 * dry run and the real run can both be repeated safely.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductImageBackfillService {

    /** Rows per page, so a hundred thousand products do not arrive at once. */
    private static final int PAGE = 500;

    /** How many worked examples to return; enough to check, not a second report. */
    private static final int EXAMPLES = 20;

    private final ProductImageStorageService images;
    private final GlobalProductRepository catalogueRepo;
    private final PosProductInfoRepository productRepo;

    /**
     * What a run did, or — in a dry run — what it would do.
     *
     * @param filesOnDisk      image files found in the upload directory
     * @param filesUnreferenced files no row points at even after the run
     * @param catalogueLinked  catalogue entries given a picture that was already on disk
     * @param catalogueRecased catalogue names corrected to the filesystem's spelling
     * @param catalogueOk      catalogue entries whose picture was already correct
     * @param catalogueMissing catalogue entries with no file to point at
     * @param productLinked    products pointed at a file already named after them
     * @param productCopied    products given their own copy of the catalogue's picture
     * @param productRecased   product names corrected to the filesystem's spelling
     * @param productOk        products whose picture was already correct
     * @param productMissing   products with no picture available anywhere
     */
    public record Report(String uploadDirectory,
                         boolean dryRun,
                         int filesOnDisk,
                         int filesUnreferenced,
                         int catalogueLinked,
                         int catalogueRecased,
                         int catalogueOk,
                         int catalogueMissing,
                         int productLinked,
                         int productCopied,
                         int productRecased,
                         int productOk,
                         int productMissing,
                         List<String> examples) {

        /** True when a real run would still change something. */
        public boolean anythingToDo() {
            return catalogueLinked + catalogueRecased + productLinked + productCopied + productRecased > 0;
        }

        public Map<String, Object> asMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("uploadDirectory", uploadDirectory);
            m.put("dryRun", dryRun);
            m.put("filesOnDisk", filesOnDisk);
            m.put("filesUnreferenced", filesUnreferenced);
            Map<String, Object> cat = new LinkedHashMap<>();
            cat.put("linked", catalogueLinked);
            cat.put("recased", catalogueRecased);
            cat.put("alreadyCorrect", catalogueOk);
            cat.put("noFileFound", catalogueMissing);
            m.put("catalogue", cat);
            Map<String, Object> prod = new LinkedHashMap<>();
            prod.put("linked", productLinked);
            prod.put("copied", productCopied);
            prod.put("recased", productRecased);
            prod.put("alreadyCorrect", productOk);
            prod.put("noPictureAvailable", productMissing);
            m.put("products", prod);
            m.put("examples", examples);
            return m;
        }
    }

    /**
     * Reconciles the upload directory with the catalogue and every pharmacy's
     * products.
     *
     * @param dryRun true to report without writing a file or a row
     */
    @Transactional
    public Report run(boolean dryRun) {
        Path root = images.resolveRoot();
        DiskIndex disk = DiskIndex.of(root);
        List<String> examples = new ArrayList<>();

        log.info("Product image backfill starting ({}). {} file(s) in {}",
                 dryRun ? "dry run" : "writing", disk.size(), root);

        Counters cat = new Counters();
        Map<Long, String> catalogueImages = reconcileCatalogue(disk, dryRun, cat, examples);

        Counters prod = new Counters();
        reconcileProducts(disk, catalogueImages, dryRun, prod, examples);

        Report report = new Report(root.toString(), dryRun,
                disk.size(), disk.unreferenced(),
                cat.linked, cat.recased, cat.ok, cat.missing,
                prod.linked, prod.copied, prod.recased, prod.ok, prod.missing,
                List.copyOf(examples));

        log.info("Product image backfill finished ({}). catalogue linked={} recased={} ok={} missing={}; "
                 + "products linked={} copied={} recased={} ok={} missing={}",
                 dryRun ? "dry run — nothing written" : "written",
                 cat.linked, cat.recased, cat.ok, cat.missing,
                 prod.linked, prod.copied, prod.recased, prod.ok, prod.missing);
        return report;
    }

    /* ── the catalogue ───────────────────────────────────────────────────── */

    /**
     * Points catalogue entries at the files that belong to them, and returns the
     * picture each entry ends up with.
     *
     * <p>The map is built here rather than queried per product below: a hundred
     * thousand shelf rows must not become a hundred thousand catalogue lookups.
     */
    private Map<Long, String> reconcileCatalogue(DiskIndex disk, boolean dryRun,
                                                 Counters c, List<String> examples) {
        Map<Long, String> byCatalogueId = new HashMap<>();
        Pageable page = PageRequest.of(0, PAGE, Sort.by("pidGlobalProduct"));

        while (true) {
            Page<GlobalProduct> batch = catalogueRepo.findAll(page);
            for (GlobalProduct g : batch) {
                Long id = g.getPidGlobalProduct();
                String current = g.getProductImage();

                // Already correct, or correct but for the spelling of its case.
                String actual = disk.actualName(current);
                if (actual != null) {
                    if (actual.equals(current)) {
                        c.ok++;
                    } else {
                        c.recased++;
                        note(examples, "catalogue " + id + ": " + current + " → " + actual);
                        if (!dryRun) g.setProductImage(actual);
                    }
                    byCatalogueId.put(id, actual);
                    continue;
                }

                // Nothing usable recorded. A picture named after this entry — by
                // its id, or by the code the source system knew it as — is this
                // entry's picture; that is the convention the whole folder uses.
                String found = disk.byBaseName(String.valueOf(id));
                if (found == null) found = disk.byBaseName(g.getProductCode());
                if (found == null) found = disk.byBaseName(baseNameOf(g.getFileName()));

                if (found == null) {
                    c.missing++;
                    continue;
                }
                c.linked++;
                note(examples, "catalogue " + id + ": " + (current == null ? "(none)" : current) + " → " + found);
                if (!dryRun) g.setProductImage(found);
                byCatalogueId.put(id, found);
            }
            if (!dryRun) catalogueRepo.saveAll(batch.getContent());
            if (!batch.hasNext()) break;
            page = batch.nextPageable();
        }
        return byCatalogueId;
    }

    /* ── the pharmacies' own products ────────────────────────────────────── */

    private void reconcileProducts(DiskIndex disk, Map<Long, String> catalogueImages,
                                   boolean dryRun, Counters c, List<String> examples) {
        Pageable page = PageRequest.of(0, PAGE, Sort.by("pidProduct"));

        while (true) {
            Page<PosProductInfo> batch = productRepo.findAll(page);
            for (PosProductInfo p : batch) {
                Long id = p.getPidProduct();
                String current = p.getProductImage();

                String actual = disk.actualName(current);
                if (actual != null) {
                    if (actual.equals(current)) {
                        c.ok++;
                    } else {
                        c.recased++;
                        note(examples, "product " + id + ": " + current + " → " + actual);
                        if (!dryRun) p.setProductImage(actual);
                    }
                    continue;
                }

                // A file already named after this product needs no copying — the
                // row simply has to say so.
                String own = disk.byBaseName(String.valueOf(id));
                if (own != null) {
                    c.linked++;
                    note(examples, "product " + id + ": " + (current == null ? "(none)" : current) + " → " + own);
                    if (!dryRun) p.setProductImage(own);
                    continue;
                }

                // Otherwise take the catalogue's picture, the way the import
                // would have done had the file been there at the time.
                String source = p.getPidGlobalProduct() != null
                        ? catalogueImages.get(p.getPidGlobalProduct()) : null;
                if (source == null && p.getPidGlobalProduct() != null) {
                    source = disk.byBaseName(String.valueOf(p.getPidGlobalProduct()));
                }
                if (source == null) source = disk.byBaseName(p.getProductCode());
                if (source == null) source = disk.byBaseName(baseNameOf(p.getFileName()));

                if (source == null) {
                    c.missing++;
                    continue;
                }

                if (dryRun) {
                    c.copied++;
                    note(examples, "product " + id + ": would copy " + source + " → " + id + extensionOf(source));
                    disk.markReferenced(source);
                    continue;
                }

                String copied = images.copyAs(source, id, p.getPidBranch());
                if (copied == null) {          // unreadable, or an extension we do not serve
                    c.missing++;
                    continue;
                }
                c.copied++;
                note(examples, "product " + id + ": copied " + source + " → " + copied);
                p.setProductImage(copied);
                disk.remember(copied);
            }
            if (!dryRun) productRepo.saveAll(batch.getContent());
            if (!batch.hasNext()) break;
            page = batch.nextPageable();
        }
    }

    /* ── helpers ─────────────────────────────────────────────────────────── */

    private static void note(List<String> examples, String line) {
        if (examples.size() < EXAMPLES) examples.add(line);
    }

    /** ".png" from "201.png"; empty when there is no extension. */
    private static String extensionOf(String name) {
        int dot = name == null ? -1 : name.lastIndexOf('.');
        return dot > -1 ? name.substring(dot).toLowerCase(Locale.ROOT) : "";
    }

    /** "201" from "201.png" — the part a file is matched on. */
    private static String baseNameOf(String name) {
        if (name == null || name.isBlank()) return null;
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static final class Counters {
        int linked, copied, recased, ok, missing;
    }

    /**
     * The upload directory, read once.
     *
     * <p>Two questions are asked of it thousands of times — "is there a file
     * called this" and "is there a file whose name before the dot is this" — and
     * both are answered case-insensitively, because the answer must not depend
     * on whether the server is Windows or Linux. What comes back is always the
     * name the filesystem really uses, which is what gets written to the column.
     */
    private static final class DiskIndex {
        private final Map<String, String> byFullName = new HashMap<>();
        private final Map<String, String> byBaseName = new HashMap<>();
        private final java.util.Set<String> referenced = new java.util.HashSet<>();

        static DiskIndex of(Path root) {
            DiskIndex index = new DiskIndex();
            if (!Files.isDirectory(root)) {
                log.warn("Upload directory {} does not exist; nothing to reconcile.", root);
                return index;
            }
            try (Stream<Path> files = Files.list(root)) {
                files.filter(Files::isRegularFile)
                     .map(f -> f.getFileName().toString())
                     .forEach(index::remember);
            } catch (IOException e) {
                log.warn("Could not read the upload directory {}: {}", root, e.getMessage());
            }
            return index;
        }

        void remember(String fileName) {
            String lower = fileName.toLowerCase(Locale.ROOT);
            byFullName.put(lower, fileName);
            int dot = lower.lastIndexOf('.');
            // putIfAbsent: with 201.png and 201.jpg both present the first wins,
            // deterministically, rather than whichever the directory listed last.
            byBaseName.putIfAbsent(dot > 0 ? lower.substring(0, dot) : lower, fileName);
        }

        /** The filesystem's spelling of this name, or null when there is no such file. */
        String actualName(String stored) {
            if (stored == null || stored.isBlank()) return null;
            if (stored.contains("/") || stored.indexOf('\\') >= 0 || stored.contains("..")) return null;
            String found = byFullName.get(stored.toLowerCase(Locale.ROOT));
            if (found != null) referenced.add(found.toLowerCase(Locale.ROOT));
            return found;
        }

        /** The file whose name before the dot is this, or null. */
        String byBaseName(String base) {
            if (base == null || base.isBlank()) return null;
            String found = byBaseName.get(base.trim().toLowerCase(Locale.ROOT));
            if (found != null) referenced.add(found.toLowerCase(Locale.ROOT));
            return found;
        }

        void markReferenced(String fileName) {
            referenced.add(fileName.toLowerCase(Locale.ROOT));
        }

        int size() { return byFullName.size(); }

        /** Files nothing points at — usually a name the rows never knew about. */
        int unreferenced() { return Math.max(0, byFullName.size() - referenced.size()); }
    }
}
