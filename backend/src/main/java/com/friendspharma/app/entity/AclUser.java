package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Maps to ACL_USER table — the primary user authentication table.
 * Extended columns: PASSWORD_HASH, PID_ROLE, FULL_NAME, EMAIL, MOBILE_NO, AVATAR_URL, LAST_LOGIN.
 */
@Entity
@Table(name = "acl_user")
@NoArgsConstructor @AllArgsConstructor
public class AclUser extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "pid_user")
    private Long pidUser;

    @Column(name = "pid_org")
    private Long pidOrg;

    @Column(name = "pid_company")
    private Long pidCompany;

    @Column(name = "pid_branch")
    private Long pidBranch;

    @Column(name = "pid_area")
    private Long pidArea;

    @Column(name = "is_admin", length = 1)
    private String isAdmin;

    @Column(name = "user_name", length = 50, nullable = false, unique = true)
    private String userName;

    /** Original plaintext PIN — kept for legacy migration */
    @Column(name = "pin_no", length = 500)
    private String pinNo;

    /** [NEW] BCrypt password hash — used by Spring Security */
    @Column(name = "password_hash", length = 500)
    private String passwordHash;

    /** [NEW] FK to ACL_GROUP (role) */
    @Column(name = "pid_role")
    private Long pidRole;

    /**
     * The staff record this login belongs to, if any.
     *
     * <p>Required for a delivery man: an invoice records who it was dispatched
     * to as an employee id, so this is the only thing that lets a signed-in
     * driver be recognised as that person. Null for office logins, which have
     * no employee record to point at.
     */
    @Column(name = "pid_employee")
    private Long pidEmployee;

    /** [NEW] Display name */
    @Column(name = "full_name", length = 100)
    private String fullName;

    /** [NEW] Email */
    @Column(name = "email", length = 100)
    private String email;

    /** [NEW] Mobile */
    @Column(name = "mobile_no", length = 20)
    private String mobileNo;

    /** [NEW] Avatar image URL */
    @Column(name = "avatar_url", length = 500)
    private String avatarUrl;

    /** [NEW] Last login timestamp */
    @Column(name = "last_login")
    private LocalDateTime lastLogin;

    /**
     * [V71] "Y" while the account holds a password somebody else chose.
     *
     * <p>Set by a Super Admin reset and cleared only when the user sets one of
     * their own. Initialised to "N" rather than left null because the column is
     * NOT NULL: a default fires for an INSERT that omits the column, and an
     * entity that names every column never omits it — it sends the null.
     */
    @Column(name = "must_change_password", length = 1, nullable = false)
    private String mustChangePassword = "N";

    /** [V71] When the current password was set, by whoever set it. */
    @Column(name = "password_changed_at")
    private LocalDateTime passwordChangedAt;

    /** [V71] The Super Admin who last reset it, or null if the user set it themselves. */
    @Column(name = "password_reset_by")
    private Long passwordResetBy;

    /**
     * [V71] When the temporary password stops being usable.
     *
     * <p>A temporary password that never expires is a permanent one that nobody
     * chose. Null on an account whose password is the user's own.
     */
    @Column(name = "temp_password_expires")
    private LocalDateTime tempPasswordExpires;

    public String getMustChangePassword() { return mustChangePassword; }
    public void setMustChangePassword(String v) { this.mustChangePassword = v; }

    public LocalDateTime getPasswordChangedAt() { return passwordChangedAt; }
    public void setPasswordChangedAt(LocalDateTime v) { this.passwordChangedAt = v; }

    public Long getPasswordResetBy() { return passwordResetBy; }
    public void setPasswordResetBy(Long v) { this.passwordResetBy = v; }

    public LocalDateTime getTempPasswordExpires() { return tempPasswordExpires; }
    public void setTempPasswordExpires(LocalDateTime v) { this.tempPasswordExpires = v; }

    /** [V71] True when this account must set its own password before doing anything else. */
    public boolean mustChangePassword() { return "Y".equalsIgnoreCase(mustChangePassword); }

    @Lob
    @Column(name = "image")
    private byte[] image;

    @Column(name = "file_name", length = 256)
    private String fileName;

    @Column(name = "file_mime_type", length = 200)
    private String fileMimeType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pid_role", insertable = false, updatable = false)
    private AclGroup role;

    // ── Getters and Setters ──────────────────────────────────────
    public Long getPidUser() { return pidUser; }
    public void setPidUser(Long pidUser) { this.pidUser = pidUser; }

    public Long getPidOrg() { return pidOrg; }
    public void setPidOrg(Long pidOrg) { this.pidOrg = pidOrg; }

    public Long getPidCompany() { return pidCompany; }
    public void setPidCompany(Long pidCompany) { this.pidCompany = pidCompany; }

    public Long getPidBranch() { return pidBranch; }
    public void setPidBranch(Long pidBranch) { this.pidBranch = pidBranch; }

    public Long getPidArea() { return pidArea; }
    public void setPidArea(Long pidArea) { this.pidArea = pidArea; }

    public String getIsAdmin() { return isAdmin; }
    public void setIsAdmin(String isAdmin) { this.isAdmin = isAdmin; }

    public String getUserName() { return userName; }
    public void setUserName(String userName) { this.userName = userName; }

    public String getPinNo() { return pinNo; }
    public void setPinNo(String pinNo) { this.pinNo = pinNo; }

    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }

    public Long getPidRole() { return pidRole; }
    public void setPidRole(Long pidRole) { this.pidRole = pidRole; }

    public Long getPidEmployee() { return pidEmployee; }
    public void setPidEmployee(Long pidEmployee) { this.pidEmployee = pidEmployee; }

    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getMobileNo() { return mobileNo; }
    public void setMobileNo(String mobileNo) { this.mobileNo = mobileNo; }

    public String getAvatarUrl() { return avatarUrl; }
    public void setAvatarUrl(String avatarUrl) { this.avatarUrl = avatarUrl; }

    public LocalDateTime getLastLogin() { return lastLogin; }
    public void setLastLogin(LocalDateTime lastLogin) { this.lastLogin = lastLogin; }

    public byte[] getImage() { return image; }
    public void setImage(byte[] image) { this.image = image; }

    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }

    public String getFileMimeType() { return fileMimeType; }
    public void setFileMimeType(String fileMimeType) { this.fileMimeType = fileMimeType; }

    public AclGroup getRole() { return role; }
    public void setRole(AclGroup role) { this.role = role; }

    // ── Aliases for new service layer ──────────────────────────────────────
    public String  getUsername()             { return userName; }
    public void    setUsername(String v)     { this.userName = v; }
    public String  getPhoneNo()              { return mobileNo; }
    public void    setPhoneNo(String v)      { this.mobileNo = v; }
    public Long    getPidGroup()             { return pidRole; }
    public void    setPidGroup(Long v)       { this.pidRole = v; }
    public Integer getIsActive()             { return "Y".equals(getStatus()) ? 1 : 0; }
    public void    setIsActive(Integer v)    { setStatus(v == 1 ? "Y" : "N"); }
}
