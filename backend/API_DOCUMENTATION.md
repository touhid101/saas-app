# Friends Pharma POS - API Documentation

## Base URL
```
http://localhost:8080
```

## Authentication
Most endpoints require JWT authentication. Include the token in the Authorization header:
```
Authorization: Bearer <your_jwt_token>
```

---

## 📋 Table of Contents
1. [Authentication](#authentication-endpoints)
2. [POS (Point of Sale)](#pos-endpoints)
3. [Sales](#sales-endpoints)
4. [Purchase](#purchase-endpoints)
5. [Inventory](#inventory-endpoints)
6. [Master Data](#master-data-endpoints)
7. [Products](#product-endpoints)
8. [Collection](#collection-endpoints)
9. [Reports](#reports-endpoints)
10. [Dashboard](#dashboard-endpoints)
11. [Notifications](#notification-endpoints)
12. [RBAC (Roles & Permissions)](#rbac-endpoints)
13. [Global Search](#global-search-endpoints)
14. [AI Assistant](#ai-assistant-endpoints)
15. [Invoice Merge](#invoice-merge-endpoints)
16. [Requisition](#requisition-endpoints)

---

## Authentication Endpoints

### Login
```http
POST /api/auth/login
```

**Request Body:**
```json
{
  "username": "string",
  "password": "string"
}
```

**Response:**
```json
{
  "success": true,
  "message": "Login successful",
  "data": {
    "accessToken": "string",
    "refreshToken": "string",
    "tokenType": "Bearer",
    "expiresIn": 3600,
    "user": {
      "pidUser": 1,
      "username": "string",
      "fullName": "string",
      "email": "string",
      "roles": ["ROLE_ADMIN"]
    }
  }
}
```

### Refresh Token
```http
POST /api/auth/refresh
```

**Request Body:**
```json
{
  "refreshToken": "string"
}
```

### Logout
```http
POST /api/auth/logout
```

### Register
```http
POST /api/auth/register
```

**Request Body:**
```json
{
  "username": "string",
  "password": "string",
  "email": "string",
  "fullName": "string",
  "pidOrg": 1,
  "pidBranch": 1
}
```

---

## POS Endpoints

### Get or Create Cart
```http
GET /api/pos/cart
```

**Query Parameters:**
- `orgId` (Long) - Organization ID
- `branchId` (Long) - Branch ID
- `userId` (Long) - User ID

**Response:**
```json
{
  "success": true,
  "data": {
    "pidCart": 1,
    "pidOrg": 1,
    "pidBranch": 1,
    "pidCustomer": null,
    "customerName": null,
    "stage": "OPEN",
    "cartRef": "C12345678",
    "subTotal": 0.00,
    "discountAmount": 0.00,
    "additionalDiscountPct": 0.00,
    "grandTotal": 0.00,
    "items": [],
    "createdAt": "2026-08-14T12:00:00",
    "updatedAt": "2026-08-14T12:00:00"
  }
}
```

### Lookup Product by Barcode
```http
GET /api/pos/barcode/{barcode}
```

**Path Parameters:**
- `barcode` (String) - Product barcode or SKU

**Query Parameters:**
- `orgId` (Long) - Organization ID

**Response:**
```json
{
  "success": true,
  "data": {
    "pidProduct": 1,
    "productName": "Paracetamol 500mg",
    "productCode": "MED001",
    "barcodeNo": "1234567890123",
    "productImageUrl": "/images/med001.jpg",
    "genericName": "Paracetamol",
    "company": "Square Pharmaceuticals",
    "category": "Tablet",
    "mrpBox": 120.00,
    "mrpStrip": 12.00,
    "mrpPcs": 1.20,
    "pcsPerStrip": 10,
    "stripPerBox": 10
  }
}
```

### Add Item to Cart
```http
POST /api/pos/cart/{cartId}/items
```

**Path Parameters:**
- `cartId` (Long) - Cart ID

**Request Body:**
```json
{
  "pidProduct": 1,
  "salesUnit": "PCS",
  "qty": 10.00,
  "unitPriceOverride": null
}
```

### Update Cart Item
```http
PUT /api/pos/cart/{cartId}/items/{itemId}
```

**Request Body:**
```json
{
  "qty": 20.00,
  "discountPct": 5.00
}
```

### Remove Cart Item
```http
DELETE /api/pos/cart/{cartId}/items/{itemId}
```

### Clear Cart
```http
DELETE /api/pos/cart/{cartId}/clear
```

### Hold Cart
```http
POST /api/pos/cart/{cartId}/hold
```

**Request Body:**
```json
{
  "holdNote": "Customer will return in 10 minutes"
}
```

### Recall Held Cart
```http
POST /api/pos/cart/recall
```

**Request Body:**
```json
{
  "cartRef": "C12345678"
}
```

### List Held Carts
```http
GET /api/pos/cart/held
```

**Query Parameters:**
- `branchId` (Long) - Branch ID

### Assign Customer to Cart
```http
PUT /api/pos/cart/{cartId}/customer/{customerId}
```

### Finalize Cart (Complete Sale)
```http
POST /api/pos/cart/finalize
```

**Request Body:**
```json
{
  "pidCart": 1,
  "pidCustomer": 5,
  "paymentType": "CASH",
  "additionalDiscountPct": 2.00,
  "remarks": "Regular customer discount"
}
```

**Response:**
```json
{
  "success": true,
  "data": {
    "invoiceNo": "INV-2026-00001",
    "invoiceDate": "2026-08-14",
    "customerName": "John Doe",
    "subTotal": 500.00,
    "discountAmount": 10.00,
    "grandTotal": 490.00,
    "paymentType": "CASH",
    "lines": [
      {
        "productName": "Paracetamol 500mg",
        "salesUnit": "PCS",
        "qty": 10.00,
        "unitPrice": 50.00,
        "discountPct": 0.00,
        "lineTotal": 500.00,
        "expiryDate": "2027-12-31"
      }
    ]
  }
}
```

### Quick Sale
```http
POST /api/pos/quick-sale
```

**Request Body:**
```json
{
  "pidOrg": 1,
  "pidBranch": 1,
  "pidCustomer": null,
  "paymentType": "CASH",
  "additionalDiscountPct": 0.00,
  "remarks": "",
  "items": [
    {
      "pidProduct": 1,
      "salesUnit": "PCS",
      "qty": 10.00,
      "unitPriceOverride": null
    }
  ]
}
```

### Get Receipt
```http
GET /api/pos/receipt/{transactionId}
```

---

## Sales Endpoints

### Create Sale
```http
POST /api/sales
```

**Request Body:**
```json
{
  "pidOrg": 1,
  "pidBranch": 1,
  "pidCustomer": 5,
  "invoiceDate": "2026-08-14",
  "paymentType": "CASH",
  "remarks": "",
  "items": [
    {
      "pidProduct": 1,
      "salesUnit": "BOX",
      "quantity": 2.00,
      "salesPrice": 120.00,
      "discountPct": 5.00
    }
  ]
}
```

### Get Sale by ID
```http
GET /api/sales/{id}
```

### Get All Sales (Paginated)
```http
GET /api/sales
```

**Query Parameters:**
- `page` (int, default: 0)
- `size` (int, default: 20)
- `orgId` (Long)
- `branchId` (Long, optional)
- `startDate` (LocalDate, optional)
- `endDate` (LocalDate, optional)

### Update Sale
```http
PUT /api/sales/{id}
```

### Delete Sale
```http
DELETE /api/sales/{id}
```

### Search Sales
```http
GET /api/sales/search
```

**Query Parameters:**
- `invoiceNo` (String, optional)
- `customerName` (String, optional)
- `startDate` (LocalDate, optional)
- `endDate` (LocalDate, optional)

---

## Purchase Endpoints

### Create Purchase
```http
POST /api/purchase
```

**Request Body:**
```json
{
  "pidOrg": 1,
  "pidBranch": 1,
  "pidSupplier": 3,
  "purchaseDate": "2026-08-14",
  "challanNo": "CH-2026-001",
  "remarks": "",
  "items": [
    {
      "pidProduct": 1,
      "quantity": 100.00,
      "purchasePrice": 80.00,
      "mrp": 120.00,
      "batchNo": "BATCH-001",
      "expiryDate": "2027-12-31"
    }
  ]
}
```

### Get Purchase by ID
```http
GET /api/purchase/{id}
```

### Get All Purchases (Paginated)
```http
GET /api/purchase
```

**Query Parameters:**
- `page` (int, default: 0)
- `size` (int, default: 20)
- `orgId` (Long)
- `branchId` (Long, optional)

### Update Purchase
```http
PUT /api/purchase/{id}
```

### Delete Purchase
```http
DELETE /api/purchase/{id}
```

---

## Inventory Endpoints

### Get Inventory Summary
```http
GET /api/inventory/summary
```

**Query Parameters:**
- `orgId` (Long)
- `branchId` (Long, optional)

**Response:**
```json
{
  "success": true,
  "data": {
    "totalProducts": 150,
    "totalStockValue": 250000.00,
    "lowStockItems": 12,
    "outOfStockItems": 3,
    "expiringSoon": 8
  }
}
```

### Get Stock by Product
```http
GET /api/inventory/stock/{productId}
```

### Get Low Stock Products
```http
GET /api/inventory/low-stock
```

**Query Parameters:**
- `orgId` (Long)

### Get Out of Stock Products
```http
GET /api/inventory/out-of-stock
```

### Get Expiring Products
```http
GET /api/inventory/expiring
```

**Query Parameters:**
- `orgId` (Long)
- `days` (int, default: 90) - Days ahead to check

### Adjust Stock
```http
POST /api/inventory/adjust
```

**Request Body:**
```json
{
  "pidProduct": 1,
  "pidOrg": 1,
  "pidBranch": 1,
  "adjustmentType": "DAMAGE",
  "quantity": 5.00,
  "remarks": "Damaged during storage"
}
```

---

## Master Data Endpoints

### Organizations

#### Get All Organizations
```http
GET /api/master/organizations
```

#### Create Organization
```http
POST /api/master/organizations
```

**Request Body:**
```json
{
  "organizationName": "Friends Pharma Ltd",
  "address": "123 Main St, Dhaka",
  "phone": "01712345678",
  "email": "info@friendspharma.com"
}
```

### Companies

#### Get All Companies
```http
GET /api/master/companies
```

**Query Parameters:**
- `orgId` (Long)

#### Create Company
```http
POST /api/master/companies
```

### Branches

#### Get All Branches
```http
GET /api/master/branches
```

**Query Parameters:**
- `orgId` (Long)
- `companyId` (Long, optional)

#### Create Branch
```http
POST /api/master/branches
```

### Categories

#### Get All Categories
```http
GET /api/master/categories
```

#### Create Category
```http
POST /api/master/categories
```

**Request Body:**
```json
{
  "pidOrg": 1,
  "categoryName": "Antibiotics",
  "categoryDescription": "Antibiotic medicines"
}
```

### Sub-Categories

#### Get All Sub-Categories
```http
GET /api/master/sub-categories
```

**Query Parameters:**
- `categoryId` (Long, optional)

#### Create Sub-Category
```http
POST /api/master/sub-categories
```

### Units

#### Get All Units
```http
GET /api/master/units
```

#### Create Unit
```http
POST /api/master/units
```

### Generic Info

#### Get All Generics
```http
GET /api/master/generics
```

#### Create Generic
```http
POST /api/master/generics
```

### Manufacturers

#### Get All Manufacturers
```http
GET /api/master/manufacturers
```

#### Create Manufacturer
```http
POST /api/master/manufacturers
```

### Customers

#### Get All Customers
```http
GET /api/master/customers
```

**Query Parameters:**
- `orgId` (Long)
- `page` (int, default: 0)
- `size` (int, default: 20)

#### Create Customer
```http
POST /api/master/customers
```

**Request Body:**
```json
{
  "pidOrg": 1,
  "customerName": "John Doe",
  "phoneNo": "01712345678",
  "email": "john@example.com",
  "address": "Dhaka, Bangladesh",
  "customerType": "REGULAR"
}
```

### Suppliers

#### Get All Suppliers
```http
GET /api/master/suppliers
```

#### Create Supplier
```http
POST /api/master/suppliers
```

### Employees

#### Get All Employees
```http
GET /api/master/employees
```

#### Create Employee
```http
POST /api/master/employees
```

---

## Product Endpoints

### Get All Products
```http
GET /api/products
```

**Query Parameters:**
- `orgId` (Long)
- `categoryId` (Long, optional)
- `page` (int, default: 0)
- `size` (int, default: 20)

### Get Product by ID
```http
GET /api/products/{id}
```

### Search Products
```http
GET /api/products/search
```

**Query Parameters:**
- `orgId` (Long)
- `query` (String) - Search by name or barcode

### Create Product
```http
POST /api/products
```

**Request Body:**
```json
{
  "pidOrg": 1,
  "pidCategory": 5,
  "pidGeneric": 10,
  "pidCompany": 3,
  "productName": "Paracetamol 500mg",
  "barcode": "1234567890123",
  "sku": "MED001",
  "mrpPrice": 120.00,
  "buyPrice": 80.00,
  "tpPrice": 12.00,
  "vatPct": 0.00,
  "discountPct": 0.00,
  "alertQty": 20,
  "prescriptionFlag": "N"
}
```

### Update Product
```http
PUT /api/products/{id}
```

### Delete Product
```http
DELETE /api/products/{id}
```

---

## Collection Endpoints

### Get All Collections
```http
GET /api/collection
```

**Query Parameters:**
- `orgId` (Long)
- `page` (int, default: 0)
- `size` (int, default: 20)

### Create Collection
```http
POST /api/collection
```

**Request Body:**
```json
{
  "pidOrg": 1,
  "pidBranch": 1,
  "pidCustomer": 5,
  "collectionDate": "2026-08-14",
  "amount": 5000.00,
  "paymentMethod": "CASH",
  "remarks": "Payment for invoice INV-001"
}
```

### Get Collection by ID
```http
GET /api/collection/{id}
```

### Get Collections by Customer
```http
GET /api/collection/customer/{customerId}
```

---

## Reports Endpoints

### Sales Report
```http
GET /api/reports/sales
```

**Query Parameters:**
- `orgId` (Long)
- `branchId` (Long, optional)
- `startDate` (LocalDate)
- `endDate` (LocalDate)
- `format` (String: "JSON" | "PDF" | "EXCEL", default: "JSON")

**Response:**
```json
{
  "success": true,
  "data": {
    "reportDate": "2026-08-14",
    "totalSales": 50000.00,
    "totalDiscount": 2500.00,
    "netSales": 47500.00,
    "totalTransactions": 125,
    "averageTransactionValue": 380.00,
    "details": [...]
  }
}
```

### Purchase Report
```http
GET /api/reports/purchase
```

**Query Parameters:**
- `orgId` (Long)
- `startDate` (LocalDate)
- `endDate` (LocalDate)

### Stock Report
```http
GET /api/reports/stock
```

**Query Parameters:**
- `orgId` (Long)
- `branchId` (Long, optional)

### Profit/Loss Report
```http
GET /api/reports/profit-loss
```

**Query Parameters:**
- `orgId` (Long)
- `startDate` (LocalDate)
- `endDate` (LocalDate)

### Customer Ledger
```http
GET /api/reports/customer-ledger/{customerId}
```

**Query Parameters:**
- `startDate` (LocalDate)
- `endDate` (LocalDate)

### Supplier Ledger
```http
GET /api/reports/supplier-ledger/{supplierId}
```

### Expiry Report
```http
GET /api/reports/expiry
```

**Query Parameters:**
- `orgId` (Long)
- `days` (int, default: 90)

---

## Dashboard Endpoints

### Get Dashboard Summary
```http
GET /api/dashboard/summary
```

**Query Parameters:**
- `orgId` (Long)
- `branchId` (Long, optional)

**Response:**
```json
{
  "success": true,
  "data": {
    "todaySales": 12500.00,
    "todayPurchase": 8000.00,
    "todayProfit": 4500.00,
    "pendingPayments": 25000.00,
    "lowStockCount": 12,
    "expiringCount": 8,
    "totalCustomers": 450,
    "totalProducts": 1250
  }
}
```

### Get Sales Trend
```http
GET /api/dashboard/sales-trend
```

**Query Parameters:**
- `orgId` (Long)
- `days` (int, default: 30)

### Get Top Selling Products
```http
GET /api/dashboard/top-products
```

**Query Parameters:**
- `orgId` (Long)
- `limit` (int, default: 10)
- `startDate` (LocalDate)
- `endDate` (LocalDate)

---

## Notification Endpoints

### Get All Notifications
```http
GET /api/notifications
```

**Query Parameters:**
- `orgId` (Long)
- `userId` (Long)
- `page` (int, default: 0)
- `size` (int, default: 20)

**Response:**
```json
{
  "success": true,
  "data": {
    "content": [
      {
        "pidNotification": 1,
        "notifType": "LOW_STOCK",
        "title": "Low Stock Alert",
        "message": "Paracetamol 500mg is running low (5 units remaining)",
        "severity": "WARNING",
        "isRead": 0,
        "actionUrl": "/inventory/products/1",
        "createdAt": "2026-08-14T10:30:00"
      }
    ],
    "totalElements": 25,
    "totalPages": 2,
    "currentPage": 0
  }
}
```

### Get Unread Notifications
```http
GET /api/notifications/unread
```

### Get Notification Count
```http
GET /api/notifications/count
```

### Mark Notification as Read
```http
PUT /api/notifications/{id}/read
```

### Mark All as Read
```http
PUT /api/notifications/mark-all-read
```

**Query Parameters:**
- `orgId` (Long)
- `userId` (Long)

---

## RBAC Endpoints

### Get All Roles
```http
GET /api/rbac/roles
```

**Query Parameters:**
- `orgId` (Long)

### Create Role
```http
POST /api/rbac/roles
```

**Request Body:**
```json
{
  "pidOrg": 1,
  "groupName": "Store Manager",
  "groupDescription": "Store management role",
  "roleColor": "#4CAF50",
  "groupCode": "ROLE05"
}
```

### Get Role Permissions
```http
GET /api/rbac/roles/{roleId}/permissions
```

### Assign Permissions to Role
```http
POST /api/rbac/roles/{roleId}/permissions
```

**Request Body:**
```json
{
  "permissions": [
    {
      "pidModule": 1,
      "canView": true,
      "canCreate": true,
      "canEdit": true,
      "canDelete": false
    }
  ]
}
```

### Get User Roles
```http
GET /api/rbac/users/{userId}/roles
```

### Assign Role to User
```http
POST /api/rbac/users/{userId}/roles/{roleId}
```

### Get All Modules
```http
GET /api/rbac/modules
```

---

## Global Search Endpoints

### Search Everything
```http
GET /api/search/global
```

**Query Parameters:**
- `orgId` (Long)
- `query` (String)
- `type` (String, optional: "PRODUCT" | "CUSTOMER" | "SUPPLIER" | "INVOICE")

**Response:**
```json
{
  "success": true,
  "data": {
    "products": [...],
    "customers": [...],
    "suppliers": [...],
    "invoices": [...]
  }
}
```

---

## AI Assistant Endpoints

### Chat with AI
```http
POST /api/ai/chat
```

**Request Body:**
```json
{
  "message": "What are the top-selling products this month?",
  "context": {
    "orgId": 1,
    "userId": 1
  }
}
```

**Response:**
```json
{
  "success": true,
  "data": {
    "response": "Based on sales data for August 2026, your top 5 selling products are...",
    "suggestions": [
      "View detailed sales report",
      "Check inventory levels"
    ]
  }
}
```

### Get AI Recommendations
```http
GET /api/ai/recommendations
```

**Query Parameters:**
- `orgId` (Long)
- `type` (String: "REORDER" | "PRICING" | "DISCOUNT")

---

## Invoice Merge Endpoints

### Merge Invoices
```http
POST /api/invoice-merge
```

**Request Body:**
```json
{
  "pidOrg": 1,
  "pidBranch": 1,
  "pidCustomer": 5,
  "invoiceIds": [101, 102, 103],
  "mergedInvoiceNo": "MERGED-2026-001",
  "remarks": "Consolidated billing for August"
}
```

### Get Merged Invoice
```http
GET /api/invoice-merge/{id}
```

---

## Requisition Endpoints

### Create Requisition
```http
POST /api/requisition
```

**Request Body:**
```json
{
  "pidOrg": 1,
  "pidBranch": 1,
  "requisitionDate": "2026-08-14",
  "remarks": "Monthly stock requisition",
  "items": [
    {
      "pidProduct": 1,
      "requestedQty": 100.00,
      "remarks": "Regular stock"
    }
  ]
}
```

### Get All Requisitions
```http
GET /api/requisition
```

**Query Parameters:**
- `orgId` (Long)
- `status` (String, optional: "PENDING" | "APPROVED" | "REJECTED")
- `page` (int, default: 0)
- `size` (int, default: 20)

### Approve Requisition
```http
PUT /api/requisition/{id}/approve
```

### Reject Requisition
```http
PUT /api/requisition/{id}/reject
```

**Request Body:**
```json
{
  "remarks": "Out of budget for this month"
}
```

---

## Common Response Format

### Success Response
```json
{
  "success": true,
  "message": "Operation successful",
  "data": {...}
}
```

### Error Response
```json
{
  "success": false,
  "message": "Error message",
  "errorCode": "ERROR_CODE",
  "data": null
}
```

### Paginated Response
```json
{
  "success": true,
  "data": {
    "content": [...],
    "totalElements": 100,
    "totalPages": 10,
    "currentPage": 0,
    "pageSize": 10,
    "hasNext": true,
    "hasPrevious": false
  }
}
```

---

## Common Error Codes

| Code | Description |
|------|-------------|
| `AUTH_001` | Invalid credentials |
| `AUTH_002` | Token expired |
| `AUTH_003` | Unauthorized access |
| `VAL_001` | Validation error |
| `RES_001` | Resource not found |
| `BUS_001` | Business logic error |
| `DB_001` | Database error |
| `SYS_001` | System error |

---

## Swagger UI

Access interactive API documentation at:
```
http://localhost:8080/swagger-ui.html
```

OpenAPI JSON specification:
```
http://localhost:8080/v3/api-docs
```

---

## Additional Notes

1. **Date Format**: ISO 8601 format (`YYYY-MM-DD`)
2. **DateTime Format**: ISO 8601 format (`YYYY-MM-DDTHH:mm:ss`)
3. **Decimal Precision**: 4 decimal places for prices, 2 for quantities
4. **Currency**: All amounts in BDT (Bangladeshi Taka)
5. **Pagination**: Default page size is 20, max is 100

---

## Database Schema

Database: PostgreSQL 15+
Database Name: `minipos_db`
Schema: `PHARMA`
Connection: `jdbc:postgresql://localhost:5432/minipos_db`

---

## Environment Variables

```properties
# Database
POSTGRES_HOST=localhost
POSTGRES_PORT=5432
POSTGRES_DB=minipos_db
POSTGRES_USER=pharma
POSTGRES_PASSWORD=pharma

# JWT
JWT_SECRET=your-secret-key
JWT_EXPIRATION=3600000

# Server
SERVER_PORT=8080
```

---

**Last Updated:** August 14, 2026  
**Version:** 2.0.0  
**Contact:** info@friendspharma.com
