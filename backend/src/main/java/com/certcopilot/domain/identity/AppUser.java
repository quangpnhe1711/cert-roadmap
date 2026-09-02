package com.certcopilot.domain.identity;

import java.time.Instant;
import java.util.UUID;

public record AppUser(
        UUID id,
        String email,
        String displayName,
        String locale,
        Instant createdAt) {
}
