-- ============================================================================
-- PostgreSQL Database Setup for Friends Pharma ERP
-- ============================================================================
-- Database: minipos_db
-- User: pharma
-- Password: pharma
-- ============================================================================

-- Step 1: Create Database (run as postgres superuser)
CREATE DATABASE minipos_db
    WITH 
    OWNER = pharma
    ENCODING = 'UTF8'
    LC_COLLATE = 'en_US.UTF-8'
    LC_CTYPE = 'en_US.UTF-8'
    TABLESPACE = pg_default
    CONNECTION LIMIT = -1;

-- Step 2: Create User (if doesn't exist)
DO
$$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_catalog.pg_user WHERE usename = 'pharma') THEN
        CREATE USER pharma WITH PASSWORD 'pharma';
    END IF;
END
$$;

-- Step 3: Grant Privileges
GRANT ALL PRIVILEGES ON DATABASE minipos_db TO pharma;

-- Connect to minipos_db database
\c minipos_db

-- Grant schema privileges
GRANT ALL ON SCHEMA public TO pharma;
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO pharma;
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO pharma;

-- Set default privileges for future objects
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO pharma;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON SEQUENCES TO pharma;

-- ============================================================================
-- Demo Data (Optional - Insert after application creates tables)
-- ============================================================================

-- NOTE: Run the application first to let Hibernate create tables
-- Then run the INSERT statements below

/*

-- Demo Organization
INSERT INTO all_organization (org_name, org_address, mobile_no, email, status, inserted_date)
VALUES ('Friends Pharma Ltd', 'Dhaka, Bangladesh', '01711111111', 'info@friendspharma.com', 'Y', NOW())
RETURNING pid_org;

-- Demo User (password: admin123)
-- BCrypt hash: $2a$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/PbH.j.6N7qT6HqG6u
INSERT INTO tb_user (username, email, password_hash, full_name, mobile_no, pid_org, status, inserted_date)
VALUES ('admin', 'admin@friendspharma.com', 
        '$2a$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/PbH.j.6N7qT6HqG6u', 
        'System Administrator', '01711111111', 1, 'Y', NOW())
RETURNING pid_user;

-- Demo Company
INSERT INTO all_company (company_name, pid_org, status, inserted_date)
VALUES ('Head Office', 1, 'Y', NOW())
RETURNING pid_company;

-- Demo Branch
INSERT INTO all_branch (branch_name, pid_org, pid_company, branch_address, mobile_no, status, inserted_date)
VALUES ('Main Branch', 1, 1, 'Dhaka', '01711111111', 'Y', NOW())
RETURNING pid_branch;

-- Demo Region
INSERT INTO all_region (region_name, status, inserted_date)
VALUES ('Dhaka Region', 'Y', NOW())
RETURNING pid_region;

-- Demo Role/Group
INSERT INTO tb_group (group_name, description, pid_org, status, inserted_date)
VALUES ('Super Admin', 'Full system access', 1, 'Y', NOW())
RETURNING pid_group;

-- Demo Modules (Hierarchical Menu)
INSERT INTO tb_module (module_name, module_code, module_icon, module_route, pid_parent, sort_order, status, inserted_date)
VALUES 
('Dashboard', 'dashboard', 'dashboard', '/dashboard', NULL, 1, 'Y', NOW()),
('Sales', 'sales', 'shopping_cart', '/sales', NULL, 2, 'Y', NOW()),
('Purchase', 'purchase', 'shopping_basket', '/purchase', NULL, 3, 'Y', NOW()),
('Inventory', 'inventory', 'inventory', '/inventory', NULL, 4, 'Y', NOW()),
('Reports', 'reports', 'assessment', '/reports', NULL, 5, 'Y', NOW()),
('Master Data', 'master', 'settings', '/master', NULL, 6, 'Y', NOW()),
('RBAC', 'rbac', 'security', '/rbac', NULL, 7, 'Y', NOW());

-- Assign role to user
INSERT INTO tb_user_group_map (pid_user, pid_group, inserted_date)
VALUES (1, 1, NOW());

-- Grant all permissions to Super Admin role
INSERT INTO tb_group_detail (pid_group, pid_module, can_create, can_view, can_edit, can_delete, inserted_date)
SELECT 1, pid_module, true, true, true, true, NOW()
FROM tb_module;

-- Demo Product Category
INSERT INTO pos_product_category (category_name, pid_org, status, inserted_date)
VALUES ('Medicines', 1, 'Y', NOW())
RETURNING pid_category;

-- Demo Product Sub-Category
INSERT INTO pos_product_sub_category (sub_category_name, pid_category, pid_org, status, inserted_date)
VALUES ('Tablets', 1, 1, 'Y', NOW())
RETURNING pid_sub_category;

-- Demo Generic
INSERT INTO pos_generic_info (generic_name, pid_org, status, inserted_date)
VALUES ('Paracetamol', 1, 'Y', NOW())
RETURNING pid_generic;

-- Demo Product
INSERT INTO pos_product_info (
    product_name, product_code, barcode_no, pid_category, pid_sub_category, 
    pid_generic, pid_org, unit_type, mrp_price, purchase_price, sales_price,
    current_stock, reorder_level, status, inserted_date
)
VALUES (
    'Napa 500mg', 'NAPA500', '1234567890123', 1, 1, 1, 1, 'PCS',
    2.50, 2.00, 2.30, 1000, 100, 'Y', NOW()
)
RETURNING pid_product;

-- Demo Supplier
INSERT INTO pos_supplier_info (
    supplier_name, supplier_code, mobile_no, email, address,
    pid_org, status, inserted_date
)
VALUES (
    'Square Pharmaceuticals', 'SUP001', '01711222222', 
    'square@example.com', 'Dhaka', 1, 'Y', NOW()
)
RETURNING pid_supplier;

-- Demo Customer
INSERT INTO pos_customer_info (
    customer_name, mobile_no, email, address, pid_org, pid_branch,
    status, inserted_date
)
VALUES (
    'Walk-in Customer', '01711333333', NULL, 'N/A', 1, 1, 'Y', NOW()
)
RETURNING pid_customer;

-- Demo Employee
INSERT INTO hr_employee (
    emp_name, emp_code, phone, email, pid_org, pid_branch,
    status, inserted_date
)
VALUES (
    'John Doe', 'EMP001', '01711444444', 'john@friendspharma.com',
    1, 1, 'Y', NOW()
)
RETURNING pid_employee;

*/

-- ============================================================================
-- Verification Queries
-- ============================================================================

-- List all tables
-- SELECT tablename FROM pg_tables WHERE schemaname = 'public' ORDER BY tablename;

-- Check row counts
-- SELECT 'all_organization' as table_name, COUNT(*) FROM all_organization
-- UNION ALL
-- SELECT 'tb_user', COUNT(*) FROM tb_user
-- UNION ALL
-- SELECT 'tb_module', COUNT(*) FROM tb_module
-- UNION ALL
-- SELECT 'tb_group', COUNT(*) FROM tb_group;

-- Test login user
-- SELECT pid_user, username, email, full_name FROM tb_user WHERE username = 'admin';

-- Check user permissions
-- SELECT m.module_name, gd.can_create, gd.can_view, gd.can_edit, gd.can_delete
-- FROM tb_user_group_map ugm
-- JOIN tb_group_detail gd ON ugm.pid_group = gd.pid_group
-- JOIN tb_module m ON gd.pid_module = m.pid_module
-- WHERE ugm.pid_user = 1;

-- ============================================================================
-- Useful PostgreSQL Commands
-- ============================================================================

-- Connect to database:
-- \c minipos_db

-- List tables:
-- \dt

-- Describe table:
-- \d pos_transaction_mst

-- List users:
-- \du

-- Show current database:
-- SELECT current_database();

-- Show current user:
-- SELECT current_user;

-- Exit psql:
-- \q

-- ============================================================================
-- Backup & Restore
-- ============================================================================

-- Backup database:
-- pg_dump -U pharma -d minipos_db > backup.sql

-- Restore database:
-- psql -U pharma -d minipos_db < backup.sql

-- ============================================================================
-- END OF SCRIPT
-- ============================================================================
