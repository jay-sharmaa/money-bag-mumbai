ALTER TABLE account_product_ownerships
    ADD COLUMN settlement_status VARCHAR(20) NOT NULL DEFAULT 'NONE';
ALTER TABLE account_product_ownerships
    ADD COLUMN settlement_type VARCHAR(24) NULL;
ALTER TABLE account_product_ownerships
    ADD COLUMN settlement_destination_account_id VARCHAR(36) NULL;
ALTER TABLE account_product_ownerships
    ADD COLUMN settlement_interest_amount DECIMAL(19,4) NULL;
ALTER TABLE account_product_ownerships
    ADD COLUMN settlement_transaction_id VARCHAR(36) NULL;
ALTER TABLE account_product_ownerships
    ADD COLUMN settled_at DATETIME(6) NULL;

CREATE UNIQUE INDEX uk_owned_product_settlement_transaction
    ON account_product_ownerships (settlement_transaction_id);
CREATE INDEX idx_owned_product_maturity
    ON account_product_ownerships (product_type, status, settlement_status, maturity_date);
