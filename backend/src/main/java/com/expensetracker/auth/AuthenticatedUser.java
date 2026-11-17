package com.expensetracker.auth;

import java.util.UUID;

/** Principal stored in the SecurityContext for authenticated requests. */
public record AuthenticatedUser(UUID id, String email) {
}
