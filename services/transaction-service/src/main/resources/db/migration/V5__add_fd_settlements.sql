ALTER TABLE product_purchases ADD COLUMN settlement_type VARCHAR(24) NULL;
ALTER TABLE product_purchases ADD COLUMN settlement_transaction_id VARCHAR(36) NULL;
ALTER TABLE product_purchases ADD COLUMN settled_at TIMESTAMP(6) NULL;
CREATE UNIQUE INDEX uk_product_purchase_settlement
    ON product_purchases (settlement_transaction_id);

CREATE TABLE fd_settlements (
    settlement_id           VARCHAR(36)   NOT NULL,
    transaction_id          VARCHAR(36)   NOT NULL,
    ownership_id            VARCHAR(64)   NOT NULL,
    purchase_transaction_id VARCHAR(36)   NULL,
    source_fd_account_id    VARCHAR(36)   NULL,
    destination_account_id  VARCHAR(36)   NOT NULL,
    principal_amount        DECIMAL(19,4) NOT NULL,
    interest_amount         DECIMAL(19,4) NOT NULL,
    interest_rate           DECIMAL(8,4)  NOT NULL,
    settlement_type         VARCHAR(24)   NOT NULL,
    acquired_on             DATE          NOT NULL,
    maturity_date           DATE          NOT NULL,
    settlement_date         DATE          NOT NULL,
    created_at              TIMESTAMP(6)  NOT NULL,
    PRIMARY KEY (settlement_id),
    CONSTRAINT uk_fd_settlement_transaction UNIQUE (transaction_id),
    CONSTRAINT uk_fd_settlement_ownership UNIQUE (ownership_id),
    CONSTRAINT fk_fd_settlement_transaction FOREIGN KEY (transaction_id)
        REFERENCES transactions(transaction_id),
    CONSTRAINT chk_fd_settlement_type CHECK (
        settlement_type IN ('MATURITY','PREMATURE_BREAK')),
    CONSTRAINT chk_fd_settlement_amounts CHECK (
        principal_amount > 0 AND interest_amount >= 0),
    CONSTRAINT chk_fd_settlement_funding CHECK (
        (purchase_transaction_id IS NOT NULL AND source_fd_account_id IS NULL)
        OR (purchase_transaction_id IS NULL AND source_fd_account_id IS NOT NULL))
);
