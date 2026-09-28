package com.friendspharma.app.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Fields a user is allowed to edit on their own profile.
 * Deliberately excludes role, branch, org and active flag — those are
 * administrative concerns and stay behind the RBAC endpoints.
 */
@Data
public class UpdateProfileRequest {

    @NotBlank(message = "Full name is required")
    @Size(max = 100, message = "Full name must be 100 characters or fewer")
    private String fullName;

    @Email(message = "Please enter a valid email address")
    @Size(max = 100, message = "Email must be 100 characters or fewer")
    private String email;

    @Pattern(regexp = "^$|^[0-9+\\-\\s()]{6,20}$",
             message = "Please enter a valid phone number")
    private String mobileNo;

    /** Accepts {"phoneNo": ...} as an alias for {"mobileNo": ...}. */
    public void setPhoneNo(String phoneNo) {
        this.mobileNo = phoneNo;
    }
}
