package com.reservation.engine.api;

import com.reservation.engine.application.CurrentUser;
import org.springframework.security.core.Authentication;

final class Principals {
    private Principals() {
    }

    static CurrentUser of(Authentication auth) {
        boolean admin = auth.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        return new CurrentUser(auth.getName(), admin);
    }
}
