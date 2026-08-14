package com.moneybags.transaction.service;

import com.moneybags.transaction.config.TransactionProperties;
import com.moneybags.transaction.domain.*;
import com.moneybags.transaction.entity.*;
import com.moneybags.transaction.repository.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class JournalServiceTest {
    @Test void withdrawalAmountAndFeeProduceBalancedOneSidedLines(){
        TransactionLegRepository legs=mock(TransactionLegRepository.class);JournalEntryRepository entries=mock(JournalEntryRepository.class);
        OutboxService outbox=mock(OutboxService.class);JournalService service=new JournalService(legs,entries,new TransactionProperties(),outbox);
        Transaction tx=Transaction.builder().id("t").reference("T1").type(TransactionType.WITHDRAWAL).sourceAccountId("A1").amount(new BigDecimal("40.00")).feeAmount(new BigDecimal("1.00")).currency("INR").build();
        service.createInitialFinancialFacts(tx);ArgumentCaptor<JournalEntry> captor=ArgumentCaptor.forClass(JournalEntry.class);verify(entries).save(captor.capture());JournalEntry journal=captor.getValue();
        assertThat(journal.getTotalDebit()).isEqualByComparingTo("41.00");assertThat(journal.getTotalCredit()).isEqualByComparingTo("41.00");
        assertThat(journal.getLines()).allSatisfy(l->assertThat(l.getDebit().signum()>0 ^ l.getCredit().signum()>0).isTrue());
        verify(outbox).ledgerJournal(tx,journal);
    }
}
