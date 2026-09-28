/* ─────────────────────────────────────────────────────────────────────────────
   Super Admin password reset: the forced change, and the record of who did it.

   Two things are added, and they answer two different questions.

   On ACL_USER, three columns describe the *state* of a credential — whether it
   is a real password the user chose or a temporary one handed to them, when it
   was last set, and by whom. This is what the reset screen reports in place of
   the password itself. A BCrypt hash cannot be turned back into the password it
   was made from, so a screen that promised to show one could only ever show the
   passwords that had not been hashed yet, which are exactly the accounts that
   need fixing rather than displaying.

   ACL_PASSWORD_RESET_AUDIT is the second question: not what the state is, but
   who changed it. It is a separate table rather than a flag on the user because
   the answer must survive the row it describes — a reset that is later
   overwritten by another reset must still be readable, and the user could be
   deleted entirely.

   Names are copied into the audit row rather than joined. An audit that reads
   "user 47 in branch 3" stops being an audit the moment either is renamed or
   removed, and this is the table a dispute about an account takeover would be
   settled from.
   ───────────────────────────────────────────────────────────────────────────── */

ALTER TABLE pharma.acl_user
  ADD COLUMN IF NOT EXISTS must_change_password  varchar(1),
  ADD COLUMN IF NOT EXISTS password_changed_at   timestamp,
  ADD COLUMN IF NOT EXISTS password_reset_by     bigint,
  ADD COLUMN IF NOT EXISTS temp_password_expires timestamp;

/* Backfill before the NOT NULL. Existing accounts chose their own passwords and
   are not mid-reset, so 'N' is the truthful value for every row already here. */
UPDATE pharma.acl_user SET must_change_password = 'N' WHERE must_change_password IS NULL;

ALTER TABLE pharma.acl_user
  ALTER COLUMN must_change_password SET DEFAULT 'N',
  ALTER COLUMN must_change_password SET NOT NULL;

ALTER TABLE pharma.acl_user
  DROP CONSTRAINT IF EXISTS ck_acl_user_must_change;
ALTER TABLE pharma.acl_user
  ADD CONSTRAINT ck_acl_user_must_change CHECK (must_change_password IN ('Y', 'N'));

CREATE TABLE IF NOT EXISTS pharma.acl_password_reset_audit (
    pid_audit        bigserial PRIMARY KEY,

    /* Who it was done to. The id may dangle; the name may not. */
    pid_user         bigint       NOT NULL,
    user_name        varchar(50)  NOT NULL,
    full_name        varchar(100),

    pid_branch       bigint,
    branch_name      varchar(150),
    pid_org          bigint,

    /* Who did it. Never nullable: a reset with no author is the one row that
       would make the whole table worthless. */
    reset_by_user    bigint       NOT NULL,
    reset_by_name    varchar(50)  NOT NULL,

    reset_at         timestamp    NOT NULL DEFAULT now(),
    ip_address       varchar(45),

    /* RESET, or a refusal. Failures are recorded too — repeated refusals
       against one account are the shape an attack has, and a table that only
       holds successes cannot show it. */
    outcome          varchar(20)  NOT NULL,
    reason           varchar(200)
);

CREATE INDEX IF NOT EXISTS ix_pwd_audit_user   ON pharma.acl_password_reset_audit (pid_user, reset_at DESC);
CREATE INDEX IF NOT EXISTS ix_pwd_audit_when   ON pharma.acl_password_reset_audit (reset_at DESC);
CREATE INDEX IF NOT EXISTS ix_pwd_audit_actor  ON pharma.acl_password_reset_audit (reset_by_user, reset_at DESC);

/* How many accounts still hold a password that was never hashed. The reset
   screen reports these as WEAK so they can be cleared deliberately, rather than
   waiting for each user to happen to sign in. */
SELECT CASE WHEN password_hash LIKE '{bcrypt}%' OR password_hash LIKE '$2%'
            THEN 'hashed' ELSE 'plaintext' END AS storage,
       count(*)
  FROM pharma.acl_user
 GROUP BY 1;
