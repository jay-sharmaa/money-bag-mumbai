package com.moneybags.transaction.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moneybags.transaction.client.AccountClient.ProjectionInstruction;
import com.moneybags.transaction.client.LedgerClient;
import com.moneybags.transaction.client.StatementClient;
import com.moneybags.transaction.domain.TransactionType;
import com.moneybags.transaction.domain.FinancialEnums.OutboxStatus;
import com.moneybags.transaction.entity.*;
import com.moneybags.transaction.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.UUID;

@Service @RequiredArgsConstructor
public class OutboxService {
    public static final String LEDGER_JOURNAL = "LEDGER_JOURNAL";
    public static final String STATEMENT_PROJECTION = "STATEMENT_PROJECTION";

    private final OutboxEventRepository repository;
    private final ObjectMapper mapper;

    public void ledgerJournal(Transaction tx, JournalEntry journal) {
        var lines = journal.getLines().stream().map(line -> new LedgerClient.JournalLineRequest(
                line.getLedgerAccountCode(), line.getAccountId(),
                line.getDebit().signum() > 0 ? "DEBIT" : "CREDIT",
                line.getDebit().signum() > 0 ? line.getDebit() : line.getCredit(), line.getDescription())).toList();
        save(tx, LEDGER_JOURNAL, "ledger:" + journal.getReference(),
                new LedgerClient.JournalPostRequest(journal.getReference(), tx.getId(), journal.getType(),
                        tx.getNarration(), tx.getCurrency(), "transaction-service", lines));
    }

    public void accountProjection(Transaction tx,String accountId,String direction,java.math.BigDecimal amount,String eventType,String holdId,String suffix){
        String id=UUID.randomUUID().toString();
        save(tx,eventType,tx.getId()+":"+suffix,new ProjectionInstruction(id,tx.getId(),tx.getReference(),accountId,direction,amount,tx.getCurrency(),holdId,eventType,tx.getCorrelationId()),id);
    }

    public void statementProjection(Transaction tx, ProjectionInstruction projection,
                                    com.moneybags.transaction.client.AccountClient.AccountContext account,
                                    String ledgerEntryId) {
        String id = UUID.randomUUID().toString();
        boolean sourceDebit = "DEBIT".equals(projection.direction())
                && projection.accountId().equals(tx.getSourceAccountId())
                && tx.getType() != TransactionType.REVERSAL;
        java.math.BigDecimal amount = sourceDebit ? tx.getAmount() : projection.amount();
        java.math.BigDecimal fee = sourceDebit ? tx.getFeeAmount() : java.math.BigDecimal.ZERO;
        var event = new StatementClient.TransactionEvent(id, tx.getId(), ledgerEntryId, tx.getReference(),
                projection.accountId(), account.accountHolderId(), tx.getBranchCode(), projection.direction(),
                amount, fee, tx.getCurrency(), tx.getType().name(), "POSTED", tx.getNarration(),
                tx.getReversalOf() == null ? null : tx.getReversalOf().getId(), java.time.Instant.now(),
                account.ledgerBalance(), java.time.Instant.now());
        save(tx, STATEMENT_PROJECTION, "statement:" + projection.eventId(), event, id);
    }

    private void save(Transaction tx,String eventType,String key,Object payload){save(tx,eventType,key,payload,UUID.randomUUID().toString());}
    private void save(Transaction tx,String eventType,String key,Object payload,String id){
        try {
            OutboxEvent event=OutboxEvent.builder().id(id).aggregateType("TRANSACTION").aggregateId(tx.getId()).eventType(eventType)
                    .deduplicationKey(key).status(OutboxStatus.PENDING).build();
            event.setPayload(mapper.writeValueAsString(payload)); repository.save(event);
        } catch(Exception e){throw new IllegalStateException("Could not serialize outbox event",e);}
    }
}
