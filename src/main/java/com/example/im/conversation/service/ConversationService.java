package com.example.im.conversation.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.im.conversation.model.Conversation;
import com.example.im.conversation.model.ConversationMember;
import com.example.im.conversation.repository.ConversationMapper;
import com.example.im.conversation.repository.ConversationMemberMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@ConditionalOnProperty(name = "im.chat.enabled", havingValue = "true", matchIfMissing = true)
public class ConversationService {

    public static final String TYPE_SINGLE = "SINGLE";

    private final ConversationMapper conversationMapper;
    private final ConversationMemberMapper memberMapper;

    public ConversationService(
            ConversationMapper conversationMapper,
            ConversationMemberMapper memberMapper) {
        this.conversationMapper = conversationMapper;
        this.memberMapper = memberMapper;
    }

    public Conversation getOrCreateSingleConversation(Long firstUserId, Long secondUserId) {
        if (firstUserId == null || secondUserId == null) {
            throw new IllegalArgumentException("single conversation users are required");
        }

        String bizKey = singleBizKey(firstUserId, secondUserId);
        Conversation existing = findByBizKey(bizKey);
        if (existing != null) {
            return existing;
        }

        Conversation created = new Conversation();
        created.setType(TYPE_SINGLE);
        created.setBizKey(bizKey);
        created.setCreatedAt(LocalDateTime.now());

        try {
            conversationMapper.insert(created);
            insertMemberIfAbsent(created.getId(), firstUserId);
            insertMemberIfAbsent(created.getId(), secondUserId);
            return created;
        } catch (DuplicateKeyException exception) {
            Conversation concurrent = findByBizKey(bizKey);
            if (concurrent == null) {
                throw exception;
            }
            return concurrent;
        }
    }

    public String singleBizKey(Long firstUserId, Long secondUserId) {
        long min = Math.min(firstUserId, secondUserId);
        long max = Math.max(firstUserId, secondUserId);
        return "single:" + min + ":" + max;
    }

    public boolean isMember(Long conversationId, Long userId) {
        if (conversationId == null || conversationId <= 0 || userId == null || userId <= 0) {
            return false;
        }
        Long count = memberMapper.selectCount(new LambdaQueryWrapper<ConversationMember>()
                .eq(ConversationMember::getConversationId, conversationId)
                .eq(ConversationMember::getUserId, userId));
        return count != null && count > 0;
    }

    private Conversation findByBizKey(String bizKey) {
        return conversationMapper.selectOne(new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getBizKey, bizKey)
                .last("limit 1"));
    }

    private void insertMemberIfAbsent(Long conversationId, Long userId) {
        ConversationMember member = new ConversationMember();
        member.setConversationId(conversationId);
        member.setUserId(userId);
        member.setJoinedAt(LocalDateTime.now());
        try {
            memberMapper.insert(member);
        } catch (DuplicateKeyException ignored) {
            // Another transaction created the same member row first.
        }
    }
}
