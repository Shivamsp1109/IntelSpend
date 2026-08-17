-- 003 — Loan structure.
--
-- What a debt actually is, as distinct from what is paid towards it.
--
-- The `recurring` table already tracks EMI payments: an amount, a cadence, a
-- due date. That is enough to say what leaves the account each month and no more.
-- It cannot say what is still owed, what the borrowing costs, when it ends, or
-- whether paying it down early is worth doing — every one of which needs the
-- loan's terms rather than its payment history.
--
-- So this hangs off a recurring entry rather than replacing it. The commitment
-- stays the record of money moving; this is the record of the obligation behind
-- it, and it is optional, because a user who never enters their interest rate
-- should still have their EMI counted in their outgoings.
--
-- Every field that could be missing is nullable on purpose. A loan with an
-- unknown rate is a real and common case — people know their EMI and not their
-- APR — and the engine's job there is to say what it cannot work out, not to
-- assume a plausible figure and present the result as fact.

CREATE TABLE IF NOT EXISTS loan_details (
    id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
    uid                     VARCHAR(128) NOT NULL,

    -- The commitment this describes. One set of terms per tracked EMI.
    recurring_local_id      INT NOT NULL,

    -- DECIMAL, never DOUBLE: a balance that drifts by a paisa per read would
    -- make two payoff comparisons of the same loan disagree.
    principal_outstanding   DECIMAL(18, 2) NOT NULL,
    -- A balance is only true on the day it was read. Without this the figure
    -- silently ages into a number the user has already paid down.
    outstanding_as_of       BIGINT NOT NULL,
    currency                VARCHAR(10) NOT NULL DEFAULT 'INR',

    -- Annual nominal rate as a percentage, e.g. 8.75. Nullable because it is the
    -- field people most often do not know.
    interest_rate           DECIMAL(7, 4) DEFAULT NULL,
    rate_type               ENUM('FIXED', 'VARIABLE', 'UNKNOWN') NOT NULL DEFAULT 'UNKNOWN',
    -- When a variable rate is next repriced, if known. A projection past this
    -- date is guesswork and the engine says so.
    rate_reset_date         BIGINT DEFAULT NULL,
    interest_compounding    ENUM('MONTHLY', 'ANNUAL', 'UNKNOWN') NOT NULL DEFAULT 'UNKNOWN',

    -- The contractual payment, which is not always what the recurring entry
    -- observed: a part-payment or a bounced month would make the two differ, and
    -- the schedule should be computed from the contract.
    scheduled_payment       DECIMAL(18, 2) DEFAULT NULL,
    payment_frequency       ENUM('MONTHLY', 'QUARTERLY', 'YEARLY', 'WEEKLY', 'BIWEEKLY')
                                NOT NULL DEFAULT 'MONTHLY',
    remaining_installments  INT DEFAULT NULL,
    next_payment_date       BIGINT DEFAULT NULL,

    -- Enumerated rather than free text. A payoff comparison has to subtract this
    -- from the interest saved, and "2% or ₹5000 whichever is higher" typed into a
    -- text box cannot drive a deterministic calculation — so an unrecognised
    -- arrangement is recorded as UNKNOWN and the engine declines to net it off.
    prepayment_charge_type  ENUM('NONE', 'FLAT', 'PERCENT_OF_PRINCIPAL', 'UNKNOWN')
                                NOT NULL DEFAULT 'UNKNOWN',
    prepayment_charge_value DECIMAL(18, 4) DEFAULT NULL,

    -- Standing charges the schedule does not capture, so a total cost figure can
    -- say it excluded them rather than quietly understating.
    fees_or_penalties       DECIMAL(18, 2) DEFAULT NULL,

    updated_at              BIGINT NOT NULL,

    UNIQUE KEY unique_loan_per_commitment (uid, recurring_local_id),

    CONSTRAINT fk_loan_details_user
        FOREIGN KEY (uid) REFERENCES users(uid)
        ON DELETE CASCADE,
    -- Composite, matching the recurring table's own key: deleting the commitment
    -- takes its terms with it rather than leaving orphaned figures behind.
    CONSTRAINT fk_loan_details_recurring
        FOREIGN KEY (uid, recurring_local_id) REFERENCES recurring(uid, local_id)
        ON DELETE CASCADE
);
