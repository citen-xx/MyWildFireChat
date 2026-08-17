package com.example.im.auth.security;

public record JwtClaims(Long userId, long issuedAt, long expiresAt) {
}
