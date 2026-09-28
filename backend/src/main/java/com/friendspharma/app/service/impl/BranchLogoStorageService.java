package com.friendspharma.app.service.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;

/**
 * Disk storage for a pharmacy's own logo.
 *
 * <p>A thin naming over {@link ImageStorageService}, which does the work for
 * every bucket the application writes to. This existed first and was the only
 * one of the three upload paths that checked its input properly; the validation
 * moved into the shared service so avatars are held to the same standard rather
 * than each caller deciding for itself.
 *
 * <p>Kept as a type of its own so the call sites still read as branch logos
 * rather than as "bucket 2", and so the branch-specific meaning stays visible
 * where a logo is set.
 */
@Service
@RequiredArgsConstructor
public class BranchLogoStorageService {

    private static final ImageStorageService.Bucket BUCKET = ImageStorageService.Bucket.BRANCH_LOGO;

    private final ImageStorageService images;

    /** Absolute upload root; relative configuration anchors to the user home. */
    public Path resolveRoot() {
        return images.resolveRoot(BUCKET);
    }

    /**
     * Validates and stores an upload.
     *
     * @return the public URL to persist in {@code ALL_BRANCH.LOGO_URL}
     */
    public String store(MultipartFile file, Long branchId) {
        return images.store(file, BUCKET, branchId);
    }

    /** Removes a previously stored logo, best-effort. */
    public void delete(String publicUrl) {
        images.delete(BUCKET, publicUrl);
    }

    /** Public URL for a stored filename. */
    public String toPublicUrl(String storedName) {
        return images.toPublicUrl(BUCKET, storedName);
    }
}
