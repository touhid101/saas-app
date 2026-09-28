# 02 — Existing Object Reuse Matrix

Populated from the actual repository and the live schema on 2026-09-09, not from
the requirement list. Every row was checked before an action was chosen.

**Legend** — REUSE: used unchanged · MODIFY: existing object gains columns or
behaviour · CREATE: genuinely does not exist · INTEGRATE: exists and stays where
it is, HR reads it.

---

## A. Organisation, people and access

| Requirement | Existing object | Existing page / API | Required change | Action |
|---|---|---|---|---|
| Organisation | `all_organization` | Org Structure (`/orgstructure`) | none | **REUSE** |
| Company | `all_company` | Org Structure | none | **REUSE** |
| Branch | `all_branch` | Org Structure | none for HR itself; working policy is a separate table rather than columns, because it is effective-dated | **REUSE** |
| Department | `all_departments` | Master Data → HR group | none | **REUSE** |
| Designation | `hr_designation` | Master Data → HR group | none | **REUSE** |
| Employee master | `hr_employees` (6 rows, referenced by invoices and delivery) | Master Data → HR → `/api/employees` | +13 cols (V73), +14 cols (V78) | **MODIFY** |
| Employee ↔ login | `acl_user.pid_employee` | RBAC | none | **REUSE** |
| Roles | `acl_group` — ROLE04 "Accounts" already existed | RBAC | none | **REUSE** |
| Permissions | `acl_module_info`, `pos_permissions`, `acl_tenant_module`, `acl_perm_template_line` | RBAC / Branch RBAC | 5 new modules registered the V64 way | **REUSE** |
| Authentication | JWT, `AclUser`, `UserPrincipal`, refresh tokens | Login | none | **REUSE** |
| Tenant / branch scope | `SecurityUtils`, `BranchScopeService`, `ActingBranch` | — | none | **REUSE** |

## B. Files and images

| Requirement | Existing object | Existing page / API | Required change | Action |
|---|---|---|---|---|
| Image storage | `ImageStorageService` — extension whitelist, MIME-vs-extension agreement, magic-byte check, path re-check | avatars, branch logos | **+`EMPLOYEE_IMAGE` bucket** | **MODIFY** |
| Upload root config | `app.upload.root` ← `UPLOAD_ROOT` | — | +`employee-image-dir`, +`employee-image-url-prefix` | **MODIFY** |
| Static serving | `WebMvcConfig.addResourceHandlers` | — | +`/uploads/employee_image/**` | **MODIFY** |
| Employee photo column | `hr_employees.emp_image` (oid), `file_name`, `file_mime_type` — **written by nothing** | — | +`image_path`; the BLOB columns are **kept, not dropped** | **MODIFY** |
| Avatar rendering | none for employees | — | `EmployeeAvatar.jsx`, initials fallback | **CREATE** |

**Why the BLOB was not dropped.** This migration must be safe on a production
database, and a large object nobody has looked at might still hold the only copy
of a photograph. Dropping it is a separate decision once those rows are known to
be empty.

## C. Reporting, approval, calendar, expense

| Requirement | Existing object | Existing page / API | Required change | Action |
|---|---|---|---|---|
| Report framework | `ReportGridServiceImpl` catalogue + dispatch; `/api/reports/catalogue`, `/api/reports/{id}` | Reports (`/reports`) | HR reports register into the same catalogue — **no new report engine** | **MODIFY** |
| Export | `src/utils/csv.js`, `productExcel.js` | Reports | reuse | **REUSE** |
| Print | `InvoiceDocumentService`, `PrintDocument.jsx` | invoices | reuse for the payslip | **REUSE** |
| Notification | `pos_notification`, `NotificationService` | topbar bell | reuse for approvals | **REUSE** |
| Dashboard | `DashboardServiceImpl`, `Dashboard.jsx` | `/dashboard` | HR KPI tiles | **MODIFY** |
| Document numbering | `sequence_tracker` + `DocumentNumberService` | — | reuse — payroll `PR…`, voucher `SP…` | **REUSE** |
| Audit pattern | `PasswordResetAuditRecorder` (`REQUIRES_NEW`) | — | reuse the pattern | **REUSE** |
| **Approval workflow** | **none generic** — purchase and requisition approval are each bespoke | Purchase, Requisition | — | **CREATE** (HR-local: each object carries its own decided_by/at) |
| **Calendar** | **none** | — | — | **CREATE** |
| **Expense / conveyance** | **none** | — | — | **CREATE** |

Searched for all three across the whole tree; every hit was a false positive
(a comment, or my own HR code's `HOLIDAY`/`WEEKEND` attendance statuses).

## D. HR transactional objects

| Requirement | Existing object | Required change | Action | Status |
|---|---|---|---|---|
| Working policy | none | — | **CREATE** `hr_working_policy` | V79 ✅ |
| Shift | none | — | **CREATE** `hr_shift` | V79 ✅ |
| Weekend policy | none | — | **CREATE** — `weekend_days` on the policy | V79 ✅ |
| Holiday | none | — | **CREATE** `hr_holiday` | V79 ✅ |
| Attendance | none | — | **CREATE** `hr_attendance` | V74 ✅ |
| Attendance correction | none | — | **MODIFY** `hr_attendance` +4 cols | V79 ✅ (schema only) |
| Leave type / balance / request | none | — | **CREATE** ×3 | V74 ✅ |
| Salary structure / component | none | — | **CREATE** ×2 | V73 ✅ |
| Advance | none | — | **CREATE** `hr_advance` | V75 ✅ |
| Loan + schedule | none | — | **CREATE** ×2 | V75 ✅ |
| Payroll period / run / payslip / component | none | — | **CREATE** ×4 | V76 ✅ |
| Salary payment | none | — | **CREATE** `hr_salary_payment` | V76 ✅ |
| Separation | none | — | **CREATE** `hr_separation` | V76 ✅ |
| Overtime **request** | none — attendance holds minutes, but nothing is *claimed and approved* | — | **CREATE** | ❌ not built |
| PF | none | — | **CREATE** | ❌ not built |
| Gratuity | none | — | **CREATE** | ❌ not built |
| Increment | none | — | **CREATE** | ❌ not built |
| Expense claim | none | — | **CREATE** | ❌ not built |

## E. Objects deliberately NOT created

| Tempting | Why not |
|---|---|
| `hr_department` | `all_departments` exists, is populated, and is already on the HR group of the Master Data screen |
| `hr_branch` | `all_branch` exists; the *policy* is a separate effective-dated table, which is not the same as duplicating the branch |
| `hr_employee` (new) | `hr_employees` exists with live rows referenced by invoices and delivery assignment |
| A second employee CRUD | `/api/employees` already creates and edits; HR adds pay basis, profile and photograph beside it |
| An HR report engine | `ReportGridServiceImpl` already has a catalogue, dispatch, filters and export |
| A second permission system | the matrix decides everything, via `PermissionGuard` |
| A separate HR file-upload service | `ImageStorageService` already validates properly; HR added a bucket |
| An accounting ledger inside HR | Accounts owns journals. HR records operational facts and will publish events |

## F. Frontend pages — modified vs created

| Page | Action | What changed |
|---|---|---|
| `MasterData.jsx` | **REUSE** | employee/department/designation CRUD stays here |
| `Sidebar.jsx` | **MODIFY** | 5 HR icons added to `ICON_PATHS` |
| `App.jsx` | **MODIFY** | 5 routes + page metadata |
| `main.jsx` | **MODIFY** | registers `hr.css` |
| `HrEmployees.jsx` | **CREATE** | summary, staff, profile, photograph, separation |
| `HrAttendance.jsx` | **CREATE** | register, one-tap marking, history |
| `HrLeave.jsx` | **CREATE** | queue, requests, balances, types |
| `HrAdvances.jsx` | **CREATE** | advance and loan |
| `HrPayroll.jsx` | **CREATE** | structures, periods, run, payslip, payment |
| `EmployeeAvatar.jsx` | **CREATE** | photo with initials fallback |
| `HrKit.jsx` | **CREATE** | shared frame, table, tabs, status |
| `Dashboard.jsx` | — | ❌ HR KPI tiles not added |
| `Reports.jsx` | — | ❌ HR reports not registered |
