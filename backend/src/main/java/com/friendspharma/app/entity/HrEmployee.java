package com.friendspharma.app.entity;

import com.friendspharma.app.entity.base.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity @Table(name = "HR_EMPLOYEES", schema = "PHARMA")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class HrEmployee extends BaseEntity {

    @Id @Column(name = "PID_EMPLOYEE")
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_emp")
    @SequenceGenerator(name = "seq_emp", sequenceName = "SEQ_HR_EMPLOYEES", allocationSize = 1)
    private Long pidEmployee;

    @Column(name = "PID_ORG", nullable = false)
    private Long pidOrg;

    @Column(name = "PID_COMPANY")
    private Long pidCompany;

    @Column(name = "PID_BRANCH")
    private Long pidBranch;

    @Column(name = "PID_DEPARTMENT")
    private Long pidDepartment;

    @Column(name = "PID_DESIGNATION")
    private Long pidDesignation;

    @Column(name = "EMP_ID", length = 20, nullable = false)
    private String empId;

    @Column(name = "EMP_NAME", length = 50, nullable = false)
    private String empName;

    @Column(name = "EMAIL", length = 50)
    private String email;

    @Column(name = "PHONE", length = 20)
    private String phone;

    @Column(name = "HIRE_DATE")
    private LocalDate hireDate;

    @Column(name = "ADDRESS", length = 500)
    private String address;

    @Column(name = "GENDER", length = 20)
    private String gender;

    @Lob @Column(name = "EMP_IMAGE")
    private byte[] empImage;

    @Column(name = "FILE_NAME", length = 256)
    private String fileName;

    @Column(name = "FILE_MIME_TYPE", length = 200)
    private String fileMimeType;

    /** Vehicle number. Only meaningful for {@code DELIVERY_MAN}. */
    @Column(name = "VEHICLE_NO", length = 20)
    private String vehicleNo;

    /**
     * What this employee does — see {@link EmployeeType}.
     *
     * <p>{@code DELIVERY_MAN} is the only value the application branches on: it
     * gates who may be assigned an invoice to deliver. Before this column
     * existed the delivery-man list was every active employee, so an invoice
     * could be dispatched to the accountant.
     */
    @Column(name = "EMP_TYPE", length = 20, nullable = false)
    @Builder.Default
    private String empType = EmployeeType.OTHER;

    /* ── [V73] What payroll needs ──────────────────────────────────────────
     *
     * All nullable, because the table already held staff records when these
     * arrived. A NOT NULL column on a populated table either fails the
     * migration or invents a value for six real people; the service asks for
     * what payroll needs at the point payroll needs it, which is a better place
     * to say so than a constraint firing during an upgrade.
     */

    /**
     * The one figure every salary structure is computed from.
     *
     * <p>Null is not zero here: an employee nobody has set a salary for is not
     * in payroll at all, which is a different thing from an employee who earns
     * nothing. The payroll calculator skips them and says so.
     */
    @Column(name = "BASIC_SALARY", precision = 18, scale = 2)
    private BigDecimal basicSalary;

    @Column(name = "PID_SALARY_STRUCTURE")
    private Long pidSalaryStructure;

    /**
     * The basis they are engaged on — permanent, probation, contract.
     *
     * <p>Not the same question as {@link #empType}, which is what they do. A
     * cashier can be on probation and a pharmacist on contract, so one column
     * cannot carry both. See {@link HrConstants.EmploymentType}.
     */
    @Column(name = "EMPLOYMENT_TYPE", length = 20)
    private String employmentType;

    @Column(name = "CONFIRMATION_DATE") private LocalDate confirmationDate;
    @Column(name = "DATE_OF_BIRTH")     private LocalDate dateOfBirth;

    @Column(name = "NATIONAL_ID", length = 30)       private String nationalId;
    @Column(name = "BLOOD_GROUP", length = 5)        private String bloodGroup;
    @Column(name = "EMERGENCY_CONTACT", length = 20) private String emergencyContact;

    /**
     * How salary normally reaches them. A standing arrangement, so it is held
     * here rather than asked again every month — but the payment row still
     * records what was actually used, since the arrangement can be overridden
     * for one run. See {@link HrConstants.PaymentMode}.
     */
    @Column(name = "SALARY_PAYMENT_MODE", length = 20) private String salaryPaymentMode;

    @Column(name = "BANK_NAME", length = 100)      private String bankName;
    @Column(name = "BANK_BRANCH", length = 100)    private String bankBranch;
    @Column(name = "BANK_ACCOUNT_NO", length = 50) private String bankAccountNo;
    @Column(name = "MOBILE_WALLET_NO", length = 20) private String mobileWalletNo;

    /* ── [V78] The photograph, and the rest of the personal record ─────────
     *
     * IMAGE_PATH holds a public URL such as
     * {@code /uploads/employee_image/employee_42_9f3c….jpg}, never a filesystem
     * path. Where that folder sits is configuration — {@code UPLOAD_ROOT} —
     * and an absolute path in the database would break the moment the
     * application moved machines.
     *
     * The legacy {@link #empImage} large object is left alone rather than
     * dropped: nothing writes it, but a row that has one may hold the only copy
     * of somebody's photograph.
     */
    @Column(name = "IMAGE_PATH", length = 300)
    private String imagePath;

    @Column(name = "PREFERRED_NAME", length = 50)   private String preferredName;
    @Column(name = "MARITAL_STATUS", length = 20)   private String maritalStatus;
    @Column(name = "FATHER_NAME", length = 100)     private String fatherName;
    @Column(name = "MOTHER_NAME", length = 100)     private String motherName;
    @Column(name = "PRESENT_ADDRESS", length = 500) private String presentAddress;

    /**
     * Who this person reports to.
     *
     * <p>A self-reference rather than a separate hierarchy table: a manager is
     * an employee, and a second table would let the two disagree about who
     * exists. {@code ck_hr_employees_manager} stops the one-step cycle; longer
     * ones are a service concern, since a constraint cannot see them.
     */
    @Column(name = "PID_MANAGER")
    private Long pidManager;

    @Column(name = "GRADE", length = 30)      private String grade;
    @Column(name = "PROBATION_MONTHS")        private Integer probationMonths;
    @Column(name = "NOTICE_PERIOD_DAYS")      private Integer noticePeriodDays;

    /** The shift normally worked. Null falls back to the branch policy default. */
    @Column(name = "PID_SHIFT")
    private Long pidShift;

    /* Both schemes default to off. Enrolling somebody is a decision, and a
       default of on would have signed up everybody the day the column appeared. */
    @Column(name = "PF_APPLICABLE")       @Builder.Default private Integer pfApplicable = 0;
    @Column(name = "GRATUITY_APPLICABLE") @Builder.Default private Integer gratuityApplicable = 0;
    @Column(name = "TAX_APPLICABLE")      @Builder.Default private Integer taxApplicable = 0;

    public boolean isPfApplicable()       { return pfApplicable != null && pfApplicable == 1; }
    public boolean isGratuityApplicable() { return gratuityApplicable != null && gratuityApplicable == 1; }

    /** What to show when there is no photograph — initials, not a broken image. */
    @Transient
    public String getInitials() {
        String name = (preferredName != null && !preferredName.isBlank()) ? preferredName : empName;
        if (name == null || name.isBlank()) return "?";
        String[] parts = name.trim().split("\\s+");
        if (parts.length == 1) {
            return parts[0].substring(0, Math.min(2, parts[0].length())).toUpperCase();
        }
        return ("" + parts[0].charAt(0) + parts[parts.length - 1].charAt(0)).toUpperCase();
    }

    public boolean isDeliveryMan() {
        return EmployeeType.DELIVERY_MAN.equals(empType);
    }

    /** Whether payroll can value this person at all. */
    public boolean isPayrollReady() {
        return basicSalary != null
                && basicSalary.signum() > 0
                && pidSalaryStructure != null;
    }

    public boolean isActive() {
        return "Y".equals(getStatus());
    }
}
