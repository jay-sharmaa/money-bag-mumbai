package com.moneybags.account.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moneybags.account.client.AuditClient;
import com.moneybags.account.client.StatementClient;
import com.moneybags.account.client.TransactionClient;
import com.moneybags.account.client.TransactionClient.InterestPayoutCommand;
import com.moneybags.account.client.TransactionClient.InterestPayoutResult;
import com.moneybags.account.config.AccountProperties;
import com.moneybags.account.entity.*;
import com.moneybags.account.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@DataJpaTest
@Import({InterestCalculationService.class, InterestPayoutBatchService.class,
        AccountEventPublisher.class, AccountOutboxPublisher.class,
        InterestRunStateService.class, SavingsInterestSchedule.class,
        AccountProperties.class, InterestCalculationServiceTest.JacksonConfiguration.class})
class InterestCalculationServiceTest {

    @Autowired InterestCalculationService interest;
    @Autowired AccountRepository accounts;
    @Autowired BalanceHistoryRepository history;
    @Autowired InterestAccrualRepository accruals;
    @Autowired InterestPayoutBatchRepository payoutBatches;
    @Autowired InterestRunRepository runs;
    @Autowired AccountOutboxRepository outbox;
    @Autowired ObjectMapper objectMapper;
    @Autowired AccountOutboxPublisher publisher;
    @MockBean StatementClient statementClient;
    @MockBean AuditClient auditClient;
    @MockBean TransactionClient transactionClient;

    @Test
    void storesSevenDayAverageAndWeeklyAmountWithoutPayingIt() {
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
        assertThat(run.payoutsQueued()).isZero();
        assertThat(run.accruals()).filteredOn(x -> x.accountId().equals("REG-1"))
                .singleElement().satisfies(value -> {
                    assertThat(value.averageBalance()).isEqualByComparingTo("2000");
                    assertThat(value.interestAmount()).isEqualByComparingTo("1.342466");
                });
        assertThat(run.accruals()).filteredOn(x -> x.accountId().equals("SENIOR-1"))
                .singleElement().satisfies(value -> {
                    assertThat(value.averageBalance()).isEqualByComparingTo("8000");
                    assertThat(value.interestAmount()).isEqualByComparingTo("6.520548");
                });
        assertThat(accruals.findAll()).allSatisfy(accrual -> {
            assertThat(accrual.getAccrualDate()).isEqualTo(end);
            assertThat(accrual.getPosted()).isFalse();
            assertThat(accrual.getPayoutBatchId()).isNull();
        });
        assertThat(outbox.count()).isZero();
        assertThat(payoutBatches.count()).isZero();
        assertThat(runs.findByPeriodEndDate(end)).hasValueSatisfying(checkpoint -> {
            assertThat(checkpoint.getStatus()).isEqualTo(InterestRunStatus.COMPLETED);
            assertThat(checkpoint.getPayoutsQueued()).isZero();
        });
    }

    @Test
    void sums52WeeklyAmountsAndPostsOnePayout() throws Exception {
        LocalDate finalEnd = LocalDate.now(ZoneOffset.UTC).minusDays(1);
        LocalDate firstEnd = finalEnd.minusWeeks(51);
        Account account = accounts.save(account("REG-52", "SAV-REG", "3.5000", "3000",
                firstEnd.minusDays(7)));

        for (int week = 0; week < 51; week++) {
            LocalDate end = firstEnd.plusWeeks(week);
            accruals.save(InterestAccrual.builder()
                    .accrualId(UUID.randomUUID().toString())
                    .accountId(account.getAccountId())
                    .accrualDate(end)
                    .principalBase(new BigDecimal("1000"))
                    .rate(new BigDecimal("3.5000"))
                    .dayCountBasis(365)
                    .accruedAmount(new BigDecimal("1.000000"))
                    .posted(false)
                    .createdAt(Instant.now())
                    .build());
        }

        var run = interest.run(finalEnd, "52-week-interest-test");

        assertThat(run.accrualsCreated()).isEqualTo(1);
        assertThat(run.payoutsQueued()).isEqualTo(1);
        assertThat(accruals.count()).isEqualTo(52);
        assertThat(outbox.count()).isEqualTo(1);
        var batch = payoutBatches.findAll().get(0);
        assertThat(batch.getWeeklyAccrualCount()).isEqualTo(52);
        assertThat(batch.getAccruedAmount()).isEqualByComparingTo("53.013699");
        assertThat(batch.getPayoutAmount()).isEqualByComparingTo("53");
        assertThat(batch.getStatus()).isEqualTo(InterestPayoutBatchStatus.PAYOUT_QUEUED);
        assertThat(accruals.findAll()).allSatisfy(accrual ->
                assertThat(accrual.getPayoutBatchId()).isEqualTo(batch.getBatchId()));

        AccountOutbox event = outbox.findAll().get(0);
        InterestPayoutCommand command = objectMapper.readValue(
                event.getPayload(), InterestPayoutCommand.class);
        assertThat(command.payoutBatchId()).isEqualTo(batch.getBatchId());
        assertThat(command.amount()).isEqualByComparingTo("53");
        assertThat(command.periodStartDate()).isEqualTo(firstEnd.minusDays(6));
        assertThat(command.periodEndDate()).isEqualTo(finalEnd);

        when(transactionClient.createInterestPayout(eq("account-service"), any(), any()))
                .thenReturn(new InterestPayoutResult("TXN-INT-52", "TXN-INT-TEST",
                        "PROJECTION_PENDING"));
        publisher.publish();
        assertThat(outbox.findAll()).allSatisfy(pending -> {
            assertThat(pending.getStatus()).isEqualTo(OutboxStatus.PENDING);
            pending.setNextAttemptAt(Instant.now().minusSeconds(1));
            outbox.save(pending);
        });
        assertThat(accruals.findAll()).allSatisfy(accrual ->
                assertThat(accrual.getPosted()).isFalse());

        when(transactionClient.createInterestPayout(eq("account-service"), any(), any()))
                .thenReturn(new InterestPayoutResult("TXN-INT-52", "TXN-INT-TEST", "COMPLETED"));
        publisher.publish();

        assertThat(outbox.findAll()).allSatisfy(published ->
                assertThat(published.getStatus()).isEqualTo(OutboxStatus.PUBLISHED));
        assertThat(accruals.findAll()).allSatisfy(accrual -> {
            assertThat(accrual.getPosted()).isTrue();
            assertThat(accrual.getPostedTransactionId()).isEqualTo("TXN-INT-52");
        });
        assertThat(payoutBatches.findById(batch.getBatchId())).hasValueSatisfying(completed -> {
            assertThat(completed.getStatus()).isEqualTo(InterestPayoutBatchStatus.COMPLETED);
            assertThat(completed.getPayoutTransactionId()).isEqualTo("TXN-INT-52");
            assertThat(completed.getCompletedAt()).isNotNull();
        });
    }

    private Account account(String id, String product, String rate, String balance,
                            LocalDate openedOn) {
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
        @Bean ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }
    }
}
