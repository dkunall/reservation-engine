package com.reservation.engine.support;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;

/** Mints real HS256 tokens so tests exercise the actual JwtDecoder path. */
public final class TestJwt {
    public static final String SECRET = "test-only-hmac-secret-0123456789-abcdefghijklmnop";

    private TestJwt() {
    }

    public static String token(String subject, String... roles) {
        return token(SECRET, subject, Instant.now().plusSeconds(3600), roles);
    }

    public static String token(String secret, String subject, Instant expiresAt, String... roles) {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .subject(subject)
                    .claim("roles", List.of(roles))
                    .issueTime(new Date())
                    .expirationTime(Date.from(expiresAt))
                    .build();
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String bearer(String subject, String... roles) {
        return "Bearer " + token(subject, roles);
    }
}
