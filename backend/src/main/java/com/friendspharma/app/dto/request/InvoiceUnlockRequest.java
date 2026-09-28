package com.friendspharma.app.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * A supervisor standing at the till, permitting one correction.
 *
 * <p>The credentials are verified and discarded. They are never stored, never
 * logged, and never returned — only the fact that this supervisor authorised
 * this invoice is kept.
 */
@Data
public class InvoiceUnlockRequest {

    @NotBlank(message = "The supervisor's username is required")
    private String username;

    @NotBlank(message = "The supervisor's password is required")
    private String password;

    /**
     * Why the invoice is being reopened.
     *
     * <p>Not required by the server, because a required field on a dialog
     * somebody is holding up a queue to fill in becomes "correction" every
     * time. The screen asks for it; the audit records whatever was given.
     */
    private String reason;
}
