CREATE TABLE account_read_models (
  account_id VARCHAR(64) NOT NULL, customer_id VARCHAR(64) NOT NULL, branch_id VARCHAR(64), masked_account_number VARCHAR(32),
  account_name VARCHAR(160), status VARCHAR(24) NOT NULL, currency CHAR(3) NOT NULL, current_balance DECIMAL(19,2) NOT NULL,
  dormant_since DATE, source_updated_at TIMESTAMP(6) NOT NULL, PRIMARY KEY (account_id)
);
CREATE INDEX idx_account_read_customer ON account_read_models(customer_id);
CREATE INDEX idx_account_read_branch_status ON account_read_models(branch_id,status);

CREATE TABLE transaction_read_models (
  transaction_id VARCHAR(64) NOT NULL, ledger_entry_id VARCHAR(64), transaction_reference VARCHAR(64) NOT NULL,
  account_id VARCHAR(64) NOT NULL, customer_id VARCHAR(64) NOT NULL, branch_id VARCHAR(64), direction VARCHAR(8) NOT NULL,
  amount DECIMAL(19,2) NOT NULL, fee_amount DECIMAL(19,2) DEFAULT 0 NOT NULL, currency CHAR(3) NOT NULL,
  transaction_type VARCHAR(32) NOT NULL, status VARCHAR(32) NOT NULL, narration VARCHAR(500), reversal_of_transaction_id VARCHAR(64),
  posted_at TIMESTAMP(6) NOT NULL, balance_after DECIMAL(19,2), source_updated_at TIMESTAMP(6) NOT NULL,
  PRIMARY KEY (transaction_id,account_id,direction)
);
CREATE INDEX idx_tx_read_account_posted ON transaction_read_models(account_id,posted_at);
CREATE INDEX idx_tx_read_branch_posted ON transaction_read_models(branch_id,posted_at);
CREATE INDEX idx_tx_read_ledger ON transaction_read_models(ledger_entry_id);
CREATE INDEX idx_tx_read_source_transaction ON transaction_read_models(transaction_id);

CREATE TABLE consumed_source_events (
  source_event_id VARCHAR(128) NOT NULL, source_type VARCHAR(32) NOT NULL, request_hash CHAR(64) NOT NULL, consumed_at TIMESTAMP(6) NOT NULL,
  PRIMARY KEY (source_event_id)
);

CREATE TABLE statement_requests (
  statement_request_id VARCHAR(36) NOT NULL, request_ref VARCHAR(128) NOT NULL, request_hash CHAR(64) NOT NULL,
  account_id VARCHAR(64) NOT NULL, requested_by_user_id VARCHAR(64) NOT NULL, requested_by_cif VARCHAR(64), requester_branch_id VARCHAR(64),
  from_date DATE NOT NULL, to_date DATE NOT NULL, output_format VARCHAR(8) NOT NULL, statement_kind VARCHAR(16) NOT NULL,
  status VARCHAR(16) NOT NULL, source_snapshot_at TIMESTAMP(6), safe_error_code VARCHAR(64), safe_error_message VARCHAR(500),
  version_no NUMBER(19) DEFAULT 0 NOT NULL, created_at TIMESTAMP(6) NOT NULL, updated_at TIMESTAMP(6) NOT NULL,
  PRIMARY KEY (statement_request_id), CONSTRAINT uk_statement_request_ref UNIQUE (request_ref),
  CONSTRAINT chk_statement_format CHECK (output_format IN ('PDF','CSV','XLSX')),
  CONSTRAINT chk_statement_status CHECK (status IN ('PENDING','GENERATING','READY','FAILED','CANCELLED'))
);
CREATE INDEX idx_statement_account_dates ON statement_requests(account_id,from_date,to_date);
CREATE INDEX idx_statement_queue ON statement_requests(status,created_at);
CREATE INDEX idx_statement_requester ON statement_requests(requested_by_user_id,created_at);

CREATE TABLE generated_statement_files (
  file_id VARCHAR(36) NOT NULL, statement_request_id VARCHAR(36) NOT NULL, storage_key VARCHAR(255) NOT NULL,
  content_type VARCHAR(100) NOT NULL, file_name VARCHAR(255) NOT NULL, file_size_bytes NUMBER(19) NOT NULL,
  checksum_sha256 CHAR(64) NOT NULL, expires_at TIMESTAMP(6) NOT NULL, created_at TIMESTAMP(6) NOT NULL,
  PRIMARY KEY (file_id), CONSTRAINT fk_file_request FOREIGN KEY (statement_request_id) REFERENCES statement_requests(statement_request_id),
  CONSTRAINT uk_file_request UNIQUE (statement_request_id), CONSTRAINT uk_file_storage UNIQUE (storage_key),
  CONSTRAINT chk_file_size CHECK (file_size_bytes > 0)
);

CREATE TABLE statement_download_history (
  download_id VARCHAR(36) NOT NULL, statement_request_id VARCHAR(36) NOT NULL, file_id VARCHAR(36) NOT NULL,
  downloaded_by_user_id VARCHAR(64) NOT NULL, source_ip VARCHAR(64), outcome VARCHAR(16) NOT NULL, reason_code VARCHAR(64), downloaded_at TIMESTAMP(6) NOT NULL,
  PRIMARY KEY (download_id), CONSTRAINT fk_download_request FOREIGN KEY (statement_request_id) REFERENCES statement_requests(statement_request_id),
  CONSTRAINT fk_download_file FOREIGN KEY (file_id) REFERENCES generated_statement_files(file_id),
  CONSTRAINT chk_download_outcome CHECK (outcome IN ('SUCCESS','DENIED','EXPIRED','FAILED'))
);
CREATE INDEX idx_download_requester ON statement_download_history(downloaded_by_user_id,downloaded_at);

CREATE TABLE report_schedules (
  schedule_id VARCHAR(36) NOT NULL, owner_user_id VARCHAR(64) NOT NULL, owner_cif VARCHAR(64), branch_id VARCHAR(64),
  account_id VARCHAR(64), report_type VARCHAR(32) NOT NULL, output_format VARCHAR(8) NOT NULL, frequency VARCHAR(16) NOT NULL,
  next_run_at TIMESTAMP(6) NOT NULL, active NUMBER(1) NOT NULL, last_run_at TIMESTAMP(6), version_no NUMBER(19) DEFAULT 0 NOT NULL,
  created_at TIMESTAMP(6) NOT NULL, updated_at TIMESTAMP(6) NOT NULL, PRIMARY KEY (schedule_id)
);
CREATE INDEX idx_schedule_due ON report_schedules(active,next_run_at);
