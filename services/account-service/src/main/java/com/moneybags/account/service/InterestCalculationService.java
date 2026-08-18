package com.moneybags.account.service;

import com.moneybags.account.dto.InterestAccrualView;
import com.moneybags.account.dto.InterestRunView;
import com.moneybags.account.entity.Account;
import com.moneybags.account.entity.AccountStatus;
import com.moneybags.account.entity.BalanceHistory;
import com.moneybags.account.entity.InterestAccrual;
import com.moneybags.account.entity.InterestRun;
import com.moneybags.account.repository.AccountRepository;
import com.moneybags.account.repository.BalanceHistoryRepository;
import com.moneybags.account.repository.InterestAccrualRepository;
import com.moneybags.account.security.RequestActor;
import com.moneybags.account.support.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class InterestCalculationService {

    private static final int PERIOD_DAYS = 7;
    private static final int DAY_COUNT_BASIS = 365;
    private static final Set<String> SAVINGS_PRODUCTS = Set.of("SAV-REG", "SAV-SENIOR");

    private final AccountRepository accounts;
    private final BalanceHistoryRepository balanceHistory;
    private final InterestAccrualRepository accruals;
    private final AccountEventPublisher events;
    private final InterestRunStateService runStates;
    private final SavingsInterestSchedule schedule;
    private final PlatformTransactionManager transactionManager;

    public InterestRunView run(LocalDate periodEndDate, String correlationId) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        if (!periodEndDate.isBefore(today)) {
            throw ApiException.unprocessable("INTEREST_PERIOD_NOT_COMPLETE",
                    "Interest can only be calculated after the period end date has completed");
        }
        InterestRunStateService.Claim claim = runStates.claim(
                periodEndDate, schedule.scheduledAt(periodEndDate));
        if (!claim.execute()) {
            return completedReplay(claim.run());
        }
        try {
            InterestRunView result = new TransactionTemplate(transactionManager).execute(status -> {
                InterestRunView calculated = calculate(periodEndDate, correlationId);
                runStates.complete(claim.run().getRunId(), calculated);
                return calculated;
            });
            if (result == null) throw new IllegalStateException("Interest calculation returned no result");
            return result;
        } catch (RuntimeException failure) {
            runStates.fail(claim.run().getRunId(), failure);
            throw failure;
        }
    }

    private InterestRunView calculate(LocalDate periodEndDate, String correlationId) {
        LocalDate periodStartDate = periodEndDate.minusDays(PERIOD_DAYS - 1L);
        List<Account> eligible = accounts.findByStatusAndProductCodeIn(
                AccountStatus.ACTIVE, SAVINGS_PRODUCTS);
        List<InterestAccrualView> created = new ArrayList<>();
        int skipped = 0;
        int queued = 0;

        for (Account account : eligible) {
            if (account.getOpenedOn().isAfter(periodStartDate)
                    || accruals.existsByAccountIdAndAccrualDateBetween(
                    account.getAccountId(), periodStartDate, periodEndDate.plusDays(PERIOD_DAYS - 1L))) {
                skipped++;
                continue;
            }
            BigDecimal average = sevenDayAverage(account, periodStartDate, periodEndDate);
            BigDecimal interest = average
                    .multiply(account.getInterestRate())
                    .multiply(BigDecimal.valueOf(PERIOD_DAYS))
                    .divide(BigDecimal.valueOf(100L * DAY_COUNT_BASIS), 6, RoundingMode.HALF_EVEN)
                    .setScale(0, RoundingMode.HALF_UP);

            InterestAccrual accrual = InterestAccrual.builder()
                    .accrualId(UUID.randomUUID().toString())
                    .accountId(account.getAccountId())
                    .accrualDate(periodEndDate)
                    .principalBase(average)
                    .rate(account.getInterestRate())
                    .dayCountBasis(DAY_COUNT_BASIS)
                    .accruedAmount(interest)
                    .posted(interest.signum() == 0)
                    .createdAt(Instant.now())
                    .build();
            accruals.save(accrual);
            if (interest.signum() > 0) {
                events.enqueueInterestPayout(account, accrual, periodStartDate, correlationId);
                queued++;
            }
            created.add(toView(account, accrual));
        }
        return new InterestRunView(periodStartDate, periodEndDate, eligible.size(),
                created.size(), queued, skipped, created);
    }

    private InterestRunView completedReplay(InterestRun run) {
        int evaluated = run.getAccountsEvaluated() == null ? 0 : run.getAccountsEvaluated();
        List<InterestAccrualView> existing = accruals
                .findByAccrualDateOrderByAccountIdAsc(run.getPeriodEndDate()).stream()
                .map(accrual -> accounts.findById(accrual.getAccountId())
                        .map(account -> toView(account, accrual)).orElse(null))
                .filter(java.util.Objects::nonNull)
                .toList();
        return new InterestRunView(run.getPeriodStartDate(), run.getPeriodEndDate(), evaluated,
                0, 0, evaluated, existing);
    }

    @Transactional(readOnly = true)
    public List<InterestAccrualView> list(RequestActor actor, String accountId) {
        actor.require(RequestActor.PERMISSION_VIEW);
        Account account = accounts.findById(accountId)
                .orElseThrow(() -> ApiException.notFound("ACCOUNT_NOT_FOUND", "No account with id " + accountId));
        actor.requireBranchAccess(account.getBranchCode());
        return accruals.findByAccountIdOrderByAccrualDateDesc(accountId).stream()
                .map(accrual -> toView(account, accrual)).toList();
    }

    private BigDecimal sevenDayAverage(Account account, LocalDate start, LocalDate end) {
        List<BalanceHistory> rows = balanceHistory
                .findByAccountIdOrderByBusinessDateAscCreatedAtAsc(account.getAccountId());
        BigDecimal carried = startingBalance(account, rows, start);
        BigDecimal total = BigDecimal.ZERO;
        int index = 0;
        while (index < rows.size() && rows.get(index).getBusinessDate().isBefore(start)) index++;
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            while (index < rows.size() && rows.get(index).getBusinessDate().equals(date)) {
                carried = rows.get(index).getLedgerBalanceAfter();
                index++;
            }
            total = total.add(carried.max(BigDecimal.ZERO));
        }
        return total.divide(BigDecimal.valueOf(PERIOD_DAYS), 6, RoundingMode.HALF_EVEN);
    }

    private BigDecimal startingBalance(Account account, List<BalanceHistory> rows, LocalDate start) {
        BalanceHistory prior = null;
        for (BalanceHistory row : rows) {
            if (row.getBusinessDate().isBefore(start)) prior = row;
            else break;
        }
        if (prior != null) return prior.getLedgerBalanceAfter();
        return rows.isEmpty() ? account.getLedgerBalance() : rows.get(0).getLedgerBalanceBefore();
    }

    private InterestAccrualView toView(Account account, InterestAccrual accrual) {
        return new InterestAccrualView(accrual.getAccrualId(), account.getAccountId(),
                account.getProductCode(), accrual.getAccrualDate().minusDays(PERIOD_DAYS - 1L),
                accrual.getAccrualDate(), accrual.getPrincipalBase(), accrual.getRate(),
                accrual.getDayCountBasis(), accrual.getAccruedAmount(),
                Boolean.TRUE.equals(accrual.getPosted()), accrual.getPostedTransactionId(),
                accrual.getCreatedAt());
    }
}
