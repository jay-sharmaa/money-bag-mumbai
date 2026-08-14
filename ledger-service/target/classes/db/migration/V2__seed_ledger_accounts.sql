INSERT INTO ledger_accounts(code, name, account_type, normal_side, balance, currency_code, active, created_at, updated_at, version)
VALUES ('110100', 'Cash and Settlement Asset', 'ASSET', 'DEBIT', 0, 'INR', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0);

INSERT INTO ledger_accounts(code, name, account_type, normal_side, balance, currency_code, active, created_at, updated_at, version)
VALUES ('210000', 'Customer Deposit Control', 'LIABILITY', 'CREDIT', 0, 'INR', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0);

INSERT INTO ledger_accounts(code, name, account_type, normal_side, balance, currency_code, active, created_at, updated_at, version)
VALUES ('220100', 'Internal Payment Clearing', 'CLEARING', 'CREDIT', 0, 'INR', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0);

INSERT INTO ledger_accounts(code, name, account_type, normal_side, balance, currency_code, active, created_at, updated_at, version)
VALUES ('220200', 'External Clearing', 'CLEARING', 'CREDIT', 0, 'INR', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0);

INSERT INTO ledger_accounts(code, name, account_type, normal_side, balance, currency_code, active, created_at, updated_at, version)
VALUES ('410100', 'Payment Fee Income', 'INCOME', 'CREDIT', 0, 'INR', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0);
