package com.friendspharma.app.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Serves uploaded avatar files as static resources.
 *
 * Path resolution:
 *   - absolute path in app.upload.avatar-dir → used as-is
 *   - relative path → anchored to System.getProperty("user.home")
 *     e.g. "uploads/avatars" → "C:/Users/Touhid/uploads/avatars"
 *
 * File is accessible at http://localhost:8080/uploads/avatars/{filename}
 */
@Slf4j
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Value("${app.upload.avatar-dir:uploads/avatars}")
    private String avatarDir;

    @Value("${app.upload.avatar-url-prefix:/uploads/avatars/}")
    private String avatarUrlPrefix;

    @Value("${app.upload.product-image-dir:uploads/product_image}")
    private String productImageDir;

    @Value("${app.upload.product-image-url-prefix:/uploads/product_image/}")
    private String productImageUrlPrefix;

    @Value("${app.upload.logo-dir:uploads/logo}")
    private String logoDir;

    @Value("${app.upload.logo-url-prefix:/uploads/logo/}")
    private String logoUrlPrefix;

    @Value("${app.upload.employee-image-dir:uploads/employee_image}")
    private String employeeImageDir;

    @Value("${app.upload.employee-image-url-prefix:/uploads/employee_image/}")
    private String employeeImageUrlPrefix;

    @Value("${app.upload.expense-receipt-dir:uploads/expense_receipt}")
    private String expenseReceiptDir;

    @Value("${app.upload.expense-receipt-url-prefix:/uploads/expense_receipt/}")
    private String expenseReceiptUrlPrefix;

    /* [V109] Photographs taken at a customer's door. */
    @Value("${app.upload.delivery-proof-dir:uploads/delivery_proof}")
    private String deliveryProofDir;

    @Value("${app.upload.delivery-proof-url-prefix:/uploads/delivery_proof/}")
    private String deliveryProofUrlPrefix;

    /**
     * Resolve the configured path to an absolute Path using the same
     * logic as PermissionServiceImpl.uploadUserAvatar().
     */
    public Path resolvedAvatarPath() {
        return resolve(avatarDir);
    }

    /** Same resolution rules for product images — see ProductImageStorageService. */
    public Path resolvedProductImagePath() {
        return resolve(productImageDir);
    }

    /** Same again for branch logos — see BranchLogoStorageService. */
    public Path resolvedLogoPath() {
        return resolve(logoDir);
    }

    /** Receipts on expense claims — see ImageStorageService.Bucket.EXPENSE_RECEIPT. */
    Path resolvedExpenseReceiptPath() {
        return resolve(expenseReceiptDir);
    }

    /** Proof of delivery — see ImageStorageService.Bucket.DELIVERY_PROOF. */
    Path resolvedDeliveryProofPath() {
        return resolve(deliveryProofDir);
    }

    /** And for staff photographs — see ImageStorageService.Bucket.EMPLOYEE_IMAGE. */
    public Path resolvedEmployeeImagePath() {
        return resolve(employeeImageDir);
    }

    private Path resolve(String configured) {
        Path path = Paths.get(configured);
        return (path.isAbsolute() ? path : Paths.get(System.getProperty("user.home"), configured))
                .toAbsolutePath().normalize();
    }

    /**
     * Turn a URL prefix into the pattern that serves it: "/uploads/logo/"
     * becomes "/uploads/logo/**".
     *
     * <p>Shared by all three handlers so the URL the server listens on cannot
     * drift from the URL the prefix names. They are the same setting, and the
     * one failure mode worth designing out is a prefix that changes the address
     * the browser is given without changing the address the server answers.
     */
    private static String pattern(String urlPrefix) {
        return urlPrefix.endsWith("/") ? urlPrefix + "**" : urlPrefix + "/**";
    }

    /** Create the upload directories on startup so the first upload never fails. */
    @PostConstruct
    public void ensureUploadDirExists() {
        createDir(resolvedAvatarPath(), "Avatar");
        createDir(resolvedProductImagePath(), "Product image");
        createDir(resolvedLogoPath(), "Branch logo");
        createDir(resolvedEmployeeImagePath(), "Employee image");
        createDir(resolvedExpenseReceiptPath(), "Expense receipt");
        createDir(resolvedDeliveryProofPath(), "Delivery proof");

        /* Printed together, because the question people actually have is "where
           did my upload go" and the answer is three paths that are otherwise
           scattered through the startup log. Naming the variable beside each one
           means the fix is in the message rather than in the documentation. */
        log.info("Upload directories (change with UPLOAD_ROOT, or per folder):");
        log.info("  avatars       {}  [UPLOAD_AVATAR_DIR]", resolvedAvatarPath());
        log.info("  branch logos  {}  [UPLOAD_LOGO_DIR]", resolvedLogoPath());
        log.info("  product image {}  [UPLOAD_PRODUCT_IMAGE_DIR]", resolvedProductImagePath());
        log.info("  employee image {}  [UPLOAD_EMPLOYEE_IMAGE_DIR]", resolvedEmployeeImagePath());
        log.info("  expense receipt {}  [UPLOAD_EXPENSE_RECEIPT_DIR]", resolvedExpenseReceiptPath());
        log.info("  started in    {}", System.getProperty("user.dir"));
        logEnvFilesSeen();

        warnIfServedOutsideUploads();
    }

    /**
     * Report which .env files were actually in scope.
     *
     * <p>"./" in spring.config.import means the working directory, which differs
     * between `mvn spring-boot:run` in backend/ and an IDE launching from the
     * project root. Get it wrong and the defaults apply silently — the paths
     * above are simply the old ones, with nothing explaining why. Naming the
     * files that were read turns that into a one-line answer.
     */
    private void logEnvFilesSeen() {
        String[] candidates = {".env", "backend/.env"};
        boolean any = false;
        for (String candidate : candidates) {
            Path file = Paths.get(candidate).toAbsolutePath().normalize();
            if (!Files.isRegularFile(file)) continue;
            any = true;
            log.info("  read          {}{}", file,
                    mentionsUpload(file) ? "" : "  (sets nothing about uploads)");
        }
        if (!any) {
            log.info("  read          no .env found - built-in defaults in use");
        }
    }

    private boolean mentionsUpload(Path file) {
        try {
            return Files.readAllLines(file).stream()
                    .anyMatch(line -> !line.trim().startsWith("#") && line.contains("UPLOAD_"));
        } catch (IOException ex) {
            return false;
        }
    }

    /**
     * The directories can point anywhere. The URLs cannot.
     *
     * <p>Two allowlists let images through unauthenticated by literal prefix —
     * SecurityConfig permits "/uploads/**", and TenantStatusFilter exempts
     * "/uploads/" from the subscription check. Move a URL prefix outside that
     * tree and the files are still served, but every image request needs a token
     * the browser does not send on an &lt;img&gt; tag, so the pictures vanish and
     * the log fills with 403s that look like nothing to do with configuration.
     *
     * <p>Warned rather than fatal: a wrong prefix breaks pictures, and refusing
     * to start over pictures would be the worse trade.
     */
    private void warnIfServedOutsideUploads() {
        warnIfOutside(avatarUrlPrefix, "UPLOAD_AVATAR_URL_PREFIX");
        warnIfOutside(logoUrlPrefix, "UPLOAD_LOGO_URL_PREFIX");
        warnIfOutside(productImageUrlPrefix, "UPLOAD_PRODUCT_IMAGE_URL_PREFIX");
        warnIfOutside(employeeImageUrlPrefix, "UPLOAD_EMPLOYEE_IMAGE_URL_PREFIX");
        warnIfOutside(expenseReceiptUrlPrefix, "UPLOAD_EXPENSE_RECEIPT_URL_PREFIX");
        warnIfOutside(deliveryProofUrlPrefix, "UPLOAD_DELIVERY_PROOF_URL_PREFIX");
    }

    private void warnIfOutside(String urlPrefix, String variable) {
        if (urlPrefix.startsWith("/uploads/")) return;
        log.warn("{}={} is outside /uploads/. Images there require a token the browser "
                + "will not send, so they will not load. Change the folder with the matching "
                + "*_DIR variable instead, or leave the URL under /uploads/.", variable, urlPrefix);
    }

    private void createDir(Path dir, String label) {
        try {
            Files.createDirectories(dir);
            log.info("{} upload directory ready: {}", label, dir);
        } catch (IOException ex) {
            log.warn("Could not pre-create {} upload directory: {}", label.toLowerCase(), ex.getMessage());
        }
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String avatarPattern = pattern(avatarUrlPrefix);
        registry.addResourceHandler(avatarPattern)
                .addResourceLocations(asLocation(resolvedAvatarPath()));
        log.info("Static resource handler: {} -> {}", avatarPattern, resolvedAvatarPath());

        // Product images are served read-only from the configured directory.
        // Filenames are generated server-side (ProductImageStorageService), so a
        // request can only ever reach a file this application wrote.
        String imagePattern = pattern(productImageUrlPrefix);
        registry.addResourceHandler(imagePattern)
                .addResourceLocations(asLocation(resolvedProductImagePath()))
                .setCachePeriod(3600);
        log.info("Static resource handler: {} -> {}", imagePattern, resolvedProductImagePath());

        // Logos are read by the browser on every page load, including the
        // favicon, so they are cached like the product images are.
        String logoPattern = pattern(logoUrlPrefix);
        registry.addResourceHandler(logoPattern)
                .addResourceLocations(asLocation(resolvedLogoPath()))
                .setCachePeriod(3600);
        log.info("Static resource handler: {} -> {}", logoPattern, resolvedLogoPath());

        // Staff photographs. Filenames are generated server-side, so a request
        // can only ever reach a file this application wrote — the same argument
        // as the product images above.
        String employeePattern = pattern(employeeImageUrlPrefix);
        registry.addResourceHandler(employeePattern)
                .addResourceLocations(asLocation(resolvedEmployeeImagePath()))
                .setCachePeriod(3600);
        log.info("Static resource handler: {} -> {}", employeePattern, resolvedEmployeeImagePath());

        // Receipts. Same argument again: filenames are generated server-side, so
        // a request can only reach a file this application wrote. Not cached as
        // long as the others — a receipt is looked at while a claim is being
        // decided and rarely afterwards.
        String receiptPattern = pattern(expenseReceiptUrlPrefix);
        registry.addResourceHandler(receiptPattern)
                .addResourceLocations(asLocation(resolvedExpenseReceiptPath()))
                .setCachePeriod(600);
        log.info("Static resource handler: {} -> {}", receiptPattern, resolvedExpenseReceiptPath());

        // Delivery photographs. Same argument once more — server-generated
        // filenames — and cached briefly: a customer looks at theirs when the
        // order arrives, and the branch when somebody disputes that it did.
        String proofPattern = pattern(deliveryProofUrlPrefix);
        registry.addResourceHandler(proofPattern)
                .addResourceLocations(asLocation(resolvedDeliveryProofPath()))
                .setCachePeriod(600);
        log.info("Static resource handler: {} -> {}", proofPattern, resolvedDeliveryProofPath());
    }

    private String asLocation(Path dir) {
        String uri = dir.toUri().toString();
        return uri.endsWith("/") ? uri : uri + "/";
    }
}
