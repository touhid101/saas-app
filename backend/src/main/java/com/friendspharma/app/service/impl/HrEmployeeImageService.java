package com.friendspharma.app.service.impl;

import com.friendspharma.app.entity.HrEmployee;
import com.friendspharma.app.exception.custom.BusinessException;
import com.friendspharma.app.repository.HrEmployeeRepository;
import com.friendspharma.app.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * The staff photograph.
 *
 * <p><b>It stores nothing itself.</b> Validation, naming, the path re-check and
 * the write all belong to {@link ImageStorageService}, which already does them
 * for avatars and branch logos. This class contributes the three things that
 * are specific to an employee: who is allowed to change whose picture, keeping
 * the row and the disk in step, and not leaving the old file behind.
 *
 * <h3>Who may change a photograph</h3>
 *
 * <p>Either somebody with Edit on Employees, or the person themselves. The
 * second case is what makes self-service possible without handing every
 * employee the right to edit the whole staff list — and it is checked against
 * {@code ACL_USER.PID_EMPLOYEE}, the link that already ties a login to a staff
 * record, rather than against anything the client sends.
 *
 * <h3>The old file goes after the row is updated, not before</h3>
 *
 * <p>Deleting first and then failing to save would leave a row pointing at a
 * file that no longer exists — a broken image on every screen that shows this
 * person. Deleting afterwards can at worst leave an orphaned file, which costs
 * disk and nothing else. {@link ImageStorageService#delete} is best-effort for
 * the same reason.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HrEmployeeImageService {

    private final HrEmployeeRepository employeeRepo;
    private final ImageStorageService  images;
    private final HrScope              scope;

    /**
     * Replaces an employee's photograph.
     *
     * @return the public URL now stored on the row
     */
    @Transactional
    public String upload(Long employeeId, MultipartFile file) {
        HrEmployee employee = requireEditable(employeeId);
        String previous = employee.getImagePath();

        String url = images.store(file, ImageStorageService.Bucket.EMPLOYEE_IMAGE, employeeId);

        employee.setImagePath(url);
        employeeRepo.save(employee);

        // Only now, and only if it is genuinely a different file.
        if (previous != null && !previous.equals(url)) {
            images.delete(ImageStorageService.Bucket.EMPLOYEE_IMAGE, previous);
        }
        log.info("Employee {} photograph updated by {}", employeeId, scope.actor());
        return url;
    }

    /** Removes the photograph. The screens then fall back to initials. */
    @Transactional
    public void remove(Long employeeId) {
        HrEmployee employee = requireEditable(employeeId);
        String previous = employee.getImagePath();
        if (previous == null) return;

        employee.setImagePath(null);
        employeeRepo.save(employee);
        images.delete(ImageStorageService.Bucket.EMPLOYEE_IMAGE, previous);
        log.info("Employee {} photograph removed by {}", employeeId, scope.actor());
    }

    /**
     * The employee, if this caller may change their picture.
     *
     * <p>{@link HrScope#employee} has already refused anybody outside the
     * session's branch — as a 404, so a caller at another pharmacy cannot even
     * learn that the employee exists.
     */
    private HrEmployee requireEditable(Long employeeId) {
        HrEmployee employee = scope.employee(employeeId);

        boolean isSelf = employeeId.equals(SecurityUtils.getCurrentEmployeeId());
        if (isSelf) return employee;

        scope.require(HrScope.MOD_EMPLOYEE, PermissionGuard.Right.EDIT,
                      "change another employee's photograph");
        return employee;
    }

    /**
     * What the screens should show for somebody with no photograph.
     *
     * <p>Returned alongside the URL so a client never has to decide between an
     * empty {@code src} and a broken-image icon — it has initials to fall back
     * on before the first request is made.
     */
    public static String initialsFor(HrEmployee employee) {
        return employee == null ? "?" : employee.getInitials();
    }

    /** Guards a request that arrived with no file at all. */
    public static void requireFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("Please choose an image to upload.",
                    HttpStatus.BAD_REQUEST, "EMPTY_FILE");
        }
    }
}
