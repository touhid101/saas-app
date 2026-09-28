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
import com.friendspharma.app.repository.AllBranchRepository;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.file.*;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Disk storage for product images.
 *
 * <p>The upload directory is configuration-driven ({@code app.upload.product-image-dir}),
 * never a literal in the source. A relative value is anchored to the user home,
 * matching how {@code WebMvcConfig} already resolves the avatar directory, so a
 * dev box lands on {@code <user-home>/uploads/product_image} while production
 * points the same key at a server volume.
 *
 * <p>A product's image is named after the product — {@code 201.png} — so the
 * upload directory can be read on its own, and one product keeps one file rather
 * than leaving an orphan behind every time its picture is replaced. Copies taken
 * from the catalogue are the exception: at the moment a catalogue entry is copied
 * the new product has no id yet, so those keep a generated name.
 *
 * <p>Each pharmacy keeps its pictures in its own folder, named after the branch
 * id: {@code product_image/7/201.png}, stored in the column as {@code 7/201.png}.
 * The catalogue's own pictures stay at the root, because they belong to no
 * pharmacy. A stored name is therefore either a bare filename or exactly one
 * numeric directory followed by a bare filename — {@link #isSuspicious} admits
 * nothing else, so the folder can never be steered anywhere but a branch's own.
 * Names written before the folders existed stay readable where they are; a
 * pharmacy's next upload lands in its folder and removes the old file.
 * The folder is created when a branch is created (and, best-effort, for every
 * branch at startup), and again on demand before a write, so a folder removed
 * by hand costs nothing but a log line.
 *
 * <p>Security posture — the original filename is treated as hostile:
 * <ul>
 *   <li>no part of the stored name comes from the client: the id is one the
 *       database issued and the extension comes from the whitelist below, so
 *       path traversal ({@code ../../etc/passwd}) and null-byte tricks have
 *       nothing to act on;</li>
 *   <li>the extension is taken from a whitelist, not from the client string;</li>
 *   <li>the declared MIME type and the extension must agree, and the file's
 *       magic bytes must match — a {@code .png} that is really a JAR is rejected;</li>
 *   <li>the resolved path is re-checked to be inside the configured root before
 *       any write, which catches configuration mistakes as well as attacks.</li>
 * </ul>
 */
@Slf4j
@Service
public class ProductImageStorageService {

    /** Extensions we will write to disk. Anything else is rejected outright. */
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp", "gif");

    /** MIME types accepted from the client, each mapped to its canonical extension. */
    private static final Set<String> ALLOWED_MIME_TYPES = Set.of(
            "image/jpeg", "image/png", "image/webp", "image/gif");

    @Value("${app.upload.product-image-dir:uploads/product_image}")
    private String productImageDir;

    @Value("${app.upload.product-image-url-prefix:/uploads/product_image/}")
    private String urlPrefix;

    @Value("${app.upload.max-size-bytes:2097152}")
    private long maxSizeBytes;

    /**
     * The one shape a stored name may take beyond a bare filename: a branch id
     * (digits only) and a filename with no separator of its own.
     */
    private static final Pattern BRANCH_NAME = Pattern.compile("^(\\d{1,18})/([^/\\\\]+)$");

    /**
     * Resolved lazily rather than injected outright: the startup pass over the
     * branches is a courtesy, and this service must construct even where no
     * branch repository is wired (tests, tools).
     */
    private final ObjectProvider<AllBranchRepository> branches;

    public ProductImageStorageService(ObjectProvider<AllBranchRepository> branches) {
        this.branches = branches;
    }

    /**
     * Absolute upload root. Relative configuration is anchored to the user home,
     * mirroring WebMvcConfig.resolvedAvatarPath().
     */
    public Path resolveRoot() {
        Path configured = Paths.get(productImageDir);
        return (configured.isAbsolute() ? configured
                                        : Paths.get(System.getProperty("user.home"), productImageDir))
                .toAbsolutePath().normalize();
    }

    @PostConstruct
    void ensureDirectoryExists() {
        try {
            Files.createDirectories(resolveRoot());
            log.info("Product image upload directory ready: {}", resolveRoot());
        } catch (IOException e) {
            log.warn("Could not pre-create product image directory {}: {}", resolveRoot(), e.getMessage());
        }
        // Every pharmacy that already exists gets its folder now, so the layout
        // on disk reads the same for a branch created before this convention as
        // for one created after. Best-effort, like the root above.
        try {
            AllBranchRepository repo = branches.getIfAvailable();
            if (repo != null) {
                int made = 0;
                for (Long id : repo.findAllBranchIds()) {
                    if (id != null && ensureBranchDirectory(id) != null) made++;
                }
                log.info("Product image folders ready for {} branches", made);
            }
        } catch (RuntimeException e) {
            log.warn("Could not pre-create the branch image folders: {}", e.getMessage());
        }
    }

    /** The folder a pharmacy's pictures live in: {@code <root>/<branchId>}. */
    public Path branchDirectory(Long branchId) {
        return resolveRoot().resolve(String.valueOf(branchId)).normalize();
    }

    /**
     * Creates a pharmacy's picture folder if it is not there. Called when a
     * branch is created, at startup for the branches that already exist, and
     * before every write, so the folder is never assumed.
     *
     * <p>Best-effort: returns the folder, or null when it could not be made.
     * A branch is not refused because a directory could not be created — the
     * upload that later needs it will report the failure at the point it
     * matters.
     */
    public Path ensureBranchDirectory(Long branchId) {
        if (branchId == null || branchId < 0) return null;
        Path dir = branchDirectory(branchId);
        if (!dir.startsWith(resolveRoot())) return null;
        try {
            Files.createDirectories(dir);
            return dir;
        } catch (IOException e) {
            log.warn("Could not create product image folder for branch {}: {}", branchId, e.getMessage());
            return null;
        }
    }

    /**
     * The file a stored name points at, or null when the name is not one this
     * service would have written or it resolves outside the root.
     */
    private Path resolveStored(String storedName) {
        if (isSuspicious(storedName)) return null;
        Path root   = resolveRoot();
        Path target = root.resolve(storedName).normalize();
        return target.startsWith(root) ? target : null;
    }

    /** The name a pharmacy's file is stored under: {@code 7/201.png}, or bare at the root. */
    private static String inBranch(Long branchId, String fileName) {
        return branchId == null ? fileName : branchId + "/" + fileName;
    }

    /**
     * Validates and stores an upload at the root — the catalogue's own pictures.
     *
     * @return the generated filename to persist (a bare name)
     */
    public String store(MultipartFile file, Long productId) {
        return store(file, productId, null);
    }

    /**
     * Validates and stores an upload in a pharmacy's folder.
     *
     * @param branchId the pharmacy the product belongs to; null stores at the root
     * @return the name to persist in POS_PRODUCT_INFO.PRODUCT_IMAGE —
     *         {@code <branchId>/<productId>.<ext>}, or a bare name at the root
     */
    public String store(MultipartFile file, Long productId, Long branchId) {
        validate(file);

        String extension = resolveExtension(file);

        /* Named for the product it belongs to: 201.png, not prd_201_9f3c…png.
           The upload directory is then readable on its own — a file can be
           matched to a product without the database — and it matches how the
           existing product data already names its images.

           Still nothing from the client in the name: the id is a Long the
           database issued, and the extension comes from the whitelist rather
           than from the uploaded filename, so there is no traversal or
           null-byte to defend against.

           One file per product, so replacing an image overwrites rather than
           accumulating orphans — which is why toPublicUrl stamps the URL with
           the file's timestamp; without it a browser would keep showing the
           picture it cached under the same name. */
        String storedName = inBranch(branchId, productId + "." + extension);

        // Defence in depth: even though storedName is generated, confirm the
        // resolved path did not escape the configured root — and that the name
        // is one isSuspicious will let the readers follow later.
        Path target = resolveStored(storedName);
        if (target == null) {
            throw new BusinessException("Invalid upload path.", HttpStatus.BAD_REQUEST, "INVALID_UPLOAD_PATH");
        }

        try {
            Files.createDirectories(target.getParent());
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
            log.info("Stored product image for product {}: {}", productId, storedName);
            return storedName;
        } catch (IOException e) {
            log.error("Failed to store product image for product {}", productId, e);
            throw new BusinessException("Failed to save the product image. Please try again.",
                    HttpStatus.INTERNAL_SERVER_ERROR, "IMAGE_UPLOAD_FAILED");
        }
    }

    /**
     * Deletes a previously stored image. Best-effort: a failure here must never
     * fail the surrounding business transaction, because the DB row has already
     * stopped pointing at the file and an orphaned file is harmless.
     */
    public void delete(String storedName) {
        if (storedName == null || storedName.isBlank()) return;

        // A stored name is a bare filename or one branch folder deep. Anything
        // else means the column was tampered with; refuse rather than follow it.
        Path target = resolveStored(storedName);
        if (target == null) {
            log.warn("Refusing to delete suspicious product image reference: {}", storedName);
            return;
        }
        try {
            Files.deleteIfExists(target);
        } catch (IOException e) {
            log.warn("Could not delete product image {}: {}", storedName, e.getMessage());
        }
    }

    /**
     * Duplicates a stored image and returns the new filename.
     *
     * <p>Used when a catalogue entry is imported into a pharmacy. The obvious
     * alternative — copy the filename and let both rows point at one file — is
     * unsafe here: {@code uploadImage} and {@code deleteImage} both delete the
     * previous file, so the first pharmacy to change its picture would delete the
     * catalogue's, and every other pharmacy that imported the same medicine would
     * be left with a broken image. A pharmacy owns its copy outright, exactly as
     * it owns its own price and its own stock.
     *
     * <p>Best-effort: an image that cannot be copied returns null and the product
     * still imports. A missing picture is not a reason to fail an import.
     */
    public String copyOf(String storedName) {
        return copyOf(storedName, null);
    }

    /**
     * {@link #copyOf(String)}, with the copy placed in the importing pharmacy's
     * folder: {@code <branchId>/<uuid>.<ext>}. Null lands it at the root.
     */
    public String copyOf(String storedName, Long branchId) {
        if (storedName == null || storedName.isBlank()) return null;

        Path source = resolveStored(storedName);
        if (source == null) {
            log.warn("Refusing to copy suspicious product image reference: {}", storedName);
            return null;
        }
        try {
            if (!Files.exists(source)) {
                log.warn("Catalogue image {} is missing on disk; importing without it.", storedName);
                return null;
            }

            int dot = storedName.lastIndexOf('.');
            String extension = dot > -1 ? storedName.substring(dot) : "";
            String copyName  = inBranch(branchId, UUID.randomUUID() + extension.toLowerCase(Locale.ROOT));

            Path copy = resolveStored(copyName);
            if (copy == null) return null;
            Files.createDirectories(copy.getParent());
            Files.copy(source, copy, StandardCopyOption.REPLACE_EXISTING);
            return copyName;
        } catch (IOException e) {
            log.warn("Could not copy product image {}: {}", storedName, e.getMessage());
            return null;
        }
    }

    /**
     * Duplicates a stored image under the name a product's own picture would
     * have — {@code 4821.png} — and returns that name.
     *
     * <p>Same reasoning as {@link #copyOf(String)}: a pharmacy owns its copy
     * outright, because uploading or deleting a picture removes the previous
     * file and would otherwise take the catalogue's with it. This variant exists
     * for the backfill, where the product id is already known, so the copy lands
     * on the naming convention the rest of the application uses rather than on a
     * generated one.
     *
     * <p>Returns the name unchanged when the file already <em>is</em> this
     * product's — a directory of pictures named after the products they belong
     * to needs no copying at all, only the column filled in.
     *
     * <p>Best-effort: anything missing or unreadable returns null, and the
     * caller leaves that product without a picture.
     */
    public String copyAs(String storedName, Long productId) {
        return copyAs(storedName, productId, null);
    }

    /**
     * {@link #copyAs(String, Long)}, with the copy placed in the pharmacy's
     * folder: {@code <branchId>/<productId>.<ext>}. Null lands it at the root.
     */
    public String copyAs(String storedName, Long productId, Long branchId) {
        if (storedName == null || storedName.isBlank() || productId == null) return null;

        if (isSuspicious(storedName)) {
            log.warn("Refusing to copy suspicious product image reference: {}", storedName);
            return null;
        }

        int dot = storedName.lastIndexOf('.');
        String extension = dot > -1 ? storedName.substring(dot + 1).toLowerCase(Locale.ROOT) : null;
        if (extension == null || !ALLOWED_EXTENSIONS.contains(extension)) {
            log.warn("Not copying {}: {} is not an image extension we serve.", storedName, extension);
            return null;
        }

        String target = inBranch(branchId, productId + "." + extension);
        if (target.equalsIgnoreCase(storedName)) return storedName;

        try {
            Path source = resolveStored(storedName);
            Path copy   = resolveStored(target);
            if (source == null || copy == null) return null;
            if (!Files.exists(source)) {
                log.warn("Source image {} is missing on disk; product {} keeps no picture.", storedName, productId);
                return null;
            }
            Files.createDirectories(copy.getParent());
            Files.copy(source, copy, StandardCopyOption.REPLACE_EXISTING);
            return target;
        } catch (IOException e) {
            log.warn("Could not copy {} for product {}: {}", storedName, productId, e.getMessage());
            return null;
        }
    }

    /**
     * Public URL for a stored filename, or null when there is no image.
     *
     * <p>Stamped with the file's last-modified time. Now that an image is named
     * after its product, replacing one reuses the same URL — and a browser that
     * cached the old picture would go on showing it, which looks exactly like an
     * upload that silently failed. The stamp changes when the bytes do, so the
     * new picture is fetched and an unchanged one still comes from the cache.
     *
     * <p>A file that cannot be stat'd yields the bare URL rather than an error:
     * a missing timestamp is a caching detail, not a reason to fail a product
     * list.
     */
    public String toPublicUrl(String storedName) {
        if (storedName == null || storedName.isBlank()) return null;

        String base = urlPrefix.endsWith("/") ? urlPrefix + storedName : urlPrefix + "/" + storedName;

        try {
            // A branch folder is part of the URL, so /uploads/product_image/7/201.png
            // is served by the same handler (its pattern ends in /**).
            Path target = resolveStored(storedName);
            if (target != null && Files.exists(target)) {
                return base + "?v=" + Files.getLastModifiedTime(target).toMillis();
            }
        } catch (IOException | RuntimeException e) {
            // Best effort — see the note above.
        }
        return base;
    }

    /**
     * True for a stored name this service would never have written: anything
     * but a bare filename or one numeric branch folder over a bare filename.
     *
     * <p>Every name this service writes is generated, so a backslash, a
     * {@code ..}, a second folder or a folder that is not a branch id means the
     * column was edited by hand or tampered with. The callers refuse rather
     * than follow it.
     */
    public boolean isSuspicious(String storedName) {
        if (storedName == null || storedName.isBlank()) return true;
        if (storedName.indexOf('\\') >= 0 || storedName.contains("..")) return true;
        if (!storedName.contains("/")) return false;
        return !BRANCH_NAME.matcher(storedName).matches();
    }

    /* ── validation ──────────────────────────────────────────────────────── */

    private void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("Please select an image to upload.",
                    HttpStatus.BAD_REQUEST, "EMPTY_FILE");
        }
        if (file.getSize() > maxSizeBytes) {
            throw new BusinessException(
                    "Image size exceeds the allowed limit of " + (maxSizeBytes / 1024 / 1024) + " MB.",
                    HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE");
        }

        String mime = file.getContentType();
        if (mime == null || !ALLOWED_MIME_TYPES.contains(mime.toLowerCase(Locale.ROOT))) {
            throw new BusinessException("Invalid image format. Allowed: JPG, PNG, WEBP, GIF.",
                    HttpStatus.BAD_REQUEST, "INVALID_FILE_TYPE");
        }

        String extension = extensionOf(file.getOriginalFilename());
        if (extension == null || !ALLOWED_EXTENSIONS.contains(extension)) {
            throw new BusinessException("Invalid image format. Allowed: JPG, PNG, WEBP, GIF.",
                    HttpStatus.BAD_REQUEST, "INVALID_FILE_EXTENSION");
        }
        if (!mimeMatchesExtension(mime.toLowerCase(Locale.ROOT), extension)) {
            throw new BusinessException(
                    "The file extension does not match its content type.",
                    HttpStatus.BAD_REQUEST, "FILE_TYPE_MISMATCH");
        }
        if (!hasImageMagicBytes(file)) {
            throw new BusinessException(
                    "The uploaded file is not a valid image.",
                    HttpStatus.BAD_REQUEST, "INVALID_IMAGE_CONTENT");
        }
    }

    /**
     * Reads the leading bytes and confirms they are a known image signature.
     * This is what stops a renamed executable or script from being written to a
     * directory that is served over HTTP.
     */
    private boolean hasImageMagicBytes(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            byte[] head = in.readNBytes(12);
            if (head.length < 12) return false;

            // JPEG: FF D8 FF
            if ((head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8 && (head[2] & 0xFF) == 0xFF) return true;
            // PNG: 89 50 4E 47 0D 0A 1A 0A
            if ((head[0] & 0xFF) == 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G') return true;
            // GIF: "GIF87a" / "GIF89a"
            if (head[0] == 'G' && head[1] == 'I' && head[2] == 'F') return true;
            // WEBP: "RIFF" .... "WEBP"
            if (head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F'
                && head[8] == 'W' && head[9] == 'E' && head[10] == 'B' && head[11] == 'P') return true;

            return false;
        } catch (IOException e) {
            log.warn("Could not read upload header: {}", e.getMessage());
            return false;
        }
    }

    private boolean mimeMatchesExtension(String mime, String extension) {
        return switch (mime) {
            case "image/jpeg" -> List.of("jpg", "jpeg").contains(extension);
            case "image/png"  -> "png".equals(extension);
            case "image/webp" -> "webp".equals(extension);
            case "image/gif"  -> "gif".equals(extension);
            default           -> false;
        };
    }

    /** Canonical extension for the stored file, derived from the verified MIME type. */
    private String resolveExtension(MultipartFile file) {
        String extension = extensionOf(file.getOriginalFilename());
        return "jpeg".equals(extension) ? "jpg" : extension;
    }

    /**
     * Lowercased extension of a client-supplied filename, or null.
     * The value is only ever compared against the whitelist — it is never used
     * to build the path we write to.
     */
    private String extensionOf(String originalFilename) {
        if (originalFilename == null) return null;
        // Strip any directory component a client may have sent (IE-style full paths).
        String name = Paths.get(originalFilename.replace('\\', '/')).getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return null;
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
