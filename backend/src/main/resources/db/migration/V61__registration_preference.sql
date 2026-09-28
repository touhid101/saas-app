/* ─────────────────────────────────────────────────────────────────────────────
   What the applicant asked for.

   The sign-up page used to collect a name, a phone number and a password. The
   first question every one of those applicants was then asked on the phone —
   which plan, and for how long — was not on the form, so it was asked again and
   written down somewhere that was not this system.

   The page now shows the plans and the terms and lets the applicant pick. These
   two columns are where that choice lands.

   ── A preference, not a decision ──

   Nothing here activates anything. A Super Admin still chooses what a pharmacy
   goes live on, and can overrule this freely; the activation screen simply
   starts on what the applicant asked for instead of the top of the list.

   The plan is held by CODE rather than by id. A plan withdrawn and re-created
   gets a new id, and an old application still pointing at that id would silently
   become an application for a different price. A code that no longer matches
   anything on sale resolves to nothing, which is the honest answer.

   ── Why these are nullable and unvalidated at the database ──

   The values arrive from the one endpoint in the application that answers
   without a token. They are checked in TenantOnboardingService against the plans
   and terms actually on sale, and anything unrecognised is dropped rather than
   stored — but a foreign key here would turn "this plan was retired while you
   were filling in the form" into a failed application, which is a customer lost
   over something they could not have known.
   ───────────────────────────────────────────────────────────────────────────── */

ALTER TABLE pharma.tenant_registration
  ADD COLUMN IF NOT EXISTS preferred_plan_code   varchar(30),
  ADD COLUMN IF NOT EXISTS preferred_term_months integer;
