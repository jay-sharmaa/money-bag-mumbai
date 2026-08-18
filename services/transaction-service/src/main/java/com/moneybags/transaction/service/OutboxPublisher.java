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
import java.math.BigDecimal;
import java.time.*;
import java.util.List;

@Service @RequiredArgsConstructor
public class OutboxPublisher {
    private final OutboxEventRepository events; private final TransactionRepository transactions; private final FundsHoldRepository holds;
    private final ClearingInstructionRepository clearing; private final AccountClient accounts; private final LedgerClient ledger; private final StatementClient statements;
    private final ProductPurchaseRepository productPurchases;
    private final FdSettlementRepository fdSettlements;
    private final ObjectMapper mapper; private final TransactionProperties properties; private final TransactionStateMachine states; private final OutboxService outbox;

    @Scheduled(fixedDelayString="${moneybags.transaction.outbox.fixed-delay-ms:5000}")
    @Transactional
    public void publish(){
        if(!properties.getOutbox().isEnabled())return;
        backfillReadyTransactions();
        for(int pass=0;pass<10;pass++){
            List<OutboxEvent> batch=events.findDeliverable(OutboxStatus.PENDING,Instant.now(),PageRequest.of(0,properties.getOutbox().getBatchSize()));
            if(batch.isEmpty())break;
            batch.forEach(this::deliver);
        }
    }

    private void backfillReadyTransactions(){
        var readyStatuses=List.of(TransactionStatus.PROJECTION_PENDING,TransactionStatus.SETTLED,TransactionStatus.COMPLETED);
        for(Transaction tx:transactions.findByStatusIn(readyStatuses,PageRequest.of(0,50)).getContent()){
            List<OutboxEvent> aggregate=events.findByAggregateId(tx.getId());
            if(aggregate.isEmpty()||aggregate.stream().anyMatch(e->e.getStatus()!=OutboxStatus.PUBLISHED))continue;
            boolean awaitingSettlement=tx.getType().externallyCleared()&&clearing.findByTransactionId(tx.getId()).map(c->c.getStatus()!=ClearingStatus.SETTLED).orElse(false);
            boolean hasLedger=aggregate.stream().anyMatch(e->OutboxService.LEDGER_POST.equals(e.getEventType()));
            if(!awaitingSettlement&&!hasLedger)outbox.ledgerPostings(tx);
            else if(!awaitingSettlement&&ownershipRequired(tx)
                    &&aggregate.stream().noneMatch(e->OutboxService.OWNERSHIP_POST.equals(e.getEventType())))
                enqueueOwnership(tx);
        }
    }
    private void deliver(OutboxEvent event){
        try{
            switch(event.getEventType()){
                case OutboxService.LEDGER_POST -> deliverLedger(event);
                case OutboxService.STATEMENT_POST -> deliverStatement(event);
                case OutboxService.OWNERSHIP_POST -> deliverOwnership(event);
                default -> deliverAccount(event);
            }
            event.setStatus(OutboxStatus.PUBLISHED);event.setPublishedAt(Instant.now());event.setAttempts(event.getAttempts()+1);event.setLastError(null);
            advance(event.getAggregateId());
        }catch(Exception ex){recordFailure(event,ex);}
    }

    private void deliverAccount(OutboxEvent event)throws Exception{
        AccountClient.ProjectionInstruction instruction=mapper.readValue(event.getPayload(),AccountClient.ProjectionInstruction.class);
        accounts.project(event.getDeduplicationKey(),instruction);
        if("DEBIT".equals(instruction.direction())&&instruction.holdId()!=null){
            FundsHold hold=holds.findByTransactionId(instruction.transactionId()).orElse(null);
            if(hold!=null&&hold.getStatus()==HoldStatus.FUNDS_HELD){accounts.consume(hold.getAccountId(),hold.getExternalHoldId(),"consume:"+instruction.transactionId());hold.setStatus(HoldStatus.CONSUMED);}
        }
    }

    private void deliverLedger(OutboxEvent event)throws Exception{
        LedgerClient.JournalPostRequest request=mapper.readValue(event.getPayload(),LedgerClient.JournalPostRequest.class);
        LedgerClient.JournalResponse posted=ledger.post("transaction-service",request);
        Transaction tx=transactions.findById(event.getAggregateId()).orElseThrow();
        for(LedgerClient.JournalLineResponse line:posted.lines()){
            if(line.customerAccountId()==null||line.customerAccountId().isBlank())continue;
            AccountClient.AccountContext account=accounts.context(line.customerAccountId());
            StatementAmounts amounts=statementAmounts(tx,line);
            outbox.statementProjection(tx,line,account,posted.postedAt(),amounts.amount(),amounts.fee());
        }
    }

    private void deliverStatement(OutboxEvent event)throws Exception{
        StatementClient.TransactionEvent payload=mapper.readValue(event.getPayload(),StatementClient.TransactionEvent.class);
        statements.push("transaction-service",payload);
    }

    private void deliverOwnership(OutboxEvent event)throws Exception{
        AccountClient.OwnedProductProjection payload=mapper.readValue(
                event.getPayload(),AccountClient.OwnedProductProjection.class);
        accounts.projectOwnedProduct("transaction-service",payload);
        ProductPurchase purchase=productPurchases.findById(payload.ownershipId()).orElseThrow();
        if("ACTIVATE".equals(payload.action()))purchase.setStatus(ProductPurchaseStatus.ACTIVE);
        else if("REVERSE".equals(payload.action())){
            purchase.setStatus(ProductPurchaseStatus.REVERSED);
            purchase.setReversalTransactionId(payload.reversalTransactionId());
        }
    }

    private StatementAmounts statementAmounts(Transaction tx,LedgerClient.JournalLineResponse line){
        if(tx.getType()==TransactionType.REVERSAL)return new StatementAmounts(line.amount(),BigDecimal.ZERO);
        if(tx.getType()==TransactionType.FD_MATURITY_PAYOUT
                ||tx.getType()==TransactionType.FD_PREMATURE_BREAK)
            return new StatementAmounts(line.amount(),BigDecimal.ZERO);
        if("DEBIT".equals(line.side())&&line.customerAccountId().equals(tx.getSourceAccountId()))
            return new StatementAmounts(tx.getAmount(),tx.getFeeAmount());
        return new StatementAmounts(line.amount(),BigDecimal.ZERO);
    }

    private void advance(String transactionId){
        List<OutboxEvent> aggregate=events.findByAggregateId(transactionId);if(aggregate.stream().anyMatch(e->e.getStatus()!=OutboxStatus.PUBLISHED))return;
        Transaction tx=transactions.findById(transactionId).orElse(null);if(tx==null)return;
        boolean awaitingSettlement=tx.getType().externallyCleared()&&clearing.findByTransactionId(tx.getId()).map(c->c.getStatus()!=ClearingStatus.SETTLED).orElse(false);
        if(awaitingSettlement&&tx.getStatus()==TransactionStatus.PROJECTION_PENDING)states.transition(tx,TransactionStatus.PROCESSING,"outbox-publisher","SYSTEM","Initial account projection applied; awaiting settlement");
        else if(awaitingSettlement)return;
        else if(aggregate.stream().noneMatch(e->OutboxService.LEDGER_POST.equals(e.getEventType()))){outbox.ledgerPostings(tx);}
        else if(ownershipRequired(tx)
                &&aggregate.stream().noneMatch(e->OutboxService.OWNERSHIP_POST.equals(e.getEventType()))){
            enqueueOwnership(tx);
        }
        else if(states.canTransition(tx.getStatus(),TransactionStatus.COMPLETED)){
            states.transition(tx,TransactionStatus.COMPLETED,"outbox-publisher","SYSTEM","Account, ledger, and statement projections completed");
            if(tx.getType()==TransactionType.FD_MATURITY_PAYOUT
                    ||tx.getType()==TransactionType.FD_PREMATURE_BREAK){
                fdSettlements.findByTransaction_Id(tx.getId()).ifPresent(settlement->{
                    if(settlement.getPurchaseTransactionId()!=null){
                        productPurchases.findByTransaction_Id(settlement.getPurchaseTransactionId())
                                .ifPresent(purchase->purchase.setSettledAt(Instant.now()));
                    }
                });
            }
            if(tx.getType()==TransactionType.REVERSAL&&tx.getReversalOf()!=null&&tx.getReversalOf().getStatus()==TransactionStatus.REVERSAL_PENDING)states.transition(tx.getReversalOf(),TransactionStatus.REVERSED,"outbox-publisher","SYSTEM","Compensating transaction completed");
        }
    }

    private void recordFailure(OutboxEvent event,Exception exception){
        int attempts=event.getAttempts()+1;event.setAttempts(attempts);
        String message=exception.getMessage()==null?exception.getClass().getSimpleName():exception.getMessage();event.setLastError(message.substring(0,Math.min(500,message.length())));
        if(attempts>=properties.getOutbox().getMaxAttempts())event.setStatus(OutboxStatus.FAILED);
        else{event.setStatus(OutboxStatus.PENDING);event.setNextAttemptAt(Instant.now().plusSeconds(Math.min(300,1L<<Math.min(8,attempts))));}
    }

    private boolean ownershipRequired(Transaction tx){
        if(tx.getType()==TransactionType.PRODUCT_PURCHASE)return true;
        return tx.getType()==TransactionType.REVERSAL&&tx.getReversalOf()!=null
                &&tx.getReversalOf().getType()==TransactionType.PRODUCT_PURCHASE;
    }

    private void enqueueOwnership(Transaction tx){
        if(tx.getType()==TransactionType.PRODUCT_PURCHASE){
            ProductPurchase purchase=productPurchases.findByTransaction_Id(tx.getId()).orElseThrow();
            outbox.productOwnership(tx,purchase,"ACTIVATE");
        }else{
            ProductPurchase purchase=productPurchases.findByTransaction_Id(
                    tx.getReversalOf().getId()).orElseThrow();
            outbox.productOwnership(tx,purchase,"REVERSE");
        }
    }

    private record StatementAmounts(BigDecimal amount,BigDecimal fee){}
}
