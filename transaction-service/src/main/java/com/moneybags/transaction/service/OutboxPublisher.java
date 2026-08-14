package com.moneybags.transaction.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moneybags.transaction.client.AccountClient;
import com.moneybags.transaction.client.LedgerClient;
import com.moneybags.transaction.client.StatementClient;
import com.moneybags.transaction.config.TransactionProperties;
import com.moneybags.transaction.domain.*;
import com.moneybags.transaction.domain.FinancialEnums.*;
import com.moneybags.transaction.entity.*;
import com.moneybags.transaction.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.List;

@Service @RequiredArgsConstructor
public class OutboxPublisher {
    private final OutboxEventRepository events; private final TransactionRepository transactions; private final FundsHoldRepository holds;
    private final ClearingInstructionRepository clearing; private final JournalEntryRepository journals;
    private final AccountClient accounts; private final LedgerClient ledger; private final StatementClient statements;
    private final OutboxService outbox; private final ObjectMapper mapper; private final TransactionProperties properties; private final TransactionStateMachine states;

    @Scheduled(fixedDelayString="${moneybags.transaction.outbox.fixed-delay-ms:5000}")
    @Transactional
    public void publish(){
        if(!properties.getOutbox().isEnabled())return;
        for(int stage=0;stage<4;stage++){
            List<OutboxEvent> batch=events.findDeliverable(OutboxStatus.PENDING,Instant.now(),PageRequest.of(0,properties.getOutbox().getBatchSize()));
            if(batch.isEmpty())break;
            boolean progressed=false;for(OutboxEvent event:batch)progressed|=deliver(event);if(!progressed)break;
        }
    }
    private boolean deliver(OutboxEvent event){
        try{
            if(OutboxService.LEDGER_JOURNAL.equals(event.getEventType())){
                Transaction tx=transactions.findById(event.getAggregateId()).orElseThrow();
                if(tx.getCompletedAt()==null)return false;
                ledger.post(mapper.readValue(event.getPayload(),LedgerClient.JournalPostRequest.class));
            }else if(OutboxService.STATEMENT_PROJECTION.equals(event.getEventType())){
                if(hasPendingLedger(event.getAggregateId()))return false;
                statements.project("transaction-service",mapper.readValue(event.getPayload(),StatementClient.TransactionEvent.class));
            }else{
                AccountClient.ProjectionInstruction instruction=mapper.readValue(event.getPayload(),AccountClient.ProjectionInstruction.class);
                accounts.project(event.getDeduplicationKey(),instruction);
                if("DEBIT".equals(instruction.direction())&&instruction.holdId()!=null){
                    FundsHold hold=holds.findByTransactionId(instruction.transactionId()).orElse(null); if(hold!=null&&hold.getStatus()==HoldStatus.FUNDS_HELD){accounts.consume(hold.getAccountId(),hold.getExternalHoldId(),"consume:"+instruction.transactionId());hold.setStatus(HoldStatus.CONSUMED);}
                }
                Transaction tx=transactions.findById(instruction.transactionId()).orElseThrow();
                AccountClient.AccountContext context=accounts.context(instruction.accountId());
                outbox.statementProjection(tx,instruction,context,ledgerReference(tx.getId()));
            }
            event.setStatus(OutboxStatus.PUBLISHED);event.setPublishedAt(Instant.now());event.setAttempts(event.getAttempts()+1); completeIfReady(event.getAggregateId());return true;
        }catch(Exception ex){event.setAttempts(event.getAttempts()+1);event.setLastError(ex.getMessage()==null?ex.getClass().getSimpleName():ex.getMessage().substring(0,Math.min(500,ex.getMessage().length())));if(event.getAttempts()>=properties.getOutbox().getMaxAttempts())event.setStatus(OutboxStatus.FAILED);else event.setNextAttemptAt(Instant.now().plusSeconds(Math.min(300,1L<<Math.min(8,event.getAttempts()))));}
        return true;
    }
    private boolean hasPendingLedger(String transactionId){return events.findByAggregateId(transactionId).stream().anyMatch(e->OutboxService.LEDGER_JOURNAL.equals(e.getEventType())&&e.getStatus()!=OutboxStatus.PUBLISHED);}
    private String ledgerReference(String transactionId){return journals.findByTransactionIdOrderByCreatedAt(transactionId).stream().map(JournalEntry::getReference).findFirst().orElse(null);}
    private void completeIfReady(String transactionId){
        List<OutboxEvent> aggregate=events.findByAggregateId(transactionId);
        if(aggregate.stream().filter(this::isAccountProjection).anyMatch(e->e.getStatus()!=OutboxStatus.PUBLISHED))return;
        Transaction tx=transactions.findById(transactionId).orElse(null); if(tx==null)return;
        boolean awaitingSettlement=tx.getType().externallyCleared()&&clearing.findByTransactionId(tx.getId()).map(c->c.getStatus()!=ClearingStatus.SETTLED).orElse(false);
        if(awaitingSettlement&&tx.getStatus()==TransactionStatus.PROJECTION_PENDING)states.transition(tx,TransactionStatus.PROCESSING,"outbox-publisher","SYSTEM","Initial account projection applied; awaiting settlement");
        else if(states.canTransition(tx.getStatus(),TransactionStatus.COMPLETED)){
            states.transition(tx,TransactionStatus.COMPLETED,"outbox-publisher","SYSTEM","Customer account projection applied; post-completion ledger publication released");
            if(tx.getType()==TransactionType.REVERSAL&&tx.getReversalOf()!=null&&tx.getReversalOf().getStatus()==TransactionStatus.REVERSAL_PENDING)states.transition(tx.getReversalOf(),TransactionStatus.REVERSED,"outbox-publisher","SYSTEM","Compensating transaction completed");
        }
    }
    private boolean isAccountProjection(OutboxEvent event){return !OutboxService.LEDGER_JOURNAL.equals(event.getEventType())&&!OutboxService.STATEMENT_PROJECTION.equals(event.getEventType());}
}
