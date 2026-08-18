package com.moneybags.account.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moneybags.account.api.InternalModels.AccountEvent;
import com.moneybags.account.client.AuditClient;
import com.moneybags.account.client.StatementClient;
import com.moneybags.account.client.TransactionClient;
import com.moneybags.account.client.TransactionClient.OpeningDepositCommand;
import com.moneybags.account.client.TransactionClient.InterestPayoutCommand;
import com.moneybags.account.client.TransactionClient.InterestPayoutResult;
import com.moneybags.account.client.TransactionClient.FdSettlementCommand;
import com.moneybags.account.client.TransactionClient.FdSettlementResult;
import com.moneybags.account.config.AccountProperties;
import com.moneybags.account.entity.AccountOutbox;
import com.moneybags.account.entity.OutboxStatus;
import com.moneybags.account.entity.InterestPayoutBatchStatus;
import com.moneybags.account.entity.FdSettlementStatus;
import com.moneybags.account.entity.FdSettlementType;
import com.moneybags.account.entity.ProductOwnershipStatus;
import com.moneybags.account.entity.ProductAcquisitionType;
import com.moneybags.account.entity.AccountStatus;
import com.moneybags.account.repository.AccountOutboxRepository;
import com.moneybags.account.repository.InterestAccrualRepository;
import com.moneybags.account.repository.InterestPayoutBatchRepository;
import com.moneybags.account.repository.AccountProductOwnershipRepository;
import com.moneybags.account.repository.AccountRepository;
import com.moneybags.account.repository.AccountStatusHistoryRepository;
import com.moneybags.account.entity.AccountStatusHistory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;

/**
 * Drains the outbox to statement-reporting-service and audit-service over HTTP.
 *
 * <p>Same shape as transaction-service's OutboxPublisher: batch, exponential backoff,
 * bounded attempts. There is no broker in this deployment, so the transactional outbox
 * plus a scheduled pusher is what provides at-least-once delivery.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountOutboxPublisher {

    private static final String SERVICE_NAME = "account-service";

    private final AccountOutboxRepository outbox;
    private final StatementClient statementClient;
    private final AuditClient auditClient;
    private final TransactionClient transactionClient;
    private final ObjectMapper objectMapper;
    private final AccountProperties properties;
    private final InterestAccrualRepository interestAccruals;
    private final InterestPayoutBatchRepository interestPayoutBatches;
    private final AccountProductOwnershipRepository productOwnerships;
    private final AccountRepository accounts;
    private final AccountStatusHistoryRepository accountStatusHistory;
    private final AccountEventPublisher accountEvents;

    @Scheduled(fixedDelayString = "${moneybags.account.outbox.fixed-delay-ms:5000}")
    @Transactional
    public void publish() {
        if (!properties.getOutbox().isEnabled()) {
            return;
        }
        var batch = outbox.findDeliverable(OutboxStatus.PENDING, Instant.now(),
                PageRequest.of(0, properties.getOutbox().getBatchSize()));
        for (AccountOutbox event : batch) {
            deliver(event);
        }
    }

    private void deliver(AccountOutbox event) {
        try {
            switch (event.getDestination()) {
                case AccountEventPublisher.DESTINATION_STATEMENT -> {
                    AccountEvent payload = objectMapper.readValue(event.getPayload(), AccountEvent.class);
                    statementClient.push(SERVICE_NAME, payload);
                }
                case AccountEventPublisher.DESTINATION_AUDIT -> {
                    AccountEvent payload = objectMapper.readValue(event.getPayload(), AccountEvent.class);
                    auditClient.append(SERVICE_NAME, toAuditEvent(event, payload));
                }
                case AccountEventPublisher.DESTINATION_TRANSACTION -> {
                    if ("INTEREST_PAYOUT_REQUESTED".equals(event.getEventType())) {
                        InterestPayoutCommand command = objectMapper.readValue(
                                event.getPayload(), InterestPayoutCommand.class);
                        InterestPayoutResult result = transactionClient.createInterestPayout(
                                SERVICE_NAME, "interest-payout:" + command.payoutBatchId(), command);
                        var payoutBatch = interestPayoutBatches.findById(
                                command.payoutBatchId()).orElseThrow();
                        payoutBatch.setPayoutTransactionId(result.id());
                        if (!"COMPLETED".equals(result.status())) {
                            payoutBatch.setStatus(InterestPayoutBatchStatus.PAYOUT_QUEUED);
                            interestPayoutBatches.save(payoutBatch);
                            throw new IllegalStateException("Interest payout transaction "
                                    + result.id() + " is " + result.status());
                        }
                        var weeklyAccruals = interestAccruals
                                .findByPayoutBatchIdOrderByAccrualDateAsc(command.payoutBatchId());
                        weeklyAccruals.forEach(accrual -> {
                            accrual.setPostedTransactionId(result.id());
                            accrual.setPosted(true);
                        });
                        interestAccruals.saveAll(weeklyAccruals);
                        payoutBatch.setStatus(InterestPayoutBatchStatus.COMPLETED);
                        payoutBatch.setCompletedAt(Instant.now());
                        interestPayoutBatches.save(payoutBatch);
                    } else if ("FD_SETTLEMENT_REQUESTED".equals(event.getEventType())) {
                        FdSettlementCommand command = objectMapper.readValue(
                                event.getPayload(), FdSettlementCommand.class);
                        FdSettlementResult result = transactionClient.createFdSettlement(
                                SERVICE_NAME, "fd-settlement:" + command.ownershipId(), command);
                        var ownership = productOwnerships.findById(command.ownershipId()).orElseThrow();
                        ownership.setSettlementTransactionId(result.id());
                        if (!"COMPLETED".equals(result.status())) {
                            ownership.setSettlementStatus(FdSettlementStatus.PAYOUT_QUEUED);
                            productOwnerships.save(ownership);
                            throw new IllegalStateException("FD settlement transaction "
                                    + result.id() + " is " + result.status());
                        }
                        FdSettlementType type = FdSettlementType.valueOf(command.settlementType());
                        ownership.setSettlementStatus(FdSettlementStatus.COMPLETED);
                        ownership.setStatus(type == FdSettlementType.MATURITY
                                ? ProductOwnershipStatus.MATURED : ProductOwnershipStatus.CLOSED);
                        ownership.setSettledAt(Instant.now());
                        productOwnerships.save(ownership);
                        if (ownership.getAcquisitionType() == ProductAcquisitionType.ACCOUNT_OPENING) {
                            var fdAccount = accounts.findById(ownership.getOwnerAccountId()).orElseThrow();
                            AccountStatus previousStatus = fdAccount.getStatus();
                            AccountStatus nextStatus = type == FdSettlementType.MATURITY
                                    ? AccountStatus.MATURED : AccountStatus.CLOSED;
                            fdAccount.setStatus(nextStatus);
                            if (type == FdSettlementType.PREMATURE_BREAK) {
                                fdAccount.setClosedOn(java.time.LocalDate.now(java.time.ZoneOffset.UTC));
                            }
                            accounts.save(fdAccount);
                            accountStatusHistory.save(AccountStatusHistory.builder()
                                    .accountId(fdAccount.getAccountId())
                                    .fromStatus(previousStatus.name())
                                    .toStatus(nextStatus.name())
                                    .reason(type == FdSettlementType.MATURITY
                                            ? "Fixed deposit paid at maturity"
                                            : "Fixed deposit broken prematurely")
                                    .source("FD_SETTLEMENT")
                                    .changedAt(Instant.now())
                                    .build());
                            accountEvents.enqueueAccountEvent(fdAccount,
                                    type == FdSettlementType.MATURITY
                                            ? "FD_MATURED" : "FD_CLOSED");
                        }
                    } else {
                        OpeningDepositCommand command = objectMapper.readValue(
                                event.getPayload(), OpeningDepositCommand.class);
                        transactionClient.createOpeningDeposit(SERVICE_NAME,
                                "opening-deposit:" + event.getAggregateId(), command);
                    }
                }
                default -> throw new IllegalStateException(
                        "Unknown outbox destination " + event.getDestination());
            }
            event.setStatus(OutboxStatus.PUBLISHED);
            event.setPublishedAt(Instant.now());
            event.setLastError(null);
            outbox.save(event);
        } catch (Exception ex) {
            recordFailure(event, ex);
        }
    }

    private AuditClient.AuditEvent toAuditEvent(AccountOutbox event, AccountEvent payload) {
        Map<String, Object> body = new HashMap<>();
        body.put("accountId", payload.accountId());
        body.put("status", payload.status());
        body.put("currentBalance", payload.currentBalance());
        body.put("currency", payload.currency());
        return new AuditClient.AuditEvent(
                event.getEventId(), SERVICE_NAME, event.getEventType(), "ACCOUNT",
                payload.accountId(), null, payload.branchId(), null,
                event.getCreatedAt(), body);
    }

    private void recordFailure(AccountOutbox event, Exception ex) {
        int attempts = event.getAttempts() + 1;
        event.setAttempts(attempts);
        String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        event.setLastError(message.length() > 500 ? message.substring(0, 500) : message);

        if (attempts >= properties.getOutbox().getMaxAttempts()) {
            event.setStatus(OutboxStatus.FAILED);
            if ("FD_SETTLEMENT_REQUESTED".equals(event.getEventType())) {
                productOwnerships.findById(event.getAggregateId()).ifPresent(ownership -> {
                    ownership.setSettlementStatus(FdSettlementStatus.FAILED);
                    productOwnerships.save(ownership);
                });
            }
            log.error("Outbox event {} failed permanently after {} attempts: {}",
                    event.getEventId(), attempts, message);
        } else {
            // Exponential, capped at five minutes.
            long backoffSeconds = Math.min(300L, 1L << Math.min(8, attempts));
            event.setNextAttemptAt(Instant.now().plus(backoffSeconds, ChronoUnit.SECONDS));
            log.warn("Outbox event {} attempt {} failed, retrying in {}s: {}",
                    event.getEventId(), attempts, backoffSeconds, message);
        }
        outbox.save(event);
    }
}
