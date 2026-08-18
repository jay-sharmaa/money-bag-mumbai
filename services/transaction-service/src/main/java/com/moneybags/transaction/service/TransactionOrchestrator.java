package com.moneybags.transaction.service;

import com.moneybags.transaction.api.TransactionModels.*;
import com.moneybags.transaction.api.ProductPurchaseRequest;
import com.moneybags.transaction.api.ProductPurchaseResponse;
import com.moneybags.transaction.api.InterestPayoutRequest;
import com.moneybags.transaction.api.FdSettlementRequest;
import com.moneybags.transaction.client.*;
import com.moneybags.transaction.domain.*;
import com.moneybags.transaction.domain.FinancialEnums.*;
import com.moneybags.transaction.entity.*;
import com.moneybags.transaction.exception.DomainException;
import com.moneybags.transaction.repository.*;
import com.moneybags.transaction.security.RequestActor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class TransactionOrchestrator {
    private final TransactionRepository transactions;
    private final FundsHoldRepository holds;
    private final ClearingInstructionRepository clearing;
    private final TransactionLegRepository legs;
    private final TransactionRailDetailsRepository railDetails;
    private final ProductPurchaseRepository productPurchases;
    private final FdSettlementRepository fdSettlements;
    private final AccountClient accounts;
    private final CardClient cards;
    private final ProductClient products;
    private final LimitService limits;
    private final TransactionStateMachine states;
    private final JournalService journals;
    private final OutboxService outbox;
    private final IdempotencyService idempotency;
    private final RequestHasher hasher;

    @Transactional
    public Transaction create(TransactionType type, PaymentRail rail, CreateRequest request, String idempotencyKey, RequestActor actor) {
        return create(type, rail, request, idempotencyKey, actor, false);
    }

    @Transactional
    public Transaction createOpeningDeposit(OpeningDepositRequest request, String idempotencyKey) {
        CreateRequest deposit = new CreateRequest(
                null, request.accountId(), null, request.amount(), BigDecimal.ZERO,
                request.currency(), PaymentChannel.INTERNAL, PaymentMethod.CASH,
                null, null, "Initial deposit for " + request.applicationReference(),
                "TXN-OPEN-" + request.applicationReference());
        RequestActor actor = new RequestActor(request.initiatedByEmployeeId(), request.branchCode(),
                Set.of("TRANSACTION_CREATE"), request.correlationId() == null
                ? UUID.randomUUID().toString() : request.correlationId());
        return create(TransactionType.DEPOSIT, PaymentRail.CASH, deposit,
                idempotencyKey, actor, true);
    }

    @Transactional
    public Transaction createInterestPayout(InterestPayoutRequest request, String idempotencyKey) {
        long periodDays = java.time.temporal.ChronoUnit.DAYS.between(
                request.periodStartDate(), request.periodEndDate()) + 1;
        if (periodDays != 364) {
            throw DomainException.invalid("INVALID_INTEREST_PERIOD",
                    "An interest payout must cover exactly 52 consecutive seven-day periods");
        }
        CreateRequest payout = new CreateRequest(
                null, request.accountId(), null, request.amount(), BigDecimal.ZERO,
                request.currency(), PaymentChannel.INTERNAL, PaymentMethod.ACCOUNT,
                null, null,
                "Savings interest for " + request.periodStartDate() + " to " + request.periodEndDate(),
                "TXN-INT-" + request.payoutBatchId());
        RequestActor actor = new RequestActor("interest-engine", request.branchCode(),
                Set.of("TRANSACTION_CREATE"), request.correlationId() == null
                ? request.payoutBatchId() : request.correlationId());
        return create(TransactionType.INTEREST_PAYOUT, PaymentRail.INTERNAL, payout,
                idempotencyKey, actor, false);
    }

    @Transactional
    public Transaction createFdSettlement(FdSettlementRequest request, String idempotencyKey) {
        validateFdSettlement(request);
        AccountClient.AccountContext destination = activeAccount(
                request.destinationAccountId(), request.currency());
        AccountClient.AccountContext sourceFdAccount = null;
        if (request.sourceFdAccountId() != null && !request.sourceFdAccountId().isBlank()) {
            sourceFdAccount = activeAccount(request.sourceFdAccountId(), request.currency());
            if (!sourceFdAccount.accountHolderId().equals(destination.accountHolderId())) {
                throw DomainException.forbidden("FD_DESTINATION_OWNER_MISMATCH",
                        "The FD and destination account must belong to the same customer");
            }
            if (sourceFdAccount.ledgerBalance().compareTo(request.principalAmount()) != 0) {
                throw DomainException.conflict("FD_PRINCIPAL_BALANCE_MISMATCH",
                        "The account-opened FD balance must equal its original principal");
            }
            if (sourceFdAccount.availableBalance().compareTo(request.principalAmount()) != 0) {
                throw DomainException.conflict("FD_PRINCIPAL_UNAVAILABLE",
                        "The complete FD principal must be available before settlement");
            }
        }
        BigDecimal total = request.principalAmount().add(request.interestAmount());
        TransactionType transactionType = request.settlementType() == FdSettlementType.MATURITY
                ? TransactionType.FD_MATURITY_PAYOUT : TransactionType.FD_PREMATURE_BREAK;
        var claim = idempotency.claim("fd-settlement-engine", "CREATE_" + transactionType,
                idempotencyKey, hasher.hash(List.of(transactionType, request)));
        if (claim.replay()) return claim.record().getTransaction();

        ProductPurchase purchase = null;
        if (request.purchaseTransactionId() != null && !request.purchaseTransactionId().isBlank()) {
            purchase = productPurchases.findByTransaction_Id(request.purchaseTransactionId())
                    .orElseThrow(() -> DomainException.notFound("FD_PURCHASE_NOT_FOUND",
                            "No fixed-deposit purchase exists for transaction "
                                    + request.purchaseTransactionId()));
            validatePurchaseForSettlement(purchase, request);
        }

        Transaction tx = Transaction.builder()
                .reference("TXN-FD-" + request.ownershipId().replace("-", "")
                        .substring(0, Math.min(20, request.ownershipId().replace("-", "").length()))
                        .toUpperCase())
                .type(transactionType)
                .rail(PaymentRail.INTERNAL)
                .channel(PaymentChannel.INTERNAL)
                .method(PaymentMethod.ACCOUNT)
                .sourceAccountId(request.sourceFdAccountId())
                .destinationAccountId(request.destinationAccountId())
                .accountHolderId(destination.accountHolderId())
                .amount(total)
                .feeAmount(BigDecimal.ZERO)
                .currency(request.currency())
                .status(TransactionStatus.RECEIVED)
                .makerEmployeeId("fd-settlement-engine")
                .branchCode(request.branchCode())
                .narration(request.settlementType() == FdSettlementType.MATURITY
                        ? "Fixed deposit maturity: principal and interest"
                        : "Premature fixed deposit break: full principal return")
                .approvalRequired(false)
                .correlationId(request.correlationId())
                .build();
        transactions.saveAndFlush(tx);

        FdSettlement settlement = fdSettlements.save(FdSettlement.builder()
                .transaction(tx)
                .ownershipId(request.ownershipId())
                .purchaseTransactionId(request.purchaseTransactionId())
                .sourceFdAccountId(request.sourceFdAccountId())
                .destinationAccountId(request.destinationAccountId())
                .principalAmount(request.principalAmount())
                .interestAmount(request.interestAmount())
                .interestRate(request.interestRate())
                .settlementType(request.settlementType())
                .acquiredOn(request.acquiredOn())
                .maturityDate(request.maturityDate())
                .settlementDate(request.settlementDate())
                .build());
        if (purchase != null) {
            purchase.setSettlementType(request.settlementType().name());
            purchase.setSettlementTransactionId(tx.getId());
        }

        states.initial(tx, "fd-settlement-engine", "SYSTEM", "FD settlement accepted");
        states.transition(tx, TransactionStatus.VALIDATED, "fd-settlement-engine", "SYSTEM",
                "FD ownership, dates, destination, and settlement amounts validated");
        try {
            String sourceHoldId = null;
            if (sourceFdAccount != null) {
                reserve(tx, request.sourceFdAccountId(), request.principalAmount());
                sourceHoldId = holds.findByTransactionId(tx.getId()).orElseThrow().getExternalHoldId();
            }
            states.transition(tx, TransactionStatus.PROCESSING, "fd-settlement-engine", "SYSTEM",
                    "FD settlement financial facts created");
            journals.createFdSettlementFacts(tx, settlement);
            if (sourceFdAccount != null) {
                outbox.accountProjection(tx, request.sourceFdAccountId(), "DEBIT",
                        request.principalAmount(), "FD_PRINCIPAL_RELEASED",
                        sourceHoldId, "fd-principal-debit");
            }
            outbox.accountProjection(tx, request.destinationAccountId(), "CREDIT", total,
                    transactionType == TransactionType.FD_MATURITY_PAYOUT
                            ? "FD_MATURITY_PAYOUT_POSTED" : "FD_PREMATURE_BREAK_POSTED",
                    null, "fd-settlement-credit");
            states.transition(tx, TransactionStatus.PROJECTION_PENDING,
                    "fd-settlement-engine", "SYSTEM", "FD settlement projections queued");
        } catch (RuntimeException failure) {
            releaseAfterOrchestrationFailure(tx, failure);
            throw failure;
        }
        idempotency.complete(claim.record(), tx, 201);
        return tx;
    }

    private void validateFdSettlement(FdSettlementRequest request) {
        boolean purchasedFd = request.purchaseTransactionId() != null
                && !request.purchaseTransactionId().isBlank();
        boolean accountOpenedFd = request.sourceFdAccountId() != null
                && !request.sourceFdAccountId().isBlank();
        if (purchasedFd == accountOpenedFd) {
            throw DomainException.invalid("INVALID_FD_FUNDING_SOURCE",
                    "Settlement must identify either the FD purchase or its FD account");
        }
        if (accountOpenedFd && request.sourceFdAccountId().equals(request.destinationAccountId())) {
            throw DomainException.invalid("FD_DESTINATION_SAME_AS_SOURCE",
                    "An FD must settle to a separate savings or current account");
        }
        if (!request.acquiredOn().isBefore(request.maturityDate())) {
            throw DomainException.invalid("INVALID_FD_DATES",
                    "FD acquisition date must be before maturity date");
        }
        if (request.settlementType() == FdSettlementType.PREMATURE_BREAK) {
            if (!request.settlementDate().isBefore(request.maturityDate())) {
                throw DomainException.invalid("FD_NOT_PREMATURE",
                        "A premature break must occur before maturity");
            }
            if (request.interestAmount().signum() != 0) {
                throw DomainException.invalid("PREMATURE_FD_INTEREST_NOT_ALLOWED",
                        "A premature FD break returns principal only");
            }
            return;
        }
        if (!request.settlementDate().equals(request.maturityDate())) {
            throw DomainException.invalid("INVALID_FD_MATURITY_DATE",
                    "Maturity interest must be calculated through the maturity date");
        }
        long days = java.time.temporal.ChronoUnit.DAYS.between(
                request.acquiredOn(), request.maturityDate());
        BigDecimal expectedInterest = request.principalAmount()
                .multiply(request.interestRate())
                .multiply(BigDecimal.valueOf(days))
                .divide(BigDecimal.valueOf(36500), 2, java.math.RoundingMode.HALF_EVEN);
        if (expectedInterest.compareTo(request.interestAmount()) != 0) {
            throw DomainException.invalid("FD_INTEREST_MISMATCH",
                    "Maturity interest does not match principal, rate, and actual days");
        }
    }

    private void validatePurchaseForSettlement(ProductPurchase purchase,
                                                FdSettlementRequest request) {
        if (purchase.getStatus() != ProductPurchaseStatus.ACTIVE) {
            throw DomainException.conflict("FD_PURCHASE_NOT_ACTIVE",
                    "Only an active fixed-deposit purchase can settle");
        }
        if (purchase.getSettlementTransactionId() != null) {
            throw DomainException.conflict("FD_ALREADY_SETTLED",
                    "The fixed-deposit purchase already has a settlement transaction");
        }
        if (purchase.getPrincipalAmount().compareTo(request.principalAmount()) != 0
                || !purchase.getCurrency().equals(request.currency())
                || purchase.getInterestRate().compareTo(request.interestRate()) != 0
                || !purchase.getPurchasedOn().equals(request.acquiredOn())
                || !purchase.getMaturityDate().equals(request.maturityDate())) {
            throw DomainException.conflict("FD_SETTLEMENT_TERMS_MISMATCH",
                    "Settlement terms do not match the original fixed-deposit purchase");
        }
    }

    @Transactional
    public ProductPurchaseResponse createProductPurchase(ProductPurchaseRequest request,
                                                         String idempotencyKey,
                                                         RequestActor actor) {
        actor.require("TRANSACTION_CREATE");
        ProductClient.EffectiveProduct product = purchasableProduct(request);
        String narration = request.narration() == null || request.narration().isBlank()
                ? "Purchase of " + product.productName() : request.narration();
        CreateRequest financialRequest = new CreateRequest(
                request.sourceAccountId(), null, null, request.amount(), BigDecimal.ZERO,
                request.currency(), request.paymentChannel(), PaymentMethod.ACCOUNT,
                null, null, narration, request.clientReference());

        validateShape(TransactionType.PRODUCT_PURCHASE, PaymentRail.INTERNAL, financialRequest);
        AccountClient.AccountContext operatingAccount = validateAccounts(
                TransactionType.PRODUCT_PURCHASE, financialRequest);
        LimitQuote quote = limits.validate(operatingAccount.accountId(),
                TransactionType.PRODUCT_PURCHASE, PaymentRail.INTERNAL,
                request.paymentChannel(), request.currency(), request.amount());
        var claim = idempotency.claim(actor.callerScope(), "CREATE_PRODUCT_PURCHASE",
                idempotencyKey, hasher.hash(List.of(TransactionType.PRODUCT_PURCHASE,
                        PaymentRail.INTERNAL, request)));
        if (claim.replay()) {
            ProductPurchase replay = productPurchases.findByTransaction_Id(
                            claim.record().getTransaction().getId())
                    .orElseThrow(() -> DomainException.conflict("PURCHASE_REPLAY_INCOMPLETE",
                            "The original product purchase record is unavailable"));
            return purchaseResponse(claim.record().getTransaction(), replay);
        }

        boolean approvalRequired = quote.approvalRequired();
        Transaction tx = Transaction.builder()
                .reference(reference(request.clientReference()))
                .type(TransactionType.PRODUCT_PURCHASE)
                .rail(PaymentRail.INTERNAL)
                .channel(request.paymentChannel())
                .method(PaymentMethod.ACCOUNT)
                .sourceAccountId(request.sourceAccountId())
                .accountHolderId(operatingAccount.accountHolderId())
                .amount(request.amount())
                .feeAmount(BigDecimal.ZERO)
                .currency(request.currency())
                .status(TransactionStatus.RECEIVED)
                .makerEmployeeId(actor.employeeId())
                .branchCode(actor.branchCode())
                .narration(narration)
                .approvalRequired(approvalRequired)
                .correlationId(actor.correlationId())
                .build();
        transactions.saveAndFlush(tx);
        railDetails.save(TransactionRailDetails.builder()
                .transaction(tx).clientReference(request.clientReference()).build());

        LocalDate purchasedOn = LocalDate.now(ZoneOffset.UTC);
        ProductPurchase purchase = productPurchases.save(ProductPurchase.builder()
                .transaction(tx)
                .ownerAccountId(request.sourceAccountId())
                .productCode(product.productCode())
                .productName(product.productName())
                .productType(product.productType())
                .productVersionId(product.productVersionId())
                .productVersionNumber(product.versionNumber())
                .principalAmount(request.amount())
                .currency(request.currency())
                .interestRate(product.interestRate())
                .tenureMonths(product.tenureMonths())
                .purchasedOn(purchasedOn)
                .maturityDate(purchasedOn.plusMonths(product.tenureMonths()))
                .status(ProductPurchaseStatus.PENDING)
                .build());

        states.initial(tx, actor.employeeId(), "API", "Product purchase request accepted");
        states.transition(tx, TransactionStatus.VALIDATED, actor.employeeId(), "API",
                "Product, funding account, amount, and currency validated");
        if (approvalRequired) {
            states.transition(tx, TransactionStatus.PENDING_APPROVAL, actor.employeeId(), "API",
                    "Configured approval threshold reached");
        } else {
            try {
                process(tx, actor.employeeId(), "API", false);
            } catch (RuntimeException failure) {
                releaseAfterOrchestrationFailure(tx, failure);
                throw failure;
            }
        }
        idempotency.complete(claim.record(), tx, 201);
        return purchaseResponse(tx, purchase);
    }

    private Transaction create(TransactionType type, PaymentRail rail, CreateRequest request,
                               String idempotencyKey, RequestActor actor,
                               boolean openingDeposit) {
        actor.require("TRANSACTION_CREATE");
        validateShape(type, rail, request);
        AccountClient.AccountContext operatingAccount = validateAccounts(type, request, openingDeposit);
        LimitQuote quote = limits.validate(operatingAccount.accountId(), type, rail, request.paymentChannel(), request.currency(), request.amount().add(fee(request)));
        String operation = "CREATE_" + type;
        var claim = idempotency.claim(actor.callerScope(), operation, idempotencyKey, hasher.hash(List.of(type, rail, request)));
        if (claim.replay()) return claim.record().getTransaction();
        boolean approvalRequired = !openingDeposit && type != TransactionType.INTEREST_PAYOUT
                && quote.approvalRequired();
        Transaction tx = Transaction.builder().reference(reference(request.clientReference())).type(type).rail(rail).channel(request.paymentChannel()).method(request.paymentMethod())
                .sourceAccountId(request.sourceAccountId()).destinationAccountId(request.destinationAccountId()).accountHolderId(operatingAccount.accountHolderId())
                .amount(request.amount()).feeAmount(fee(request)).currency(request.currency()).status(TransactionStatus.RECEIVED).makerEmployeeId(actor.employeeId()).branchCode(actor.branchCode())
                .narration(request.narration()).approvalRequired(approvalRequired).correlationId(actor.correlationId()).build();
        transactions.saveAndFlush(tx);
        railDetails.save(TransactionRailDetails.builder().transaction(tx).upiAddress(request.upiAddress()).chequeNumber(request.chequeNumber()).cardId(request.cardId()).clientReference(request.clientReference()).build());
        states.initial(tx, actor.employeeId(), "API", "Request accepted");
        states.transition(tx, TransactionStatus.VALIDATED, actor.employeeId(), "API", "Synchronous validation completed");
        if (approvalRequired)
            states.transition(tx, TransactionStatus.PENDING_APPROVAL, actor.employeeId(), "API", "Configured approval threshold reached");
        else try {
            process(tx, actor.employeeId(), "API", openingDeposit);
        } catch (RuntimeException failure) {
            releaseAfterOrchestrationFailure(tx, failure);
            throw failure;
        }
        idempotency.complete(claim.record(), tx, 201);
        return tx;
    }

    @Transactional
    public Transaction approve(String transactionId, String key, RequestActor actor) {
        actor.require("TRANSACTION_APPROVE");
        var claim = idempotency.claim(actor.callerScope(), "APPROVE:" + transactionId, key, hasher.hash(List.of(transactionId, "APPROVE")));
        if (claim.replay()) return claim.record().getTransaction();
        Transaction tx = get(transactionId);
        if (tx.getStatus() != TransactionStatus.PENDING_APPROVAL)
            throw DomainException.conflict("TRANSACTION_NOT_APPROVABLE", "Transaction is not pending approval");
        requireBranchAccess(tx, actor);
        if (tx.getMakerEmployeeId().equals(actor.employeeId()))
            throw DomainException.forbidden("MAKER_SELF_APPROVAL", "Maker cannot approve their own transaction");
        revalidate(tx);
        tx.setCheckerEmployeeId(actor.employeeId());
        tx.setApprovedAt(Instant.now());
        states.transition(tx, TransactionStatus.APPROVED, actor.employeeId(), "CHECKER", "Approved by checker");
        try {
            process(tx, actor.employeeId(), "CHECKER", false);
        } catch (RuntimeException failure) {
            releaseAfterOrchestrationFailure(tx, failure);
            throw failure;
        }
        idempotency.complete(claim.record(), tx, 200);
        return tx;
    }

    @Transactional
    public Transaction reject(String transactionId, String reason, String key, RequestActor actor) {
        actor.require("TRANSACTION_APPROVE");
        var claim = idempotency.claim(actor.callerScope(), "REJECT:" + transactionId, key, hasher.hash(List.of(transactionId, reason)));
        if (claim.replay()) return claim.record().getTransaction();
        Transaction tx = get(transactionId);
        if (tx.getStatus() != TransactionStatus.PENDING_APPROVAL)
            throw DomainException.conflict("TRANSACTION_NOT_REJECTABLE", "Transaction is not pending approval");
        requireBranchAccess(tx, actor);
        if (tx.getMakerEmployeeId().equals(actor.employeeId()))
            throw DomainException.forbidden("MAKER_SELF_APPROVAL", "Maker cannot act as checker on their own transaction");
        tx.setCheckerEmployeeId(actor.employeeId());
        tx.setRejectionReason(requireReason(reason));
        states.transition(tx, TransactionStatus.REJECTED, actor.employeeId(), "CHECKER", reason);
        cancelProductPurchase(tx);
        idempotency.complete(claim.record(), tx, 200);
        return tx;
    }

    @Transactional
    public Transaction cancel(String transactionId, String reason, String key, RequestActor actor) {
        actor.require("TRANSACTION_CANCEL");
        var claim = idempotency.claim(actor.callerScope(), "CANCEL:" + transactionId, key, hasher.hash(List.of(transactionId, reason)));
        if (claim.replay()) return claim.record().getTransaction();
        Transaction tx = get(transactionId);
        requireBranchAccess(tx, actor);
        if (!tx.getMakerEmployeeId().equals(actor.employeeId()) && !actor.permissions().contains("TRANSACTION_CANCEL_ANY"))
            throw DomainException.forbidden("CANCEL_NOT_ALLOWED", "Only the maker or an authorized supervisor can cancel this transaction");
        if (Set.of(TransactionStatus.RECEIVED, TransactionStatus.VALIDATED, TransactionStatus.PENDING_APPROVAL, TransactionStatus.APPROVED).contains(tx.getStatus())) {
            states.transition(tx, TransactionStatus.CANCELLED, actor.employeeId(), "API", requireReason(reason));
            cancelProductPurchase(tx);
            idempotency.complete(claim.record(), tx, 200);
            return tx;
        }
        if (tx.getStatus() == TransactionStatus.FUNDS_RESERVED) {
            releaseHold(tx, "cancel");
            states.transition(tx, TransactionStatus.CANCELLED, actor.employeeId(), "API", requireReason(reason));
            cancelProductPurchase(tx);
            idempotency.complete(claim.record(), tx, 200);
            return tx;
        }
        throw DomainException.conflict("TRANSACTION_NOT_CANCELLABLE", "Financial processing has already started");
    }

    @Transactional
    public Transaction reverse(String transactionId, String reason, String idempotencyKey, RequestActor actor) {
        actor.require("TRANSACTION_REVERSE");
        Transaction original = get(transactionId);
        if (original.getStatus() != TransactionStatus.COMPLETED)
            throw DomainException.conflict("TRANSACTION_NOT_REVERSIBLE", "Only a completed transaction can be reversed");
        if (original.getType() == TransactionType.REVERSAL)
            throw DomainException.conflict("TRANSACTION_NOT_REVERSIBLE", "A reversal cannot itself be reversed");
        if (original.getType() == TransactionType.FD_MATURITY_PAYOUT
                || original.getType() == TransactionType.FD_PREMATURE_BREAK)
            throw DomainException.conflict("TRANSACTION_NOT_REVERSIBLE",
                    "An FD settlement cannot be reversed through the generic reversal flow");
        if (original.getType() == TransactionType.PRODUCT_PURCHASE) {
            ProductPurchase purchase = productPurchases.findByTransaction_Id(original.getId())
                    .orElseThrow(() -> DomainException.conflict("PRODUCT_PURCHASE_NOT_FOUND",
                            "The completed transaction has no product purchase record"));
            if (purchase.getStatus() != ProductPurchaseStatus.ACTIVE) {
                throw DomainException.conflict("PRODUCT_PURCHASE_NOT_REVERSIBLE",
                        "Only an active product purchase can be reversed");
            }
        }
        if (transactions.existsByReversalOfId(original.getId()))
            throw DomainException.conflict("TRANSACTION_ALREADY_REVERSED", "A reversal already exists");
        var claim = idempotency.claim(actor.callerScope(), "REVERSE:" + original.getId(), idempotencyKey, hasher.hash(List.of(original.getId(), reason)));
        if (claim.replay()) return claim.record().getTransaction();
        requireBranchAccess(original, actor);
        states.transition(original, TransactionStatus.REVERSAL_PENDING, actor.employeeId(), "API", reason);
        Transaction reversal = Transaction.builder().reference(reference(null)).type(TransactionType.REVERSAL).rail(original.getRail()).channel(PaymentChannel.INTERNAL).method(PaymentMethod.ACCOUNT)
                .sourceAccountId(original.getSourceAccountId()).destinationAccountId(original.getDestinationAccountId()).accountHolderId(original.getAccountHolderId())
                .amount(original.getAmount()).feeAmount(original.getFeeAmount()).currency(original.getCurrency()).status(TransactionStatus.RECEIVED).makerEmployeeId(actor.employeeId()).branchCode(actor.branchCode()).narration("Reversal of " + original.getReference() + ": " + reason)
                .approvalRequired(false).reversalOf(original).correlationId(actor.correlationId()).build();
        transactions.saveAndFlush(reversal);
        states.initial(reversal, actor.employeeId(), "API", "Reversal requested");
        states.transition(reversal, TransactionStatus.VALIDATED, actor.employeeId(), "API", "Original transaction is reversible");
        reserveForReversalIfNeeded(reversal, original);
        states.transition(reversal, TransactionStatus.PROCESSING, actor.employeeId(), "API", "Compensating records created");
        journals.createReversalLegs(reversal, original);
        journals.createReversalJournal(reversal, original);
        createReversalOutbox(reversal, original);
        states.transition(reversal, TransactionStatus.PROJECTION_PENDING, actor.employeeId(), "API", "Compensating projections queued");
        idempotency.complete(claim.record(), reversal, 201);
        return reversal;
    }

    private void process(Transaction tx, String actor, String source, boolean openingDeposit) {
        if (tx.getType().debitsAccount()) reserve(tx, tx.getSourceAccountId(), tx.totalDebit());
        states.transition(tx, TransactionStatus.PROCESSING, actor, source, "Financial processing started");
        journals.createInitialFinancialFacts(tx);
        if (tx.getType().externallyCleared())
            clearing.save(ClearingInstruction.builder().transaction(tx).rail(tx.getRail()).status(ClearingStatus.CREATED).amount(tx.getAmount()).currency(tx.getCurrency()).build());
        if (tx.getType() == TransactionType.CHEQUE) return;
        if (tx.getType() == TransactionType.DEPOSIT || tx.getType() == TransactionType.INTEREST_PAYOUT)
            outbox.accountProjection(tx, tx.getDestinationAccountId(), "CREDIT", tx.getAmount(),
                    tx.getType() == TransactionType.INTEREST_PAYOUT ? "INTEREST_PAYOUT_POSTED"
                            : openingDeposit ? "OPENING_DEPOSIT_POSTED" : "DEPOSIT_POSTED",
                    null, tx.getType() == TransactionType.INTEREST_PAYOUT ? "interest-credit" : "deposit-credit");
        else {
            String hold = holds.findByTransactionId(tx.getId()).map(FundsHold::getExternalHoldId).orElse(null);
            String eventType = switch (tx.getType()) {
                case WITHDRAWAL -> "WITHDRAWAL_POSTED";
                case PRODUCT_PURCHASE -> "PRODUCT_PURCHASE_POSTED";
                default -> "PAYMENT_POSTED";
            };
            outbox.accountProjection(tx, tx.getSourceAccountId(), "DEBIT", tx.totalDebit(), eventType, hold, "source-debit");
            if (tx.getType() == TransactionType.INTERNAL_TRANSFER) {
                journals.createSettlementJournal(tx);
                outbox.accountProjection(tx, tx.getDestinationAccountId(), "CREDIT", tx.getAmount(), "CREDIT_POSTED", null, "destination-credit");
            }
        }
        states.transition(tx, TransactionStatus.PROJECTION_PENDING, actor, source, "Account projections queued in transactional outbox");
    }

    private void reserve(Transaction tx, String accountId, BigDecimal amount) {
        String key = "hold:" + tx.getId();
        AccountClient.HoldResponse response = accounts.reserve(accountId, key, new AccountClient.HoldRequest(tx.getId(), amount, tx.getCurrency(), tx.getType().name()));
        if (response.reservedAmount() == null || response.reservedAmount().compareTo(amount) != 0)
            throw DomainException.conflict("HOLD_AMOUNT_MISMATCH", "Account Service did not reserve the requested amount");
        holds.save(FundsHold.builder().transaction(tx).accountId(accountId).externalHoldId(response.holdId()).amount(amount).currency(tx.getCurrency()).status(HoldStatus.FUNDS_HELD).operationKey(key).build());
        states.transition(tx, TransactionStatus.FUNDS_RESERVED, "account-service", "ACCOUNT_SERVICE", "Funds reserved atomically by Account Service");
    }

    private void releaseHold(Transaction tx, String reason) {
        holds.findByTransactionId(tx.getId()).filter(h -> h.getStatus() == HoldStatus.FUNDS_HELD).ifPresent(h -> {
            accounts.release(h.getAccountId(), h.getExternalHoldId(), "release:" + tx.getId() + ":" + reason);
            h.setStatus(HoldStatus.RELEASED);
        });
    }

    private void releaseAfterOrchestrationFailure(Transaction tx, RuntimeException failure) {
        try {
            releaseHold(tx, "orchestration-failure");
        } catch (Exception releaseFailure) {
            log.error("hold_compensation_failed transactionId={} transactionReference={} correlationId={}", tx.getId(), tx.getReference(), tx.getCorrelationId(), releaseFailure);
            failure.addSuppressed(releaseFailure);
        }
    }

    private AccountClient.AccountContext validateAccounts(TransactionType type, CreateRequest r) {
        return validateAccounts(type, r, false);
    }

    private AccountClient.AccountContext validateAccounts(TransactionType type, CreateRequest r,
                                                           boolean allowPendingActivation) {
        if (type == TransactionType.DEPOSIT || type == TransactionType.CHEQUE
                || type == TransactionType.INTEREST_PAYOUT)
            return activeAccount(r.destinationAccountId(), r.currency(),
                    allowPendingActivation && type == TransactionType.DEPOSIT);
        AccountClient.AccountContext source = activeAccount(r.sourceAccountId(), r.currency());
        if (source.availableBalance().compareTo(r.amount().add(fee(r))) < 0)
            throw DomainException.conflict("INSUFFICIENT_FUNDS", "Available balance is insufficient for amount plus fee");
        if (type == TransactionType.INTERNAL_TRANSFER) activeAccount(r.destinationAccountId(), r.currency());
        if (type == TransactionType.CARD_PAYMENT) {
            CardClient.CardContext card = cards.context(r.cardId(), source.accountHolderId());
            if (!"ACTIVE".equals(card.status()))
                throw DomainException.conflict("CARD_INACTIVE", "Linked card is not active");
            if (!source.accountHolderId().equals(card.accountHolderId()) || !r.sourceAccountId().equals(card.linkedAccountId()))
                throw DomainException.forbidden("CARD_NOT_LINKED", "Card is not linked to the account holder and source account");
            if (!r.currency().equals(card.currency()))
                throw DomainException.invalid("CURRENCY_MISMATCH", "Transaction currency does not match card currency");
        }
        return source;
    }

    private AccountClient.AccountContext activeAccount(String id, String currency) {
        return activeAccount(id, currency, false);
    }

    private AccountClient.AccountContext activeAccount(String id, String currency,
                                                       boolean allowPendingActivation) {
        if (id == null || id.isBlank())
            throw DomainException.invalid("ACCOUNT_REQUIRED", "Required account ID is missing");
        AccountClient.AccountContext context = accounts.context(id);
        if (!"ACTIVE".equals(context.status())
                && !(allowPendingActivation && "PENDING_ACTIVATION".equals(context.status())))
            throw DomainException.conflict("ACCOUNT_INACTIVE", "Account is not active: " + id);
        if (!currency.equals(context.currency()))
            throw DomainException.invalid("CURRENCY_MISMATCH", "Transaction currency does not match account currency");
        return context;
    }

    private void revalidate(Transaction tx) {
        CreateRequest r = new CreateRequest(tx.getSourceAccountId(), tx.getDestinationAccountId(), null, tx.getAmount(), tx.getFeeAmount(), tx.getCurrency(), tx.getChannel(), tx.getMethod(), null, null, tx.getNarration(), tx.getReference());
        limits.validate(tx.getSourceAccountId() != null ? tx.getSourceAccountId() : tx.getDestinationAccountId(), tx.getType(), tx.getRail(), tx.getChannel(), tx.getCurrency(), tx.totalDebit());
        validateAccounts(tx.getType(), r);
    }

    private void validateShape(TransactionType type, PaymentRail rail, CreateRequest r) {
        PaymentRail expected = switch (type) {
            case DEPOSIT, WITHDRAWAL -> PaymentRail.CASH;
            case INTERNAL_TRANSFER -> PaymentRail.INTERNAL;
            case NEFT -> PaymentRail.NEFT;
            case RTGS -> PaymentRail.RTGS;
            case IMPS -> PaymentRail.IMPS;
            case UPI -> PaymentRail.UPI;
            case CHEQUE -> PaymentRail.CHEQUE;
            case CARD_PAYMENT -> PaymentRail.CARD;
            case PRODUCT_PURCHASE -> PaymentRail.INTERNAL;
            case INTEREST_PAYOUT -> PaymentRail.INTERNAL;
            case FD_MATURITY_PAYOUT, FD_PREMATURE_BREAK -> PaymentRail.INTERNAL;
            case REVERSAL -> PaymentRail.INTERNAL;
        };
        if (rail != expected)
            throw DomainException.invalid("UNSUPPORTED_RAIL", "Transaction type and rail do not match");
        if (r.paymentMethod().name().equals(rail.name()) == false && !(rail == PaymentRail.INTERNAL && r.paymentMethod() == PaymentMethod.ACCOUNT) && !(rail == PaymentRail.CASH && r.paymentMethod() == PaymentMethod.CASH))
            throw DomainException.invalid("INVALID_PAYMENT_METHOD", "Payment method does not match the selected rail");
        if (r.sourceAccountId() != null && r.sourceAccountId().equals(r.destinationAccountId()))
            throw DomainException.invalid("SAME_ACCOUNT_TRANSFER", "Source and destination accounts must differ");
        if (type == TransactionType.UPI && (r.upiAddress() == null || r.upiAddress().isBlank()))
            throw DomainException.invalid("UPI_ADDRESS_REQUIRED", "UPI address is required");
        if (type == TransactionType.CHEQUE && (r.chequeNumber() == null || r.chequeNumber().isBlank()))
            throw DomainException.invalid("CHEQUE_NUMBER_REQUIRED", "Cheque number is required");
        if (type == TransactionType.CARD_PAYMENT && (r.cardId() == null || r.cardId().isBlank()))
            throw DomainException.invalid("CARD_ID_REQUIRED", "Linked card ID is required");
    }

    private void reserveForReversalIfNeeded(Transaction reversal, Transaction original) {
        if (original.getType() == TransactionType.DEPOSIT
                || original.getType() == TransactionType.INTEREST_PAYOUT)
            reserve(reversal, original.getDestinationAccountId(), original.getAmount());
        else if (original.getType() == TransactionType.INTERNAL_TRANSFER)
            reserve(reversal, original.getDestinationAccountId(), original.getAmount());
    }

    private void createReversalOutbox(Transaction reversal, Transaction original) {
        List<TransactionLeg> originalLegs = legs.findByTransactionIdOrderBySequenceNo(original.getId());
        int n = 0;
        for (TransactionLeg leg : originalLegs) {
            if (leg.getAccountId() != null)
                outbox.accountProjection(reversal, leg.getAccountId(), leg.getDirection() == Direction.DEBIT ? "CREDIT" : "DEBIT", leg.getAmount(), "TRANSACTION_REVERSED", holds.findByTransactionId(reversal.getId()).map(FundsHold::getExternalHoldId).orElse(null), "reversal-" + (++n));
        }
    }

    public Transaction get(String id) {
        return transactions.findById(id).orElseThrow(() -> DomainException.notFound("TRANSACTION_NOT_FOUND", "Transaction not found: " + id));
    }

    private void requireBranchAccess(Transaction tx, RequestActor actor) {
        if (!actor.canAccessAllBranches() && !Objects.equals(tx.getBranchCode(), actor.branchCode()))
            throw DomainException.forbidden("BRANCH_SCOPE_DENIED", "Transaction belongs to another branch");
    }

    private BigDecimal fee(CreateRequest r) {
        return r.feeAmount() == null ? BigDecimal.ZERO : r.feeAmount();
    }

    private String requireReason(String reason) {
        if (reason == null || reason.isBlank())
            throw DomainException.invalid("REASON_REQUIRED", "A reason is required");
        return reason;
    }

    private String reference(String client) {
        return client != null && !client.isBlank() ? client : "TXN-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase();
    }

    private ProductClient.EffectiveProduct purchasableProduct(ProductPurchaseRequest request) {
        ProductClient.EffectiveProduct product;
        try {
            product = products.effective(request.productCode());
        } catch (Exception exception) {
            throw DomainException.invalid("PRODUCT_NOT_AVAILABLE",
                    "Could not resolve product " + request.productCode() + ": " + exception.getMessage());
        }
        if (product == null) {
            throw DomainException.invalid("PRODUCT_NOT_AVAILABLE",
                    "Product service returned no terms for " + request.productCode());
        }
        if (!"FD-12M".equals(product.productCode())
                || !"TERM_DEPOSIT".equals(product.productType())
                || !product.requiresFunding()
                || product.tenureMonths() == null
                || product.tenureMonths() != 12) {
            throw DomainException.invalid("PRODUCT_NOT_PURCHASABLE",
                    "Only FD-12M is available through product-purchase transactions");
        }
        if (!request.currency().equals(product.currency())) {
            throw DomainException.invalid("CURRENCY_MISMATCH",
                    "Purchase currency does not match the product currency");
        }
        if (request.amount().compareTo(product.minOpeningDeposit()) < 0) {
            throw DomainException.invalid("MINIMUM_PURCHASE_AMOUNT",
                    "FD-12M requires at least " + product.minOpeningDeposit() + " " + product.currency());
        }
        return product;
    }

    private void cancelProductPurchase(Transaction tx) {
        if (tx.getType() != TransactionType.PRODUCT_PURCHASE) return;
        productPurchases.findByTransaction_Id(tx.getId())
                .ifPresent(purchase -> purchase.setStatus(ProductPurchaseStatus.CANCELLED));
    }

    private ProductPurchaseResponse purchaseResponse(Transaction tx, ProductPurchase purchase) {
        return new ProductPurchaseResponse(
                purchase.getPurchaseId(), tx.getId(), tx.getReference(), tx.getStatus(),
                purchase.getOwnerAccountId(), purchase.getProductCode(), purchase.getProductName(),
                purchase.getProductType(), purchase.getProductVersionId(),
                purchase.getProductVersionNumber(), purchase.getPrincipalAmount(),
                purchase.getCurrency(), purchase.getInterestRate(), purchase.getTenureMonths(),
                purchase.getPurchasedOn(), purchase.getMaturityDate(), purchase.getStatus(),
                purchase.getReversalTransactionId(), purchase.getCreatedAt(), purchase.getUpdatedAt());
    }
}
