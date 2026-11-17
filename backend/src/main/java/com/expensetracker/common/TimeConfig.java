package com.expensetracker.common;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

@Configuration
public class TimeConfig {

    /** All calendar boundaries (months, days) are computed in Indian Standard Time. */
    public static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    public static Instant startOfDay(LocalDate date) {
        return date.atStartOfDay(IST).toInstant();
    }

    public static LocalDate today(Clock clock) {
        return LocalDate.now(clock.withZone(IST));
    }
}
