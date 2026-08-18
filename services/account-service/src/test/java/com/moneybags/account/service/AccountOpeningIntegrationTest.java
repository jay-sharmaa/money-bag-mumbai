package com.moneybags.account.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moneybags.account.api.ApiModels.CreateApplicationRequest;
import com.moneybags.account.api.ApiModels.DecisionRequest;
import com.moneybags.account.api.InternalModels.AccountEvent;
import com.moneybags.account.client.CustomerClient;
import com.moneybags.account.client.ProductClient;
import com.moneybags.account.dto.OwnedProductProjectionRequest;
import com.moneybags.account.client.TransactionClient.OpeningDepositCommand;
import com.moneybags.account.entity.Account;
import com.moneybags.account.entity.AccountOutbox;
import com.moneybags.account.repository.AccountOutboxRepository;
import com.moneybags.account.repository.AccountProductOwnershipRepository;
import com.moneybags.account.repository.AccountRepository;
import com.moneybags.account.security.RequestActor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@DataJpaTest
@Import({AccountApplicationService.class, AccountEventPublisher.class,
        AccountProductOwnershipService.class,
        AccountOpeningIntegrationTest.JacksonConfiguration.class})
class AccountOpeningIntegrationTest {

    @Autowired AccountApplicationService applications;
    @Autowired AccountRepository accounts;
    @Autowired AccountOutboxRepository outbox;
    @Autowired AccountProductOwnershipRepository ownerships;
    @Autowired AccountProductOwnershipService productOwnershipService;
    @Autowired ObjectMapper objectMapper;

    @MockBean ProductClient productClient;
    @MockBean CustomerClient customerClient;
    @MockBean FdSettlementService fdSettlementService;

    @Test
    void approvalProjectsANonNullTimestampAndQueuesTheRequestedOpeningDeposit() throws Exception {
        when(customerClient.eligibility("CIF-OPEN-1"))
                .thenReturn(new CustomerClient.EligibilityResponse(true, "VERIFIED", "ACTIVE", null));
        when(productClient.effective("SAV-REG")).thenReturn(new ProductClient.EffectiveProduct(
                "SAV-REG", "Regular Savings", "SAVINGS", "INR",
                new BigDecimal("3.5"), new BigDecimal("1000"), new BigDecimal("1000"),
                new BigDecimal("50000"), 5, null, BigDecimal.ZERO,
                false, false, 18, "ACTIVE", "2026-08-16", 11L, 3));

        RequestActor maker = new RequestActor("EMP-1", "BR001",
                Set.of(RequestActor.PERMISSION_OPEN), "create-correlation");
        RequestActor checker = new RequestActor("EMP-2", "BR001",
                Set.of(RequestActor.PERMISSION_APPROVE), "approve-correlation");

        var application = applications.create(maker, new CreateApplicationRequest(
                "CIF-OPEN-1", "SAV-REG", "Opening Deposit Test",
                new BigDecimal("5000"), "INR"));
        var approved = applications.approve(checker, application.applicationId(),
                new DecisionRequest("approved"));

        Account account = accounts.findById(approved.createdAccountId()).orElseThrow();
        assertThat(account.getUpdatedAt()).isNotNull();
        var ownedProduct = ownerships.findByOwnerAccountIdOrderByAcquiredOnDescCreatedAtDesc(
                account.getAccountId()).get(0);
        assertThat(ownedProduct.getProductCode()).isEqualTo("SAV-REG");
        assertThat(ownedProduct.getProductVersionId()).isEqualTo(11L);
        assertThat(ownedProduct.getProductVersionNumber()).isEqualTo(3);
        assertThat(ownedProduct.getPrincipalAmount()).isEqualByComparingTo("5000");

        List<AccountOutbox> events = outbox.findAll();
        assertThat(events).hasSize(3);

        AccountOutbox statementEvent = events.stream()
                .filter(e -> AccountEventPublisher.DESTINATION_STATEMENT.equals(e.getDestination()))
                .findFirst().orElseThrow();
        AccountEvent projectedAccount = objectMapper.readValue(
                statementEvent.getPayload(), AccountEvent.class);
        assertThat(projectedAccount.sourceUpdatedAt()).isNotNull();
        assertThat(statementEvent.getEventId()).isEqualTo(projectedAccount.sourceEventId());

        AccountOutbox transactionEvent = events.stream()
                .filter(e -> AccountEventPublisher.DESTINATION_TRANSACTION.equals(e.getDestination()))
                .findFirst().orElseThrow();
        OpeningDepositCommand command = objectMapper.readValue(
                transactionEvent.getPayload(), OpeningDepositCommand.class);
        assertThat(command.accountId()).isEqualTo(account.getAccountId());
        assertThat(command.amount()).isEqualByComparingTo("5000");
        assertThat(command.applicationReference()).isEqualTo(application.applicationReference());
        assertThat(command.initiatedByEmployeeId()).isEqualTo("EMP-2");
        assertThat(command.correlationId()).isEqualTo("approve-correlation");
    }

    @Test
    void purchasedProductProjectionIsIdempotentAndReversible() {
        when(customerClient.eligibility("CIF-OWN-1"))
                .thenReturn(new CustomerClient.EligibilityResponse(true, "VERIFIED", "ACTIVE", null));
        when(productClient.effective("SAV-REG")).thenReturn(new ProductClient.EffectiveProduct(
                "SAV-REG", "Regular Savings", "SAVINGS", "INR",
                new BigDecimal("3.5"), new BigDecimal("1000"), new BigDecimal("1000"),
                new BigDecimal("50000"), 5, null, BigDecimal.ZERO,
                false, false, 18, "ACTIVE", "2026-08-16", 1L, 1));
        RequestActor maker = new RequestActor("EMP-1", "BR001",
                Set.of(RequestActor.PERMISSION_OPEN), "create-owned");
        RequestActor checker = new RequestActor("EMP-2", "BR001",
                Set.of(RequestActor.PERMISSION_APPROVE), "approve-owned");
        var application = applications.create(maker, new CreateApplicationRequest(
                "CIF-OWN-1", "SAV-REG", "Owned Product Test", new BigDecimal("10000"), "INR"));
        String accountId = applications.approve(checker, application.applicationId(),
                new DecisionRequest("approved")).createdAccountId();

        LocalDate acquiredOn = LocalDate.of(2026, 8, 16);
        OwnedProductProjectionRequest activation = new OwnedProductProjectionRequest(
                "purchase-1", "ACTIVATE", "transaction-1", null, accountId,
                "FD-12M", "12-Month Fixed Deposit", "TERM_DEPOSIT", 4L, 2,
                new BigDecimal("5000"), "INR", new BigDecimal("6.75"), 12,
                acquiredOn, acquiredOn.plusMonths(12));
        var first = productOwnershipService.projectPurchase(activation);
        var replay = productOwnershipService.projectPurchase(activation);
        assertThat(replay.ownershipId()).isEqualTo(first.ownershipId());
        assertThat(ownerships.findByOwnerAccountIdOrderByAcquiredOnDescCreatedAtDesc(accountId))
                .hasSize(2);

        OwnedProductProjectionRequest reversal = new OwnedProductProjectionRequest(
                "purchase-1", "REVERSE", "transaction-1", "reversal-1", accountId,
                "FD-12M", "12-Month Fixed Deposit", "TERM_DEPOSIT", 4L, 2,
                new BigDecimal("5000"), "INR", new BigDecimal("6.75"), 12,
                acquiredOn, acquiredOn.plusMonths(12));
        assertThat(productOwnershipService.projectPurchase(reversal).status())
                .isEqualTo("REVERSED");
    }

    @TestConfiguration
    static class JacksonConfiguration {
        @Bean ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }
    }
}
