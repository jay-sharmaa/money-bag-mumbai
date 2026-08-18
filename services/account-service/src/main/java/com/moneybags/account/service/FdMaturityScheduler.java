package com.moneybags.account.service;

import com.moneybags.account.config.AccountProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneOffset;

@Component
@RequiredArgsConstructor
@Slf4j
public class FdMaturityScheduler {
    private final FdSettlementService settlements;
    private final AccountProperties properties;

    @Scheduled(fixedDelayString = "${moneybags.account.fd-settlement.check-delay-ms:60000}",
            initialDelayString = "${moneybags.account.fd-settlement.initial-delay-ms:15000}")
    public void settleDueDeposits() {
        if (!properties.getFdSettlement().isEnabled()) return;
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        for (var ownership : settlements.due(today)) {
            try {
                settlements.requestMaturity(ownership.getOwnershipId(), today,
                        "fd-maturity:" + ownership.getOwnershipId() + ":" + ownership.getMaturityDate());
            } catch (RuntimeException failure) {
                log.error("FD maturity settlement failed for ownership {}: {}",
                        ownership.getOwnershipId(), failure.getMessage());
            }
        }
    }
}
