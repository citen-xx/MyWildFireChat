package com.example.im.group.service;

public record GroupMemberView(
        Long userId,
        String username,
        String role,
        String status,
        Long joinSequence,
        Long leaveSequence) {
}
