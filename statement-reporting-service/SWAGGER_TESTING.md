# Swagger-only testing

Only Eureka, local Oracle, and Statement Reporting Service are required for this test. The dummy Account and Transaction/Ledger data is inserted through the service's internal projection endpoints, so Account Service and Transaction Service may remain stopped.

## 1. Start the services

From the repository root, start Eureka in one PowerShell window:

```powershell
mvn.cmd -f eureka-server\pom.xml spring-boot:run
```

Start Statement Reporting Service in another window:

```powershell
mvn.cmd -f statement-reporting-service\pom.xml clean spring-boot:run
```

Open Swagger at `http://localhost:8086/swagger-ui.html` and use **Try it out** for every call below.

## 2. Insert the account first

Use `POST /internal/v1/statement-read-model/accounts`.

Header:

```text
X-Service-Name: account-service
```

Body:

```json
{
  "sourceEventId": "account-event-001",
  "accountId": "account-001",
  "customerId": "cif-001",
  "branchId": "branch-001",
  "maskedAccountNumber": "XXXX1234",
  "accountName": "Hriday Savings Account",
  "status": "ACTIVE",
  "currency": "INR",
  "currentBalance": 1040.00,
  "dormantSince": null,
  "sourceUpdatedAt": "2026-08-02T11:31:00Z"
}
```

## 3. Insert two transactions

Use `POST /internal/v1/statement-read-model/transactions` twice.

Header for both calls:

```text
X-Service-Name: transaction-service
```

First body:

```json
{
  "sourceEventId": "transaction-event-001",
  "transactionId": "transaction-001",
  "ledgerEntryId": "ledger-entry-001",
  "transactionReference": "MB-DEMO-001",
  "accountId": "account-001",
  "customerId": "cif-001",
  "branchId": "branch-001",
  "direction": "CREDIT",
  "amount": 500.00,
  "feeAmount": 0.00,
  "currency": "INR",
  "transactionType": "DEPOSIT",
  "status": "SUCCESS",
  "narration": "Cash deposit",
  "reversalOfTransactionId": null,
  "postedAt": "2026-08-01T10:00:00Z",
  "balanceAfter": 1250.00,
  "sourceUpdatedAt": "2026-08-01T10:00:01Z"
}
```

Second body:

```json
{
  "sourceEventId": "transaction-event-002",
  "transactionId": "transaction-002",
  "ledgerEntryId": "ledger-entry-002",
  "transactionReference": "MB-DEMO-002",
  "accountId": "account-001",
  "customerId": "cif-001",
  "branchId": "branch-001",
  "direction": "DEBIT",
  "amount": 200.00,
  "feeAmount": 10.00,
  "currency": "INR",
  "transactionType": "WITHDRAWAL",
  "status": "SUCCESS",
  "narration": "ATM withdrawal",
  "reversalOfTransactionId": null,
  "postedAt": "2026-08-02T11:30:00Z",
  "balanceAfter": 1040.00,
  "sourceUpdatedAt": "2026-08-02T11:30:01Z"
}
```

## 4. Headers for customer endpoints

Swagger now displays these fields on statement/report operations. Enter:

```text
X-User-Id: user-001
X-Customer-Id: cif-001
X-Permissions: STATEMENT_VIEW,REPORT_VIEW
X-Correlation-Id: swagger-test-001
```

Leave `X-Employee-Id` and `X-Branch-Id` empty.

## 5. Test the mini statement

Use `GET /api/v1/statements/accounts/{accountId}/mini`:

```text
accountId: account-001
size: 10
```

The response should contain both dummy transactions.

## 6. Generate a statement

Use `POST /api/v1/statements/accounts/{accountId}`:

```text
accountId: account-001
Idempotency-Key: statement-pdf-001
```

Body:

```json
{
  "fromDate": "2026-08-01",
  "toDate": "2026-08-31",
  "outputFormat": "PDF",
  "statementKind": "MONTHLY"
}
```

Copy `requestId` from the `202` response. Wait about three seconds, then use `GET /api/v1/statements/requests/{id}` with that ID. Its status should be `READY`.

## 7. Download it

1. Call `POST /api/v1/statements/requests/{id}/download-link` using the request ID.
2. Copy `file.fileId` from the status response.
3. Copy only the value after `token=` from the returned URL.
4. Call `GET /api/v1/statements/files/{fileId}/download` and enter the file ID and token.

For CSV or Excel, repeat step 6 with `CSV` or `XLSX` and use a new idempotency key, such as `statement-csv-001` or `statement-xlsx-001`.

## Common errors

- `403 AUTHENTICATION_REQUIRED`: a required Swagger header was left empty.
- `403 ACCOUNT_SCOPE_DENIED`: `X-Customer-Id` is not `cif-001`.
- `409 IDEMPOTENCY_CONFLICT`: reuse a new `Idempotency-Key`.
- `FAILED / SOURCE_BALANCE_MISMATCH`: the account `currentBalance` does not agree with the latest transaction `balanceAfter`.
- Wrong schema shown: stop the service, run the clean start command above, then hard-refresh Swagger with `Ctrl+F5`.
