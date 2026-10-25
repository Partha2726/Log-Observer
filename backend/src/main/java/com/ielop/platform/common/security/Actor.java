package com.ielop.platform.common.security;

import org.springframework.security.core.Authentication;

/**
 * The signed-in user as features record them. The JWT subject (and so {@code Authentication.getName()})
 * is the stable user ID; reviews and audit rows also show the username, which the JWT filter puts in
 * the authentication details.
 */
public record Actor(String userId, String username, String remoteAddress) {

    /** The username of {@code auth}, or its name (the user ID) when no username is known. */
    public static String username(Authentication auth) {
        return auth.getDetails() instanceof Actor a && a.username() != null ? a.username() : auth.getName();
    }

    /** The client address of the request that authenticated, if known. */
    public static String remoteAddress(Authentication auth) {
        return auth.getDetails() instanceof Actor a ? a.remoteAddress() : null;
    }
}
