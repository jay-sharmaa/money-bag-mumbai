package com.moneybags.transaction.controller;

import com.moneybags.transaction.api.TransactionModels.CallbackRequest;
import com.moneybags.transaction.api.TransactionModels.OpeningDepositRequest;
import com.moneybags.transaction.api.InterestPayoutRequest;
import com.moneybags.transaction.entity.Transaction;
import com.moneybags.transaction.exception.DomainException;
import com.moneybags.transaction.service.CallbackService;
import com.moneybags.transaction.service.TransactionOrchestrator;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;

@RestController @RequestMapping("/internal/v1/transactions") @RequiredArgsConstructor @SecurityRequirements
public class InternalTransactionController {
    private final CallbackService callbacks;
    private final TransactionOrchestrator transactions;

    @PostMapping("/opening-deposits")
    public Transaction openingDeposit(@RequestHeader("X-Service-Name") String serviceName,
                                      @RequestHeader("Idempotency-Key") String idempotencyKey,
                                      @Valid @RequestBody OpeningDepositRequest body) {
        if (!"account-service".equals(serviceName)) {
            throw DomainException.forbidden("SERVICE_AUTH_DENIED",
                    "Only account-service may create an opening deposit");
        }
        return transactions.createOpeningDeposit(body, idempotencyKey);
    }

    @PostMapping("/interest-payouts")
    public Transaction interestPayout(@RequestHeader("X-Service-Name") String serviceName,
                                      @RequestHeader("Idempotency-Key") String idempotencyKey,
                                      @Valid @RequestBody InterestPayoutRequest body) {
        if (!"account-service".equals(serviceName)) {
            throw DomainException.forbidden("SERVICE_AUTH_DENIED",
                    "Only account-service may create an interest payout");
        }
        return transactions.createInterestPayout(body, idempotencyKey);
    }

    @PostMapping("/{id}/settle") public Transaction settle(@PathVariable String id,@Valid @RequestBody CallbackRequest body){return callbacks.settle(id,body);}
    @PostMapping("/{id}/fail") public Transaction fail(@PathVariable String id,@Valid @RequestBody CallbackRequest body){return callbacks.fail(id,body);}
    @PostMapping("/{id}/cheque-clearing") public Transaction cheque(@PathVariable String id,@Valid @RequestBody CallbackRequest body){return callbacks.cheque(id,body);}
}
