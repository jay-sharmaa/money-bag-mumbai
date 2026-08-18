package com.moneybags.transaction;

import com.moneybags.transaction.api.TransactionModels.*;
import com.moneybags.transaction.api.ProductPurchaseRequest;
import com.moneybags.transaction.api.InterestPayoutRequest;
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
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Instant;
import java.util.Set;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;
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
    @MockBean AccountClient accountClient; @MockBean CardClient cardClient; @MockBean LedgerClient ledgerClient; @MockBean StatementClient statementClient; @MockBean ProductClient productClient;
    RequestActor maker;

    @BeforeEach void setup(){
        maker=new RequestActor("EMP-100","MB001",Set.of("TRANSACTION_CREATE","TRANSACTION_VIEW","TRANSACTION_APPROVE","TRANSACTION_CANCEL","TRANSACTION_REVERSE","RECONCILIATION_MANAGE"),"corr-1");
        when(accountClient.context(anyString())).thenAnswer(i->new AccountClient.AccountContext(i.getArgument(0),"AH-1","ACTIVE","INR",new BigDecimal("5000000"),new BigDecimal("5000000"),1));
        when(accountClient.reserve(anyString(),anyString(),any())).thenAnswer(i->{AccountClient.HoldRequest r=i.getArgument(2);return new AccountClient.HoldResponse("H-"+r.transactionId(),"FUNDS_HELD",r.amount());});
        AtomicLong ids=new AtomicLong(1);
        when(ledgerClient.post(eq("transaction-service"),any())).thenAnswer(i->{LedgerClient.JournalPostRequest r=i.getArgument(1);List<LedgerClient.JournalLineResponse> lines=r.lines().stream().map(l->new LedgerClient.JournalLineResponse(ids.getAndIncrement(),1,l.ledgerCode(),l.description(),l.customerAccountId(),l.side(),l.amount(),l.description(),Instant.now())).toList();return new LedgerClient.JournalResponse(ids.getAndIncrement(),r.journalReference(),r.transactionId(),r.journalType(),r.description(),"POSTED",r.currencyCode(),r.lines().stream().filter(l->"DEBIT".equals(l.side())).map(LedgerClient.JournalLineRequest::amount).reduce(BigDecimal.ZERO,BigDecimal::add),r.lines().stream().filter(l->"CREDIT".equals(l.side())).map(LedgerClient.JournalLineRequest::amount).reduce(BigDecimal.ZERO,BigDecimal::add),null,Instant.now(),Instant.now(),"transaction-service",lines);});
        when(statementClient.push(eq("transaction-service"),any())).thenAnswer(i->{StatementClient.TransactionEvent e=i.getArgument(1);return new StatementClient.IngestResult(e.sourceEventId(),"APPLIED");});
        when(productClient.effective("FD-12M")).thenReturn(new ProductClient.EffectiveProduct(
                "FD-12M","12-Month Fixed Deposit","TERM_DEPOSIT","INR",
                new BigDecimal("6.75"),BigDecimal.ZERO,new BigDecimal("5000"),
                BigDecimal.ZERO,0,12,BigDecimal.ZERO,false,true,18,"ACTIVE",
                LocalDate.now(),41L,4));
        when(accountClient.projectOwnedProduct(eq("transaction-service"),any())).thenAnswer(i->{
            AccountClient.OwnedProductProjection p=i.getArgument(1);
            return new AccountClient.OwnedProductResult(p.ownershipId(),p.ownerAccountId(),p.productCode(),p.productName(),p.productType(),p.productVersionId(),p.productVersionNumber(),"TRANSACTION_PURCHASE",p.principalAmount(),p.currency(),p.interestRate(),p.tenureMonths(),p.acquiredOn(),p.maturityDate(),"ACTIVATE".equals(p.action())?"ACTIVE":"REVERSED",p.purchaseTransactionId(),p.reversalTransactionId());
        });
    }
    @AfterEach void resetOutbox(){properties.getOutbox().setEnabled(false);}

    @Test void depositCreatesLegBalancedJournalAndOutbox(){
        Transaction tx=orchestrator.create(TransactionType.DEPOSIT,PaymentRail.CASH,deposit(),"dep-1",maker);
        assertThat(tx.getStatus()).isEqualTo(TransactionStatus.PROJECTION_PENDING);assertThat(legs.findByTransactionIdOrderBySequenceNo(tx.getId())).hasSize(1);assertThat(journals.findByTransactionIdOrderByCreatedAt(tx.getId())).hasSize(1);assertThat(outbox.findByAggregateId(tx.getId())).hasSize(1);assertThat(holds.findByTransactionId(tx.getId())).isEmpty();
    }
    @Test void accountServiceOpeningDepositIsIdempotentAndCompletesAllProjections(){
        when(accountClient.context("OPEN-A1")).thenReturn(new AccountClient.AccountContext(
                "OPEN-A1","CIF-1","PENDING_ACTIVATION","INR",BigDecimal.ZERO,BigDecimal.ZERO,0));
        OpeningDepositRequest request=new OpeningDepositRequest("OPEN-A1",new BigDecimal("5000"),"INR",
                "APP-OPEN-001","EMP-200","BR001","opening-correlation");
        long before=transactions.count();
        Transaction first=orchestrator.createOpeningDeposit(request,"opening-deposit:OPEN-A1");
        Transaction replay=orchestrator.createOpeningDeposit(request,"opening-deposit:OPEN-A1");
        assertThat(replay.getId()).isEqualTo(first.getId());
        assertThat(transactions.count()-before).isEqualTo(1);
        assertThat(first.getReference()).isEqualTo("TXN-OPEN-APP-OPEN-001");
        properties.getOutbox().setEnabled(true);publisher.publish();
        assertThat(transactions.findById(first.getId()).orElseThrow().getStatus()).isEqualTo(TransactionStatus.COMPLETED);
        ArgumentCaptor<AccountClient.ProjectionInstruction> projection=ArgumentCaptor.forClass(AccountClient.ProjectionInstruction.class);
        verify(accountClient,atLeastOnce()).project(anyString(),projection.capture());
        List<AccountClient.ProjectionInstruction> openingProjections=projection.getAllValues().stream()
                .filter(value->value.transactionId().equals(first.getId())).toList();
        assertThat(openingProjections).hasSize(1);
        AccountClient.ProjectionInstruction openingProjection=openingProjections.get(0);
        assertThat(openingProjection.eventType()).isEqualTo("OPENING_DEPOSIT_POSTED");
        assertThat(openingProjection.amount()).isEqualByComparingTo("5000");
        ArgumentCaptor<LedgerClient.JournalPostRequest> journal=ArgumentCaptor.forClass(LedgerClient.JournalPostRequest.class);
        verify(ledgerClient,atLeastOnce()).post(eq("transaction-service"),journal.capture());
        List<LedgerClient.JournalPostRequest> openingJournals=journal.getAllValues().stream()
                .filter(value->value.transactionId().equals(first.getId())).toList();
        assertThat(openingJournals).hasSize(1);
        LedgerClient.JournalPostRequest openingJournal=openingJournals.get(0);
        assertThat(openingJournal.journalType()).isEqualTo("DEPOSIT");
        verify(statementClient).push(eq("transaction-service"),argThat(e->
                e.transactionId().equals(first.getId())&&e.transactionType().equals("DEPOSIT")
                        &&e.amount().compareTo(new BigDecimal("5000"))==0));
    }
    @Test void sevenDaySavingsInterestCreditsAccountLedgerAndStatement(){
        LocalDate end=LocalDate.now().minusDays(1),start=end.minusDays(6);
        String accrualId="11111111-1111-1111-1111-111111111111";
        InterestPayoutRequest request=new InterestPayoutRequest("A1",new BigDecimal("13"),"INR",
                accrualId,start,end,"MB001","interest-correlation");
        Transaction payout=orchestrator.createInterestPayout(request,"interest-payout:"+accrualId);
        assertThat(payout.getType()).isEqualTo(TransactionType.INTEREST_PAYOUT);
        assertThat(payout.getReference()).isEqualTo("TXN-INT-"+accrualId);
        assertThat(payout.getStatus()).isEqualTo(TransactionStatus.PROJECTION_PENDING);
        assertThat(journals.findByTransactionIdOrderByCreatedAt(payout.getId()))
                .singleElement().satisfies(journal ->
                        assertThat(journal.getReference()).hasSizeLessThanOrEqualTo(64));
        assertThat(holds.findByTransactionId(payout.getId())).isEmpty();
        properties.getOutbox().setEnabled(true);publisher.publish();
        assertThat(transactions.findById(payout.getId()).orElseThrow().getStatus()).isEqualTo(TransactionStatus.COMPLETED);
        verify(accountClient).project(anyString(),argThat(p->p.transactionId().equals(payout.getId())
                &&p.accountId().equals("A1")&&"CREDIT".equals(p.direction())
                &&"INTEREST_PAYOUT_POSTED".equals(p.eventType())&&p.amount().compareTo(new BigDecimal("13"))==0));
        verify(ledgerClient).post(eq("transaction-service"),argThat(j->j.transactionId().equals(payout.getId())
                &&"INTEREST_PAYOUT".equals(j.journalType())
                &&j.lines().get(0).ledgerCode().equals("510100")&&j.lines().get(0).side().equals("DEBIT")
                &&j.lines().get(1).ledgerCode().equals("210000")&&j.lines().get(1).side().equals("CREDIT")
                &&j.lines().get(1).customerAccountId().equals("A1")));
        verify(statementClient).push(eq("transaction-service"),argThat(e->e.transactionId().equals(payout.getId())
                &&"INTEREST_PAYOUT".equals(e.transactionType())&&"CREDIT".equals(e.direction())
                &&e.amount().compareTo(new BigDecimal("13"))==0));
        InOrder order=inOrder(accountClient,ledgerClient,statementClient);
        order.verify(accountClient).project(anyString(),argThat(p->p.transactionId().equals(payout.getId())));
        order.verify(ledgerClient).post(eq("transaction-service"),argThat(j->j.transactionId().equals(payout.getId())));
        order.verify(statementClient).push(eq("transaction-service"),argThat(e->e.transactionId().equals(payout.getId())));
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
        assertThat(transactions.findById(tx.getId()).orElseThrow().getStatus()).isEqualTo(TransactionStatus.COMPLETED);assertThat(holds.findByTransactionId(tx.getId()).orElseThrow().getStatus().name()).isEqualTo("CONSUMED");verify(accountClient).project(anyString(),any());verify(accountClient).consume(eq("A1"),anyString(),anyString());verify(ledgerClient).post(eq("transaction-service"),any());verify(statementClient).push(eq("transaction-service"),any());
    }
    @Test void duplicateSettlementCallbackCreatesOneSettlementEffect(){
        Transaction tx=orchestrator.create(TransactionType.NEFT,PaymentRail.NEFT,external(new BigDecimal("500")),"neft-settle",maker);properties.getOutbox().setEnabled(true);publisher.publish();
        CallbackRequest cb=new CallbackRequest("rail-event-1","NEFT-EXT-1",LocalDate.now(),"SETTLED",null);callbacks.settle(tx.getId(),cb);publisher.publish();callbacks.settle(tx.getId(),cb);
        assertThat(transactions.findById(tx.getId()).orElseThrow().getStatus()).isEqualTo(TransactionStatus.COMPLETED);assertThat(journals.findByTransactionIdOrderByCreatedAt(tx.getId())).hasSize(2);
    }
    @Test void completedWithdrawalReversalIsLinkedAndCompensating(){
        Transaction original=orchestrator.create(TransactionType.WITHDRAWAL,PaymentRail.CASH,withdrawal(),"reverse-source",maker);properties.getOutbox().setEnabled(true);publisher.publish();
        clearInvocations(accountClient,ledgerClient,statementClient);
        Transaction reversal=orchestrator.reverse(original.getId(),"Erroneous withdrawal","reverse-action",maker);assertThat(reversal.getReversalOf().getId()).isEqualTo(original.getId());assertThat(journals.findByTransactionIdOrderByCreatedAt(reversal.getId())).isNotEmpty();publisher.publish();
        assertThat(transactions.findById(reversal.getId()).orElseThrow().getStatus()).isEqualTo(TransactionStatus.COMPLETED);assertThat(transactions.findById(original.getId()).orElseThrow().getStatus()).isEqualTo(TransactionStatus.REVERSED);
        verify(ledgerClient).post(eq("transaction-service"),argThat(j->"REVERSAL".equals(j.journalType())));
        verify(statementClient).push(eq("transaction-service"),argThat(e->
                e.transactionId().equals(reversal.getId())&&e.transactionType().equals("REVERSAL")
                        &&"CREDIT".equals(e.direction())));
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
        callbacks.cheque(tx.getId(),new CallbackRequest("cheque-event-1","CLR-CHQ-1",LocalDate.now(),"SETTLED",null));assertThat(journals.findByTransactionIdOrderByCreatedAt(tx.getId())).hasSize(1);assertThat(outbox.findByAggregateId(tx.getId())).hasSize(1);properties.getOutbox().setEnabled(true);publisher.publish();assertThat(transactions.findById(tx.getId()).orElseThrow().getStatus()).isEqualTo(TransactionStatus.COMPLETED);
    }
    @Test void internalTransferUsesStaticPaymentAndSettlementLedgerMappings(){
        CreateRequest transfer=new CreateRequest("A1","A2",null,new BigDecimal("300"),BigDecimal.ZERO,"INR",PaymentChannel.BRANCH,PaymentMethod.ACCOUNT,null,null,"Internal transfer", "TXN-TRF-001");
        Transaction tx=orchestrator.create(TransactionType.INTERNAL_TRANSFER,PaymentRail.INTERNAL,transfer,"transfer-ledger",maker);properties.getOutbox().setEnabled(true);publisher.publish();
        ArgumentCaptor<LedgerClient.JournalPostRequest> captor=ArgumentCaptor.forClass(LedgerClient.JournalPostRequest.class);verify(ledgerClient,times(2)).post(eq("transaction-service"),captor.capture());
        LedgerClient.JournalPostRequest payment=captor.getAllValues().stream().filter(x->"PAYMENT".equals(x.journalType())).findFirst().orElseThrow();
        assertThat(payment.journalReference()).isEqualTo("JRN-TXN-TRF-001-PAYMENT");
        assertThat(payment.lines()).extracting(LedgerClient.JournalLineRequest::ledgerCode,LedgerClient.JournalLineRequest::side,LedgerClient.JournalLineRequest::description)
                .containsExactly(tuple("210000","DEBIT","Customer Deposit Control"),tuple("220100","CREDIT","Internal Payment Clearing"));
        assertThat(transactions.findById(tx.getId()).orElseThrow().getStatus()).isEqualTo(TransactionStatus.COMPLETED);
    }
    @Test void fixedDepositPurchaseCompletesAfterLedgerStatementAndOwnership(){
        ProductPurchaseRequest request=new ProductPurchaseRequest("A1","FD-12M",new BigDecimal("5000"),"INR",PaymentChannel.WEB,"Twelve month investment","TXN-FD-001");
        var purchase=orchestrator.createProductPurchase(request,"fd-purchase-1",maker);
        assertThat(purchase.transactionStatus()).isEqualTo(TransactionStatus.PROJECTION_PENDING);
        assertThat(purchase.productVersionNumber()).isEqualTo(4);
        properties.getOutbox().setEnabled(true);publisher.publish();
        assertThat(transactions.findById(purchase.transactionId()).orElseThrow().getStatus()).isEqualTo(TransactionStatus.COMPLETED);
        ArgumentCaptor<LedgerClient.JournalPostRequest> journal=ArgumentCaptor.forClass(LedgerClient.JournalPostRequest.class);
        verify(ledgerClient,atLeastOnce()).post(eq("transaction-service"),journal.capture());
        LedgerClient.JournalPostRequest purchaseJournal=journal.getAllValues().stream()
                .filter(value->value.transactionId().equals(purchase.transactionId()))
                .findFirst().orElseThrow();
        assertThat(purchaseJournal.journalReference()).isEqualTo("JRN-TXN-FD-001-PRODUCT_PURCHASE");
        assertThat(purchaseJournal.journalType()).isEqualTo("PRODUCT_PURCHASE");
        assertThat(purchaseJournal.lines()).extracting(LedgerClient.JournalLineRequest::ledgerCode,LedgerClient.JournalLineRequest::side,LedgerClient.JournalLineRequest::description)
                .containsExactly(tuple("210000","DEBIT","Customer Deposit Control"),tuple("210100","CREDIT","Term Deposit Control"));
        verify(statementClient).push(eq("transaction-service"),argThat(e->e.transactionId().equals(purchase.transactionId())&&"PRODUCT_PURCHASE".equals(e.transactionType())&&"DEBIT".equals(e.direction())));
        verify(accountClient).projectOwnedProduct(eq("transaction-service"),argThat(p->"ACTIVATE".equals(p.action())&&"FD-12M".equals(p.productCode())&&p.productVersionNumber()==4));
        InOrder completionOrder=inOrder(accountClient,ledgerClient,statementClient);
        completionOrder.verify(accountClient).project(anyString(),argThat(p->p.transactionId().equals(purchase.transactionId())));
        completionOrder.verify(ledgerClient).post(eq("transaction-service"),argThat(j->j.transactionId().equals(purchase.transactionId())));
        completionOrder.verify(statementClient).push(eq("transaction-service"),argThat(e->e.transactionId().equals(purchase.transactionId())));
        completionOrder.verify(accountClient).projectOwnedProduct(eq("transaction-service"),argThat(p->p.purchaseTransactionId().equals(purchase.transactionId())));
    }
    @Test void completedFixedDepositPurchaseReversalRefundsAndReversesOwnership(){
        ProductPurchaseRequest request=new ProductPurchaseRequest("A1","FD-12M",new BigDecimal("5000"),"INR",PaymentChannel.WEB,null,"TXN-FD-REV-001");
        var purchase=orchestrator.createProductPurchase(request,"fd-purchase-reverse",maker);properties.getOutbox().setEnabled(true);publisher.publish();
        clearInvocations(accountClient,ledgerClient,statementClient);
        Transaction reversal=orchestrator.reverse(purchase.transactionId(),"Customer cancellation","fd-reversal",maker);publisher.publish();
        assertThat(transactions.findById(reversal.getId()).orElseThrow().getStatus()).isEqualTo(TransactionStatus.COMPLETED);
        verify(accountClient).project(anyString(),argThat(p->p.accountId().equals("A1")&&"CREDIT".equals(p.direction())&&p.amount().compareTo(new BigDecimal("5000"))==0));
        verify(accountClient).projectOwnedProduct(eq("transaction-service"),argThat(p->"REVERSE".equals(p.action())&&reversal.getId().equals(p.reversalTransactionId())));
        verify(statementClient).push(eq("transaction-service"),argThat(e->e.transactionId().equals(reversal.getId())&&"CREDIT".equals(e.direction())));
    }
    @Test void completedInternalTransferReversalRestoresBothAccountsLedgerAndStatements(){
        CreateRequest transfer=new CreateRequest("A1","A2",null,new BigDecimal("300"),BigDecimal.ZERO,"INR",PaymentChannel.BRANCH,PaymentMethod.ACCOUNT,null,null,"Internal transfer to reverse",null);
        Transaction original=orchestrator.create(TransactionType.INTERNAL_TRANSFER,PaymentRail.INTERNAL,transfer,"transfer-to-reverse",maker);properties.getOutbox().setEnabled(true);publisher.publish();
        clearInvocations(accountClient,ledgerClient,statementClient);
        Transaction reversal=orchestrator.reverse(original.getId(),"Transfer entered in error","reverse-transfer",maker);publisher.publish();
        assertThat(transactions.findById(reversal.getId()).orElseThrow().getStatus()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(transactions.findById(original.getId()).orElseThrow().getStatus()).isEqualTo(TransactionStatus.REVERSED);
        ArgumentCaptor<AccountClient.ProjectionInstruction> projections=ArgumentCaptor.forClass(AccountClient.ProjectionInstruction.class);
        verify(accountClient,times(2)).project(anyString(),projections.capture());
        assertThat(projections.getAllValues()).anySatisfy(p->{assertThat(p.accountId()).isEqualTo("A1");assertThat(p.direction()).isEqualTo("CREDIT");assertThat(p.amount()).isEqualByComparingTo("300");});
        assertThat(projections.getAllValues()).anySatisfy(p->{assertThat(p.accountId()).isEqualTo("A2");assertThat(p.direction()).isEqualTo("DEBIT");assertThat(p.amount()).isEqualByComparingTo("300");});
        verify(ledgerClient,times(2)).post(eq("transaction-service"),argThat(j->"REVERSAL".equals(j.journalType())));
        ArgumentCaptor<StatementClient.TransactionEvent> statements=ArgumentCaptor.forClass(StatementClient.TransactionEvent.class);
        verify(statementClient,times(2)).push(eq("transaction-service"),statements.capture());
        assertThat(statements.getAllValues()).allMatch(e->e.transactionId().equals(reversal.getId())&&e.transactionType().equals("REVERSAL"));
        assertThat(statements.getAllValues()).extracting(StatementClient.TransactionEvent::direction).containsExactlyInAnyOrder("CREDIT","DEBIT");
    }
    @Test void ledgerFailurePreventsCompletionAndStatementProjection(){
        doThrow(new IllegalStateException("ledger unavailable")).when(ledgerClient).post(eq("transaction-service"),any());
        Transaction tx=orchestrator.create(TransactionType.DEPOSIT,PaymentRail.CASH,deposit(),"ledger-failure",maker);properties.getOutbox().setEnabled(true);publisher.publish();
        assertThat(transactions.findById(tx.getId()).orElseThrow().getStatus()).isEqualTo(TransactionStatus.PROJECTION_PENDING);
        assertThat(outbox.findByAggregateId(tx.getId())).anyMatch(e->OutboxService.LEDGER_POST.equals(e.getEventType())&&e.getStatus().name().equals("PENDING"));
        assertThat(outbox.findByAggregateId(tx.getId())).noneMatch(e->OutboxService.STATEMENT_POST.equals(e.getEventType()));
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
                .andExpect(jsonPath("$.paths['/api/v1/transactions/product-purchases']").exists())
                .andExpect(jsonPath("$.components.schemas.CreateRequest.properties.customerId").doesNotExist())
                .andExpect(jsonPath("$.components.schemas.CreateRequest.properties.beneficiaryId").doesNotExist())
                .andExpect(jsonPath("$.components.schemas.TransactionView.properties.accountHolderId").exists())
                .andExpect(jsonPath("$.components.schemas.TransactionView.properties.makerEmployeeId").exists())
                .andExpect(jsonPath("$.components.securitySchemes.sessionId.name").value("X-Session-Id"))
                .andExpect(jsonPath("$.components.securitySchemes.employeeId").doesNotExist())
                .andExpect(jsonPath("$.components.securitySchemes.branchCode").doesNotExist())
                .andExpect(jsonPath("$.components.schemas.TransactionView.properties.beneficiaryId").doesNotExist());
    }
    private boolean attemptWithdrawal(String key){try{orchestrator.create(TransactionType.WITHDRAWAL,PaymentRail.CASH,withdrawal(),key,maker);return true;}catch(Exception expected){return false;}}
    private CreateRequest deposit(){return new CreateRequest(null,"A1",null,new BigDecimal("500"),BigDecimal.ZERO,"INR",PaymentChannel.BRANCH,PaymentMethod.CASH,null,null,"Cash deposit",null);}
    private CreateRequest withdrawal(){return new CreateRequest("A1",null,null,new BigDecimal("40"),new BigDecimal("1"),"INR",PaymentChannel.BRANCH,PaymentMethod.CASH,null,null,"Cash withdrawal",null);}
    private CreateRequest external(BigDecimal amount){return new CreateRequest("A1",null,null,amount,new BigDecimal("2"),"INR",PaymentChannel.WEB,amount.compareTo(new BigDecimal("200000"))>=0?PaymentMethod.RTGS:PaymentMethod.NEFT,null,null,"External transfer",null);}
}
