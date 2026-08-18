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
    private static final String CASH_ASSET = "Cash and Settlement Asset";
    private static final String CUSTOMER_DEPOSIT_CONTROL = "Customer Deposit Control";
    private static final String TERM_DEPOSIT_CONTROL = "Term Deposit Control";
    private static final String INTERNAL_CLEARING = "Internal Payment Clearing";
    private static final String EXTERNAL_CLEARING = "External Clearing";
    private static final String FEE_INCOME = "Payment Fee Income";
    private static final String SAVINGS_INTEREST_EXPENSE = "Savings Interest Expense";

    private final TransactionLegRepository legs;
    private final JournalEntryRepository journals;
    private final TransactionProperties properties;

    public void createInitialFinancialFacts(Transaction tx){
        createLegs(tx);
        switch(tx.getType()){
            case DEPOSIT -> save(tx,"POSTING",List.of(dr(properties.getLedger().getCashAsset(),null,tx.getAmount(),CASH_ASSET),cr(properties.getLedger().getAccountDepositControl(),tx.getDestinationAccountId(),tx.getAmount(),CUSTOMER_DEPOSIT_CONTROL)));
            case WITHDRAWAL -> save(tx,"POSTING",debitWithFee(tx,properties.getLedger().getCashAsset(),CASH_ASSET));
            case INTERNAL_TRANSFER -> save(tx,"PAYMENT",debitWithFee(tx,properties.getLedger().getInternalClearing(),INTERNAL_CLEARING));
            case NEFT,RTGS,IMPS,UPI,CARD_PAYMENT -> save(tx,"PAYMENT",debitWithFee(tx,properties.getLedger().getExternalClearing(),EXTERNAL_CLEARING));
            case PRODUCT_PURCHASE -> save(tx,"PRODUCT_PURCHASE",List.of(
                    dr(properties.getLedger().getAccountDepositControl(),tx.getSourceAccountId(),tx.getAmount(),CUSTOMER_DEPOSIT_CONTROL),
                    cr(properties.getLedger().getTermDepositControl(),null,tx.getAmount(),TERM_DEPOSIT_CONTROL)));
            case INTEREST_PAYOUT -> save(tx,"INTEREST_PAYOUT",List.of(
                    dr(properties.getLedger().getSavingsInterestExpense(),null,tx.getAmount(),SAVINGS_INTEREST_EXPENSE),
                    cr(properties.getLedger().getAccountDepositControl(),tx.getDestinationAccountId(),tx.getAmount(),CUSTOMER_DEPOSIT_CONTROL)));
            case FD_MATURITY_PAYOUT, FD_PREMATURE_BREAK ->
                    throw new IllegalArgumentException("FD settlement facts require settlement details");
            case CHEQUE -> { }
            case REVERSAL -> throw new IllegalArgumentException("Reversal journals are created from the original transaction");
        }
    }

    public void createFdSettlementFacts(Transaction tx, FdSettlement settlement) {
        BigDecimal total = settlement.getPrincipalAmount().add(settlement.getInterestAmount());
        int destinationSequence = 1;
        if (settlement.getSourceFdAccountId() != null) {
            legs.save(leg(tx, 1, LegRole.SOURCE, Direction.DEBIT,
                    settlement.getSourceFdAccountId(), settlement.getPrincipalAmount(),
                    "FD principal released from term account"));
            destinationSequence = 2;
        }
        legs.save(leg(tx, destinationSequence, LegRole.DESTINATION, Direction.CREDIT,
                tx.getDestinationAccountId(), total,
                settlement.getSettlementType() == FdSettlementType.MATURITY
                        ? "FD maturity principal and interest" : "FD premature-break principal"));
        List<Line> lines = new ArrayList<>();
        if (settlement.getSourceFdAccountId() == null) {
            lines.add(dr(properties.getLedger().getTermDepositControl(), null,
                    settlement.getPrincipalAmount(), TERM_DEPOSIT_CONTROL));
        } else {
            lines.add(dr(properties.getLedger().getAccountDepositControl(),
                    settlement.getSourceFdAccountId(), settlement.getPrincipalAmount(),
                    CUSTOMER_DEPOSIT_CONTROL));
        }
        if (settlement.getInterestAmount().signum() > 0) {
            lines.add(dr(properties.getLedger().getTermDepositInterestExpense(), null,
                    settlement.getInterestAmount(), "Term Deposit Interest Expense"));
        }
        lines.add(cr(properties.getLedger().getAccountDepositControl(),
                tx.getDestinationAccountId(), total, CUSTOMER_DEPOSIT_CONTROL));
        save(tx, tx.getType().name(), lines);
    }
    public void createSettlementJournal(Transaction tx){
        if(tx.getType()==TransactionType.INTERNAL_TRANSFER){
            save(tx,"SETTLEMENT",List.of(dr(properties.getLedger().getInternalClearing(),null,tx.getAmount(),INTERNAL_CLEARING),cr(properties.getLedger().getAccountDepositControl(),tx.getDestinationAccountId(),tx.getAmount(),CUSTOMER_DEPOSIT_CONTROL)));
        } else if(tx.getType().externallyCleared()) {
            save(tx,"SETTLEMENT",List.of(dr(properties.getLedger().getExternalClearing(),null,tx.getAmount(),EXTERNAL_CLEARING),cr(properties.getLedger().getCashAsset(),null,tx.getAmount(),CASH_ASSET)));
        }
    }
    public void createChequeSettlementJournal(Transaction tx){
        save(tx,"CHEQUE_SETTLEMENT",List.of(dr(properties.getLedger().getCashAsset(),null,tx.getAmount(),CASH_ASSET),cr(properties.getLedger().getAccountDepositControl(),tx.getDestinationAccountId(),tx.getAmount(),CUSTOMER_DEPOSIT_CONTROL)));
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
        if(tx.getType()==TransactionType.DEPOSIT||tx.getType()==TransactionType.CHEQUE||tx.getType()==TransactionType.INTEREST_PAYOUT) result.add(leg(tx,n++,LegRole.DESTINATION,Direction.CREDIT,tx.getDestinationAccountId(),tx.getAmount(),tx.getType()==TransactionType.INTEREST_PAYOUT?"Savings interest credit":"Destination credit"));
        else {
            result.add(leg(tx,n++,LegRole.SOURCE,Direction.DEBIT,tx.getSourceAccountId(),tx.getAmount(),"Source debit"));
            if(tx.getFeeAmount().signum()>0) result.add(leg(tx,n++,LegRole.FEE,Direction.DEBIT,tx.getSourceAccountId(),tx.getFeeAmount(),"Transaction fee"));
            if(tx.getType()==TransactionType.INTERNAL_TRANSFER) result.add(leg(tx,n,LegRole.DESTINATION,Direction.CREDIT,tx.getDestinationAccountId(),tx.getAmount(),"Destination credit"));
        }
        legs.saveAll(result);
    }
    private List<Line> debitWithFee(Transaction tx,String creditAccount,String description){
        List<Line> lines=new ArrayList<>(); lines.add(dr(properties.getLedger().getAccountDepositControl(),tx.getSourceAccountId(),tx.totalDebit(),CUSTOMER_DEPOSIT_CONTROL));
        lines.add(cr(creditAccount,null,tx.getAmount(),description)); if(tx.getFeeAmount().signum()>0) lines.add(cr(properties.getLedger().getFeeIncome(),null,tx.getFeeAmount(),FEE_INCOME)); return lines;
    }
    private void save(Transaction tx,String type,List<Line> lines){
        BigDecimal debits=lines.stream().map(Line::debit).reduce(BigDecimal.ZERO,BigDecimal::add); BigDecimal credits=lines.stream().map(Line::credit).reduce(BigDecimal.ZERO,BigDecimal::add);
        if(debits.signum()<=0||debits.compareTo(credits)!=0) throw DomainException.conflict("UNBALANCED_JOURNAL","Journal debits and credits must be equal and positive");
        JournalEntry entry=JournalEntry.builder().transaction(tx).reference("JRN-"+tx.getReference()+"-"+type).type(type).status(JournalStatus.POSTED).totalDebit(debits).totalCredit(credits).postedAt(Instant.now()).build();
        int n=1; for(Line line:lines) entry.addLine(JournalLine.builder().lineNo(n++).ledgerAccountCode(line.code()).accountId(line.accountId()).debit(line.debit()).credit(line.credit()).description(line.description()).build());
        journals.save(entry);
    }
    private TransactionLeg leg(Transaction tx,int n,LegRole role,Direction direction,String account,BigDecimal amount,String description){return TransactionLeg.builder().transaction(tx).sequenceNo(n).role(role).direction(direction).accountId(account).amount(amount).currency(tx.getCurrency()).description(description).build();}
    private Line dr(String code,String account,BigDecimal amount,String description){return new Line(code,account,amount,BigDecimal.ZERO,description);}
    private Line cr(String code,String account,BigDecimal amount,String description){return new Line(code,account,BigDecimal.ZERO,amount,description);}
    private record Line(String code,String accountId,BigDecimal debit,BigDecimal credit,String description){}
}
