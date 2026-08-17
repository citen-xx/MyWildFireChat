package com.example.im.auth.service;

import com.example.im.auth.security.JwtService;
import com.example.im.common.exception.AuthException;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final UserCredentialReader userCredentialReader;
    private final PasswordHashService passwordHashService;
    private final JwtService jwtService;

    public AuthService(
            UserCredentialReader userCredentialReader,
            PasswordHashService passwordHashService,
            JwtService jwtService) {
        this.userCredentialReader = userCredentialReader;
        this.passwordHashService = passwordHashService;
        this.jwtService = jwtService;
    }

    public LoginResult login(LoginCommand command) {
        if (command == null || blank(command.username()) || blank(command.password())) {
            throw new AuthException("INVALID_CREDENTIALS", "username or password is invalid");
        }

        UserCredential user = userCredentialReader.findByUsername(command.username())
                .filter(UserCredential::active)
                .orElseThrow(() -> new AuthException("INVALID_CREDENTIALS", "username or password is invalid"));

        if (!passwordHashService.matches(command.password(), user.passwordHash())) {
            throw new AuthException("INVALID_CREDENTIALS", "username or password is invalid");
        }

        JwtService.TokenPair tokenPair = jwtService.generate(user.userId());
        return new LoginResult(user.userId(), user.username(), tokenPair.token(), tokenPair.expiresAt());
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
