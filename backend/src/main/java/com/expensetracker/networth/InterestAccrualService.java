package com.expensetracker.networth;

import com.expensetracker.common.TimeConfig;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Adds monthly interest to PF/EPF, PPF and other balances that have a rate (see {@link InterestAccrual}). Runs every
 * night, at start-up and when net worth is opened, so values are current even after the server was asleep.
 */
@Service
public class InterestAccrualService {

    private final HoldingRepository holdings;
    private final TransactionTemplate tx;
    private final Clock clock;

    public InterestAccrualService(HoldingRepository holdings, PlatformTransactionManager txManager, Clock clock) {
        this.holdings = holdings;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
    }

    @Scheduled(cron = "0 20 0 * * *", zone = "Asia/Kolkata")
    @EventListener(ApplicationReadyEvent.class)
    public void accrueAll() {
        tx.executeWithoutResult(s -> holdings.findAll().forEach(this::accrue));
    }

    public void accrueFor(UUID userId) {
        tx.executeWithoutResult(s -> holdings.findByUserIdAndArchivedFalse(userId).forEach(this::accrue));
    }

    private void accrue(Holding h) {
        if (h.isArchived() || h.getValuationMethod() != ValuationMethod.MANUAL || h.getInterestRate() == null
                || h.getInterestAccruedTo() == null || h.getManualValue() == null) return;
        LocalDate today = TimeConfig.today(clock);
        var before = new InterestAccrual.State(h.getManualValue(), h.getAccruedInterest(), h.getInterestAccruedTo());
        var after = InterestAccrual.accrue(before, h.getInterestRate(), today);
        if (after == before || after.accruedTo().equals(before.accruedTo())) return;
        h.setManualValue(after.balance());
        h.setAccruedInterest(after.accrued());
        h.setInterestAccruedTo(after.accruedTo());
    }
}
