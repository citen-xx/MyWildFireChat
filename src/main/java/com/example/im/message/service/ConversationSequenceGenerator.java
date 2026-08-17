package com.example.im.message.service;

public interface ConversationSequenceGenerator {

    long nextSequence(Long conversationId);
}
