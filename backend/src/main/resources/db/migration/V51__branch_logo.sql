/* ─────────────────────────────────────────────────────────────────────────────
   A pharmacy's own name and mark in the menu.

   The sidebar showed "Pharma ERP / Friends Pharma" to every tenant, because
   both strings were literals in the JSX. On a platform selling to many
   pharmacies that is the first thing a customer notices is not theirs.

   Only the URL is stored. The file itself lives on disk under the configured
   upload directory, the same arrangement avatars and product images already
   use — a database is a poor place to keep an image, and putting one there
   makes every query that touches the row heavier for the sake of a picture
   nothing but the browser ever reads.
   ───────────────────────────────────────────────────────────────────────────── */

ALTER TABLE pharma.all_branch
    ADD COLUMN IF NOT EXISTS logo_url varchar(300);

COMMENT ON COLUMN pharma.all_branch.logo_url IS
    'Public path to the branch logo, e.g. /uploads/logo/branch_95_a1b2c3.png. '
    'Null means no logo has been uploaded; the UI falls back to the initial.';

COMMIT;

SELECT pid_branch, branch_name, logo_url
  FROM pharma.all_branch
 ORDER BY pid_branch;
