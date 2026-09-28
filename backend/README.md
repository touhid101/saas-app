# Friends Pharma POS — Spring Boot Backend

Production-quality REST API backend for the Friends Pharma pharmacy ERP.
Replaces all React mock services with real Oracle-backed endpoints.

---

## Table of Contents
1. [Tech Stack](#tech-stack)
2. [Prerequisites](#prerequisites)
3. [Oracle Database Setup](#oracle-database-setup)
4. [Configuration](#configuration)
5. [Running the Application](#running-the-application)
6. [Demo Users](#demo-users)
7. [API Overview](#api-overview)
8. [Frontend `.env` Change](#frontend-env-change)
9. [Flyway Migrations](#flyway-migrations)
10. [Scheduled Jobs](#scheduled-jobs)
11. [Project Structure](#project-structure)

---

## Tech Stack

| Layer         | Technology                                      |
|---------------|-------------------------------------------------|
| Language      | Java 21                                         |
| Framework     | Spring Boot 3.2                                 |
| Security      | Spring Security + JWT (access + refresh tokens) |
| ORM           | Spring Data JPA / Hibernate 6                   |
| Database      | Oracle Database 21c / 19c (schema: PHARMA)      |
| Migrations    | Flyway                                          |
| Documentation | SpringDoc OpenAPI 3 (Swagger UI)                |
| Build         | Maven 3.9+                                      |

---

## Prerequisites

- Java 21 JDK
- Maven 3.9+
- Oracle Database 21c Express (or 19c+)
- Oracle JDBC driver (`ojdbc11`) — auto-downloaded via Maven Central

---

## Oracle Database Setup

### 1. Create the schema user

```sql
-- connect as SYSTEM or SYS
CREATE USER PHARMA IDENTIFIED BY pharma123
  DEFAULT TABLESPACE USERS
  TEMPORARY TABLESPACE TEMP
  QUOTA UNLIMITED ON USERS;

GRANT CONNECT, RESOURCE, CREATE VIEW TO PHARMA;
GRANT CREATE SEQUENCE, CREATE TABLE, CREATE PROCEDURE TO PHARMA;
```

### 2. Run the reconciled schema (baseline)

Flyway runs migrations automatically on startup.
The migration scripts are in `src/main/resources/db/migration/`:

| File                       | Purpose                              |
|----------------------------|--------------------------------------|
| `V1__baseline_schema.sql`  | All core tables + sequences          |
| `V2__schema_additions.sql` | Module additions (cart, requisition) |
| `V3__seed_rbac.sql`        | Seed roles, modules, demo users      |

> **First run:** Flyway detects an empty schema and runs all migrations in order.
> **Subsequent runs:** Only new migration files are applied.

### 3. Required stored procedure

The FEFO stock allocation procedure must exist before sales are processed:

```sql
-- Verify it exists:
SELECT OBJECT_NAME FROM ALL_OBJECTS
WHERE OWNER='PHARMA' AND OBJECT_NAME='PROC_EXPIRY_WISE_SALES_QTY_DIS';
```

If missing, import it from `db/reconciled-schema.sql` (section: Stored Procedures).

---

## Configuration

### `src/main/resources/application.yml` — key values to change

```yaml
spring:
  datasource:
    url:      jdbc:oracle:thin:@//localhost:1521/FREEPDB1   # change host/SID as needed
    username: PHARMA
    password: pharma123                                      # change in production

app:
  jwt:
    secret: "FriendsPharmaSuperSecretKeyForJWT2024ProductionUsePleasChangeThis512BitLong"
    # MUST be changed to a random 64-char string in production
    access-token-expiry-ms:  3600000    # 1 hour
    refresh-token-expiry-ms: 604800000  # 7 days
  cors:
    allowed-origins:
      - "http://localhost:5173"         # Vite dev server
      - "http://localhost:5174"
      - "https://your-production-domain.com"
  upload:
    max-size-bytes: 2097152             # 2 MB product image limit
  scheduler:
    cart-idle-hours: 24                 # cancel carts idle longer than this
    default-org-id: 1
```

### Environment variable overrides (recommended for production)

```bash
export SPRING_DATASOURCE_URL=jdbc:oracle:thin:@//db-host:1521/PRODPDB
export SPRING_DATASOURCE_USERNAME=PHARMA
export SPRING_DATASOURCE_PASSWORD=<strong-password>
export APP_JWT_SECRET=<64-char-random-string>
```

---

## Running the Application

### Development

```bash
cd backend
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

### Production JAR

```bash
mvn clean package -DskipTests
java -jar target/pharma-pos-0.0.1-SNAPSHOT.jar --spring.profiles.active=prod
```

### Verify startup

```
http://localhost:8080/actuator/health
# → {"status":"UP"}

http://localhost:8080/swagger-ui.html
# → Swagger UI with all 14 API groups
```

---

## Demo Users

Seeded by `V3__seed_rbac.sql`. All passwords are BCrypt-encoded.

| Username  | Password    | Role    | Access                       |
|-----------|-------------|---------|------------------------------|
| `admin`   | `admin123`  | ADMIN   | Full system access            |
| `manager` | `manager123`| MANAGER | Sales, Purchase, Reports, AI  |
| `sales`   | `sales123`  | SALES   | POS, Sales Activity only      |
| `pharmacy`| `pharma123` | PHARMACIST | Inventory, Purchase view   |

### Get a JWT token

```bash
curl -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}'
```

Response:
```json
{
  "success": true,
  "data": {
    "accessToken": "eyJhbGci...",
    "refreshToken": "eyJhbGci...",
    "tokenType": "Bearer",
    "expiresIn": 3600000,
    "user": { ... }
  }
}
```

Use the `accessToken` as `Authorization: Bearer <token>` in all subsequent requests.

---

## API Overview

All endpoints are under `/api/v1/`. Full interactive docs at `/swagger-ui.html`.

| Group             | Base Path                    | Endpoints |
|-------------------|------------------------------|-----------|
| Auth              | `/auth`                      | login, refresh, logout, profile |
| Master Data       | `/master`                    | products, customers, suppliers, categories, etc. |
| POS               | `/pos`                       | barcode lookup, cart, hold/recall, finalise, quick-sale, receipt |
| Sales             | `/sales`                     | create invoice, activity list, delivery-man assign |
| Sales Discount    | `/sales-discount`            | company/product-wise BOX/STRIP/PCS/SPECIAL % |
| Purchase          | `/purchases`                 | create, confirm, list |
| Inventory         | `/inventory`                 | stock query, batch allocation |
| Collection        | `/collections`               | record payment, summary |
| Requisition       | `/requisitions`              | full workflow + approval modal |
| Invoice Merge     | `/invoice-merges`            | merge/cancel |
| Reports (31)      | `/reports`                   | sales, purchase, inventory, P&L, collection |
| Dashboard         | `/dashboard`                 | 16 KPIs, charts |
| AI Copilot        | `/ai`                        | recommendations, hot products, create-from-AI |
| RBAC              | `/rbac`                      | modules, roles, permissions, users, menu |
| Notifications     | `/notifications`             | unread, mark-read, delete |
| Global Search     | `/search`                    | cross-entity search |

---

## Frontend `.env` Change

In the React application, update `.env` (or `.env.local`):

```dotenv
# Before (mock service)
VITE_API_BASE_URL=http://localhost:5173

# After (Spring Boot backend)
VITE_API_BASE_URL=http://localhost:8080/api/v1
VITE_USE_MOCK=false
```

Then in each React service file, replace the mock base URL:

```typescript
// src/services/apiClient.ts  (or equivalent)
const BASE_URL = import.meta.env.VITE_API_BASE_URL;

// All axios/fetch calls already use BASE_URL — no other changes needed
// if the service layer is centralised.
```

**Auth flow change:** The backend returns `accessToken` + `refreshToken`.
Store `accessToken` in memory (not localStorage) and `refreshToken` in an
`httpOnly` cookie or secure localStorage. Pass `Authorization: Bearer <token>`
on every request.

---

## Flyway Migrations

```
src/main/resources/db/migration/
├── V1__baseline_schema.sql    ← All tables, sequences, constraints
├── V2__schema_additions.sql   ← Cart, requisition, merge, notification tables
└── V3__seed_rbac.sql          ← Demo users, roles, default modules
```

To add a new migration:

```
V4__your_description.sql
```

Flyway enforces sequential versioning — never modify an already-applied file.

---

## Scheduled Jobs

| Job                  | Schedule          | Replaces                          |
|----------------------|-------------------|------------------------------------|
| Cancel stale carts   | Every 30 minutes  | `PROC_AUTO_CLOSE_NOT_SUBMITTED`   |
| Low-stock alerts     | Every 6 hours     | Manual stock check                |
| Expiry alerts        | Daily at 08:00    | Manual expiry review              |

All schedules are configurable in `application.yml` under `app.scheduler.*`.

---

## Project Structure

```
backend/
├── src/main/java/com/friendspharma/app/
│   ├── PharmaPosApplication.java
│   ├── api/              ApiResponse, PageResponse
│   ├── config/           Security, CORS, Jackson, OpenAPI, Auditor
│   ├── controller/       All 16 REST controllers
│   ├── dto/
│   │   ├── request/      All request DTOs
│   │   └── response/     All response DTOs
│   ├── entity/           All 30+ JPA entities
│   │   └── base/         BaseEntity (audit fields)
│   ├── exception/
│   │   ├── custom/       Domain exceptions
│   │   └── global/       GlobalExceptionHandler
│   ├── mapper/           Hand-written mappers (no MapStruct)
│   ├── repository/       All Spring Data JPA repositories
│   ├── scheduler/        CartCleanupScheduler
│   ├── security/         JWT, UserPrincipal, SecurityUtils
│   └── service/
│       ├── interfaces/   Service contracts
│       └── impl/         Service implementations
├── src/main/resources/
│   ├── application.yml
│   ├── application-dev.yml
│   └── db/migration/     Flyway SQL scripts
├── db/
│   └── reconciled-schema.sql
├── docs/
│   ├── api-mapping.md
│   └── backend-notes.md
└── pom.xml
```

---

## Notes

- **No MapStruct** — all entity ↔ DTO mapping is hand-written in `mapper/` classes and service `toDto()` helpers.
- **FEFO** — stock allocation calls Oracle stored procedure `PROC_EXPIRY_WISE_SALES_QTY_DIS` via `SimpleJdbcCall`.
- **Delivery** — tracked directly on `POS_TRANSACTION_MST.DELIVERY_MAN` + `DELIVERY_STATUS`; no separate delivery-invoice table.
- **Passwords** — BCrypt-encoded; seed script uses `$2a$10$...` hashes.
- **Oracle sequences** — every entity uses its own named sequence; Hibernate `SEQUENCE` strategy.
