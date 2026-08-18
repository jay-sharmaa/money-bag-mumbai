package com.moneybags.account.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "moneybags.account")
public class AccountProperties {

    private String defaultCurrency = "INR";
    /** Days of inactivity before the dormancy sweep marks an account dormant. */
    private int dormancyDays = 365;
    private Outbox outbox = new Outbox();
    private Reconciliation reconciliation = new Reconciliation();
    private Interest interest = new Interest();

    @Getter
    @Setter
    public static class Outbox {
        private boolean enabled = true;
        private int batchSize = 50;
        private int maxAttempts = 10;
        private long fixedDelayMs = 5000;
    }

    @Getter
    @Setter
    public static class Reconciliation {
        private boolean enabled = true;
        private long fixedDelayMs = 60000;
    }

    @Getter
    @Setter
    public static class Interest {
        private boolean enabled = true;
        private long checkDelayMs = 60000;
        private long initialDelayMs = 10000;
        private LocalDate firstPeriodEnd = LocalDate.of(2026, 8, 16);
        private int runHourUtc = 0;
        private int runMinuteUtc = 10;
        private int maxCatchUpRuns = 52;
        private int staleAfterMinutes = 30;
        private int retryDelayMinutes = 5;
    }
}
