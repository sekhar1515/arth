package com.expensetracker.auth;

import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.UUID;

/** Access to the authenticated user's id. Every service call is scoped by this id (user-level isolation). */
public final class CurrentUser {
    private CurrentUser() {
    }

    public static UUID id() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthenticatedUser user) {
            return user.id();
        }
        throw new AuthenticationCredentialsNotFoundException("Not authenticated");
    }
}
