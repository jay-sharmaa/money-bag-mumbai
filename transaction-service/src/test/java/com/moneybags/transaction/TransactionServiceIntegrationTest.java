package com.moneybags.transaction;

import com.moneybags.transaction.api.TransactionModels.*;
import com.moneybags.transaction.client.*;
import com.moneybags.transaction.config.TransactionProperties;
import com.moneybags.transaction.domain.*;
import com.moneybags.transaction.entity.Transaction;
import com.moneybags.transaction.exception.DomainException;
import com.moneybags.transaction.repository.*;
import com.moneybags.transaction.security.RequestActor;
import com.moneybags.transaction.service.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class TransactionServiceIntegrationTest {
    @Autowired TransactionOrchestrator orchestrator; @Autowired CallbackService callbacks; @Autowired OutboxPublisher publisher; @Autowired TransactionProperties properties;
    @Autowired TransactionRepository transactions; @Autowired TransactionLegRepository legs; @Autowired FundsHoldRepository holds; @Autowired JournalEntryRepository journals;
    @Autowired ClearingInstructionRepository clearing; @Autowired OutboxEventRepository outbox;
    @Autowired MockMvc mockMvc;
    @MockBean AccountClient accountClient; @MockBean CardClient cardClient; @MockBean LedgerClient ledgerClient; @MockBean StatementClient statementClient;
    RequestActor maker;

    @BeforeEach void setup(){
        maker=new RequestActor("EMP-100","MB001",Set.of("TRANSACTION_CREATE","TRANSACTION_VIEW","TRANSACTION_APPROVE","TRANSACTION_CANCEL","TRANSACTION_REVERSE","RECONCILIATION_MANAGE"),"corr-1");
        when(accountClient.context(anyString())).thenAnswer(i->new AccountClient.AccountContext(i.getArgument(0),"AH-1","ACTIVE","INR",new BigDecimal("5000000"),new BigDecimal("5000000"),1));
        when(accountClient.reserve(anyString(),anyString(),any())).thenAnswer(i->{AccountClient.HoldRequest r=i.getArgument(2);return new AccountClient.HoldResponse("H-"+r.transactionId(),"FUNDS_HELD",r.amount());});
        doAnswer(i->{LedgerClient.JournalPostRequest r=i.getArgument(0);assertThat(transactions.findById(r.transactionId()).orElseThrow().getCompletedAt()).isNotNull();return null;}).when(ledgerClient).post(any());
    }
    @AfterEach void resetOutbox(){properties.getOutbox().setEnabled(false);}

    @Test void depositCreatesLegBalancedJournalAndOutbox(){
        Transaction tx=orchestrator.create(TransactionType.DEPOSIT,PaymentRail.CASH,deposit(),"dep-1",maker);
        assertThat(tx.getStatus()).isEqualTo(TransactionStatus.PROJECTION_PENDING);assertThat(legs.findByTransactionIdOrderBySequenceNo(tx.getId())).hasSize(1);assertThat(journals.findByTransactionIdOrderByCreatedAt(tx.getId())).hasSize(1);assertThat(outbox.findByAggregateId(tx.getId())).hasSize(2);assertThat(holds.findByTransactionId(tx.getId())).isEmpty();
    }
    @Test void duplicateCreateReturnsOriginalWithoutDuplicateHoldOrFacts(){
        Transaction first=orchestrator.create(TransactionType.WITHDRAWAL,PaymentRail.CASH,withdrawal(),"wd-dup",maker);Transaction second=orchestrator.create(TransactionType.WITHDRAWAL,PaymentRail.CASH,withdrawal(),"wd-dup",maker);
        assertThat(second.getId()).isEqualTo(first.getId());assertThat(holds.findByTransactionId(first.getId())).isPresent();assertThat(journals.findByTransactionIdOrderByCreatedAt(first.getId())).hasSize(1);verify(accountClient,times(1)).reserve(anyString(),anyString(),any());
    }
    @Test void makerCannotApproveOwnHighValueTransaction(){
        Transaction tx=orchestrator.create(TransactionType.RTGS,PaymentRail.RTGS,external(new BigDecimal("1000000")),"approval-1",maker);assertThat(tx.getStatus()).isEqualTo(TransactionStatus.PENDING_APPROVAL);assertThat(holds.findByTransactionId(tx.getId())).isEmpty();
        assertThatThrownBy(()->orchestrator.approve(tx.getId(),"approve-self",maker)).isInstanceOf(DomainException.class).hasMessageContaining("Maker");
    }
    @Test void employeeFromAnotherBranchCannotApproveTransaction(){
        Transaction tx=orchestrator.create(TransactionType.RTGS,PaymentRail.RTGS,external(new BigDecimal("1000000")),"branch-approval",maker);
        RequestActor otherBranch=new RequestActor("EMP-200","MB002",Set.of("TRANSACTION_APPROVE"),"corr-2");
        assertThatThrownBy(()->orchestrator.approve(tx.getId(),"other-branch-approval",otherBranch))
                .isInstanceOf(DomainException.class).hasMessageContaining("another branch");
    }
    @Test void cancellationBeforeProcessingHasNoFinancialSideEffects(){
        Transaction tx=orchestrator.create(TransactionType.RTGS,PaymentRail.RTGS,external(new BigDecimal("1000000")),"cancel-create",maker);Transaction cancelled=orchestrator.cancel(tx.getId(),"Employee cancelled the request","cancel-action",maker);
        assertThat(cancelled.getStatus()).isEqualTo(TransactionStatus.CANCELLED);assertThat(holds.findByTransactionId(tx.getId())).isEmpty();assertThat(legs.findByTransactionIdOrderBySequenceNo(tx.getId())).isEmpty();assertThat(journals.findByTransactionIdOrderByCreatedAt(tx.getId())).isEmpty();assertThat(clearing.findByTransactionId(tx.getId())).isEmpty();assertThat(outbox.findByAggregateId(tx.getId())).isEmpty();
    }
    @Test void outboxDeliveryConsumesHoldAndCompletesWithdrawalOnce(){
        Transaction tx=orchestrator.create(TransactionType.WITHDRAWAL,PaymentRail.CASH,withdrawal(),"wd-publish",maker);properties.getOutbox().setEnabled(true);publisher.publish();
        assertThat(transactions.findById(tx.getId()).orElseThrow().getStatus()).isEqualTo(TransactionStatus.COMPLETED);assertThat(holds.findByTransactionId(tx.getId()).orElseThrow().getStatus().name()).isEqualTo("CONSUMED");verify(accountClient,atLeastOnce()).project(anyString(),any());verify(accountClient).consume(eq("A1"),anyString(),anyString());
        var ordered=inOrder(accountClient,ledgerClient,statementClient);ordered.verify(accountClient).project(anyString(),any());ordered.verify(ledgerClient).post(any());ordered.verify(statementClient).project(eq("transaction-service"),any());
    }
    @Test void duplicateSettlementCallbackCreatesOneSettlementEffect(){
        Transaction tx=orchestrator.create(TransactionType.NEFT,PaymentRail.NEFT,external(new BigDecimal("500")),"neft-settle",maker);properties.getOutbox().setEnabled(true);publisher.publish();
        CallbackRequest cb=new CallbackRequest("rail-event-1","NEFT-EXT-1",LocalDate.now(),"SETTLED",null);callbacks.settle(tx.getId(),cb);callbacks.settle(tx.getId(),cb);publisher.publish();
        assertThat(transactions.findById(tx.getId()).orElseThrow().getStatus()).isEqualTo(TransactionStatus.COMPLETED);assertThat(journals.findByTransactionIdOrderByCreatedAt(tx.getId())).hasSize(2);
    }
    @Test void completedWithdrawalReversalIsLinkedAndCompensating(){
        Transaction original=orchestrator.create(TransactionType.WITHDRAWAL,PaymentRail.CASH,withdrawal(),"reverse-source",maker);properties.getOutbox().setEnabled(true);publisher.publish();
        Transaction reversal=orchestrator.reverse(original.getId(),"Erroneous withdrawal","reverse-action",maker);assertThat(reversal.getReversalOf().getId()).isEqualTo(original.getId());assertThat(journals.findByTransactionIdOrderByCreatedAt(reversal.getId())).isNotEmpty();publisher.publish();
        assertThat(transactions.findById(reversal.getId()).orElseThrow().getStatus()).isEqualTo(TransactionStatus.COMPLETED);assertThat(transactions.findById(original.getId()).orElseThrow().getStatus()).isEqualTo(TransactionStatus.REVERSED);
    }
    @Test void twoWithdrawalsRacingForSameAvailableFundsCannotDoubleSpend() throws Exception {
        when(accountClient.context(anyString())).thenAnswer(i->new AccountClient.AccountContext(i.getArgument(0),"AH-1","ACTIVE","INR",new BigDecimal("50"),new BigDecimal("50"),1));
        AtomicReference<BigDecimal> available=new AtomicReference<>(new BigDecimal("50"));CyclicBarrier bothAtHold=new CyclicBarrier(2);
        doAnswer(i->{AccountClient.HoldRequest r=i.getArgument(2);bothAtHold.await(10,TimeUnit.SECONDS);synchronized(available){if(available.get().compareTo(r.amount())<0)throw DomainException.conflict("INSUFFICIENT_FUNDS","Atomic hold rejected");available.set(available.get().subtract(r.amount()));}return new AccountClient.HoldResponse("RACE-"+r.transactionId(),"FUNDS_HELD",r.amount());}).when(accountClient).reserve(anyString(),anyString(),any());
        long beforeTransactions=transactions.count(),beforeHolds=holds.count();ExecutorService pool=Executors.newFixedThreadPool(2);
        try{List<Future<Boolean>> results=List.of(pool.submit(()->attemptWithdrawal("race-a")),pool.submit(()->attemptWithdrawal("race-b")));long successes=0;for(Future<Boolean> result:results)if(result.get(15,TimeUnit.SECONDS))successes++;assertThat(successes).isEqualTo(1);}finally{pool.shutdownNow();}
        assertThat(transactions.count()-beforeTransactions).isEqualTo(1);assertThat(holds.count()-beforeHolds).isEqualTo(1);assertThat(available.get()).isEqualByComparingTo("9");
    }
    @Test void definitiveFailureReleasesHoldAndDuplicateCallbackDoesNotCompensateTwice(){
        Transaction tx=orchestrator.create(TransactionType.NEFT,PaymentRail.NEFT,external(new BigDecimal("500")),"neft-fail",maker);CallbackRequest cb=new CallbackRequest("rail-fail-1","NEFT-F-1",null,"FAILED","Receiving bank rejected");
        callbacks.fail(tx.getId(),cb);callbacks.fail(tx.getId(),cb);assertThat(transactions.findById(tx.getId()).orElseThrow().getStatus()).isEqualTo(TransactionStatus.FAILED);assertThat(holds.findByTransactionId(tx.getId()).orElseThrow().getStatus().name()).isEqualTo("RELEASED");verify(accountClient,times(1)).release(eq("A1"),anyString(),anyString());
    }
    @Test void chequeCreditsAccountOnlyAfterSuccessfulClearing(){
        CreateRequest cheque=new CreateRequest(null,"A1",null,new BigDecimal("700"),BigDecimal.ZERO,"INR",PaymentChannel.BRANCH,PaymentMethod.CHEQUE,null,"CHQ-100", "Cheque deposit",null);
        Transaction tx=orchestrator.create(TransactionType.CHEQUE,PaymentRail.CHEQUE,cheque,"cheque-create",maker);assertThat(journals.findByTransactionIdOrderByCreatedAt(tx.getId())).isEmpty();assertThat(outbox.findByAggregateId(tx.getId())).isEmpty();
        callbacks.cheque(tx.getId(),new CallbackRequest("cheque-event-1","CLR-CHQ-1",LocalDate.now(),"SETTLED",null));assertThat(journals.findByTransactionIdOrderByCreatedAt(tx.getId())).hasSize(1);assertThat(outbox.findByAggregateId(tx.getId())).hasSize(2);properties.getOutbox().setEnabled(true);publisher.publish();assertThat(transactions.findById(tx.getId()).orElseThrow().getStatus()).isEqualTo(TransactionStatus.COMPLETED);
    }
    @Test void swaggerUiAndOpenApiDocumentAreAvailable() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/swagger-ui.html"));
        mockMvc.perform(get("/swagger-ui.html"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/swagger-ui/index.html"));
        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("MoneyBags Transaction Service API"))
                .andExpect(jsonPath("$.paths['/api/v1/transactions/deposits']").exists())
                .andExpect(jsonPath("$.components.schemas.CreateRequest.properties.customerId").doesNotExist())
                .andExpect(jsonPath("$.components.schemas.CreateRequest.properties.beneficiaryId").doesNotExist())
                .andExpect(jsonPath("$.components.schemas.TransactionView.properties.accountHolderId").exists())
                .andExpect(jsonPath("$.components.schemas.TransactionView.properties.makerEmployeeId").exists())
                .andExpect(jsonPath("$.components.securitySchemes.employeeId.name").value("X-Employee-Id"))
                .andExpect(jsonPath("$.components.securitySchemes.branchCode.name").value("X-Branch-Code"))
                .andExpect(jsonPath("$.components.schemas.TransactionView.properties.beneficiaryId").doesNotExist());
    }
    private boolean attemptWithdrawal(String key){try{orchestrator.create(TransactionType.WITHDRAWAL,PaymentRail.CASH,withdrawal(),key,maker);return true;}catch(Exception expected){return false;}}
    private CreateRequest deposit(){return new CreateRequest(null,"A1",null,new BigDecimal("500"),BigDecimal.ZERO,"INR",PaymentChannel.BRANCH,PaymentMethod.CASH,null,null,"Cash deposit",null);}
    private CreateRequest withdrawal(){return new CreateRequest("A1",null,null,new BigDecimal("40"),new BigDecimal("1"),"INR",PaymentChannel.BRANCH,PaymentMethod.CASH,null,null,"Cash withdrawal",null);}
    private CreateRequest external(BigDecimal amount){return new CreateRequest("A1",null,null,amount,new BigDecimal("2"),"INR",PaymentChannel.WEB,amount.compareTo(new BigDecimal("200000"))>=0?PaymentMethod.RTGS:PaymentMethod.NEFT,null,null,"External transfer",null);}
}
