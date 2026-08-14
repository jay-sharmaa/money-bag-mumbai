# Oracle-backed transaction pipeline

The financial flow is implemented as an ordered, retryable pipeline:

```text
Transaction accepted
  -> balanced journal fact committed in Transaction Service
  -> customer account projection applied
  -> transaction marked COMPLETED
  -> identical journal posted in Ledger Service and GL balances updated
  -> transaction entry projected into Statement Reporting Service
```

Every boundary uses the Transaction Service transactional outbox. Ledger publication is blocked until the transaction has reached `COMPLETED`, and statement publication is blocked until every ledger journal for that transaction has been posted. Ledger journal references, account projection keys, and statement source-event IDs are stable, so retries are idempotent. If Ledger Service is unavailable, the completed transaction remains intact while ledger and statement events retry. Statement Reporting stores the posted transaction in its read model; PDF/CSV/XLSX documents are still generated only when a statement request is made.

## 1. Prepare local Oracle

Install Oracle Database Free (or XE) and connect from Oracle SQL Developer as `SYS` with role `SYSDBA`. Run [oracle-local-setup.sql](oracle-local-setup.sql) with **Run Script** (`F5`). This creates one schema per service:

- `MONEYBAGS_TRANSACTION`
- `MONEYBAGS_LEDGER`
- `MONEYBAGS_STATEMENT`

All database-backed services read the same `DBURL`, `DBUSER`, and `DBPASSWORD` settings. Their configured defaults point to the shared Oracle connection, while environment variables can override those values without editing YAML.

```powershell
$env:DBURL='jdbc:oracle:thin:@//localhost:1521/XEPDB1'
$env:DBUSER='your-user'
$env:DBPASSWORD='your-password'
```

Transaction, Ledger, and Statement Reporting use separate Flyway history tables. Ledger journals use `ledger_journal_entries` and `ledger_journal_lines`, avoiding collisions with Transaction Service journal facts when all services share one Oracle schema.

## 2. Start the services

```powershell
.\start.ps1
```

The script enables the Maven `oracle` profile for Transaction, Ledger, and Statement Reporting services. On first use Maven downloads the Oracle JDBC driver and Flyway Oracle support. Flyway then creates each service-owned schema automatically.

Direct service URLs default to:

- Ledger: `http://localhost:8085`
- Statement Reporting: `http://localhost:8086`

They can be overridden with `LEDGER_SERVICE_URL` and `STATEMENT_SERVICE_URL`.

## 3. Verify a completed transaction

Use the transaction API to create a transaction, then inspect:

1. Transaction Service `journal_entries`, `journal_lines`, and `outbox_events`.
2. Ledger Service `journal_entries`, `journal_lines`, and `ledger_accounts`.
3. Statement Service `transaction_read_models` and `consumed_source_events`.

The transaction status remains `PROJECTION_PENDING` (or `PROCESSING` while external settlement is pending) until its customer account projection succeeds. Only after it reaches `COMPLETED` is the journal visible in Ledger Service; Statement Reporting follows the successful ledger post.

## Tests

Tests use H2 in Oracle compatibility mode and exercise the same Flyway SQL:

```powershell
mvn -q -f transaction-service/pom.xml test
mvn -q -f ledger-service/pom.xml test
mvn -q -f statement-reporting-service/pom.xml test
```
api-gateway - 8090
customer-service - 8082
eureka-server - 8080
ledger-service - 8085
statement-service - 8086
transaction-service - 8084