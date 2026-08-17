package com.example.im.auth.service;

public record LoginResult(
        Long userId,
        String username,
        String token,
        long expiresAt) {
}
