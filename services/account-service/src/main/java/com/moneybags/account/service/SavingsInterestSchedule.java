package com.moneybags.account.service;

import com.moneybags.account.config.AccountProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;

@Component
@RequiredArgsConstructor
public class SavingsInterestSchedule {
    private final AccountProperties properties;

    public Instant scheduledAt(LocalDate periodEndDate) {
        AccountProperties.Interest interest = properties.getInterest();
        LocalTime time = LocalTime.of(interest.getRunHourUtc(), interest.getRunMinuteUtc());
        return periodEndDate.plusDays(1).atTime(time).toInstant(ZoneOffset.UTC);
    }

    public LocalDate latestDuePeriodEnd(Instant now) {
        ZonedDateTime utc = now.atZone(ZoneOffset.UTC);
        LocalDate candidate = utc.toLocalDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY));
        if (scheduledAt(candidate).isAfter(now)) {
            candidate = candidate.minusWeeks(1);
        }
        return candidate;
    }
}
