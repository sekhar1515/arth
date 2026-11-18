package com.expensetracker.networth;

import com.expensetracker.common.ApiException;
import com.expensetracker.user.User;
import com.expensetracker.user.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/** Applies {@link RefreshWindows} per person; the last press is stored so restarts don't reset it. */
@Service
public class ManualRefreshLimiter {

    private final UserRepository users;
    private final Clock clock;

    public ManualRefreshLimiter(UserRepository users, Clock clock) {
        this.users = users;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public RefreshWindows.Status status(UUID userId) {
        User u = users.findById(userId).orElseThrow(() -> ApiException.notFound("User"));
        return RefreshWindows.status(u.getLastPriceRefreshAt(), clock.instant());
    }

    /** Records this press, or refuses it (429) when the current window is closed or already used. */
    @Transactional
    public void claim(UUID userId) {
        User u = users.findById(userId).orElseThrow(() -> ApiException.notFound("User"));
        Instant now = clock.instant();
        RefreshWindows.Status s = RefreshWindows.status(u.getLastPriceRefreshAt(), now);
        if (!s.allowed()) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "REFRESH_NOT_NOW", s.reason());
        u.setLastPriceRefreshAt(now);
    }
}
