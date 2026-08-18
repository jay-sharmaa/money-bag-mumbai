ALTER TABLE interest_accruals ADD COLUMN payout_batch_id VARCHAR(36) NULL;
CREATE INDEX idx_accruals_unpaid
    ON interest_accruals (account_id, posted, accrual_date);
CREATE INDEX idx_accruals_payout_batch
    ON interest_accruals (payout_batch_id);

CREATE TABLE interest_payout_batches (
    batch_id                VARCHAR(36)   NOT NULL,
    account_id              VARCHAR(36)   NOT NULL,
    period_start_date       DATE          NOT NULL,
    period_end_date         DATE          NOT NULL,
    weekly_accrual_count    INT           NOT NULL,
    accrued_amount          DECIMAL(19,6) NOT NULL,
    payout_amount           DECIMAL(19,4) NOT NULL,
    status                  VARCHAR(20)   NOT NULL,
    payout_transaction_id   VARCHAR(36)   NULL,
    created_at              DATETIME(6)   NOT NULL,
    completed_at            DATETIME(6)   NULL,
    PRIMARY KEY (batch_id),
    CONSTRAINT uk_interest_payout_account_period UNIQUE (account_id, period_end_date),
    CONSTRAINT chk_interest_payout_week_count CHECK (weekly_accrual_count = 52),
    CONSTRAINT chk_interest_payout_status CHECK (
        status IN ('PENDING','PAYOUT_QUEUED','COMPLETED')),
    KEY idx_interest_payout_status (status, created_at)
) ENGINE = InnoDB;
