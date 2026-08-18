# Seed fixtures

Cross-service constants used by the Flyway seed migrations.

## The rule

**Each service seeds only its own tables, in its own migration, using hardcoded literal
identifiers.** No seed migration may call another service.

Services start in parallel, and Flyway runs during each service's own startup. A seed
that needed to look something up in another service would deadlock the whole system on
first boot. Everything below is therefore duplicated as a literal in several places — and
those literals must agree.

## Users and employees

`identity-service` owns the users; `branch-employee-service` owns the employees. The link
between them is denormalised onto `users.employee_id` / `users.branch_code` so the gateway
can resolve a session in one call.

| user_id | username | password | employee_id | branch_code | role |
|---|---|---|---|---|---|
| 1 | `teller1` | `Password@123` | 1001 | BR001 | TELLER |
| 2 | `checker1` | `Password@123` | 1002 | BR001 | CHECKER |
| 3 | `manager1` | `Password@123` | 1003 | BR002 | BRANCH_MANAGER |
| 4 | `opsadmin` | `Password@123` | 1004 | BR001 | OPS_ADMIN |

The password hash is a **precomputed BCrypt literal**, never generated at runtime — a seed
that produced a different hash on every run would not be a fixture.

**teller1 and checker1 are both at BR001 on purpose.** They are the maker-checker pair;
an approval across branches is rejected by branch scoping, so a smoke test needs them in
the same branch.

**`emp_id` 1002 is load-bearing:** customer-service names it as the relationship manager
for CIF900101.

## Branches

| id | branch_code | name | ifsc |
|---|---|---|---|
| 501 | BR001 | Mumbai Fort Main | MBAG0000001 |
| 502 | BR002 | Pune Hinjewadi | MBAG0000002 |

`branch_code` is the value carried downstream as **both** `X-Branch-Code` (transaction-service)
and `X-Branch-Id` (statement-reporting-service). If those two ever disagree, statement
branch scoping denies every request.

## Customers

| cif_no | name | kyc_status | user_id | relationship manager |
|---|---|---|---|---|
| CIF900101 | Vikram Rao | VERIFIED | 1 | 1002 |
| CIF900102 | Ananya Deshmukh | PENDING | 2 | 1002 |

`cif_no` is also the `accountHolderId` in transaction-service and the `customerId` in
statement-reporting-service. One value, three names.

## Products

| code | type | rate | min balance | tenure |
|---|---|---|---|---|
| SAV-REG | SAVINGS | 3.50% | 1000 | — |
| SAV-SENIOR | SAVINGS | 4.25% | 500 | — |
| CUR-BASIC | CURRENT | 0.00% | 5000 | — (overdraft 25000) |
| FD-12M | TERM_DEPOSIT | 6.75% | 0 | 12 |
| FD-24M | TERM_DEPOSIT | 7.10% | 0 | 24 |
| RD-12M | RECURRING_DEPOSIT | 6.50% | 0 | 12 |

account-service **snapshots** these terms onto an account at opening rather than
referencing them, so a later rate change cannot rewrite the terms of an account already
open.

## Accounts

Fixed UUIDs so tests and the smoke script can address them directly.

| account_id | number | cif | product | branch | balance |
|---|---|---|---|---|---|
| `a0000000-…-101` | 510000000101 | CIF900101 | SAV-REG | BR001 | 50,000 |
| `a0000000-…-102` | 510000000102 | CIF900101 | CUR-BASIC | BR001 | 120,000 |
| `a0000000-…-103` | 520000000103 | CIF900102 | SAV-SENIOR | BR002 | 8,000 |

Each seeded account also gets a **PENDING `account_outbox` row**, so the scheduled
publisher backfills the statement read model on first run. Without those, a statement for
a seeded account falls back to the source API instead of reading the projection.

## General ledger

Control accounts `110100`, `210000`, `210100`, `220100`, `220200`, `410100`, `510100`, referenced by
configuration in both transaction-service and ledger-service. `V3` realigns them from USD
to **INR** to match the rest of the system.

`210100` is the static **Term Deposit Control** liability used by the `FD-12M`
product-purchase journal.

`510100` is the static **Savings Interest Expense** account. Savings interest accrues weekly,
and each 52-week payout debits
this account and credit `210000` (**Customer Deposit Control**) for the receiving account.

## Permissions

The permission strings are matched literally by code that already exists. These must not
be renamed without changing the services that check them:

`TRANSACTION_CREATE`, `TRANSACTION_APPROVE`, `TRANSACTION_CANCEL`, `TRANSACTION_CANCEL_ANY`,
`TRANSACTION_REVERSE`, `TRANSACTION_VIEW`, `TRANSACTION_VIEW_ALL_BRANCHES`,
`RECONCILIATION_MANAGE`, `STATEMENT_VIEW`, `REPORT_VIEW`, `REPORT_ADMIN`,
`ACCOUNT_VIEW`, `ACCOUNT_VIEW_ALL_BRANCHES`, `ACCOUNT_OPEN`, `ACCOUNT_APPROVE`,
`ACCOUNT_STATUS_MANAGE`, `CUSTOMER_READ`, `CUSTOMER_UPDATE`, `KYC_VERIFY`,
`PRODUCT_READ`, `PRODUCT_MANAGE`, `USER_MANAGE`, `ROLE_PERMISSION_MANAGE`,
`BRANCH_MANAGE`, `EMPLOYEE_MANAGE`, `CONFIG_MANAGE`, `NOTIFICATION_MANAGE`, `AUDIT_VIEW`.
