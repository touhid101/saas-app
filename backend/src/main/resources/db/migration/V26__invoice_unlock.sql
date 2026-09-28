-- ═══════════════════════════════════════════════════════════════════════════
-- V26 — Supervisor authorisation to reopen an invoice
--
-- A counter operator raises an invoice and, until it is confirmed, can edit it
-- freely: change the lines, change the customer, change the money. That is the
-- oldest hole in any till — ring up a sale, take the cash, then quietly reduce
-- the invoice. Nothing in the system recorded that it had happened.
--
-- Two things were wrong, and both are addressed here and in the code:
--
--   1. POS_PERMISSIONS already said the Pharmacist role cannot edit sales
--      (CAN_EDIT = 0), but the endpoint only checked role codes and allowed it.
--      The screen hid the button while the API answered anyone who asked.
--
--   2. There was no way for a supervisor to permit a legitimate correction, so
--      the only workable setting was to leave editing open to everyone.
--
-- This table is both the mechanism and the audit trail: a row is written when a
-- supervisor authorises, and the same row records whether the authorisation was
-- actually used. An unlock that was granted and never used is as interesting as
-- one that was.
-- ═══════════════════════════════════════════════════════════════════════════

CREATE SEQUENCE IF NOT EXISTS PHARMA.SQ_INVOICE_UNLOCK START WITH 1 INCREMENT BY 1;

CREATE TABLE IF NOT EXISTS PHARMA.POS_INVOICE_UNLOCK (
    PID_UNLOCK          BIGINT        NOT NULL,
    PID_ORG             BIGINT        NOT NULL,
    PID_BRANCH          BIGINT,

    /* The invoice being reopened. */
    PID_TRAN_MST        BIGINT        NOT NULL,
    INVOICE_NO          VARCHAR(50),

    /* Who asked. */
    REQUESTED_BY        BIGINT        NOT NULL,
    REQUESTED_BY_NAME   VARCHAR(100),

    /* Who allowed it. Never the same person as the requester. */
    AUTHORISED_BY       BIGINT        NOT NULL,
    AUTHORISED_BY_NAME  VARCHAR(100),

    REASON              VARCHAR(255),

    -- SHA-256 of the token, not the token. The server only ever compares
    -- hashes, so a copy of this table yields nothing that can reopen anything.
    TOKEN_HASH          VARCHAR(64)   NOT NULL,
    ISSUED_AT           TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    EXPIRES_AT          TIMESTAMP(6)  NOT NULL,
    -- Set the moment it is spent. One authorisation, one edit: without this a
    -- single "yes" from a manager would reopen the invoice all afternoon.
    USED_AT             TIMESTAMP(6),

    STATUS              VARCHAR(1)    NOT NULL DEFAULT 'Y',

    INSERTED_BY         VARCHAR(50),
    INSERTED_DATE       TIMESTAMP(6)  DEFAULT CURRENT_TIMESTAMP,
    UPDATED_BY          VARCHAR(50),
    UPDATED_DATE        TIMESTAMP(6),

    CONSTRAINT PK_INVOICE_UNLOCK PRIMARY KEY (PID_UNLOCK),
    CONSTRAINT UX_INVOICE_UNLOCK_TOKEN UNIQUE (TOKEN_HASH),
    CONSTRAINT CK_INVOICE_UNLOCK_STATUS CHECK (STATUS IN ('Y','C')),
    -- Self-authorisation is the whole thing this exists to prevent.
    CONSTRAINT CK_INVOICE_UNLOCK_DISTINCT CHECK (AUTHORISED_BY <> REQUESTED_BY),
    CONSTRAINT CK_INVOICE_UNLOCK_WINDOW CHECK (EXPIRES_AT > ISSUED_AT),
    CONSTRAINT FK_INVOICE_UNLOCK_TRAN
        FOREIGN KEY (PID_TRAN_MST) REFERENCES PHARMA.POS_TRANSACTION_MST (PID_TRAN_MST),
    CONSTRAINT FK_INVOICE_UNLOCK_REQ
        FOREIGN KEY (REQUESTED_BY) REFERENCES PHARMA.ACL_USER (PID_USER),
    CONSTRAINT FK_INVOICE_UNLOCK_AUTH
        FOREIGN KEY (AUTHORISED_BY) REFERENCES PHARMA.ACL_USER (PID_USER)
);

CREATE INDEX IF NOT EXISTS IX_INVOICE_UNLOCK_TRAN ON PHARMA.POS_INVOICE_UNLOCK (PID_TRAN_MST, ISSUED_AT DESC);
CREATE INDEX IF NOT EXISTS IX_INVOICE_UNLOCK_ORG  ON PHARMA.POS_INVOICE_UNLOCK (PID_ORG, ISSUED_AT DESC);

/* ── The trail, in the form anyone auditing would want it ────────────────── */

CREATE OR REPLACE VIEW PHARMA.V_INVOICE_UNLOCK_LOG AS
SELECT u.PID_UNLOCK,
       u.PID_ORG,
       u.PID_BRANCH,
       u.INVOICE_NO,
       t.TRANSACTION_DATE          AS INVOICE_DATE,
       t.AMOUNT_TOBE_PAID          AS INVOICE_AMOUNT,
       u.REQUESTED_BY_NAME,
       u.AUTHORISED_BY_NAME,
       u.REASON,
       u.ISSUED_AT,
       u.USED_AT,
       CASE WHEN u.STATUS = 'C'          THEN 'CANCELLED'
            WHEN u.USED_AT IS NOT NULL   THEN 'USED'
            WHEN u.EXPIRES_AT < CURRENT_TIMESTAMP THEN 'EXPIRED UNUSED'
            ELSE 'OPEN' END        AS OUTCOME
  FROM PHARMA.POS_INVOICE_UNLOCK u
  LEFT JOIN PHARMA.POS_TRANSACTION_MST t ON t.PID_TRAN_MST = u.PID_TRAN_MST;
