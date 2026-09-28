package com.friendspharma.app.exception.global;

import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.friendspharma.app.api.ApiResponse;
import com.friendspharma.app.exception.custom.BusinessException;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.util.HashMap;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Global exception handler — converts every exception to the ApiResponse envelope.
 * Never returns generic "Something went wrong" — always a specific, actionable message.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /* ── Business / domain exceptions ── */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException ex) {
        log.warn("Business rule violation: {} [{}]", ex.getMessage(), ex.getErrorCode());
        return ResponseEntity
                .status(ex.getHttpStatus())
                .body(ApiResponse.error(ex.getMessage(), ex.getErrorCode()));
    }

    /* ── Bean validation (@Valid) ── */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleValidation(MethodArgumentNotValidException ex) {
        // LinkedHashMap: the message below is built from these in order, and a
        // message whose wording shuffles between identical requests is one nobody
        // can search the logs for.
        Map<String, String> errors = new LinkedHashMap<>();
        ex.getBindingResult().getAllErrors().forEach(e -> {
            String field = e instanceof FieldError fe ? fe.getField() : e.getObjectName();
            errors.put(field, e.getDefaultMessage());
        });

        // The reasons were already collected, and then thrown away: the caller was
        // told to "correct the highlighted fields" by an API that highlights
        // nothing. Every constraint in this codebase carries a written message, so
        // say them. A screen that only shows the message now shows the reason.
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(
                        describe(errors),
                        "VALIDATION_FAILED",
                        errors
                ));
    }

    /**
     * One sentence naming what is actually wrong.
     *
     * <p>Capped at three: a form with eight empty fields produces a message nobody
     * reads, and the full set is in the response body for a client that wants to
     * mark each field individually.
     */
    private static String describe(Map<String, String> errors) {
        if (errors.isEmpty()) {
            return "Validation failed. Please correct the highlighted fields.";
        }
        List<String> reasons = errors.values().stream()
                .filter(m -> m != null && !m.isBlank())
                .distinct()
                .toList();
        if (reasons.isEmpty()) {
            return "Validation failed. Please correct the highlighted fields.";
        }

        String head = String.join(" ", reasons.subList(0, Math.min(3, reasons.size())).stream()
                .map(m -> m.endsWith(".") ? m : m + ".")
                .toList());
        int rest = reasons.size() - Math.min(3, reasons.size());
        return rest > 0 ? head + " (and " + rest + " more)" : head;
    }

    /* ── Constraint violations ── */
    /**
     * A request that is malformed rather than wrong.
     *
     * <p>These four all mean "the caller asked badly", and all four were falling
     * through to the catch-all and coming back as 500 with "an unexpected server
     * error has been logged". A missing query parameter is not a server fault
     * and reporting it as one sends whoever is integrating to read our logs
     * instead of their own request.
     *
     * <p>An unmapped URL is included: it was answering 500 as well, which is a
     * confusing way to say "no such endpoint".
     */
    @ExceptionHandler({
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            HttpRequestMethodNotSupportedException.class,
            NoResourceFoundException.class })
    public ResponseEntity<ApiResponse<Void>> handleBadRequest(Exception ex) {
        boolean notFound = ex instanceof NoResourceFoundException;
        HttpStatus status = notFound ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST;

        String message;
        if (ex instanceof MissingServletRequestParameterException e) {
            message = "'" + e.getParameterName() + "' is required.";
        } else if (ex instanceof MethodArgumentTypeMismatchException e) {
            message = "'" + e.getName() + "' is not in the expected format.";
        } else if (ex instanceof HttpRequestMethodNotSupportedException e) {
            String[] allowed = e.getSupportedMethods();
            message = e.getMethod() + " is not supported here."
                    + (allowed == null || allowed.length == 0 ? ""
                       : " Use " + String.join(" or ", allowed) + ".");
        } else {
            message = "No such endpoint.";
        }

        log.warn("Bad request: {}", message);
        return ResponseEntity.status(status).body(
                ApiResponse.error(message, notFound ? "NOT_FOUND" : "BAD_REQUEST"));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraint(ConstraintViolationException ex) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error("Constraint violation: " + ex.getMessage(), "CONSTRAINT_VIOLATION"));
    }

    /* ── Authentication / authorization ── */
    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiResponse<Void>> handleBadCredentials(BadCredentialsException ex) {
        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.error("Invalid username or password. Please check your credentials.", "BAD_CREDENTIALS"));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiResponse<Void>> handleAuth(AuthenticationException ex) {
        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.error("Authentication required. Please log in to continue.", "AUTHENTICATION_REQUIRED"));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccess(AccessDeniedException ex) {
        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error("You do not have permission to perform this action.", "ACCESS_DENIED"));
    }

    /* ── Database ── */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataIntegrity(DataIntegrityViolationException ex) {
        String msg = ex.getMostSpecificCause().getMessage();
        log.error("Data integrity violation: {}", msg);
        
        // Try to extract meaningful error messages from constraint violations
        String userMsg = "A database constraint was violated. Please verify your data and try again.";
        
        if (msg != null) {
            String lowerMsg = msg.toLowerCase();
            
            // Check for unique constraint violations
            if (lowerMsg.contains("unique") || lowerMsg.contains("uk_") || lowerMsg.contains("duplicate")) {
                if (lowerMsg.contains("group_name") || lowerMsg.contains("groupname")) {
                    userMsg = "A role with this name already exists. Please use a different name.";
                } else if (lowerMsg.contains("role_code") || lowerMsg.contains("group_code") || lowerMsg.contains("rolecode")) {
                    userMsg = "A role with this code already exists. Please use a different code.";
                } else if (lowerMsg.contains("module_code") || lowerMsg.contains("modulecode")) {
                    userMsg = "A module with this code already exists. Please use a different code.";
                } else if (lowerMsg.contains("module_name") || lowerMsg.contains("modulename")) {
                    userMsg = "A module with this name already exists. Please use a different name.";
                } else if (lowerMsg.contains("username")) {
                    userMsg = "This username is already taken. Please choose a different username.";
                } else {
                    userMsg = "A record with the same unique identifier already exists. Please check for duplicates.";
                }
            }
            // Check for foreign key violations
            else if (lowerMsg.contains("foreign key") || lowerMsg.contains("fk_")) {
                userMsg = foreignKeyMessage(msg);
            }
            // Check for not null violations
            else if (lowerMsg.contains("not null") || lowerMsg.contains("null value")) {
                // Postgres names the column: null value in column "customer_type".
                // Saying which one turns "something is missing" into something the
                // person filling the form can act on.
                String column = between(msg, "null value in column \"", "\"");
                userMsg = column == null
                        ? "Required field is missing. Please provide all mandatory fields."
                        : readable(column) + " is required.";
            }
            // A CHECK constraint — a value that is present but not one of the
            // permitted ones. This fell through to "a database constraint was
            // violated", which tells the person filling the form nothing at all.
            else if (lowerMsg.contains("check constraint")) {
                String constraint = between(msg, "violates check constraint \"", "\"");
                userMsg = explainCheck(constraint);
            }
        }
        
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(userMsg, "DATA_INTEGRITY_VIOLATION"));
    }

    /**
     * A foreign-key failure, said in terms of the records involved.
     *
     * <p>Postgres already names the constraint and the key in its detail —
     * {@code Key (pid_supplier)=(901) is not present in table "pos_supplier_info"}
     * or {@code Key (pid_stock)=(12) is still referenced from table "pos_stock_movement"}.
     * Repeating that costs nothing and turns "referenced record does not exist"
     * into something the person can act on and support can search for. The
     * table names are the schema's own; they are internal, but a generic
     * sentence hid the cause entirely.
     */
    static String foreignKeyMessage(String msg) {
        String constraint = between(msg, "constraint \"", "\"");
        String column = between(msg, "Key (", ")");
        String value = column == null ? null : between(msg.substring(msg.indexOf("Key (")), "=(", ")");
        String missingIn = between(msg, "is not present in table \"", "\"");
        String usedBy = between(msg, "is still referenced from table \"", "\"");
        String what = column == null ? "a record" : readable(column.replaceFirst("^pid_", "")).toLowerCase() + (value == null ? "" : " " + value);
        String text;
        if (missingIn != null) {
            text = "Cannot complete operation: it refers to " + what + " that does not exist in " + missingIn + ".";
        } else if (usedBy != null) {
            text = "Cannot complete operation: " + what + " is still used by records in " + usedBy + " and cannot be removed.";
        } else {
            text = "Cannot complete operation: referenced record does not exist or is in use by other records.";
        }
        return constraint == null ? text : text + " (" + constraint + ")";
    }

    /** The text between two markers, or null when it is not there. */
    private static String between(String s, String open, String close) {
        int a = s.indexOf(open);
        if (a < 0) return null;
        int b = s.indexOf(close, a + open.length());
        return b < 0 ? null : s.substring(a + open.length(), b);
    }

    /** {@code customer_type} → {@code Customer type}. */
    private static String readable(String column) {
        String words = column.replace('_', ' ').trim();
        return words.isEmpty() ? column
                : Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    /**
     * What a named CHECK constraint means, in the words of the person who hit it.
     *
     * <p>Only the ones somebody can actually trip from a form are listed. The rest
     * fall back to naming the constraint, which is still better than not saying
     * which rule was broken — it can be searched for.
     *
     * <p>Where a constraint lists permitted values, the message lists them: being
     * told a value is wrong without being told what is right leaves the person
     * guessing at a dropdown.
     */
    private static String explainCheck(String constraint) {
        if (constraint == null) {
            return "That value is not one this field allows. Please choose a different one.";
        }
        return switch (constraint) {
            case "ck_customer_type" ->
                "Customer type must be Retailer, Wholesaler, Hospital, Pharmacy, Clinic or Special.";
            case "ck_hr_employees_emp_type" ->
                "That is not a valid employee type.";
            case "ck_movement_qty", "ck_tran_dtl_quantity" ->
                "Quantity must be greater than zero.";
            case "ck_stock_qty_positive" ->
                "Stock quantity cannot be negative.";
            case "ck_product_prices_nonneg" ->
                "Prices cannot be negative.";
            case "ck_product_pct_range", "ck_product_sales_pct_range", "ck_tran_dtl_pct_range" ->
                "Percentages must be between 0 and 100.";
            case "ck_product_stock_ladder" ->
                "Stock levels must run in order: safety stock, then minimum, then reorder level, then maximum.";
            case "ck_sub_dates" ->
                "A subscription cannot expire before it starts.";
            default ->
                "That value breaks the rule '" + constraint + "'. Please check the field and try again.";
        };
    }

    /* ── Malformed / unreadable request body ──
       Without this, any unparseable JSON fell through to the catch-all and
       returned 500, which reads as a server fault for what is a client error.

       Unknown properties are rejected rather than ignored: JacksonConfig
       supplies a raw ObjectMapper, so FAIL_ON_UNKNOWN_PROPERTIES is on. For
       Product Master that is the behaviour we want — posting a server-owned
       field such as tpPrice is refused loudly instead of silently dropped. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadable(HttpMessageNotReadableException ex) {
        Throwable cause = ex.getCause();

        if (cause instanceof UnrecognizedPropertyException upe) {
            String field = upe.getPropertyName();
            log.warn("Rejected request containing unknown field '{}' for {}",
                    field, upe.getReferringClass() != null ? upe.getReferringClass().getSimpleName() : "request");
            return ResponseEntity
                    .status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(
                            "'" + field + "' is not an accepted field for this request. "
                            + "Calculated and system-generated values cannot be supplied by the client.",
                            "UNKNOWN_FIELD"));
        }

        if (cause instanceof InvalidFormatException ife) {
            String field = ife.getPath().isEmpty() ? "a field"
                    : ife.getPath().get(ife.getPath().size() - 1).getFieldName();
            return ResponseEntity
                    .status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(
                            "'" + field + "' has an invalid value: " + ife.getValue() + ".",
                            "INVALID_FIELD_FORMAT"));
        }

        log.warn("Unreadable request body: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(
                        "The request body could not be read. Please check that it is valid JSON.",
                        "MALFORMED_REQUEST"));
    }

    /* ── File upload ── */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleMaxUpload(MaxUploadSizeExceededException ex) {
        return ResponseEntity
                .status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(ApiResponse.error("Uploaded file is too large. Maximum allowed size is 2 MB.", "FILE_TOO_LARGE"));
    }

    /**
     * A multipart request that arrived without the file part.
     *
     * <p>Without this it falls to the catch-all and answers 500, which says the
     * server broke when in fact the request was incomplete. A client cannot tell
     * "retry later" from "you forgot the file" out of a 500, and an upload form
     * with a bug would look like an outage.
     */
    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingPart(MissingServletRequestPartException ex) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error("No file was included in the upload.", "MISSING_FILE"));
    }

    /* ── Catch-all ── */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneric(Exception ex) {
        log.error("Unexpected error occurred", ex);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(
                        "An unexpected server error occurred. The issue has been logged. Please contact support if it persists.",
                        "INTERNAL_SERVER_ERROR"
                ));
    }
}
