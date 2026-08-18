-- FD-12M pays simple ACT/365 interest at maturity. A premature break returns the
-- complete original principal and pays no interest, closure charge, or penalty.

UPDATE product_charges
SET amount = 0.00
WHERE product_code = 'FD-12M'
  AND charge_type = 'PREMATURE_CLOSURE';

UPDATE product_rules
SET rule_value = '0.0'
WHERE product_code = 'FD-12M'
  AND rule_key = 'PREMATURE_PENALTY_PCT';

INSERT INTO product_rules (product_code, rule_key, rule_value, data_type) VALUES
    ('FD-12M', 'MATURITY_INTEREST_METHOD', 'SIMPLE_ACTUAL_365', 'STRING'),
    ('FD-12M', 'MATURITY_PAYOUT', 'PRINCIPAL_PLUS_INTEREST', 'STRING'),
    ('FD-12M', 'PREMATURE_PAYOUT', 'FULL_PRINCIPAL_ONLY', 'STRING');

UPDATE products
SET effective_from = '2026-08-18', updated_at = NOW(6)
WHERE product_code = 'FD-12M';

INSERT INTO product_versions (
    product_code, version_number, product_name, product_type, description, currency,
    interest_rate, min_balance, min_opening_deposit, max_withdrawal_per_day,
    free_txn_per_month, tenure_months, allows_overdraft, requires_funding, min_age,
    status, effective_from, effective_to, recorded_at)
SELECT
    product_code, 2, product_name, product_type, description, currency,
    interest_rate, min_balance, min_opening_deposit, max_withdrawal_per_day,
    free_txn_per_month, tenure_months, allows_overdraft, requires_funding, min_age,
    status, effective_from, effective_to, updated_at
FROM products
WHERE product_code = 'FD-12M';

INSERT INTO product_version_charges (product_version_id, charge_type, amount, frequency)
SELECT pv.product_version_id, pc.charge_type, pc.amount, pc.frequency
FROM product_versions pv
JOIN product_charges pc ON pc.product_code = pv.product_code
WHERE pv.version_number = 2 AND pv.product_code = 'FD-12M';

INSERT INTO product_version_rules (product_version_id, rule_key, rule_value, data_type, active)
SELECT pv.product_version_id, pr.rule_key, pr.rule_value, pr.data_type, pr.active
FROM product_versions pv
JOIN product_rules pr ON pr.product_code = pv.product_code
WHERE pv.version_number = 2 AND pv.product_code = 'FD-12M';
