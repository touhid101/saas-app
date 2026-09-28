package com.friendspharma.app.service.impl;

import com.friendspharma.app.exception.custom.BusinessException;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Disk storage for uploaded images.
 *
 * <p>One implementation for every bucket the application writes to. There were
 * three, and they did not agree: branch logos were checked properly, admin
 * avatar uploads trusted the file extension, and self-service avatar uploads
 * did not touch the disk at all — they base64-encoded the image into a
 * {@code VARCHAR(500)} column, which no image on earth fits into.
 *
 * <p><b>The original filename is treated as hostile.</b> The stored name is
 * generated, the extension comes from a whitelist, the declared MIME type and
 * the extension must agree, the leading bytes must be a real image signature,
 * and the resolved path is re-checked to be inside the configured root before
 * anything is written. These directories are served over HTTP, so a renamed
 * script that reached one would be served back.
 *
 * <p>SVG is deliberately absent from the whitelist. It is an image everywhere
 * except in a browser, where it is a document that can carry script.
 *
 * <p>Directories are configuration-driven; a relative value is anchored to the
 * user home, so a dev box lands under {@code <user-home>/uploads} while
 * production points the same keys at a server volume.
 */
@Slf4j
@Service
public class ImageStorageService {

    /**
     * Where an image goes, and what it is called when it gets there.
     *
     * <p>{@code EMPLOYEE_IMAGE} joins the existing two rather than getting a
     * service of its own. Everything that makes this class worth having — the
     * extension whitelist, the MIME-and-extension agreement, the magic-byte
     * check, the path re-check against the configured root — applies to a staff
     * photograph exactly as it does to an avatar, and a second implementation
     * would be a second place for one of those to be forgotten.
     */
    public enum Bucket { AVATAR, BRANCH_LOGO, EMPLOYEE_IMAGE, EXPENSE_RECEIPT, DELIVERY_PROOF }

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp");
    private static final Set<String> ALLOWED_MIME_TYPES = Set.of("image/jpeg", "image/png", "image/webp");

    @Value("${app.upload.avatar-dir:uploads/avatars}")
    private String avatarDir;

    @Value("${app.upload.avatar-url-prefix:/uploads/avatars/}")
    private String avatarUrlPrefix;

    @Value("${app.upload.logo-dir:uploads/logo}")
    private String logoDir;

    @Value("${app.upload.logo-url-prefix:/uploads/logo/}")
    private String logoUrlPrefix;

    /**
     * Staff photographs. Defaults to {@code <upload-root>/employee_image}, and
     * the root is {@code UPLOAD_ROOT} — never a path compiled into the code, and
     * never a path written into the database.
     */
    @Value("${app.upload.employee-image-dir:uploads/employee_image}")
    private String employeeImageDir;

    /**
     * Photographs of receipts on expense claims. Same rules as everything else
     * here — a phone photograph of a till slip is an image, and restricting it
     * to one keeps the magic-byte check meaningful. A pharmacy that needs PDFs
     * is a change to the whitelist, not to this class.
     */
    @Value("${app.upload.expense-receipt-dir:uploads/expense_receipt}")
    private String expenseReceiptDir;

    /**
     * Photographs taken at a customer's door (V109). Their own folder rather
     * than a shared one: a delivery photograph is evidence about a person's
     * home, and mixing it with product images makes it harder to reason about
     * who can see what, and impossible to retain the two differently.
     */
    @Value("${app.upload.delivery-proof-dir:uploads/delivery_proof}")
    private String deliveryProofDir;

    @Value("${app.upload.delivery-proof-url-prefix:/uploads/delivery_proof/}")
    private String deliveryProofUrlPrefix;

    @Value("${app.upload.expense-receipt-url-prefix:/uploads/expense_receipt/}")
    private String expenseReceiptUrlPrefix;

    @Value("${app.upload.employee-image-url-prefix:/uploads/employee_image/}")
    private String employeeImageUrlPrefix;

    @Value("${app.upload.max-size-bytes:2097152}")
    private long maxSizeBytes;

    private String dirOf(Bucket b) {
        return switch (b) {
            case AVATAR         -> avatarDir;
            case BRANCH_LOGO    -> logoDir;
            case EMPLOYEE_IMAGE -> employeeImageDir;
            case EXPENSE_RECEIPT -> expenseReceiptDir;
            case DELIVERY_PROOF -> deliveryProofDir;
        };
    }

    private String prefixOf(Bucket b) {
        return switch (b) {
            case AVATAR         -> avatarUrlPrefix;
            case BRANCH_LOGO    -> logoUrlPrefix;
            case EMPLOYEE_IMAGE -> employeeImageUrlPrefix;
            case EXPENSE_RECEIPT -> expenseReceiptUrlPrefix;
            case DELIVERY_PROOF -> deliveryProofUrlPrefix;
        };
    }

    /** The readable half of a stored filename — the UUID after it is what makes it unique. */
    private String nameOf(Bucket b) {
        return switch (b) {
            case AVATAR         -> "user";
            case BRANCH_LOGO    -> "branch";
            case EMPLOYEE_IMAGE -> "employee";
            case EXPENSE_RECEIPT -> "receipt";
            case DELIVERY_PROOF -> "delivery";
        };
    }

    /** Absolute upload root for a bucket; relative configuration anchors to the user home. */
    public Path resolveRoot(Bucket bucket) {
        Path configured = Paths.get(dirOf(bucket));
        return (configured.isAbsolute() ? configured
                                        : Paths.get(System.getProperty("user.home"), dirOf(bucket)))
                .toAbsolutePath().normalize();
    }

    @PostConstruct
    void ensureDirectoriesExist() {
        for (Bucket b : Bucket.values()) {
            try {
                Files.createDirectories(resolveRoot(b));
                log.info("Upload directory ready ({}): {}", b, resolveRoot(b));
            } catch (IOException e) {
                log.warn("Could not pre-create {} directory {}: {}", b, resolveRoot(b), e.getMessage());
            }
        }
    }

    /**
     * Validates and stores an upload.
     *
     * @param ownerId the user or branch the image belongs to — in the filename
     *                only so the directory is readable to a human. Nothing reads
     *                it back; uniqueness comes from the UUID.
     * @return the public URL to persist
     */
    public String store(MultipartFile file, Bucket bucket, Long ownerId) {
        validate(file);

        String extension = extensionOf(file.getOriginalFilename());
        String storedName = nameOf(bucket) + "_" + ownerId + "_"
                          + UUID.randomUUID().toString().replace("-", "") + "." + extension;

        Path root   = resolveRoot(bucket);
        Path target = root.resolve(storedName).normalize();
        if (!target.startsWith(root)) {
            throw new BusinessException("Invalid upload path.",
                    HttpStatus.BAD_REQUEST, "INVALID_UPLOAD_PATH");
        }

        try {
            Files.createDirectories(root);
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
            log.info("Stored {} image for {}: {}", bucket, ownerId, storedName);
            return toPublicUrl(bucket, storedName);
        } catch (IOException e) {
            log.error("Failed to store {} image for {}", bucket, ownerId, e);
            throw new BusinessException("Could not save the image. Please try again.",
                    HttpStatus.INTERNAL_SERVER_ERROR, "UPLOAD_FAILED");
        }
    }

    /**
     * Removes a previously stored image, best-effort.
     *
     * <p>Never fails the surrounding transaction: the row has already stopped
     * pointing at the file, and an orphaned file is harmless where a failed save
     * is not.
     */
    public void delete(Bucket bucket, String publicUrl) {
        String name = fileNameOf(publicUrl);
        if (name == null) return;
        try {
            Path root   = resolveRoot(bucket);
            Path target = root.resolve(name).normalize();
            if (target.startsWith(root)) Files.deleteIfExists(target);
        } catch (IOException e) {
            log.warn("Could not delete {} image {}: {}", bucket, name, e.getMessage());
        }
    }

    /** Public URL for a stored filename. */
    public String toPublicUrl(Bucket bucket, String storedName) {
        if (storedName == null || storedName.isBlank()) return null;
        String prefix = prefixOf(bucket);
        return prefix.endsWith("/") ? prefix + storedName : prefix + "/" + storedName;
    }

    /** The bare filename inside a stored URL, or null if it looks tampered with. */
    private String fileNameOf(String publicUrl) {
        if (publicUrl == null || publicUrl.isBlank()) return null;
        String name = publicUrl.substring(publicUrl.lastIndexOf('/') + 1);
        if (name.isBlank() || name.contains("\\") || name.contains("..")) {
            log.warn("Refusing to act on a suspicious image reference: {}", publicUrl);
            return null;
        }
        return name;
    }

    /* ── validation ──────────────────────────────────────────────────────── */

    private void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("Please choose an image to upload.",
                    HttpStatus.BAD_REQUEST, "EMPTY_FILE");
        }
        if (file.getSize() > maxSizeBytes) {
            throw new BusinessException(
                    "The image is larger than the " + (maxSizeBytes / 1024 / 1024) + " MB limit.",
                    HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE");
        }

        String mime = file.getContentType();
        if (mime == null || !ALLOWED_MIME_TYPES.contains(mime.toLowerCase(Locale.ROOT))) {
            throw new BusinessException("Use a JPG, PNG or WEBP image.",
                    HttpStatus.BAD_REQUEST, "INVALID_FILE_TYPE");
        }
        String extension = extensionOf(file.getOriginalFilename());
        if (extension == null || !ALLOWED_EXTENSIONS.contains(extension)) {
            throw new BusinessException("Use a JPG, PNG or WEBP image.",
                    HttpStatus.BAD_REQUEST, "INVALID_FILE_EXTENSION");
        }
        if (!mimeMatchesExtension(mime.toLowerCase(Locale.ROOT), extension)) {
            throw new BusinessException("The file extension does not match its content type.",
                    HttpStatus.BAD_REQUEST, "FILE_TYPE_MISMATCH");
        }
        if (!hasImageMagicBytes(file)) {
            throw new BusinessException("That file is not a valid image.",
                    HttpStatus.BAD_REQUEST, "INVALID_IMAGE_CONTENT");
        }
    }

    /** The leading bytes must be a known image signature, whatever the name says. */
    private boolean hasImageMagicBytes(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            byte[] head = in.readNBytes(12);
            if (head.length < 12) return false;
            if ((head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8 && (head[2] & 0xFF) == 0xFF) return true;
            if ((head[0] & 0xFF) == 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G') return true;
            return head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F'
                && head[8] == 'W' && head[9] == 'E' && head[10] == 'B' && head[11] == 'P';
        } catch (IOException e) {
            log.warn("Could not read image header: {}", e.getMessage());
            return false;
        }
    }

    private boolean mimeMatchesExtension(String mime, String extension) {
        return switch (mime) {
            case "image/jpeg" -> List.of("jpg", "jpeg").contains(extension);
            case "image/png"  -> "png".equals(extension);
            case "image/webp" -> "webp".equals(extension);
            default           -> false;
        };
    }

    private String extensionOf(String filename) {
        if (filename == null) return null;
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) return null;
        return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
