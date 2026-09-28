package com.friendspharma.app.controller;

import com.friendspharma.app.api.ApiResponse;
import com.friendspharma.app.service.impl.ProductImageBackfillService;
import com.friendspharma.app.service.impl.ProductImageStorageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * One-shot repair for pictures that are on the server but not on the screen.
 *
 * <p>Super Admin only, and platform-wide by design: images are migrated once,
 * for every pharmacy at once, so this is the one operation that deliberately
 * looks past a single branch.
 *
 * <p>Read it before you run it. {@code GET} reports what a run would change and
 * writes nothing; {@code POST} does it. Both are safe to repeat — no file is
 * deleted and no picture already in place is replaced.
 */
@RestController
@RequestMapping("/api/admin/product-images")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ROLE01')")
@Tag(name = "Product image backfill",
     description = "Connect migrated image files to the rows that should show them — Super Admin only")
public class ProductImageBackfillController {

    private final ProductImageBackfillService backfill;
    private final ProductImageStorageService images;
    private final Environment env;

    /**
     * Where uploads are actually being written, and why.
     *
     * <p>This exists because the honest answer to "the picture uploaded but the
     * folder is empty" is never a guess. The application resolves its upload
     * directory from configuration that can arrive from four places — an OS
     * environment variable, a {@code .env} that may or may not have been found,
     * a per-folder override, or the built-in default — and when the answer is
     * the built-in default it is a <em>relative</em> path anchored to the home
     * directory of whatever account the service runs as. On a Windows service
     * that is often {@code C:\Windows\System32\config\systemprofile}, which
     * nobody thinks to look in.
     *
     * <p>So this reports the resolved absolute path, whether it can be written,
     * what is in it, and the raw configuration values behind it — including the
     * two system properties that explain a relative path. Pass {@code ?file=}
     * to ask about one image by name.
     */
    @GetMapping("/where")
    @Operation(summary = "The directory uploads are written to, and the settings behind it")
    public ResponseEntity<ApiResponse<Map<String, Object>>> where(
            @RequestParam(required = false) String file) {

        Path root = images.resolveRoot();
        Map<String, Object> m = new LinkedHashMap<>();

        m.put("resolvedDirectory", root.toString());
        m.put("exists", Files.isDirectory(root));
        m.put("writable", Files.isWritable(root));

        List<String> newest = new ArrayList<>();
        long count = 0;
        if (Files.isDirectory(root)) {
            try (Stream<Path> files = Files.list(root)) {
                List<Path> all = files.filter(Files::isRegularFile).toList();
                count = all.size();
                // Newest first: after an upload the file just written is the
                // one worth seeing, and it settles the question on its own.
                newest = all.stream()
                        .sorted((a, b) -> Long.compare(lastModified(b), lastModified(a)))
                        .limit(10)
                        .map(p -> p.getFileName().toString())
                        .toList();
            } catch (IOException e) {
                m.put("listingError", e.getMessage());
            }
        }
        m.put("fileCount", count);
        m.put("newestFiles", newest);

        if (file != null && !file.isBlank()) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("name", file);
            if (images.isSuspicious(file)) {
                one.put("error", "not a stored image name (a bare filename, or <branchId>/<filename>)");
            } else {
                Path target = root.resolve(file).normalize();
                one.put("fullPath", target.toString());
                one.put("exists", target.startsWith(root) && Files.exists(target));
            }
            m.put("lookup", one);
        }

        // The settings behind the answer. UPLOAD_ROOT is shown as the
        // application actually received it, which is the point: a value that
        // never arrived reads as null here, and a relative one explains itself
        // through user.home below.
        Map<String, Object> from = new LinkedHashMap<>();
        from.put("UPLOAD_ROOT", env.getProperty("UPLOAD_ROOT"));
        from.put("UPLOAD_PRODUCT_IMAGE_DIR", env.getProperty("UPLOAD_PRODUCT_IMAGE_DIR"));
        from.put("app.upload.root", env.getProperty("app.upload.root"));
        from.put("app.upload.product-image-dir", env.getProperty("app.upload.product-image-dir"));
        from.put("app.upload.product-image-url-prefix", env.getProperty("app.upload.product-image-url-prefix"));
        m.put("configuration", from);

        Map<String, Object> jvm = new LinkedHashMap<>();
        // A relative product-image-dir is anchored to user.home, so these two
        // are the difference between "misconfigured" and "configured, but the
        // service runs as an account whose home you were not looking in".
        jvm.put("user.home", System.getProperty("user.home"));
        jvm.put("user.dir", System.getProperty("user.dir"));
        jvm.put("user.name", System.getProperty("user.name"));
        m.put("process", jvm);

        return ResponseEntity.ok(ApiResponse.ok(
                Files.isDirectory(root)
                        ? "Uploads are written to " + root
                        : "Uploads resolve to " + root + ", which does not exist.",
                m));
    }

    private static long lastModified(Path p) {
        try { return Files.getLastModifiedTime(p).toMillis(); } catch (IOException e) { return 0L; }
    }

    @GetMapping("/backfill")
    @Operation(summary = "Report what a backfill would change, without writing anything")
    public ResponseEntity<ApiResponse<Map<String, Object>>> preview() {
        var report = backfill.run(true);
        String message = report.anythingToDo()
                ? "Dry run — nothing written. POST to the same address to apply it."
                : "Nothing to do: every row already points at a file that exists.";
        return ResponseEntity.ok(ApiResponse.ok(message, report.asMap()));
    }

    @PostMapping("/backfill")
    @Operation(summary = "Connect migrated images to the catalogue and to every pharmacy's products")
    public ResponseEntity<ApiResponse<Map<String, Object>>> apply() {
        var report = backfill.run(false);
        return ResponseEntity.ok(ApiResponse.ok("Product images reconciled.", report.asMap()));
    }
}
