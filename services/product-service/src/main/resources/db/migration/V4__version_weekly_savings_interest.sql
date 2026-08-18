-- The seven-day EOD-average implementation pays interest weekly. Preserve the
-- original quarterly terms in version 1 and publish new immutable versions for
-- the two supported savings products.

UPDATE product_rules
SET rule_value = 'WEEKLY'
WHERE product_code IN ('SAV-REG', 'SAV-SENIOR')
  AND rule_key = 'INTEREST_PAYOUT';

UPDATE products
SET effective_from = '2026-08-17', updated_at = NOW(6)
WHERE product_code IN ('SAV-REG', 'SAV-SENIOR');

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
WHERE product_code IN ('SAV-REG', 'SAV-SENIOR');

INSERT INTO product_version_charges (product_version_id, charge_type, amount, frequency)
SELECT pv.product_version_id, pc.charge_type, pc.amount, pc.frequency
FROM product_versions pv
JOIN product_charges pc ON pc.product_code = pv.product_code
WHERE pv.version_number = 2
  AND pv.product_code IN ('SAV-REG', 'SAV-SENIOR');

INSERT INTO product_version_rules (product_version_id, rule_key, rule_value, data_type, active)
SELECT pv.product_version_id, pr.rule_key, pr.rule_value, pr.data_type, pr.active
FROM product_versions pv
JOIN product_rules pr ON pr.product_code = pv.product_code
WHERE pv.version_number = 2
  AND pv.product_code IN ('SAV-REG', 'SAV-SENIOR');
