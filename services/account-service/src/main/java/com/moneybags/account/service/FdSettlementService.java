package com.moneybags.account.service;

import com.moneybags.account.entity.*;
import com.moneybags.account.repository.AccountProductOwnershipRepository;
import com.moneybags.account.repository.AccountRepository;
import com.moneybags.account.security.RequestActor;
import com.moneybags.account.support.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class FdSettlementService {
    private static final int DAY_COUNT_BASIS = 365;
    private static final Set<String> DESTINATION_PRODUCTS = Set.of(
            "SAV-REG", "SAV-SENIOR", "CUR-BASIC");

    private final AccountProductOwnershipRepository ownerships;
    private final AccountRepository accounts;
    private final AccountEventPublisher events;

    @Transactional
    public AccountProductOwnership requestPrematureBreak(RequestActor actor, String ownershipId) {
        actor.require(RequestActor.PERMISSION_STATUS_MANAGE);
        AccountProductOwnership ownership = requireActiveFd(ownershipId);
        Account ownerAccount = requireAccount(ownership.getOwnerAccountId());
        actor.requireBranchAccess(ownerAccount.getBranchCode());
        LocalDate today = LocalDate.now(java.time.ZoneOffset.UTC);
        if (!today.isBefore(ownership.getMaturityDate())) {
            throw ApiException.conflict("FD_ALREADY_MATURE",
                    "The fixed deposit has reached maturity and cannot be broken prematurely");
        }
        return queue(ownership, FdSettlementType.PREMATURE_BREAK, today,
                BigDecimal.ZERO.setScale(2), actor.correlationId());
    }

    @Transactional
    public AccountProductOwnership requestMaturity(String ownershipId, LocalDate today,
                                                    String correlationId) {
        AccountProductOwnership ownership = requireActiveFd(ownershipId);
        if (today.isBefore(ownership.getMaturityDate())) {
            throw ApiException.conflict("FD_NOT_MATURE",
                    "The fixed deposit has not reached its maturity date");
        }
        long interestDays = ChronoUnit.DAYS.between(
                ownership.getAcquiredOn(), ownership.getMaturityDate());
        BigDecimal interest = ownership.getPrincipalAmount()
                .multiply(ownership.getInterestRate())
                .multiply(BigDecimal.valueOf(interestDays))
                .divide(BigDecimal.valueOf(100L * DAY_COUNT_BASIS), 2, RoundingMode.HALF_EVEN);
        return queue(ownership, FdSettlementType.MATURITY,
                ownership.getMaturityDate(), interest, correlationId);
    }

    @Transactional(readOnly = true)
    public List<AccountProductOwnership> due(LocalDate today) {
        return ownerships.findDueTermDeposits(today, ProductOwnershipStatus.ACTIVE,
                List.of(FdSettlementStatus.NONE, FdSettlementStatus.FAILED));
    }

    private AccountProductOwnership queue(AccountProductOwnership ownership,
                                          FdSettlementType type,
                                          LocalDate settlementDate,
                                          BigDecimal interest,
                                          String correlationId) {
        if (ownership.getSettlementStatus() != FdSettlementStatus.NONE
                && ownership.getSettlementStatus() != FdSettlementStatus.FAILED) {
            throw ApiException.conflict("FD_SETTLEMENT_ALREADY_REQUESTED",
                    "A settlement is already in progress for this fixed deposit");
        }
        Account destination = preferredDestination(ownership);
        ownership.setSettlementStatus(FdSettlementStatus.PAYOUT_QUEUED);
        ownership.setSettlementType(type);
        ownership.setSettlementDestinationAccountId(destination.getAccountId());
        ownership.setSettlementInterestAmount(interest);
        ownership.setSettlementTransactionId(null);
        ownership.setSettledAt(null);
        ownerships.save(ownership);
        events.enqueueFdSettlement(ownership, destination, interest, type,
                settlementDate, correlationId);
        return ownership;
    }

    private Account preferredDestination(AccountProductOwnership ownership) {
        Account owner = requireAccount(ownership.getOwnerAccountId());
        return accounts.findByCifNoAndStatusAndProductCodeIn(
                        owner.getCifNo(), AccountStatus.ACTIVE, DESTINATION_PRODUCTS).stream()
                .sorted(Comparator
                        .comparingInt((Account account) ->
                                account.getProductCode().startsWith("SAV-") ? 0 : 1)
                        .thenComparing(Account::getOpenedOn)
                        .thenComparing(Account::getAccountId))
                .findFirst()
                .orElseThrow(() -> ApiException.conflict("FD_DESTINATION_ACCOUNT_REQUIRED",
                        "The customer needs an active savings or current account for FD settlement"));
    }

    private AccountProductOwnership requireActiveFd(String ownershipId) {
        AccountProductOwnership ownership = ownerships.findById(ownershipId)
                .orElseThrow(() -> ApiException.notFound("OWNED_PRODUCT_NOT_FOUND",
                        "No owned product exists with id " + ownershipId));
        if (!"TERM_DEPOSIT".equals(ownership.getProductType())) {
            throw ApiException.invalid("PRODUCT_NOT_FIXED_DEPOSIT",
                    "Only a fixed deposit can use FD settlement");
        }
        if (ownership.getStatus() != ProductOwnershipStatus.ACTIVE) {
            throw ApiException.conflict("FD_NOT_ACTIVE", "Only an active fixed deposit can settle");
        }
        return ownership;
    }

    private Account requireAccount(String accountId) {
        return accounts.findById(accountId)
                .orElseThrow(() -> ApiException.notFound("ACCOUNT_NOT_FOUND",
                        "No account with id " + accountId));
    }
}
