/* ─────────────────────────────────────────────────────────────────────────────
   V109 — Where the order is, and who took it at the door.

   Two facts an online order needs that the ERP has never recorded: the driver's
   position while a delivery is out, and evidence that it was handed over.

     ECOM_DELIVERY_TRACKING   one row per position reported by the driver's own
                              device while the order is on its way
     ECOM_DELIVERY_PROOF      one row per order: who received it, a photograph
                              if one was taken, and where it was handed over

   PRIVACY, deliberately narrow:
     · A position is written only while the order is ON_THE_WAY. Nothing follows
       a driver between deliveries, and nothing is written before or after.
     · The customer is shown the LATEST point only, never the trail — a trail is
       a record of somebody's working day, and a customer needs to know how far
       away their medicine is, not where the driver has been.
     · Retention is a decision nobody has made yet. Nothing here purges, and
       PURGE/retention policy is a BUSINESS/LEGAL CONFIRMATION item
       (docs/ecommerce 14 §3). The index below is what a purge job would use.

   Proof is one row per order (a delivery is handed over once), so the link is
   unique. Photographs live on disk like every other upload; only the URL is
   stored here.
   ───────────────────────────────────────────────────────────────────────────── */

CREATE SEQUENCE IF NOT EXISTS pharma.seq_ecom_delivery_tracking;
CREATE SEQUENCE IF NOT EXISTS pharma.seq_ecom_delivery_proof;

CREATE TABLE IF NOT EXISTS pharma.ecom_delivery_tracking (
    pid_tracking     BIGINT       PRIMARY KEY DEFAULT nextval('pharma.seq_ecom_delivery_tracking'),
    pid_ecom_order   BIGINT       NOT NULL,
    pid_branch       BIGINT       NOT NULL,
    /* The employee whose device reported it, so a position can be accounted for
       afterwards without reading it back out of a username string. */
    pid_employee     BIGINT,
    latitude         NUMERIC(9, 6)  NOT NULL,
    longitude        NUMERIC(9, 6)  NOT NULL,
    /* What the device itself claimed about the reading, in metres. A position
       good to 2 km is not a position, and this is how that is known. */
    accuracy_m       NUMERIC(8, 1),
    recorded_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    status           VARCHAR(1)   DEFAULT 'Y',
    inserted_by      VARCHAR(50),
    inserted_date    TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    updated_by       VARCHAR(50),
    updated_date     TIMESTAMP,

    CONSTRAINT fk_ecom_track_order  FOREIGN KEY (pid_ecom_order) REFERENCES pharma.ecom_order (pid_ecom_order),
    CONSTRAINT fk_ecom_track_branch FOREIGN KEY (pid_branch)     REFERENCES pharma.all_branch (pid_branch),
    CONSTRAINT ck_ecom_track_latlng CHECK (latitude BETWEEN -90 AND 90 AND longitude BETWEEN -180 AND 180)
);

/* The two questions asked of this table: "where is order X now" (the customer,
   every few seconds) and "what is older than N days" (a purge, when somebody
   decides what N is). */
CREATE INDEX IF NOT EXISTS ix_ecom_track_order ON pharma.ecom_delivery_tracking (pid_ecom_order, recorded_at DESC);
CREATE INDEX IF NOT EXISTS ix_ecom_track_age   ON pharma.ecom_delivery_tracking (recorded_at);

COMMENT ON TABLE pharma.ecom_delivery_tracking IS
  'Driver positions while an online order is ON_THE_WAY. Written by the driver''s own device, shown to the customer as the latest point only.';

CREATE TABLE IF NOT EXISTS pharma.ecom_delivery_proof (
    pid_proof        BIGINT       PRIMARY KEY DEFAULT nextval('pharma.seq_ecom_delivery_proof'),
    pid_ecom_order   BIGINT       NOT NULL,
    pid_branch       BIGINT       NOT NULL,
    pid_employee     BIGINT,
    /* How the handover was evidenced. OTP is deliberately absent: it needs an
       SMS provider nobody has chosen (docs/ecommerce 14 §3). */
    proof_kind       VARCHAR(20)  NOT NULL,
    received_by_name VARCHAR(150),
    photo_url        VARCHAR(300),
    note             VARCHAR(300),
    latitude         NUMERIC(9, 6),
    longitude        NUMERIC(9, 6),
    captured_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    captured_by      VARCHAR(50),

    status           VARCHAR(1)   DEFAULT 'Y',
    inserted_by      VARCHAR(50),
    inserted_date    TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    updated_by       VARCHAR(50),
    updated_date     TIMESTAMP,

    CONSTRAINT fk_ecom_proof_order  FOREIGN KEY (pid_ecom_order) REFERENCES pharma.ecom_order (pid_ecom_order),
    CONSTRAINT fk_ecom_proof_branch FOREIGN KEY (pid_branch)     REFERENCES pharma.all_branch (pid_branch),
    CONSTRAINT ck_ecom_proof_kind   CHECK (proof_kind IN ('PHOTO', 'NAME', 'NOTE'))
);

/* A delivery is handed over once. A second proof row would be a second story
   about the same doorstep. */
CREATE UNIQUE INDEX IF NOT EXISTS uq_ecom_proof_order ON pharma.ecom_delivery_proof (pid_ecom_order);

COMMENT ON TABLE pharma.ecom_delivery_proof IS
  'Evidence that an online order was handed over: who took it, a photograph if one was taken, and where.';

SELECT (SELECT count(*) FROM pharma.ecom_delivery_tracking) AS tracking_rows,
       (SELECT count(*) FROM pharma.ecom_delivery_proof)    AS proof_rows;
