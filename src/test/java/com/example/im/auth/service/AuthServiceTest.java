package com.example.im.auth.service;

import com.example.im.auth.security.JwtProperties;
import com.example.im.auth.security.JwtService;
import com.example.im.common.exception.AuthException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthServiceTest {

    @Test
    void loginShouldReturnJwtToken() {
        AuthService authService = authService();

        LoginResult result = authService.login(new LoginCommand("alice", "password123"));

        assertThat(result.userId()).isEqualTo(1001L);
        assertThat(result.username()).isEqualTo("alice");
        assertThat(result.token()).isNotBlank();
        assertThat(result.expiresAt()).isPositive();
    }

    @Test
    void badPasswordShouldBeRejected() {
        AuthService authService = authService();

        assertThatThrownBy(() -> authService.login(new LoginCommand("alice", "bad")))
                .isInstanceOf(AuthException.class)
                .hasMessage("username or password is invalid");
    }

    private AuthService authService() {
        UserCredentialReader reader = username -> {
            if ("alice".equals(username)) {
                return Optional.of(new UserCredential(1001L, "alice", "{noop}password123", "ACTIVE"));
            }
            return Optional.empty();
        };

        JwtProperties properties = new JwtProperties();
        properties.setIssuer("test-im");
        properties.setSecret("test-secret-test-secret-test-secret");
        properties.setTtlSeconds(3600);

        return new AuthService(
                reader,
                new PasswordHashService(),
                new JwtService(properties, new ObjectMapper()));
    }
}
