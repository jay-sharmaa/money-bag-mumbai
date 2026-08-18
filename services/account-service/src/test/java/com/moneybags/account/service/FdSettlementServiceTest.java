package com.moneybags.account.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moneybags.account.client.AuditClient;
import com.moneybags.account.client.StatementClient;
import com.moneybags.account.client.TransactionClient;
import com.moneybags.account.client.TransactionClient.FdSettlementCommand;
import com.moneybags.account.client.TransactionClient.FdSettlementResult;
import com.moneybags.account.config.AccountProperties;
import com.moneybags.account.entity.*;
import com.moneybags.account.repository.*;
import com.moneybags.account.security.RequestActor;
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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@DataJpaTest
@Import({FdSettlementService.class, AccountEventPublisher.class,
        AccountOutboxPublisher.class, AccountProperties.class,
        FdSettlementServiceTest.JacksonConfiguration.class})
class FdSettlementServiceTest {
    @Autowired FdSettlementService settlements;
    @Autowired AccountRepository accounts;
    @Autowired AccountProductOwnershipRepository ownerships;
    @Autowired AccountOutboxRepository outbox;
    @Autowired AccountOutboxPublisher publisher;
    @Autowired ObjectMapper objectMapper;
    @MockBean TransactionClient transactionClient;
    @MockBean StatementClient statementClient;
    @MockBean AuditClient auditClient;

    @Test
    void maturityUsesSavingsBeforeCurrentAndPaysAct365Interest() throws Exception {
        LocalDate maturity = LocalDate.of(2026, 8, 18);
        Account owner = accounts.save(account("CUR-OWNER", "CUR-BASIC", "CIF-FD", maturity.minusYears(2)));
        accounts.save(account("CUR-OTHER", "CUR-BASIC", "CIF-FD", maturity.minusYears(3)));
        Account savings = accounts.save(account("SAV-TARGET", "SAV-REG", "CIF-FD", maturity.minusYears(1)));
        AccountProductOwnership fd = ownerships.save(fd("FD-MATURE", owner, maturity.minusYears(1), maturity));

        settlements.requestMaturity(fd.getOwnershipId(), maturity, "maturity-test");

        AccountOutbox event = outbox.findAll().get(0);
        FdSettlementCommand command = objectMapper.readValue(event.getPayload(), FdSettlementCommand.class);
        assertThat(command.destinationAccountId()).isEqualTo(savings.getAccountId());
        assertThat(command.principalAmount()).isEqualByComparingTo("10000");
        assertThat(command.interestAmount()).isEqualByComparingTo("675.00");
        assertThat(command.settlementType()).isEqualTo("MATURITY");

        when(transactionClient.createFdSettlement(eq("account-service"), any(), any()))
                .thenReturn(new FdSettlementResult("TXN-FD-MATURE", "TXN-FD-1", "COMPLETED"));
        publisher.publish();

        assertThat(ownerships.findById(fd.getOwnershipId())).hasValueSatisfying(settled -> {
            assertThat(settled.getStatus()).isEqualTo(ProductOwnershipStatus.MATURED);
            assertThat(settled.getSettlementStatus()).isEqualTo(FdSettlementStatus.COMPLETED);
            assertThat(settled.getSettlementDestinationAccountId()).isEqualTo(savings.getAccountId());
            assertThat(settled.getSettlementTransactionId()).isEqualTo("TXN-FD-MATURE");
            assertThat(settled.getSettledAt()).isNotNull();
        });
    }

    @Test
    void prematureBreakQueuesFullPrincipalWithNoInterestOrDeduction() throws Exception {
        LocalDate today = LocalDate.now(java.time.ZoneOffset.UTC);
        Account owner = accounts.save(account("FD-BREAK-ACCOUNT", "FD-12M", "CIF-BREAK", today.minusYears(2)));
        Account savings = accounts.save(account("SAV-BREAK", "SAV-SENIOR", "CIF-BREAK", today.minusYears(1)));
        AccountProductOwnership fd = fd("FD-BREAK", owner,
                today.minusDays(100), today.plusDays(265));
        fd.setAcquisitionType(ProductAcquisitionType.ACCOUNT_OPENING);
        fd.setPurchaseTransactionId(null);
        fd = ownerships.save(fd);
        RequestActor actor = new RequestActor("EMP-1", "BR001",
                Set.of(RequestActor.PERMISSION_STATUS_MANAGE), "break-test");

        settlements.requestPrematureBreak(actor, fd.getOwnershipId());

        FdSettlementCommand command = objectMapper.readValue(
                outbox.findAll().get(0).getPayload(), FdSettlementCommand.class);
        assertThat(command.destinationAccountId()).isEqualTo(savings.getAccountId());
        assertThat(command.principalAmount()).isEqualByComparingTo("10000");
        assertThat(command.interestAmount()).isZero();
        assertThat(command.settlementType()).isEqualTo("PREMATURE_BREAK");
        assertThat(command.purchaseTransactionId()).isNull();
        assertThat(command.sourceFdAccountId()).isEqualTo(owner.getAccountId());

        when(transactionClient.createFdSettlement(eq("account-service"), any(), any()))
                .thenReturn(new FdSettlementResult("TXN-FD-BREAK", "TXN-FD-2", "COMPLETED"));
        publisher.publish();

        assertThat(ownerships.findById(fd.getOwnershipId())).hasValueSatisfying(settled -> {
            assertThat(settled.getStatus()).isEqualTo(ProductOwnershipStatus.CLOSED);
            assertThat(settled.getSettlementStatus()).isEqualTo(FdSettlementStatus.COMPLETED);
        });
        assertThat(accounts.findById(owner.getAccountId())).hasValueSatisfying(closed -> {
            assertThat(closed.getStatus()).isEqualTo(AccountStatus.CLOSED);
            assertThat(closed.getClosedOn()).isEqualTo(today);
        });
    }

    private Account account(String id, String productCode, String cif, LocalDate openedOn) {
        return Account.builder().accountId(id).accountNumber("10" + Math.abs(id.hashCode()))
                .maskedAccountNumber("XXXX" + id).accountName(id).cifNo(cif)
                .productCode(productCode).branchCode("BR001").currency("INR")
                .status(AccountStatus.ACTIVE).ledgerBalance(new BigDecimal("10000"))
                .heldAmount(BigDecimal.ZERO).minBalance(BigDecimal.ZERO)
                .overdraftLimit(BigDecimal.ZERO).interestRate(BigDecimal.ZERO)
                .openedOn(openedOn).build();
    }

    private AccountProductOwnership fd(String id, Account owner, LocalDate acquired,
                                       LocalDate maturity) {
        return AccountProductOwnership.builder()
                .ownershipId(id).ownerAccountId(owner.getAccountId())
                .productCode("FD-12M").productName("12-Month Fixed Deposit")
                .productType("TERM_DEPOSIT").productVersionId(4L).productVersionNumber(2)
                .acquisitionType(ProductAcquisitionType.TRANSACTION_PURCHASE)
                .principalAmount(new BigDecimal("10000")).currency("INR")
                .interestRate(new BigDecimal("6.7500")).tenureMonths(12)
                .acquiredOn(acquired).maturityDate(maturity)
                .status(ProductOwnershipStatus.ACTIVE)
                .purchaseTransactionId("PURCHASE-" + id)
                .build();
    }

    @TestConfiguration
    static class JacksonConfiguration {
        @Bean ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }
    }
}
