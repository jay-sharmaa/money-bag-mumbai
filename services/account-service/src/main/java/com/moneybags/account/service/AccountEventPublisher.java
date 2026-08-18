package com.moneybags.account.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moneybags.account.api.InternalModels.AccountEvent;
import com.moneybags.account.client.TransactionClient.OpeningDepositCommand;
import com.moneybags.account.client.TransactionClient.InterestPayoutCommand;
import com.moneybags.account.entity.Account;
import com.moneybags.account.entity.AccountApplication;
import com.moneybags.account.entity.AccountOutbox;
import com.moneybags.account.entity.InterestAccrual;
import com.moneybags.account.entity.OutboxStatus;
import com.moneybags.account.repository.AccountOutboxRepository;
import com.moneybags.account.security.RequestActor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Writes account events to the outbox in the same transaction as the state change they
 * describe, so an event can never be published for a change that rolled back.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountEventPublisher {

    public static final String DESTINATION_STATEMENT = "STATEMENT";
    public static final String DESTINATION_AUDIT = "AUDIT";
    public static final String DESTINATION_TRANSACTION = "TRANSACTION";

    private final AccountOutboxRepository outbox;
    private final ObjectMapper objectMapper;

    public void enqueueAccountEvent(Account account, String eventType) {
        enqueue(account, eventType, DESTINATION_STATEMENT);
        enqueue(account, eventType, DESTINATION_AUDIT);
    }

    public void enqueueOpeningDeposit(Account account, AccountApplication application,
                                      RequestActor approver) {
        if (application.getRequestedInitialDeposit() == null
                || application.getRequestedInitialDeposit().signum() <= 0) {
            return;
        }
        OpeningDepositCommand command = new OpeningDepositCommand(
                account.getAccountId(), application.getRequestedInitialDeposit(),
                account.getCurrency(), application.getApplicationReference(),
                approver.employeeId(), account.getBranchCode(), approver.correlationId());
        save(UUID.randomUUID().toString(), account.getAccountId(),
                "OPENING_DEPOSIT_REQUESTED", DESTINATION_TRANSACTION, command, true);
    }

    public void enqueueInterestPayout(Account account, InterestAccrual accrual,
                                      LocalDate periodStartDate, String correlationId) {
        InterestPayoutCommand command = new InterestPayoutCommand(
                account.getAccountId(), accrual.getAccruedAmount(), account.getCurrency(),
                accrual.getAccrualId(), periodStartDate, accrual.getAccrualDate(),
                account.getBranchCode(), correlationId);
        save(UUID.randomUUID().toString(), account.getAccountId(),
                "INTEREST_PAYOUT_REQUESTED", DESTINATION_TRANSACTION, command, true);
    }

    private void enqueue(Account account, String eventType, String destination) {
        AccountEvent event = new AccountEvent(
                UUID.randomUUID().toString(),
                account.getAccountId(),
                account.getCifNo(),
                account.getBranchCode(),
                account.getMaskedAccountNumber(),
                account.getAccountName(),
                account.getStatus().name(),
                account.getCurrency(),
                account.getLedgerBalance(),
                account.getDormantSince(),
                // The account row's post-flush updated_at, NOT Instant.now(). The consumer
                // drops any event whose sourceUpdatedAt is not strictly newer than the one
                // it already holds, so this value has to advance with the actual write.
                account.getUpdatedAt());

        save(event.sourceEventId(), account.getAccountId(),
                eventType == null ? "ACCOUNT_UPDATED" : eventType,
                destination, event, false);
    }

    private void save(String eventId, String accountId, String eventType, String destination,
                      Object payload, boolean financialCommand) {
        try {
            outbox.save(AccountOutbox.builder()
                    .eventId(eventId)
                    .aggregateType("ACCOUNT")
                    .aggregateId(accountId)
                    .eventType(eventType)
                    .destination(destination)
                    .payload(objectMapper.writeValueAsString(payload))
                    .status(OutboxStatus.PENDING)
                    .attempts(0)
                    .nextAttemptAt(Instant.now())
                    .createdAt(Instant.now())
                    .build());
        } catch (JsonProcessingException ex) {
            if (financialCommand) {
                throw new IllegalStateException("Could not serialise financial command", ex);
            }
            // Never fail the business transaction for a serialisation problem in a
            // downstream projection; the account change itself is what matters.
            log.error("Could not serialise account event for {}: {}", accountId, ex.getMessage());
        }
    }
}
