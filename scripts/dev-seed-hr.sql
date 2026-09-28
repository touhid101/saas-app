/* ═══════════════════════════════════════════════════════════════════════════
   HR — development seed data
   ═══════════════════════════════════════════════════════════════════════════

   ── THIS IS NOT A MIGRATION, AND MUST NEVER BECOME ONE ──

   It deliberately lives outside src/main/resources/db/migration. A Flyway file
   runs everywhere the application starts, and this one invents salaries, leave
   entitlements, an advance, a loan and a month of attendance. On a real
   pharmacy that is fabricated payroll data attached to real people, which is
   worse than an empty HR module by a wide margin.

   Run it by hand, against a development database only:

       psql -h localhost -p 5489 -U pharma -d minipos_db -f scripts/dev-seed-hr.sql

   ── What it does and does not do ──

   It seeds everything payroll needs as INPUT and then stops. It writes no
   payroll run, no payslip and no payslip component — those come from pressing
   Calculate on the Payroll screen, so the figures you see are the ones the real
   engine produced rather than numbers this file made up. Hand-written payslip
   rows would be a second, silently divergent implementation of the payroll
   arithmetic, and the first time it disagreed with PayrollCalculator nobody
   would know which was right.

   ── Scope ──

   Branch 1 only (Friends Pharma 60 Feet). Every other pharmacy in this database
   is left alone.

   ── Idempotent ──

   Everything is guarded on a marker, so running it twice changes nothing. The
   marker is inserted_by = 'SEED-HR', which is also how you remove it:

       -- see the tear-down block at the foot of this file

   ── The demo values are demo values ──

   Leave quotas, the overtime multiplier and every salary here are invented for
   a screenshot. They are exactly the numbers the application refuses to invent
   for itself (see docs/accounts-hr/02-hr-module-srs.md §7), and seeding them
   here is safe only because this file never runs anywhere real.
   ═══════════════════════════════════════════════════════════════════════════ */

BEGIN;

/* ── Refuse to run against anything that is not the dev branch ──────────── */
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pharma.all_branch
                    WHERE pid_branch = 1 AND branch_name = 'Friends Pharma 60 Feet') THEN
        RAISE EXCEPTION
          'Branch 1 is not the expected development branch. Refusing to seed. '
          'This script writes fabricated payroll data and must not run on real data.';
    END IF;
END $$;


/* ═══ 1. Departments and designations ═══════════════════════════════════════

   Branch 1 has exactly one of each, both left over from an unrelated test, so
   the employee form offers almost no choice. */

INSERT INTO pharma.all_departments
       (pid_department, pid_org, pid_company, pid_branch, department_name, status, inserted_by, inserted_date)
SELECT nextval('pharma.seq_all_departments'), 1, 1, 1, d.name, 'Y', 'SEED-HR', now()
  FROM (VALUES ('Dispensary'), ('Sales'), ('Delivery'), ('Accounts'), ('Store')) AS d(name)
 WHERE NOT EXISTS (SELECT 1 FROM pharma.all_departments x
                    WHERE x.pid_branch = 1 AND x.department_name = d.name);

INSERT INTO pharma.hr_designation
       (pid_designation, pid_org, pid_company, pid_branch, designation_name, description, status, inserted_by, inserted_date)
SELECT nextval('pharma.seq_hr_designation'), 1, 1, 1, g.name, g.note, 'Y', 'SEED-HR', now()
  FROM (VALUES
        ('Pharmacist',      'Dispenses prescription medicines'),
        ('Senior Cashier',  'Runs the till and the daily cash count'),
        ('Cashier',         'Runs the till'),
        ('Store Keeper',    'Receives stock and keeps the shelves'),
        ('Delivery Rider',  'Delivers orders to customers'),
        ('Branch Manager',  'Runs the branch'),
        ('Accounts Officer','Purchases, payments and reconciliation')
       ) AS g(name, note)
 WHERE NOT EXISTS (SELECT 1 FROM pharma.hr_designation x
                    WHERE x.pid_branch = 1 AND x.designation_name = g.name);


/* ═══ 2. Salary structures ══════════════════════════════════════════════════

   Two, deliberately different in the one respect that matters most:

     Standard Staff  pays overtime at 2x of an 8-hour day
     Delivery Staff  names no multiplier, so overtime is recorded and shown in
                     hours on the payslip but is NOT valued

   That second case is the state the application ships in, and it is worth
   having on screen: a manager should be able to see the difference between
   "overtime is unpaid here" and "overtime is broken". */

INSERT INTO pharma.hr_salary_structure
       (pid_structure, pid_org, pid_company, pid_branch, structure_name, description,
        effective_from, standard_hours_per_day, overtime_multiplier, absence_basis,
        status, inserted_by, inserted_date)
SELECT nextval('pharma.seq_hr_salary_structure'), 1, 1, 1, s.name, s.note,
       DATE '2026-01-01', s.hours, s.mult, 'BASIC', 'Y', 'SEED-HR', now()
  FROM (VALUES
        ('Standard Staff', 'Counter, dispensary and office staff. Overtime at 2x.', 8.00, 2.0000),
        ('Delivery Staff', 'Riders. Overtime recorded but not valued — no rate agreed.', 8.00, NULL::numeric)
       ) AS s(name, note, hours, mult)
 WHERE NOT EXISTS (SELECT 1 FROM pharma.hr_salary_structure x
                    WHERE x.pid_branch = 1 AND x.structure_name = s.name);

/* Components. SORT_ORDER is arithmetic, not presentation: the percentage-of-
   gross deduction below has to run after every earning, or it computes against
   a smaller gross and produces a different, wrong number. */
INSERT INTO pharma.hr_salary_component
       (pid_component, pid_structure, component_code, component_name, component_type,
        calc_method, amount, pct_value, is_taxable, sort_order, status, inserted_by, inserted_date)
SELECT nextval('pharma.seq_hr_salary_component'), st.pid_structure,
       c.code, c.name, c.ctype, c.method, c.amt, c.pct, c.taxable, c.ord, 'Y', 'SEED-HR', now()
  FROM pharma.hr_salary_structure st
  JOIN (VALUES
        ('Standard Staff', 'HRA',       'House Rent',      'EARNING',   'PCT_OF_BASIC', NULL::numeric, 40.0000, 1, 10),
        ('Standard Staff', 'MEDICAL',   'Medical',         'EARNING',   'FIXED',        1500.00,       NULL,    1, 20),
        ('Standard Staff', 'TRANSPORT', 'Transport',       'EARNING',   'FIXED',         800.00,       NULL,    1, 30),
        ('Standard Staff', 'PF',        'Provident Fund',  'DEDUCTION', 'PCT_OF_BASIC', NULL,           5.0000, 0, 10),
        ('Delivery Staff', 'HRA',       'House Rent',      'EARNING',   'PCT_OF_BASIC', NULL,          30.0000, 1, 10),
        ('Delivery Staff', 'FUEL',      'Fuel Allowance',  'EARNING',   'FIXED',        2000.00,       NULL,    1, 20),
        ('Delivery Staff', 'MEALS',     'Meal Allowance',  'EARNING',   'FIXED',         900.00,       NULL,    1, 30)
       ) AS c(structure, code, name, ctype, method, amt, pct, taxable, ord)
    ON c.structure = st.structure_name
 WHERE st.pid_branch = 1
   AND NOT EXISTS (SELECT 1 FROM pharma.hr_salary_component x
                    WHERE x.pid_structure = st.pid_structure AND x.component_code = c.code);


/* ═══ 3. Leave quotas ═══════════════════════════════════════════════════════

   V73 seeds the four leave types with ANNUAL_QUOTA NULL on purpose, because
   the entitlement is the pharmacy's policy and in places its statutory
   obligation. These numbers are invented for the demo and are the reason this
   file must never run anywhere real. */

UPDATE pharma.hr_leave_type SET annual_quota = v.quota, updated_by = 'SEED-HR', updated_date = now()
  FROM (VALUES ('CASUAL', 10.00), ('SICK', 14.00), ('ANNUAL', 15.00), ('UNPAID', 30.00))
       AS v(code, quota)
 WHERE hr_leave_type.pid_branch = 1
   AND upper(hr_leave_type.type_code) = v.code
   AND hr_leave_type.annual_quota IS NULL;


/* ═══ 4. Staff ══════════════════════════════════════════════════════════════

   Five new people so the register has depth, plus a pay basis for the four who
   are already there. The existing four are real development records and keep
   their names, codes and types; only the payroll columns are filled in.

   nextval rather than setval: the live maximum id is a leftover probe row in
   the 990000 range, and forcing the sequence up to meet it would leave a
   million-wide gap for no benefit. The ids this hands out are free. */

INSERT INTO pharma.hr_employees
       (pid_employee, pid_org, pid_company, pid_branch, pid_department, pid_designation,
        emp_id, emp_name, email, phone, gender, hire_date, emp_type, employment_type,
        basic_salary, pid_salary_structure, salary_payment_mode, bank_name, bank_account_no,
        confirmation_date, status, inserted_by, inserted_date)
SELECT nextval('pharma.seq_hr_employees'), 1, 1, 1,
       (SELECT pid_department FROM pharma.all_departments
         WHERE pid_branch = 1 AND department_name = e.dept LIMIT 1),
       (SELECT pid_designation FROM pharma.hr_designation
         WHERE pid_branch = 1 AND designation_name = e.desig LIMIT 1),
       e.code, e.name, lower(replace(e.name, ' ', '.')) || '@example.test', e.phone, e.gender,
       e.hired, e.etype, e.emptype, e.basic,
       (SELECT pid_structure FROM pharma.hr_salary_structure
         WHERE pid_branch = 1 AND structure_name = e.structure LIMIT 1),
       e.mode, e.bank, e.account, e.confirmed, 'Y', 'SEED-HR', now()
  FROM (VALUES
        ('SEED-001', 'Nasrin Akter',    'Dispensary', 'Pharmacist',       '01711000101', 'FEMALE', DATE '2023-02-01', 'PHARMACIST', 'PERMANENT', 34000.00, 'Standard Staff', 'BANK',   'BRAC Bank',  '1501100010101', DATE '2023-08-01'),
        ('SEED-002', 'Jahangir Alam',   'Sales',      'Senior Cashier',   '01711000102', 'MALE',   DATE '2023-06-15', 'CASHIER',    'PERMANENT', 26000.00, 'Standard Staff', 'BANK',   'City Bank',  '2201100010202', DATE '2023-12-15'),
        ('SEED-003', 'Sumaiya Islam',   'Sales',      'Cashier',          '01711000103', 'FEMALE', DATE '2025-03-10', 'CASHIER',    'PROBATION', 18000.00, 'Standard Staff', 'MOBILE', NULL,         NULL,            NULL),
        ('SEED-004', 'Abdul Malek',     'Store',      'Store Keeper',     '01711000104', 'MALE',   DATE '2024-01-20', 'OTHER',      'PERMANENT', 21000.00, 'Standard Staff', 'CASH',   NULL,         NULL,            DATE '2024-07-20'),
        ('SEED-005', 'Farhana Yasmin',  'Accounts',   'Accounts Officer', '01711000105', 'FEMALE', DATE '2022-09-05', 'ACCOUNTANT', 'PERMANENT', 30000.00, 'Standard Staff', 'BANK',   'Dutch-Bangla','3301100010303', DATE '2023-03-05')
       ) AS e(code, name, dept, desig, phone, gender, hired, etype, emptype, basic, structure, mode, bank, account, confirmed)
 WHERE NOT EXISTS (SELECT 1 FROM pharma.hr_employees x WHERE x.emp_id = e.code);

/* A mobile wallet number for the one paid that way — the service refuses a
   MOBILE pay basis without one, and seeded data should satisfy the same rule
   the UI enforces. */
UPDATE pharma.hr_employees
   SET mobile_wallet_no = '01711000103', updated_by = 'SEED-HR', updated_date = now()
 WHERE emp_id = 'SEED-003' AND mobile_wallet_no IS NULL;

/* The four who were already here. Riders go on the Delivery structure, which
   is the one that does not value overtime. */
UPDATE pharma.hr_employees e
   SET basic_salary        = v.basic,
       pid_salary_structure = (SELECT pid_structure FROM pharma.hr_salary_structure
                                WHERE pid_branch = 1 AND structure_name = v.structure LIMIT 1),
       employment_type     = 'PERMANENT',
       salary_payment_mode = 'CASH',
       pid_department      = COALESCE(e.pid_department,
                              (SELECT pid_department FROM pharma.all_departments
                                WHERE pid_branch = 1 AND department_name = v.dept LIMIT 1)),
       pid_designation     = COALESCE(e.pid_designation,
                              (SELECT pid_designation FROM pharma.hr_designation
                                WHERE pid_branch = 1 AND designation_name = v.desig LIMIT 1)),
       updated_by = 'SEED-HR', updated_date = now()
  FROM (VALUES
        ('101160', 22000.00, 'Delivery Staff', 'Delivery', 'Delivery Rider'),
        ('DM-100', 19000.00, 'Delivery Staff', 'Delivery', 'Delivery Rider'),
        ('DM-200', 19000.00, 'Delivery Staff', 'Delivery', 'Delivery Rider'),
        ('PH-100', 32000.00, 'Standard Staff', 'Dispensary', 'Pharmacist')
       ) AS v(code, basic, structure, dept, desig)
 WHERE e.emp_id = v.code AND e.pid_branch = 1 AND e.basic_salary IS NULL;


/* ═══ 5. Leave balances ═════════════════════════════════════════════════════

   ENTITLED is copied from the type's quota rather than joined to it, exactly as
   the service does — so changing next year's policy cannot rewrite what
   somebody was entitled to this year. */

INSERT INTO pharma.hr_leave_balance
       (pid_balance, pid_org, pid_branch, pid_employee, pid_leave_type, leave_year,
        entitled, carried_forward, taken, pending, status, inserted_by, inserted_date)
SELECT nextval('pharma.seq_hr_leave_balance'), 1, 1, e.pid_employee, t.pid_leave_type,
       EXTRACT(YEAR FROM CURRENT_DATE)::int,
       t.annual_quota, 0, 0, 0, 'Y', 'SEED-HR', now()
  FROM pharma.hr_employees e
 CROSS JOIN pharma.hr_leave_type t
 WHERE e.pid_branch = 1 AND e.status = 'Y'
   AND t.pid_branch = 1 AND t.status = 'Y' AND t.annual_quota IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM pharma.hr_leave_balance b
                    WHERE b.pid_employee = e.pid_employee
                      AND b.pid_leave_type = t.pid_leave_type
                      AND b.leave_year = EXTRACT(YEAR FROM CURRENT_DATE)::int);


/* ═══ 6. Leave requests ═════════════════════════════════════════════════════

   Two: one approved inside the pay month so the payslip has leave days on it,
   and one still waiting so the approval queue is not empty when the screen
   opens. */

/* Approved — Nasrin Akter, three days in August. */
INSERT INTO pharma.hr_leave_request
       (pid_leave_request, pid_org, pid_company, pid_branch, pid_employee, pid_leave_type,
        from_date, to_date, days, is_half_day, reason, request_status,
        submitted_by, submitted_at, decided_by, decided_at, decision_remarks,
        status, inserted_by, inserted_date)
SELECT nextval('pharma.seq_hr_leave_request'), 1, 1, 1, e.pid_employee, t.pid_leave_type,
       DATE '2026-08-10', DATE '2026-08-12', 3.00, 0,
       'Family commitment', 'APPROVED',
       'SEED-HR', TIMESTAMP '2026-08-03 09:15:00',
       'admin', TIMESTAMP '2026-08-04 11:00:00', 'Approved',
       'Y', 'SEED-HR', now()
  FROM pharma.hr_employees e
  JOIN pharma.hr_leave_type t ON t.pid_branch = 1 AND upper(t.type_code) = 'CASUAL'
 WHERE e.emp_id = 'SEED-001'
   AND NOT EXISTS (SELECT 1 FROM pharma.hr_leave_request r
                    WHERE r.pid_employee = e.pid_employee AND r.from_date = DATE '2026-08-10');

/* Waiting — Jahangir Alam, next week. */
INSERT INTO pharma.hr_leave_request
       (pid_leave_request, pid_org, pid_company, pid_branch, pid_employee, pid_leave_type,
        from_date, to_date, days, is_half_day, reason, request_status,
        submitted_by, submitted_at, status, inserted_by, inserted_date)
SELECT nextval('pharma.seq_hr_leave_request'), 1, 1, 1, e.pid_employee, t.pid_leave_type,
       CURRENT_DATE + 7, CURRENT_DATE + 8, 2.00, 0,
       'Medical appointment', 'SUBMITTED', 'SEED-HR', now() - INTERVAL '1 day',
       'Y', 'SEED-HR', now()
  FROM pharma.hr_employees e
  JOIN pharma.hr_leave_type t ON t.pid_branch = 1 AND upper(t.type_code) = 'SICK'
 WHERE e.emp_id = 'SEED-002'
   AND NOT EXISTS (SELECT 1 FROM pharma.hr_leave_request r
                    WHERE r.pid_employee = e.pid_employee AND r.request_status = 'SUBMITTED');

/* The balances have to agree with those two, or the Leave screen shows an
   entitlement nobody has drawn against while three days of leave sit approved.
   Approving moves days to TAKEN; submitting holds them in PENDING. */
UPDATE pharma.hr_leave_balance b
   SET taken = 3.00, updated_by = 'SEED-HR', updated_date = now()
  FROM pharma.hr_employees e, pharma.hr_leave_type t
 WHERE b.pid_employee = e.pid_employee AND b.pid_leave_type = t.pid_leave_type
   AND e.emp_id = 'SEED-001' AND upper(t.type_code) = 'CASUAL'
   AND b.leave_year = EXTRACT(YEAR FROM CURRENT_DATE)::int AND b.taken = 0;

UPDATE pharma.hr_leave_balance b
   SET pending = 2.00, updated_by = 'SEED-HR', updated_date = now()
  FROM pharma.hr_employees e, pharma.hr_leave_type t
 WHERE b.pid_employee = e.pid_employee AND b.pid_leave_type = t.pid_leave_type
   AND e.emp_id = 'SEED-002' AND upper(t.type_code) = 'SICK'
   AND b.leave_year = EXTRACT(YEAR FROM CURRENT_DATE)::int AND b.pending = 0;


/* ═══ 7. Attendance ═════════════════════════════════════════════════════════

   August 2026, every active member of branch 1.

   The approved leave goes in first and everything else works around it, because
   uq_hr_attendance_day allows one live row per person per day and the LEAVE
   rows have to carry the request id that created them — that is what lets a
   cancelled leave later remove exactly its own days and nobody else's. */

INSERT INTO pharma.hr_attendance
       (pid_attendance, pid_org, pid_company, pid_branch, pid_employee, attendance_date,
        attendance_status, source, pid_leave_request, remarks, working_minutes,
        late_minutes, early_leave_minutes, overtime_minutes, status, inserted_by, inserted_date)
SELECT nextval('pharma.seq_hr_attendance'), 1, 1, 1, r.pid_employee, d::date,
       'LEAVE', 'MANUAL', r.pid_leave_request, 'Approved leave', 0, 0, 0, 0,
       'Y', 'SEED-HR', now()
  FROM pharma.hr_leave_request r
 CROSS JOIN LATERAL generate_series(r.from_date, r.to_date, INTERVAL '1 day') d
 WHERE r.pid_branch = 1 AND r.request_status = 'APPROVED' AND r.inserted_by = 'SEED-HR'
   AND NOT EXISTS (SELECT 1 FROM pharma.hr_attendance a
                    WHERE a.pid_employee = r.pid_employee
                      AND a.attendance_date = d::date AND a.status = 'Y');

/* The rest of the month.

   Friday is treated as the weekly holiday, which is the common pattern here —
   but note that this is the seed making a choice, not the application. There is
   no weekly-off calendar in the schema, and adding one is recorded as an open
   business decision (02-hr-module-srs.md, P-04). WEEKEND days count as neither
   worked nor absent, so nothing in the payslip turns on it.

   The variation is deterministic rather than random, so two runs of this file
   produce the same month and a figure you checked yesterday still holds. */
INSERT INTO pharma.hr_attendance
       (pid_attendance, pid_org, pid_company, pid_branch, pid_employee, attendance_date,
        attendance_status, source, working_minutes, late_minutes, early_leave_minutes,
        overtime_minutes, check_in, check_out, status, inserted_by, inserted_date)
SELECT nextval('pharma.seq_hr_attendance'), 1, 1, 1, e.pid_employee, d::date,
       CASE
         WHEN EXTRACT(dow FROM d) = 5                      THEN 'WEEKEND'
         WHEN (e.pid_employee + EXTRACT(day FROM d)::int) % 23 = 0 THEN 'ABSENT'
         WHEN (e.pid_employee + EXTRACT(day FROM d)::int) % 11 = 0 THEN 'LATE'
         ELSE 'PRESENT'
       END,
       'MANUAL',
       CASE WHEN EXTRACT(dow FROM d) = 5
              OR (e.pid_employee + EXTRACT(day FROM d)::int) % 23 = 0
            THEN 0 ELSE 480 END,
       CASE WHEN (e.pid_employee + EXTRACT(day FROM d)::int) % 11 = 0
            THEN 25 ELSE 0 END,
       0,
       /* Overtime on roughly one working day in seven. The riders accrue it
          too, and theirs is deliberately not valued — see the Delivery Staff
          structure. */
       CASE WHEN EXTRACT(dow FROM d) <> 5
             AND (e.pid_employee + EXTRACT(day FROM d)::int) % 23 <> 0
             AND (e.pid_employee + EXTRACT(day FROM d)::int) % 7 = 0
            THEN 120 ELSE 0 END,
       CASE WHEN EXTRACT(dow FROM d) = 5
              OR (e.pid_employee + EXTRACT(day FROM d)::int) % 23 = 0
            THEN NULL ELSE d + TIME '09:00' END,
       CASE WHEN EXTRACT(dow FROM d) = 5
              OR (e.pid_employee + EXTRACT(day FROM d)::int) % 23 = 0
            THEN NULL ELSE d + TIME '17:00' END,
       'Y', 'SEED-HR', now()
  FROM pharma.hr_employees e
 CROSS JOIN generate_series(DATE '2026-08-01', DATE '2026-08-31', INTERVAL '1 day') d
 WHERE e.pid_branch = 1 AND e.status = 'Y'
   AND NOT EXISTS (SELECT 1 FROM pharma.hr_attendance a
                    WHERE a.pid_employee = e.pid_employee
                      AND a.attendance_date = d::date AND a.status = 'Y');

/* Today, half done — so the register opens on something to finish rather than
   on a blank sheet or a completed one. Two people marked, the rest waiting. */
INSERT INTO pharma.hr_attendance
       (pid_attendance, pid_org, pid_company, pid_branch, pid_employee, attendance_date,
        attendance_status, source, working_minutes, late_minutes, early_leave_minutes,
        overtime_minutes, status, inserted_by, inserted_date)
SELECT nextval('pharma.seq_hr_attendance'), 1, 1, 1, e.pid_employee, CURRENT_DATE,
       'PRESENT', 'MANUAL', 480, 0, 0, 0, 'Y', 'SEED-HR', now()
  FROM pharma.hr_employees e
 WHERE e.pid_branch = 1 AND e.status = 'Y' AND e.emp_id IN ('SEED-001', 'SEED-002')
   AND NOT EXISTS (SELECT 1 FROM pharma.hr_attendance a
                    WHERE a.pid_employee = e.pid_employee
                      AND a.attendance_date = CURRENT_DATE AND a.status = 'Y');


/* ═══ 8. An advance and a loan ══════════════════════════════════════════════

   Both already paid out, so payroll has something to recover. The advance is
   approved for less than was asked, because that is the case worth being able
   to see on screen: the requested figure stays beside the approved one, and
   every recovery is computed from the approved amount. */

INSERT INTO pharma.hr_advance
       (pid_advance, pid_org, pid_company, pid_branch, pid_employee, request_date,
        amount, approved_amount, recovered_amount, installments, recover_from, reason,
        advance_status, decided_by, decided_at, decision_remarks,
        paid_date, payment_mode, payment_reference, status, inserted_by, inserted_date)
SELECT nextval('pharma.seq_hr_advance'), 1, 1, 1, e.pid_employee, DATE '2026-07-28',
       8000.00, 6000.00, 0, 2, DATE '2026-08-01', 'Family expense',
       'PAID', 'admin', TIMESTAMP '2026-07-29 10:00:00', 'Approved at 6,000 of 8,000',
       DATE '2026-07-30', 'CASH', 'SEED-ADV-01', 'Y', 'SEED-HR', now()
  FROM pharma.hr_employees e
 WHERE e.emp_id = 'SEED-002'
   AND NOT EXISTS (SELECT 1 FROM pharma.hr_advance a
                    WHERE a.pid_employee = e.pid_employee AND a.inserted_by = 'SEED-HR');

INSERT INTO pharma.hr_loan
       (pid_loan, pid_org, pid_company, pid_branch, pid_employee, request_date,
        principal, approved_principal, installments, installment_amount, recovered_amount,
        start_date, purpose, loan_status, decided_by, decided_at,
        disbursed_date, payment_mode, payment_reference, status, inserted_by, inserted_date)
SELECT nextval('pharma.seq_hr_loan'), 1, 1, 1, e.pid_employee, DATE '2026-07-15',
       60000.00, 60000.00, 6, 10000.00, 0,
       DATE '2026-08-01', 'Home repair', 'DISBURSED', 'admin', TIMESTAMP '2026-07-18 12:00:00',
       DATE '2026-07-25', 'BANK', 'SEED-LOAN-01', 'Y', 'SEED-HR', now()
  FROM pharma.hr_employees e
 WHERE e.emp_id = 'SEED-001'
   AND NOT EXISTS (SELECT 1 FROM pharma.hr_loan l
                    WHERE l.pid_employee = e.pid_employee AND l.inserted_by = 'SEED-HR');

/* The schedule is written at approval, not as payroll runs, so the employee
   can be shown the whole plan before accepting it. Six equal installments of
   10,000 divide exactly here; where they do not, the service puts the rounding
   on the last one so the schedule still sums to the principal. */
INSERT INTO pharma.hr_loan_schedule
       (pid_schedule, pid_loan, installment_no, due_date, due_amount, recovered_amount,
        schedule_status, status, inserted_by, inserted_date)
SELECT nextval('pharma.seq_hr_loan_schedule'), l.pid_loan, i,
       (l.start_date + ((i - 1) * INTERVAL '1 month'))::date,
       10000.00, 0, 'PENDING', 'Y', 'SEED-HR', now()
  FROM pharma.hr_loan l
 CROSS JOIN generate_series(1, 6) i
 WHERE l.inserted_by = 'SEED-HR'
   AND NOT EXISTS (SELECT 1 FROM pharma.hr_loan_schedule s
                    WHERE s.pid_loan = l.pid_loan AND s.installment_no = i);


/* ═══ 9. The pay period ═════════════════════════════════════════════════════

   Open, with no run against it. Press Calculate on the Payroll screen and the
   real engine produces the payslips — including the advance recovery, the first
   loan installment, the overtime it values and the overtime it does not.

   WORKING_DAYS is counted from the month rather than typed, so it agrees with
   the attendance this file just wrote. The application asks for this figure
   rather than deriving it, because payroll divides by it to value an absence
   and different pharmacies count it differently. */

INSERT INTO pharma.hr_payroll_period
       (pid_period, pid_org, pid_company, pid_branch, period_year, period_month,
        period_from, period_to, working_days, period_status, remarks,
        status, inserted_by, inserted_date)
SELECT nextval('pharma.seq_hr_payroll_period'), 1, 1, 1, 2026, 8,
       DATE '2026-08-01', DATE '2026-08-31',
       (SELECT count(*) FROM generate_series(DATE '2026-08-01', DATE '2026-08-31', INTERVAL '1 day') d
         WHERE EXTRACT(dow FROM d) <> 5),
       'OPEN', 'Seeded for development. Press Calculate on the Payroll screen.',
       'Y', 'SEED-HR', now()
 WHERE NOT EXISTS (SELECT 1 FROM pharma.hr_payroll_period p
                    WHERE p.pid_branch = 1 AND p.period_year = 2026 AND p.period_month = 8);

COMMIT;


/* ═══ What landed ═══════════════════════════════════════════════════════════ */

SELECT 'departments (branch 1)'  AS object, count(*)::text AS n FROM pharma.all_departments WHERE pid_branch = 1
UNION ALL SELECT 'designations (branch 1)', count(*)::text FROM pharma.hr_designation WHERE pid_branch = 1
UNION ALL SELECT 'salary structures',       count(*)::text FROM pharma.hr_salary_structure WHERE pid_branch = 1
UNION ALL SELECT 'salary components',       count(*)::text FROM pharma.hr_salary_component
UNION ALL SELECT 'leave types with a quota',count(*)::text FROM pharma.hr_leave_type WHERE pid_branch = 1 AND annual_quota IS NOT NULL
UNION ALL SELECT 'active staff (branch 1)', count(*)::text FROM pharma.hr_employees WHERE pid_branch = 1 AND status = 'Y'
UNION ALL SELECT '  ...payroll ready',      count(*)::text FROM pharma.hr_employees
             WHERE pid_branch = 1 AND status = 'Y' AND basic_salary > 0 AND pid_salary_structure IS NOT NULL
UNION ALL SELECT 'leave balances',          count(*)::text FROM pharma.hr_leave_balance WHERE pid_branch = 1
UNION ALL SELECT 'leave requests',          count(*)::text FROM pharma.hr_leave_request WHERE pid_branch = 1
UNION ALL SELECT '  ...awaiting a decision',count(*)::text FROM pharma.hr_leave_request WHERE pid_branch = 1 AND request_status = 'SUBMITTED'
UNION ALL SELECT 'attendance rows',         count(*)::text FROM pharma.hr_attendance WHERE pid_branch = 1
UNION ALL SELECT 'advances outstanding',    count(*)::text FROM pharma.hr_advance WHERE pid_branch = 1 AND advance_status IN ('PAID','RECOVERING')
UNION ALL SELECT 'loans outstanding',       count(*)::text FROM pharma.hr_loan WHERE pid_branch = 1 AND loan_status IN ('DISBURSED','RECOVERING')
UNION ALL SELECT 'loan installments',       count(*)::text FROM pharma.hr_loan_schedule
UNION ALL SELECT 'pay periods open',        count(*)::text FROM pharma.hr_payroll_period WHERE pid_branch = 1 AND period_status = 'OPEN';


/* ═══════════════════════════════════════════════════════════════════════════
   TEAR-DOWN — everything this file created, and nothing else
   ═══════════════════════════════════════════════════════════════════════════

   Run this to get back to a clean HR module. It removes any payroll you
   calculated as well, since those payslips were computed from seeded inputs.

BEGIN;
DELETE FROM pharma.hr_salary_payment;
DELETE FROM pharma.hr_payslip_component;
DELETE FROM pharma.hr_payroll_line;
DELETE FROM pharma.hr_payroll;
DELETE FROM pharma.hr_payroll_period   WHERE inserted_by = 'SEED-HR';
DELETE FROM pharma.hr_loan_schedule    WHERE inserted_by = 'SEED-HR';
DELETE FROM pharma.hr_loan             WHERE inserted_by = 'SEED-HR';
DELETE FROM pharma.hr_advance          WHERE inserted_by = 'SEED-HR';
DELETE FROM pharma.hr_attendance       WHERE inserted_by = 'SEED-HR';
DELETE FROM pharma.hr_leave_request    WHERE inserted_by = 'SEED-HR';
DELETE FROM pharma.hr_leave_balance    WHERE inserted_by = 'SEED-HR';
DELETE FROM pharma.hr_employees        WHERE inserted_by = 'SEED-HR';
-- the four pre-existing staff keep their records; only the seeded pay basis goes
UPDATE pharma.hr_employees SET basic_salary = NULL, pid_salary_structure = NULL,
       employment_type = NULL, salary_payment_mode = NULL
 WHERE pid_branch = 1 AND updated_by = 'SEED-HR';
DELETE FROM pharma.hr_salary_component WHERE inserted_by = 'SEED-HR';
DELETE FROM pharma.hr_salary_structure WHERE inserted_by = 'SEED-HR';
-- quotas go back to unset, which is the state the application ships in
UPDATE pharma.hr_leave_type SET annual_quota = NULL
 WHERE pid_branch = 1 AND updated_by = 'SEED-HR';
DELETE FROM pharma.hr_designation      WHERE inserted_by = 'SEED-HR';
DELETE FROM pharma.all_departments     WHERE inserted_by = 'SEED-HR';
COMMIT;
   ═══════════════════════════════════════════════════════════════════════════ */
