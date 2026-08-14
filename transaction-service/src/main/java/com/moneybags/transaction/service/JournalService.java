package com.moneybags.transaction.service;

import com.moneybags.transaction.config.TransactionProperties;
import com.moneybags.transaction.domain.*;
import com.moneybags.transaction.domain.FinancialEnums.*;
import com.moneybags.transaction.entity.*;
import com.moneybags.transaction.exception.DomainException;
import com.moneybags.transaction.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

@Service @RequiredArgsConstructor
public class JournalService {
    private final TransactionLegRepository legs;
    private final JournalEntryRepository journals;
    private final TransactionProperties properties;
    private final OutboxService outbox;

    public void createInitialFinancialFacts(Transaction tx){
        createLegs(tx);
        switch(tx.getType()){
            case DEPOSIT -> save(tx,"POSTING",List.of(dr(properties.getLedger().getCashAsset(),null,tx.getAmount(),"Cash/settlement asset received"),cr(properties.getLedger().getAccountDepositControl(),tx.getDestinationAccountId(),tx.getAmount(),"Account deposit credited")));
            case WITHDRAWAL -> save(tx,"POSTING",debitWithFee(tx,properties.getLedger().getCashAsset(),"Cash disbursed"));
            case INTERNAL_TRANSFER -> save(tx,"PAYMENT",debitWithFee(tx,properties.getLedger().getInternalClearing(),"Internal payment clearing credited"));
            case NEFT,RTGS,IMPS,UPI,CARD_PAYMENT -> save(tx,"PAYMENT",debitWithFee(tx,properties.getLedger().getExternalClearing(),"External payment clearing credited"));
            case CHEQUE -> { }
            case REVERSAL -> throw new IllegalArgumentException("Reversal journals are created from the original transaction");
        }
    }
    public void createSettlementJournal(Transaction tx){
        if(tx.getType()==TransactionType.INTERNAL_TRANSFER){
            save(tx,"SETTLEMENT",List.of(dr(properties.getLedger().getInternalClearing(),null,tx.getAmount(),"Internal clearing settled"),cr(properties.getLedger().getAccountDepositControl(),tx.getDestinationAccountId(),tx.getAmount(),"Destination account credited")));
        } else if(tx.getType().externallyCleared()) {
            save(tx,"SETTLEMENT",List.of(dr(properties.getLedger().getExternalClearing(),null,tx.getAmount(),"External clearing settled"),cr(properties.getLedger().getCashAsset(),null,tx.getAmount(),"Settlement asset released")));
        }
    }
    public void createChequeSettlementJournal(Transaction tx){
        save(tx,"CHEQUE_SETTLEMENT",List.of(dr(properties.getLedger().getCashAsset(),null,tx.getAmount(),"Cheque settlement asset received"),cr(properties.getLedger().getAccountDepositControl(),tx.getDestinationAccountId(),tx.getAmount(),"Account credited after cheque clearing")));
    }
    public void createReversalLegs(Transaction reversal,Transaction original){
        List<TransactionLeg> source=legs.findByTransactionIdOrderBySequenceNo(original.getId());
        List<TransactionLeg> opposite=new ArrayList<>();
        for(TransactionLeg old:source) opposite.add(leg(reversal,old.getSequenceNo(),old.getRole(),old.getDirection()==Direction.DEBIT?Direction.CREDIT:Direction.DEBIT,old.getAccountId(),old.getAmount(),"Reversal: "+old.getDescription()));
        legs.saveAll(opposite);
    }
    public void createReversalJournal(Transaction reversal,Transaction original){
        List<JournalEntry> source=journals.findByTransactionIdOrderByCreatedAt(original.getId()); int journalIndex=0;
        for(JournalEntry old:source){
            List<Line> opposite=old.getLines().stream().map(l->new Line(l.getLedgerAccountCode(),l.getAccountId(),l.getCredit(),l.getDebit(),"Reversal: "+l.getDescription())).toList();
            save(reversal,"REVERSAL_"+(++journalIndex),opposite);
        }
    }
    private void createLegs(Transaction tx){
        List<TransactionLeg> result=new ArrayList<>(); int n=1;
        if(tx.getType()==TransactionType.DEPOSIT||tx.getType()==TransactionType.CHEQUE) result.add(leg(tx,n++,LegRole.DESTINATION,Direction.CREDIT,tx.getDestinationAccountId(),tx.getAmount(),"Destination credit"));
        else {
            result.add(leg(tx,n++,LegRole.SOURCE,Direction.DEBIT,tx.getSourceAccountId(),tx.getAmount(),"Source debit"));
            if(tx.getFeeAmount().signum()>0) result.add(leg(tx,n++,LegRole.FEE,Direction.DEBIT,tx.getSourceAccountId(),tx.getFeeAmount(),"Transaction fee"));
            if(tx.getType()==TransactionType.INTERNAL_TRANSFER) result.add(leg(tx,n,LegRole.DESTINATION,Direction.CREDIT,tx.getDestinationAccountId(),tx.getAmount(),"Destination credit"));
        }
        legs.saveAll(result);
    }
    private List<Line> debitWithFee(Transaction tx,String creditAccount,String description){
        List<Line> lines=new ArrayList<>(); lines.add(dr(properties.getLedger().getAccountDepositControl(),tx.getSourceAccountId(),tx.totalDebit(),"Account debited"));
        lines.add(cr(creditAccount,null,tx.getAmount(),description)); if(tx.getFeeAmount().signum()>0) lines.add(cr(properties.getLedger().getFeeIncome(),null,tx.getFeeAmount(),"Fee income recognized")); return lines;
    }
    private void save(Transaction tx,String type,List<Line> lines){
        BigDecimal debits=lines.stream().map(Line::debit).reduce(BigDecimal.ZERO,BigDecimal::add); BigDecimal credits=lines.stream().map(Line::credit).reduce(BigDecimal.ZERO,BigDecimal::add);
        if(debits.signum()<=0||debits.compareTo(credits)!=0) throw DomainException.conflict("UNBALANCED_JOURNAL","Journal debits and credits must be equal and positive");
        JournalEntry entry=JournalEntry.builder().transaction(tx).reference("JRN-"+tx.getReference()+"-"+type).type(type).status(JournalStatus.POSTED).totalDebit(debits).totalCredit(credits).postedAt(Instant.now()).build();
        int n=1; for(Line line:lines) entry.addLine(JournalLine.builder().lineNo(n++).ledgerAccountCode(line.code()).accountId(line.accountId()).debit(line.debit()).credit(line.credit()).description(line.description()).build());
        journals.save(entry);
        outbox.ledgerJournal(tx,entry);
    }
    private TransactionLeg leg(Transaction tx,int n,LegRole role,Direction direction,String account,BigDecimal amount,String description){return TransactionLeg.builder().transaction(tx).sequenceNo(n).role(role).direction(direction).accountId(account).amount(amount).currency(tx.getCurrency()).description(description).build();}
    private Line dr(String code,String account,BigDecimal amount,String description){return new Line(code,account,amount,BigDecimal.ZERO,description);}
    private Line cr(String code,String account,BigDecimal amount,String description){return new Line(code,account,BigDecimal.ZERO,amount,description);}
    private record Line(String code,String accountId,BigDecimal debit,BigDecimal credit,String description){}
}
