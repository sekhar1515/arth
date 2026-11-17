package com.expensetracker.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

@Service
public class JwtService {

    private final SecretKey key;
    private final Duration ttl;

    public JwtService(@Value("${app.security.jwt-secret}") String secret,
                      @Value("${app.security.session-ttl:PT12H}") Duration ttl) {
        byte[] bytes = decode(secret);
        if (bytes.length < 32) {
            throw new IllegalStateException("app.security.jwt-secret must be at least 32 bytes (256 bits)");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.ttl = ttl;
    }

    private static byte[] decode(String secret) {
        try {
            return Base64.getDecoder().decode(secret);
        } catch (IllegalArgumentException notBase64) {
            return secret.getBytes(StandardCharsets.UTF_8);
        }
    }

    public Duration ttl() {
        return ttl;
    }

    public String issue(UUID userId, String email) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId.toString())
                .claim("email", email)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key)
                .compact();
    }

    public Optional<AuthenticatedUser> verify(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            return Optional.of(new AuthenticatedUser(UUID.fromString(claims.getSubject()), claims.get("email", String.class)));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
