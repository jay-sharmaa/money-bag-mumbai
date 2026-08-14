# MoneyBags service and schema division

## Core rule

Every service owns and writes only its own database. A field that identifies a
record owned by another service is a logical reference, validated through an
API or domain event, not a cross-database foreign key.

## Service ownership

| Service | Tables owned | Existing project action |
|---|---|---|
| Identity and Access | `users`, `roles`, `user_roles`, `user_sessions`, `login_audit` | Extract from current `security-service` |
| Customer | `customers`, `customer_addresses`, `kyc_documents`, `beneficiaries` | Extend current `customer-service` |
| Product | `products`, `product_charges` | Keep current `product-service` |
| Bank Organisation | `branches`, `employees` | New service; extract from current `security-service` |
| Account | `accounts`, `account_approvals` | Keep current `account-service` |
| Transaction | `transactions`, `transaction_rail_details`, `transaction_legs`, `funds_holds`, `journal_entries`, `journal_lines`, `clearing_instructions`, `outbox_events`, `transaction_status_history`, `idempotency_records`, `callback_receipts`, `transaction_limit_rules`, `reconciliation_exceptions` | Implemented in `transaction-service` |
| Ledger | `MONEYBAGS_LEDGER` | `ledger_accounts`, `journal_entries`, `journal_lines` | Posts Transaction Service journal facts and owns GL balances |
| Statement | `MONEYBAGS_STATEMENT` | Statement/account/transaction read models and generated exports | Keep `statement-reporting-service` read-only |
| API Gateway | None | None | Keep current `api-gateway` |
| Eureka Server | None | None | Keep current `eureka-server` |

## Identity and Access Service

Tables: `users`, `roles`, `user_roles`, `user_sessions`, `login_audit`.

Local relationships:

```text
users 1 -> many user_roles
roles 1 -> many user_roles
users 1 -> many user_sessions
users 1 -> many login_audit records
```

Outbound logical references held by other services:

```text
customers.user_id -> users.user_id
employees.user_id -> users.user_id
transactions.posted_by_user_id -> users.user_id
```

`customers.user_id` is unique. CIF remains owned by Customer Service; do not
store CIF as the main customer association in `users`.

## Customer Service

Tables: `customers`, `customer_addresses`, `kyc_documents`, `beneficiaries`.

Local relationships:

```text
customers 1 -> many customer_addresses
customers 1 -> many kyc_documents
customers 1 -> many beneficiaries
```

Logical references:

```text
customers.user_id -> Identity / users.user_id
customers.relationship_manager_emp_id -> Bank / employees.emp_id
kyc_documents.verified_by_emp_id -> Bank / employees.emp_id
```

`beneficiaries` belongs here because a beneficiary is a customer-maintained
payee. A future Payment Service can use it but should not own it.

## Product Service

Tables: `products`, `product_charges`.

Local relationship:

```text
products 1 -> many product_charges
```

Logical consumer:

```text
accounts.product_code -> products.product_code
```

## Bank Organisation Service

Tables: `branches`, `employees`.

Local relationship:

```text
branches 1 -> many employees
```

Logical references:

```text
employees.user_id -> Identity / users.user_id
customers.relationship_manager_emp_id -> employees.emp_id
kyc_documents.verified_by_emp_id -> employees.emp_id
account_approvals.emp_id -> employees.emp_id
transactions.performed_by_emp_id -> employees.emp_id
accounts.branch_code -> branches.branch_code
```

## Account Service

Tables: `accounts`, `account_approvals`.

Local relationship:

```text
accounts 1 -> many account_approvals
```

Logical references:

```text
accounts.cif_no -> Customer / customers.cif_no
accounts.product_code -> Product / products.product_code
accounts.branch_code -> Bank / branches.branch_code
account_approvals.emp_id -> Bank / employees.emp_id
```

The account is the required branch relationship. A customer does not require a
direct branch relationship unless the product later needs a home or servicing
branch independent of account ownership.

## Transaction Service

Transaction Service is the financial-orchestration and accounting-fact service. Its Flyway migration is the schema source of truth. Account Service remains authoritative for live ledger and available balances.

Local relationships:

```text
transactions 1 -> many transaction_legs
transactions 1 -> zero/one funds_holds
transactions 1 -> many journal_entries -> many journal_lines
transactions 1 -> zero/one clearing_instructions
transactions 1 -> many outbox_events
transactions 1 -> many transaction_status_history records
transactions.reversal_of_transaction_id -> transactions.transaction_id
```

Logical references:

```text
transactions.source_account_id -> Account Service
transactions.destination_account_id -> Account Service
transactions.maker_user_id / checker_user_id -> Identity Service
```

Ledger rules:

```text
Transaction legs describe customer/account effects.
Journal lines describe accounting effects and each line is debit or credit, never both.
Each posted journal has equal positive debit and credit totals.
Posted financial facts are immutable; reversals use a new linked compensating transaction.
Cross-service balance instructions are committed through the transactional outbox.
```

Keep transaction facts, journals, clearing, history, and outbox records in one service consistency boundary. The local journal is the immutable source fact. After the customer account projection succeeds and the transaction reaches `COMPLETED`, its outbox projection is posted idempotently to Ledger Service. Live customer balances are never stored here.

## Statement Service

Statement Service is read-only. After the transaction reaches `COMPLETED` and
Ledger Service accepts its journal, Transaction Service projects a
posted transaction event into the statement read model. It does not write
account balances or ledger entries. Generated statement files remain an
explicit on-demand or scheduled operation over that read model.

## Services not required yet

Do not create separate Notification, Payment, Reporting, Card, or Loan
services yet. Add them only when they have their own workflow, external
integration, or independent lifecycle.
