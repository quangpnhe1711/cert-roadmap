package com.certcopilot.api.security;

import java.util.UUID;

import com.certcopilot.domain.identity.AuthException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** Reads the authenticated user id out of the security context. */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static UUID id() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof UUID userId)) {
            throw new AuthException("UNAUTHENTICATED", "no authenticated user");
        }
        return userId;
    }
}
