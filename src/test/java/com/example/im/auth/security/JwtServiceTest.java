package com.example.im.auth.security;

import com.example.im.common.exception.AuthException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    @Test
    void generatedTokenShouldBeVerified() {
        JwtService jwtService = jwtService(Instant.parse("2026-08-17T00:00:00Z"), 3600);

        JwtService.TokenPair tokenPair = jwtService.generate(1001L);
        JwtClaims claims = jwtService.verify(tokenPair.token());

        assertThat(claims.userId()).isEqualTo(1001L);
        assertThat(claims.expiresAt()).isEqualTo(tokenPair.expiresAt());
    }

    @Test
    void expiredTokenShouldBeRejected() {
        JwtService issuer = jwtService(Instant.parse("2026-08-17T00:00:00Z"), 1);
        String token = issuer.generate(1001L).token();
        JwtService verifier = jwtService(Instant.parse("2026-08-17T00:00:02Z"), 1);

        assertThatThrownBy(() -> verifier.verify(token))
                .isInstanceOf(AuthException.class)
                .hasMessage("token is expired");
    }

    private JwtService jwtService(Instant now, long ttlSeconds) {
        JwtProperties properties = new JwtProperties();
        properties.setIssuer("test-im");
        properties.setSecret("test-secret-test-secret-test-secret");
        properties.setTtlSeconds(ttlSeconds);
        return new JwtService(properties, new ObjectMapper(), Clock.fixed(now, ZoneOffset.UTC));
    }
}
