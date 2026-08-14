CREATE TABLE transactions (
    transaction_id VARCHAR(36) NOT NULL,
    transaction_reference VARCHAR(64) NOT NULL,
    transaction_type VARCHAR(32) NOT NULL,
    rail VARCHAR(24) NOT NULL,
    payment_channel VARCHAR(24) NOT NULL,
    payment_method VARCHAR(24) NOT NULL,
    source_account_id VARCHAR(64),
    destination_account_id VARCHAR(64),
    customer_id VARCHAR(64),
    beneficiary_id NUMBER(19),
    amount DECIMAL(19,4) NOT NULL,
    fee_amount DECIMAL(19,4) DEFAULT 0 NOT NULL,
    currency CHAR(3) NOT NULL,
    status VARCHAR(32) NOT NULL,
    maker_user_id VARCHAR(64) NOT NULL,
    checker_user_id VARCHAR(64),
    branch_code VARCHAR(32),
    narration VARCHAR(500),
    approval_required NUMBER(1) DEFAULT 0 NOT NULL,
    approved_at TIMESTAMP(6),
    rejection_reason VARCHAR(500),
    reversal_of_transaction_id VARCHAR(36),
    correlation_id VARCHAR(64) NOT NULL,
    version NUMBER(19) DEFAULT 0 NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    completed_at TIMESTAMP(6),
    PRIMARY KEY (transaction_id),
    CONSTRAINT uk_transactions_reference UNIQUE (transaction_reference),
    CONSTRAINT uk_transactions_reversal UNIQUE (reversal_of_transaction_id),
    CONSTRAINT fk_transactions_reversal FOREIGN KEY (reversal_of_transaction_id) REFERENCES transactions(transaction_id),
    CONSTRAINT chk_transactions_amount CHECK (amount > 0),
    CONSTRAINT chk_transactions_fee CHECK (fee_amount >= 0),
    CONSTRAINT chk_transactions_accounts CHECK (source_account_id IS NOT NULL OR destination_account_id IS NOT NULL)
);
CREATE INDEX idx_transactions_source_created ON transactions(source_account_id, created_at);
CREATE INDEX idx_transactions_destination_created ON transactions(destination_account_id, created_at);
CREATE INDEX idx_transactions_filters ON transactions(status, rail, transaction_type, created_at);
CREATE INDEX idx_transactions_maker ON transactions(maker_user_id, created_at);

CREATE TABLE transaction_rail_details (
    transaction_id VARCHAR(36) NOT NULL,
    upi_address VARCHAR(160),
    cheque_number VARCHAR(64),
    card_id VARCHAR(128),
    client_reference VARCHAR(128),
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (transaction_id),
    CONSTRAINT fk_rail_details_transaction FOREIGN KEY (transaction_id) REFERENCES transactions(transaction_id)
);

CREATE TABLE idempotency_records (
    idempotency_id VARCHAR(36) NOT NULL,
    caller_scope VARCHAR(128) NOT NULL,
    operation VARCHAR(80) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    transaction_id VARCHAR(36),
    state VARCHAR(20) NOT NULL,
    response_code NUMBER(10),
    created_at TIMESTAMP(6) NOT NULL,
    completed_at TIMESTAMP(6),
    PRIMARY KEY (idempotency_id),
    CONSTRAINT uk_idempotency_scope UNIQUE (caller_scope, operation, idempotency_key),
    CONSTRAINT fk_idempotency_transaction FOREIGN KEY (transaction_id) REFERENCES transactions(transaction_id)
);

CREATE TABLE transaction_legs (
    leg_id VARCHAR(36) NOT NULL,
    transaction_id VARCHAR(36) NOT NULL,
    sequence_no NUMBER(10) NOT NULL,
    leg_role VARCHAR(24) NOT NULL,
    direction VARCHAR(8) NOT NULL,
    account_id VARCHAR(64),
    amount DECIMAL(19,4) NOT NULL,
    currency CHAR(3) NOT NULL,
    description VARCHAR(255) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (leg_id),
    CONSTRAINT uk_transaction_leg_sequence UNIQUE (transaction_id, sequence_no),
    CONSTRAINT fk_legs_transaction FOREIGN KEY (transaction_id) REFERENCES transactions(transaction_id),
    CONSTRAINT chk_legs_amount CHECK (amount > 0),
    CONSTRAINT chk_legs_direction CHECK (direction IN ('DEBIT', 'CREDIT'))
);

CREATE TABLE funds_holds (
    hold_id VARCHAR(36) NOT NULL,
    transaction_id VARCHAR(36) NOT NULL,
    account_id VARCHAR(64) NOT NULL,
    external_hold_id VARCHAR(128) NOT NULL,
    amount DECIMAL(19,4) NOT NULL,
    currency CHAR(3) NOT NULL,
    status VARCHAR(20) NOT NULL,
    operation_key VARCHAR(128) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (hold_id),
    CONSTRAINT uk_hold_transaction UNIQUE (transaction_id),
    CONSTRAINT uk_hold_external UNIQUE (external_hold_id),
    CONSTRAINT uk_hold_operation UNIQUE (operation_key),
    CONSTRAINT fk_holds_transaction FOREIGN KEY (transaction_id) REFERENCES transactions(transaction_id),
    CONSTRAINT chk_holds_amount CHECK (amount > 0)
);

CREATE TABLE journal_entries (
    journal_id VARCHAR(36) NOT NULL,
    transaction_id VARCHAR(36) NOT NULL,
    journal_reference VARCHAR(64) NOT NULL,
    journal_type VARCHAR(32) NOT NULL,
    status VARCHAR(20) NOT NULL,
    total_debit DECIMAL(19,4) NOT NULL,
    total_credit DECIMAL(19,4) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    posted_at TIMESTAMP(6),
    PRIMARY KEY (journal_id),
    CONSTRAINT uk_journal_reference UNIQUE (journal_reference),
    CONSTRAINT uk_journal_transaction_type UNIQUE (transaction_id, journal_type),
    CONSTRAINT fk_journals_transaction FOREIGN KEY (transaction_id) REFERENCES transactions(transaction_id),
    CONSTRAINT chk_journal_balanced CHECK (total_debit = total_credit AND total_debit > 0)
);

CREATE TABLE journal_lines (
    journal_line_id VARCHAR(36) NOT NULL,
    journal_id VARCHAR(36) NOT NULL,
    line_no INT NOT NULL,
    ledger_account_code VARCHAR(32) NOT NULL,
    account_id VARCHAR(64),
    debit DECIMAL(19,4) DEFAULT 0 NOT NULL,
    credit DECIMAL(19,4) DEFAULT 0 NOT NULL,
    description VARCHAR(255) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (journal_line_id),
    CONSTRAINT uk_journal_line_no UNIQUE (journal_id, line_no),
    CONSTRAINT fk_lines_journal FOREIGN KEY (journal_id) REFERENCES journal_entries(journal_id),
    CONSTRAINT chk_journal_line_side CHECK ((debit > 0 AND credit = 0) OR (credit > 0 AND debit = 0))
);

CREATE TABLE clearing_instructions (
    clearing_instruction_id VARCHAR(36) NOT NULL,
    transaction_id VARCHAR(36) NOT NULL,
    rail VARCHAR(24) NOT NULL,
    external_reference VARCHAR(128),
    status VARCHAR(24) NOT NULL,
    amount DECIMAL(19,4) NOT NULL,
    currency CHAR(3) NOT NULL,
    settlement_date DATE,
    failure_reason VARCHAR(500),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (clearing_instruction_id),
    CONSTRAINT uk_clearing_transaction UNIQUE (transaction_id),
    CONSTRAINT fk_clearing_transaction FOREIGN KEY (transaction_id) REFERENCES transactions(transaction_id),
    CONSTRAINT chk_clearing_amount CHECK (amount > 0)
);

CREATE TABLE outbox_events (
    event_id VARCHAR(36) NOT NULL,
    aggregate_type VARCHAR(40) NOT NULL,
    aggregate_id VARCHAR(36) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    deduplication_key VARCHAR(160) NOT NULL,
    payload CLOB NOT NULL,
    status VARCHAR(20) NOT NULL,
    attempts NUMBER(10) DEFAULT 0 NOT NULL,
    next_attempt_at TIMESTAMP(6),
    created_at TIMESTAMP(6) NOT NULL,
    published_at TIMESTAMP(6),
    last_error VARCHAR(500),
    version NUMBER(19) DEFAULT 0 NOT NULL,
    PRIMARY KEY (event_id),
    CONSTRAINT uk_outbox_dedup UNIQUE (deduplication_key)
);
CREATE INDEX idx_outbox_delivery ON outbox_events(status, next_attempt_at, created_at);

CREATE TABLE transaction_status_history (
    history_id VARCHAR(36) NOT NULL,
    transaction_id VARCHAR(36) NOT NULL,
    from_status VARCHAR(32),
    to_status VARCHAR(32) NOT NULL,
    actor_id VARCHAR(64) NOT NULL,
    actor_source VARCHAR(32) NOT NULL,
    reason VARCHAR(500),
    correlation_id VARCHAR(64) NOT NULL,
    occurred_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (history_id),
    CONSTRAINT fk_status_history_transaction FOREIGN KEY (transaction_id) REFERENCES transactions(transaction_id)
);
CREATE INDEX idx_status_history_transaction ON transaction_status_history(transaction_id, occurred_at);

CREATE TABLE callback_receipts (
    callback_receipt_id VARCHAR(36) NOT NULL,
    transaction_id VARCHAR(36) NOT NULL,
    callback_type VARCHAR(32) NOT NULL,
    provider_event_id VARCHAR(128) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    processed_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (callback_receipt_id),
    CONSTRAINT uk_callback_provider_event UNIQUE (callback_type, provider_event_id),
    CONSTRAINT fk_callback_transaction FOREIGN KEY (transaction_id) REFERENCES transactions(transaction_id)
);

CREATE TABLE transaction_limit_rules (
    limit_rule_id VARCHAR(36) NOT NULL,
    transaction_type VARCHAR(32),
    rail VARCHAR(24),
    payment_channel VARCHAR(24),
    currency CHAR(3) NOT NULL,
    min_amount DECIMAL(19,4),
    max_amount DECIMAL(19,4),
    daily_limit DECIMAL(19,4),
    approval_threshold DECIMAL(19,4),
    priority NUMBER(10) DEFAULT 0 NOT NULL,
    active NUMBER(1) DEFAULT 1 NOT NULL,
    effective_from TIMESTAMP(6) NOT NULL,
    effective_to TIMESTAMP(6),
    PRIMARY KEY (limit_rule_id)
);
CREATE INDEX idx_limit_rules_match ON transaction_limit_rules(active, transaction_type, rail, payment_channel, priority);

CREATE TABLE reconciliation_exceptions (
    exception_id VARCHAR(36) NOT NULL,
    exception_type VARCHAR(64) NOT NULL,
    severity VARCHAR(16) NOT NULL,
    transaction_id VARCHAR(36),
    business_reference VARCHAR(128),
    status VARCHAR(20) NOT NULL,
    evidence CLOB NOT NULL,
    assigned_to VARCHAR(64),
    resolution VARCHAR(1000),
    detected_at TIMESTAMP(6) NOT NULL,
    resolved_at TIMESTAMP(6),
    version NUMBER(19) DEFAULT 0 NOT NULL,
    PRIMARY KEY (exception_id),
    CONSTRAINT fk_reconciliation_transaction FOREIGN KEY (transaction_id) REFERENCES transactions(transaction_id)
);
CREATE INDEX idx_reconciliation_queue ON reconciliation_exceptions(status, severity, detected_at);

INSERT INTO transaction_limit_rules(limit_rule_id, transaction_type, rail, payment_channel, currency, min_amount, max_amount, daily_limit, approval_threshold, priority, active, effective_from)
VALUES ('00000000-0000-0000-0000-000000000001', NULL, 'RTGS', NULL, 'INR', 200000.0000, NULL, NULL, 1000000.0000, 100, 1, CURRENT_TIMESTAMP);
