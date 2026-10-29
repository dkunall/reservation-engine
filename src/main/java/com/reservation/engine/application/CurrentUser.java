package com.reservation.engine.application;

/** Authenticated caller as seen by the application layer (decoupled from Spring Security types). */
public record CurrentUser(String userId, boolean admin) {
    public static CurrentUser user(String id) {
        return new CurrentUser(id, false);
    }

    public static CurrentUser admin(String id) {
        return new CurrentUser(id, true);
    }
}
