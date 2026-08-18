package com.moneybags.account.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moneybags.account.api.InternalModels.AccountEvent;
import com.moneybags.account.client.AuditClient;
import com.moneybags.account.client.StatementClient;
import com.moneybags.account.client.TransactionClient;
import com.moneybags.account.client.TransactionClient.OpeningDepositCommand;
import com.moneybags.account.client.TransactionClient.InterestPayoutCommand;
import com.moneybags.account.client.TransactionClient.InterestPayoutResult;
import com.moneybags.account.config.AccountProperties;
import com.moneybags.account.entity.AccountOutbox;
import com.moneybags.account.entity.OutboxStatus;
import com.moneybags.account.repository.AccountOutboxRepository;
import com.moneybags.account.repository.InterestAccrualRepository;
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
                                SERVICE_NAME, "interest-payout:" + command.accrualId(), command);
                        var accrual = interestAccruals.findById(command.accrualId()).orElseThrow();
                        accrual.setPostedTransactionId(result.id());
                        if (!"COMPLETED".equals(result.status())) {
                            interestAccruals.save(accrual);
                            throw new IllegalStateException("Interest payout transaction "
                                    + result.id() + " is " + result.status());
                        }
                        accrual.setPosted(true);
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
