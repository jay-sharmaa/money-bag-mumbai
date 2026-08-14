package com.moneybags.transaction.service;

import com.moneybags.transaction.api.TransactionModels.CallbackRequest;
import com.moneybags.transaction.client.AccountClient;
import com.moneybags.transaction.domain.*;
import com.moneybags.transaction.domain.FinancialEnums.*;
import com.moneybags.transaction.entity.*;
import com.moneybags.transaction.exception.DomainException;
import com.moneybags.transaction.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

@Service @RequiredArgsConstructor
public class CallbackService {
    private final TransactionOrchestrator orchestrator;
    private final CallbackReceiptRepository receipts;
    private final ClearingInstructionRepository clearingRepository;
    private final FundsHoldRepository holds;
    private final OutboxEventRepository outboxEvents;
    private final AccountClient accounts;
    private final RequestHasher hasher;
    private final TransactionStateMachine states;
    private final JournalService journals;
    private final OutboxService outbox;

    @Transactional
    public Transaction settle(String transactionId,CallbackRequest request){
        Transaction tx=orchestrator.get(transactionId); if(replay("SETTLE",request,tx))return tx;
        ClearingInstruction instruction=clearing(tx); if(instruction.getStatus()==ClearingStatus.SETTLED) throw DomainException.conflict("SETTLEMENT_ALREADY_PROCESSED","Transaction was already settled");
        if(!Set.of(TransactionStatus.PROCESSING,TransactionStatus.PROJECTION_PENDING).contains(tx.getStatus())) throw DomainException.conflict("INVALID_STATE_TRANSITION","Transaction is not awaiting settlement");
        instruction.setExternalReference(request.externalReference()); instruction.setSettlementDate(request.settlementDate()==null?LocalDate.now():request.settlementDate()); instruction.setStatus(ClearingStatus.SETTLED);
        if(tx.getType()==TransactionType.CHEQUE){ journals.createChequeSettlementJournal(tx); outbox.accountProjection(tx,tx.getDestinationAccountId(),"CREDIT",tx.getAmount(),"CHEQUE_CREDIT_POSTED",null,"cheque-credit"); }
        else journals.createSettlementJournal(tx);
        states.transition(tx,TransactionStatus.SETTLED,"rail-adapter","CALLBACK","External settlement confirmed");
        if(tx.getType()==TransactionType.CHEQUE) states.transition(tx,TransactionStatus.PROJECTION_PENDING,"rail-adapter","CALLBACK","Cheque credit projection queued");
        else if(allPublished(tx.getId())) states.transition(tx,TransactionStatus.COMPLETED,"rail-adapter","CALLBACK","Settlement and account projection completed");
        record("SETTLE",request,tx); return tx;
    }
    @Transactional
    public Transaction fail(String transactionId,CallbackRequest request){
        Transaction tx=orchestrator.get(transactionId); if(replay("FAIL",request,tx))return tx;
        if(tx.getStatus().terminal()) throw DomainException.conflict("INVALID_STATE_TRANSITION","Terminal transaction cannot receive failure callback");
        clearingRepository.findByTransactionId(tx.getId()).ifPresent(c->{c.setStatus(ClearingStatus.FAILED);c.setFailureReason(request.reason());});
        List<OutboxEvent> events=outboxEvents.findByAggregateId(tx.getId()); boolean projected=events.stream().anyMatch(e->e.getStatus()==OutboxStatus.PUBLISHED&&!OutboxService.LEDGER_JOURNAL.equals(e.getEventType())&&!OutboxService.STATEMENT_PROJECTION.equals(e.getEventType()));
        events.stream().filter(e->e.getStatus()==OutboxStatus.PENDING).forEach(e->{e.setStatus(OutboxStatus.FAILED);e.setLastError("Cancelled after definitive rail failure");});
        FundsHold hold=holds.findByTransactionId(tx.getId()).orElse(null);
        if(hold!=null&&hold.getStatus()==HoldStatus.FUNDS_HELD){accounts.release(hold.getAccountId(),hold.getExternalHoldId(),"failure-release:"+tx.getId());hold.setStatus(HoldStatus.RELEASED);}
        if(projected&&tx.getSourceAccountId()!=null) outbox.accountProjection(tx,tx.getSourceAccountId(),"CREDIT",tx.totalDebit(),"PAYMENT_FAILED_COMPENSATION",null,"failure-compensation");
        states.transition(tx,TransactionStatus.FAILED,"rail-adapter","CALLBACK",request.reason()==null?"Definitive rail failure":request.reason()); record("FAIL",request,tx); return tx;
    }
    @Transactional
    public Transaction cheque(String transactionId,CallbackRequest request){
        if("SETTLED".equalsIgnoreCase(request.outcome()))return settle(transactionId,request);
        if("FAILED".equalsIgnoreCase(request.outcome())||"RETURNED".equalsIgnoreCase(request.outcome()))return fail(transactionId,request);
        throw DomainException.invalid("INVALID_CHEQUE_OUTCOME","Cheque outcome must be SETTLED, FAILED, or RETURNED");
    }
    private ClearingInstruction clearing(Transaction tx){return clearingRepository.findByTransactionId(tx.getId()).orElseThrow(()->DomainException.conflict("CLEARING_INSTRUCTION_MISSING","Clearing instruction is missing"));}
    private boolean replay(String type,CallbackRequest request,Transaction tx){
        var old=receipts.findByCallbackTypeAndProviderEventId(type,request.providerEventId()); if(old.isEmpty())return false;
        if(!old.get().getRequestHash().equals(hasher.hash(request)))throw DomainException.conflict("CALLBACK_IDEMPOTENCY_CONFLICT","Provider event ID was reused with different data");
        if(!old.get().getTransaction().getId().equals(tx.getId()))throw DomainException.conflict("CALLBACK_IDEMPOTENCY_CONFLICT","Provider event belongs to another transaction"); return true;
    }
    private void record(String type,CallbackRequest request,Transaction tx){receipts.save(CallbackReceipt.builder().transaction(tx).callbackType(type).providerEventId(request.providerEventId()).requestHash(hasher.hash(request)).build());}
    private boolean allPublished(String transactionId){List<OutboxEvent> events=outboxEvents.findByAggregateId(transactionId);List<OutboxEvent> accountEvents=events.stream().filter(e->!OutboxService.LEDGER_JOURNAL.equals(e.getEventType())&&!OutboxService.STATEMENT_PROJECTION.equals(e.getEventType())).toList();return !accountEvents.isEmpty()&&accountEvents.stream().allMatch(e->e.getStatus()==OutboxStatus.PUBLISHED);}
}
