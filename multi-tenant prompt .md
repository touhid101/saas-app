Act as a **Senior SaaS Architect, Spring Boot Backend Expert, React Frontend Expert, and Database Security Expert**.

I already have a working **Spring Boot + React Pharmacy Management Application**. Modify the existing application to support a **multi-tenant / multi-branch B2B SaaS architecture** without breaking existing functionality.

### 1. Branch Management

Create a Branch Master where each individual pharmacy registers as a separate branch.

Each branch must have completely isolated business data:

* Products
* Inventory/Stock
* Purchase & Sales
* Ai Copilot
* Requisition
* Dashboard
* Delivaery
* Supplier Payment
* Master Data
* HR
* Accounts
* Reports
* Customers/Suppliers
* Other branch transactions

Every relevant table/entity and API must be associated with `pid_branch/`.

### 2. User & Access Control

**Super Admin**

* Can view/manage all branches.
* Can access consolidated and branch-wise reports.
* Can create, activate, suspend, and manage branches.
* Can manage global products and subscriptions.

**Branch Admin**

* Full access only to their own branch.
* Cannot view, modify, or access another branch's data.

**Branch Users**

* Access only their assigned branch and permissions.

Implement proper **Spring Security + JWT/RBAC + tenant-level authorization**. Never trust `pid_branch` coming from the frontend; determine the authorized branch from the authenticated user/session/token.

### 3. Global Product → Branch Product

Maintain a **Global Product Master** controlled by Super Admin.

Provide functionality to:

* Copy/import global products into one or multiple branches.
* Copy product image and required master information.
* Prevent duplicate products during import.
* Allow each branch to maintain its own price, stock, supplier, settings, etc.
* Allow branches to create their own local products.

### 4. Subscription Management

Maintain subscription information for every branch:

* Subscription Plan
* Monthly / Yearly billing
* Start Date
* Expiry Date
* Payment Status
* Active / Expired / Suspended status
* Renewal Date
* Payment History
* Grace Period (if required)

### 5. Automatic Subscription Blocking

This is critical.

When a branch's subscription expires or payment is overdue:

* Automatically mark the branch as **EXPIRED/SUSPENDED**.
* Immediately restrict access to the application for all users of that branch.
* Backend APIs must also reject requests — do not rely only on React UI blocking.
* Show a professional message:

**"Your subscription has expired. Please renew your subscription to continue using the application."**

* Provide a **Renew/Pay Subscription** option.
* Super Admin must still be able to access and manage the branch/subscription.

When payment is successfully completed:

* Automatically update subscription/payment status.
* Automatically activate the branch.
* Users can immediately log in and use the application again without manual database changes or deployment.

### 6. Spring Boot Backend

Implement:

* Tenant/Branch entities and relationships.
* Branch-aware repositories/services.
* Spring Security authorization.
* JWT tenant/branch context.
* Subscription validation filter/interceptor.
* Global exception handling for expired subscriptions.
* Secure payment/subscription APIs.
* Audit logging for subscription and branch activities.

Make sure **every API verifies both user permission and branch subscription status**.

### 7. React Frontend

Update the existing React application with:

* Branch management screens.
* Branch Admin/user management.
* Subscription dashboard.
* Subscription status badge.
* Expiry countdown/notification.
* Payment/Renew Subscription page.
* Expired subscription screen/modal.
* Super Admin branch-wise dashboard.
* Branch users see only their branch information.

Do not expose or allow navigation to unauthorized branch data from the frontend.

### 8. Important Implementation Rules

Before coding:

1. Analyze my existing Spring Boot + React project structure.
2. Understand the existing authentication, user, database, API, and routing architecture.
3. Reuse existing architecture wherever possible.
4. Do not unnecessarily rewrite working modules.
5. Maintain backward compatibility.
6. Follow clean architecture, SOLID principles, proper DTO/service/repository separation, validation, exception handling, and secure coding practices.
7. Use database-level constraints/indexes where appropriate for branch isolation and performance.

### 9. Automated Branch Registration & Module Permission Management

After a new pharmacy registers, implement an automated **Branch Onboarding workflow**.

#### Registration Flow

1. Pharmacy completes registration.
2. System automatically creates the Branch/Tenant with status `PENDING`.
3. Create the initial Branch Admin account with status `PENDING`.
4. Notify Super Admin about the new registration.
5. Super Admin reviews the branch and activates the account.
6. Super Admin selects:

   * Subscription Plan
   * Monthly/Yearly billing
   * Subscription start/expiry date
   * Required application modules
   * Branch Admin permissions
7. System automatically activates the branch and applies the selected permissions.

#### Module Permission Management

Create a centralized **Module & Permission Master**.

Example modules:

* Dashboard
* Product Management
* Purchase
* Sales
* Inventory
* Supplier
* Customer
* HR
* Accounts
* Reports
* Settings
* User Management

Each module should support granular permissions such as:

* View
* Create
* Edit
* Delete
* Approve
* Export
* Print

Super Admin can assign permissions at:

* Branch/Tenant level
* Role level
* User level

### Permission Templates

To make onboarding faster, support predefined permission templates:

* **Basic Pharmacy**
* **Standard Pharmacy**
* **Enterprise Pharmacy**
* **Custom**

When Super Admin selects a template, the system automatically assigns the predefined modules and permissions.

Super Admin can then modify individual permissions before activation.

### Automatic Permission Provisioning

When a branch is activated:

```text
Branch Registration
       ↓
Create Branch
       ↓
Create Branch Admin
       ↓
Select Subscription Plan
       ↓
Select Permission Template
       ↓
Automatically Assign Modules & Permissions
       ↓
Activate Branch
       ↓
Branch Admin Login
       ↓
Show Only Authorized Modules
```

Do not hard-code module permissions in React.

The **Spring Boot backend must be the source of truth** for authorization. React should dynamically build the sidebar, menus, buttons, routes, and actions based on permissions returned by the backend.

### Subscription + Permission Integration

The system must validate both:


User Authorization
        +
Branch Subscription Status
        +
Module Permission
```

before allowing access to any protected operation.

For example:

```text
User → Branch → Subscription Active?
                    ↓
                  YES
                    ↓
             Has Permission?
                    ↓
                  YES
                    ↓
             Allow Operation
```

If subscription is expired:

```text
User Login
    ↓
Check Branch Subscription
    ↓
Expired
    ↓
Block Branch Application Access
    ↓
Show "Please renew your subscription"
```

Super Admin must remain unaffected by branch subscription expiry.

### Important Automation Requirement

Make the onboarding process as automated as possible. Super Admin should not need to manually insert database records or manually assign individual permissions.

A single **"Activate Branch"** workflow should be able to:

* Create/activate the tenant
* Activate Branch Admin
* Assign subscription
* Assign permission template
* Assign modules
* Assign roles
* Initialize required branch master data
* Optionally copy selected Global Products
* Send activation notification

Design this using a clean, scalable **RBAC + Branch + Subscription + Module Permission architecture** suitable for a production SaaS application.

### Final Goal

Convert my existing Pharmacy Management Application into a **secure, scalable, production-ready Multi-Tenant Pharmacy SaaS platform**, where each pharmacy operates independently under its own branch, while the Super Admin can centrally manage all pharmacies, products, subscriptions, and business activities.

**First analyze the existing codebase and provide the implementation plan, affected files/modules, database changes, and architecture. Then implement the changes step-by-step.**
