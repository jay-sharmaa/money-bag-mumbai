# Ledger Service API testing guide

The service runs directly at `http://localhost:8085`.

- Swagger UI: `http://localhost:8085/swagger-ui.html`
- OpenAPI JSON: `http://localhost:8085/api-docs`
- Gateway Swagger UI (when Eureka and Gateway are running): `http://localhost:8090/swagger-ui.html`

In Swagger UI, open an operation, select **Try it out**, fill the parameters/body, and select **Execute**.

## 1. List seeded ledger accounts

```http
GET /api/v1/ledger/accounts
```

Expected codes: `110100`, `210000`, `220100`, `220200`, and `410100`.

## 2. Get one ledger account

```http
GET /api/v1/ledger/accounts/410100
```

## 3. Get one ledger balance

```http
GET /api/v1/ledger/accounts/110100/balance
```

## 4. Post a deposit journal

```http
POST /api/v1/ledger/journals
Content-Type: application/json
```

```json
{
  "journalReference": "DEMO-1001-DEPOSIT",
  "transactionId": 1001,
  "journalType": "DEPOSIT",
  "description": "Deposit 500 into account 10001",
  "currencyCode": "INR",
  "createdBy": "swagger-test",
  "lines": [
    {
      "ledgerCode": "110100",
      "side": "DEBIT",
      "amount": 500.00,
      "description": "Cash received"
    },
    {
      "ledgerCode": "210000",
      "customerAccountId": 10001,
      "side": "CREDIT",
      "amount": 500.00,
      "description": "Increase customer deposit liability"
    }
  ]
}
```

Save the returned `id`; it is the journal ID used by the ID and reversal endpoints. Posting the identical request again returns the same journal and does not change balances twice.

## 5. Post a withdrawal journal

```json
{
  "journalReference": "DEMO-1002-WITHDRAWAL",
  "transactionId": 1002,
  "journalType": "WITHDRAWAL",
  "description": "Withdraw 40 and charge a 1 fee",
  "currencyCode": "INR",
  "createdBy": "swagger-test",
  "lines": [
    {
      "ledgerCode": "210000",
      "customerAccountId": 10001,
      "side": "DEBIT",
      "amount": 41.00
    },
    {
      "ledgerCode": "110100",
      "side": "CREDIT",
      "amount": 40.00
    },
    {
      "ledgerCode": "410100",
      "side": "CREDIT",
      "amount": 1.00
    }
  ]
}
```

## 6. Post the payer side of an internal transfer

```json
{
  "journalReference": "DEMO-1003-PAYER",
  "transactionId": 1003,
  "journalType": "TRANSFER_PAYER",
  "description": "Transfer 250 from account 10001 with a 2 fee",
  "currencyCode": "INR",
  "createdBy": "swagger-test",
  "lines": [
    {
      "ledgerCode": "210000",
      "customerAccountId": 10001,
      "side": "DEBIT",
      "amount": 252.00
    },
    {
      "ledgerCode": "220100",
      "side": "CREDIT",
      "amount": 250.00
    },
    {
      "ledgerCode": "410100",
      "side": "CREDIT",
      "amount": 2.00
    }
  ]
}
```

## 7. Settle the internal transfer

```json
{
  "journalReference": "DEMO-1003-CLEAR",
  "transactionId": 1003,
  "journalType": "TRANSFER_SETTLEMENT",
  "description": "Settle 250 to account 20001",
  "currencyCode": "INR",
  "createdBy": "swagger-test",
  "lines": [
    {
      "ledgerCode": "220100",
      "side": "DEBIT",
      "amount": 250.00
    },
    {
      "ledgerCode": "210000",
      "customerAccountId": 20001,
      "side": "CREDIT",
      "amount": 250.00
    }
  ]
}
```

After steps 6 and 7, `GET /api/v1/ledger/accounts/220100/balance` should show that internal clearing returned to its prior balance.

## 8. Post an external-clearing journal

```json
{
  "journalReference": "DEMO-1004-EXTERNAL",
  "transactionId": 1004,
  "journalType": "EXTERNAL_TRANSFER",
  "description": "Move 75 into external clearing",
  "currencyCode": "INR",
  "createdBy": "swagger-test",
  "lines": [
    {
      "ledgerCode": "210000",
      "customerAccountId": 10001,
      "side": "DEBIT",
      "amount": 75.00
    },
    {
      "ledgerCode": "220200",
      "side": "CREDIT",
      "amount": 75.00
    }
  ]
}
```

## 9. Reverse a journal

Replace `{journalId}` with the `id` returned by step 8.

```http
POST /api/v1/ledger/journals/{journalId}/reverse
Content-Type: application/json
```

```json
{
  "journalReference": "REV-DEMO-1004-EXTERNAL",
  "description": "Reverse external transfer test",
  "createdBy": "swagger-test"
}
```

The reversal response has `reversalOfJournalId` equal to the original ID. Reversing the same journal again returns `409 Conflict`.

## 10. Query journals

```http
GET /api/v1/ledger/journals/{id}
GET /api/v1/ledger/journals/reference/DEMO-1001-DEPOSIT
GET /api/v1/ledger/journals
GET /api/v1/ledger/journals?transactionId=1003
GET /api/v1/ledger/journals?customerAccountId=10001
GET /api/v1/ledger/customer-accounts/10001/entries
```

The customer-account entries endpoint is an audit association. It does not represent the customer's spendable balance.

## 11. Verify unbalanced-journal rejection

```json
{
  "journalReference": "DEMO-INVALID-UNBALANCED",
  "currencyCode": "INR",
  "lines": [
    {"ledgerCode": "110100", "side": "DEBIT", "amount": 100.00},
    {"ledgerCode": "210000", "side": "CREDIT", "amount": 90.00}
  ]
}
```

Expected response: `422 Unprocessable Entity` with code `UNBALANCED_JOURNAL`. No journal or balance change is committed.

Customer account IDs `10001` and `20001` are the configured development accounts. Other IDs are rejected until Account Service replaces the local lookup adapter.
