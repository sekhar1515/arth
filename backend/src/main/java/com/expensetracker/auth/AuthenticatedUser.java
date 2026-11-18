package com.expensetracker.auth;

import java.time.Instant;
import java.util.UUID;

/** Principal stored in the SecurityContext for authenticated requests. {@code issuedAt} is when the session token was created. */
public record AuthenticatedUser(UUID id, String email, Instant issuedAt) {

    public AuthenticatedUser(UUID id, String email) {
        this(id, email, null);
    }
}
