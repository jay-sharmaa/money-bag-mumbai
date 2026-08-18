package com.moneybags.account.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moneybags.account.client.TransactionClient.InterestPayoutCommand;
import com.moneybags.account.client.TransactionClient.InterestPayoutResult;
import com.moneybags.account.client.AuditClient;
import com.moneybags.account.client.StatementClient;
import com.moneybags.account.client.TransactionClient;
import com.moneybags.account.config.AccountProperties;
import com.moneybags.account.entity.Account;
import com.moneybags.account.entity.AccountOutbox;
import com.moneybags.account.entity.AccountStatus;
import com.moneybags.account.entity.BalanceHistory;
import com.moneybags.account.entity.Direction;
import com.moneybags.account.entity.InterestRunStatus;
import com.moneybags.account.entity.OutboxStatus;
import com.moneybags.account.repository.AccountOutboxRepository;
import com.moneybags.account.repository.AccountRepository;
import com.moneybags.account.repository.BalanceHistoryRepository;
import com.moneybags.account.repository.InterestAccrualRepository;
import com.moneybags.account.repository.InterestRunRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@DataJpaTest
@Import({InterestCalculationService.class, AccountEventPublisher.class,
        AccountOutboxPublisher.class, InterestRunStateService.class,
        SavingsInterestSchedule.class, AccountProperties.class,
        InterestCalculationServiceTest.JacksonConfiguration.class})
class InterestCalculationServiceTest {

    @Autowired InterestCalculationService interest;
    @Autowired AccountRepository accounts;
    @Autowired BalanceHistoryRepository history;
    @Autowired InterestAccrualRepository accruals;
    @Autowired InterestRunRepository runs;
    @Autowired AccountOutboxRepository outbox;
    @Autowired ObjectMapper objectMapper;
    @Autowired AccountOutboxPublisher publisher;
    @MockBean StatementClient statementClient;
    @MockBean AuditClient auditClient;
    @MockBean TransactionClient transactionClient;

    @Test
    void usesLastRecordedEodBalanceAndCarriesItAcrossDaysWithoutTransactions() throws Exception {
        LocalDate end = LocalDate.now(ZoneOffset.UTC).minusDays(1);
        LocalDate start = end.minusDays(6);
        accounts.save(account("REG-1", "SAV-REG", "3.5000", "3000", start.minusDays(10)));
        accounts.save(account("SENIOR-1", "SAV-SENIOR", "4.2500", "8000", start.minusDays(10)));
        accounts.save(account("CURRENT-1", "CUR-BASIC", "0", "9000", start.minusDays(10)));

        history.save(entry("REG-1", start, "0", "1000", 1));
        history.save(entry("REG-1", start.plusDays(2), "1000", "1500", 2));
        history.save(entry("REG-1", start.plusDays(2), "1500", "2000", 3));
        history.save(entry("REG-1", start.plusDays(5), "2000", "3000", 4));

        var run = interest.run(end, "interest-test");

        assertThat(run.accountsEvaluated()).isEqualTo(2);
        assertThat(run.accrualsCreated()).isEqualTo(2);
        assertThat(run.payoutsQueued()).isEqualTo(2);
        assertThat(run.accruals()).filteredOn(x -> x.accountId().equals("REG-1"))
                .singleElement().satisfies(value -> {
                    assertThat(value.sevenDayAverageBalance()).isEqualByComparingTo("2000");
                    assertThat(value.interestAmount()).isEqualByComparingTo("1");
                });
        assertThat(run.accruals()).filteredOn(x -> x.accountId().equals("SENIOR-1"))
                .singleElement().satisfies(value -> {
                    assertThat(value.sevenDayAverageBalance()).isEqualByComparingTo("8000");
                    assertThat(value.interestAmount()).isEqualByComparingTo("7");
                });
        assertThat(accruals.count()).isEqualTo(2);
        assertThat(outbox.findAll()).hasSize(2);
        assertThat(runs.findByPeriodEndDate(end)).hasValueSatisfying(checkpoint -> {
            assertThat(checkpoint.getStatus()).isEqualTo(InterestRunStatus.COMPLETED);
            assertThat(checkpoint.getAttempts()).isEqualTo(1);
            assertThat(checkpoint.getAccountsEvaluated()).isEqualTo(2);
            assertThat(checkpoint.getAccrualsCreated()).isEqualTo(2);
            assertThat(checkpoint.getPayoutsQueued()).isEqualTo(2);
        });

        AccountOutbox regularEvent = outbox.findAll().stream()
                .filter(event -> event.getAggregateId().equals("REG-1"))
                .findFirst().orElseThrow();
        InterestPayoutCommand command = objectMapper.readValue(
                regularEvent.getPayload(), InterestPayoutCommand.class);
        assertThat(command.amount()).isEqualByComparingTo("1");
        assertThat(command.periodStartDate()).isEqualTo(start);
        assertThat(command.periodEndDate()).isEqualTo(end);

        when(transactionClient.createInterestPayout(eq("account-service"), any(), any()))
                .thenAnswer(invocation -> {
                    InterestPayoutCommand payout = invocation.getArgument(2);
                    return new InterestPayoutResult(payout.accrualId(), "TXN-INT-TEST",
                            "PROJECTION_PENDING");
                });
        publisher.publish();
        assertThat(outbox.findAll()).allSatisfy(event -> {
            assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
            event.setNextAttemptAt(Instant.now().minusSeconds(1));
            outbox.save(event);
        });
        assertThat(accruals.findAll()).allSatisfy(accrual -> {
            assertThat(accrual.getPosted()).isFalse();
            assertThat(accrual.getPostedTransactionId()).isEqualTo(accrual.getAccrualId());
        });

        when(transactionClient.createInterestPayout(eq("account-service"), any(), any()))
                .thenAnswer(invocation -> {
                    InterestPayoutCommand payout = invocation.getArgument(2);
                    return new InterestPayoutResult(payout.accrualId(), "TXN-INT-TEST",
                            "COMPLETED");
                });
        publisher.publish();
        assertThat(outbox.findAll()).allSatisfy(event ->
                assertThat(event.getStatus()).isEqualTo(OutboxStatus.PUBLISHED));
        assertThat(accruals.findAll()).allSatisfy(accrual -> {
            assertThat(accrual.getPosted()).isTrue();
            assertThat(accrual.getPostedTransactionId()).isEqualTo(accrual.getAccrualId());
        });

        var replay = interest.run(end, "interest-test-replay");
        assertThat(replay.accrualsCreated()).isZero();
        assertThat(replay.accountsSkipped()).isEqualTo(2);
        assertThat(accruals.count()).isEqualTo(2);
        assertThat(outbox.findAll()).hasSize(2);
    }

    private Account account(String id, String product, String rate, String balance, LocalDate openedOn) {
        return Account.builder().accountId(id).accountNumber("10" + id.hashCode())
                .maskedAccountNumber("XXXX" + id).accountName(id).cifNo("CIF-" + id)
                .productCode(product).branchCode("BR001").currency("INR")
                .status(AccountStatus.ACTIVE).ledgerBalance(new BigDecimal(balance))
                .heldAmount(BigDecimal.ZERO).minBalance(BigDecimal.ZERO)
                .overdraftLimit(BigDecimal.ZERO).interestRate(new BigDecimal(rate))
                .openedOn(openedOn).build();
    }

    private BalanceHistory entry(String accountId, LocalDate date, String before,
                                 String after, int order) {
        return BalanceHistory.builder().accountId(accountId).eventId("E-" + order)
                .transactionId("T-" + order).transactionReference("TXN-" + order)
                .direction(Direction.CREDIT).amount(new BigDecimal(after).subtract(new BigDecimal(before)))
                .ledgerBalanceBefore(new BigDecimal(before)).ledgerBalanceAfter(new BigDecimal(after))
                .heldBefore(BigDecimal.ZERO).heldAfter(BigDecimal.ZERO).businessDate(date)
                .createdAt(Instant.parse("2026-01-01T00:00:00Z").plusSeconds(order)).build();
    }

    @TestConfiguration
    static class JacksonConfiguration {
        @Bean ObjectMapper objectMapper() { return new ObjectMapper().findAndRegisterModules(); }
    }
}
