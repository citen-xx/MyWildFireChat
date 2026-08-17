package com.example.im.auth.service;

public record UserCredential(
        Long userId,
        String username,
        String passwordHash,
        String status) {

    public boolean active() {
        return "ACTIVE".equalsIgnoreCase(status);
    }
}
