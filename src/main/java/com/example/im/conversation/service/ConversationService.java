package com.example.im.conversation.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.im.conversation.model.Conversation;
import com.example.im.conversation.model.ConversationMember;
import com.example.im.conversation.repository.ConversationMapper;
import com.example.im.conversation.repository.ConversationMemberMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;

@Service
@ConditionalOnProperty(name = "im.chat.enabled", havingValue = "true", matchIfMissing = true)
public class ConversationService {

    public static final String TYPE_DIRECT = "DIRECT";
    public static final String TYPE_SINGLE = TYPE_DIRECT;
    public static final String TYPE_GROUP = "GROUP";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_LEFT = "LEFT";

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
        created.setType(TYPE_DIRECT);
        created.setBizKey(bizKey);
        created.setCreatedAt(LocalDateTime.now());

        try {
            conversationMapper.insert(created);
            insertMemberIfAbsent(created.getId(), firstUserId, "MEMBER", 0L);
            insertMemberIfAbsent(created.getId(), secondUserId, "MEMBER", 0L);
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

    public Conversation getOrCreateGroupConversation(Long groupId) {
        if (groupId == null || groupId <= 0) {
            throw new IllegalArgumentException("groupId is required");
        }
        String bizKey = groupBizKey(groupId);
        Conversation existing = findByBizKey(bizKey);
        if (existing != null) {
            return existing;
        }

        Conversation created = new Conversation();
        created.setType(TYPE_GROUP);
        created.setBizKey(bizKey);
        created.setCreatedAt(LocalDateTime.now());
        try {
            conversationMapper.insert(created);
            return created;
        } catch (DuplicateKeyException exception) {
            Conversation concurrent = findByBizKey(bizKey);
            if (concurrent == null) {
                throw exception;
            }
            return concurrent;
        }
    }

    public String groupBizKey(Long groupId) {
        return "group:" + groupId;
    }

    public boolean isMember(Long conversationId, Long userId) {
        return findActiveMember(conversationId, userId).isPresent();
    }

    public Optional<ConversationMember> findActiveMember(Long conversationId, Long userId) {
        return findMember(conversationId, userId)
                .filter(member -> STATUS_ACTIVE.equalsIgnoreCase(member.getStatus()));
    }

    public Optional<ConversationMember> findMember(Long conversationId, Long userId) {
        if (conversationId == null || conversationId <= 0 || userId == null || userId <= 0) {
            return Optional.empty();
        }
        ConversationMember member = memberMapper.selectOne(new LambdaQueryWrapper<ConversationMember>()
                .eq(ConversationMember::getConversationId, conversationId)
                .eq(ConversationMember::getUserId, userId)
                .last("limit 1"));
        return Optional.ofNullable(member);
    }

    public Optional<Conversation> findById(Long conversationId) {
        if (conversationId == null || conversationId <= 0) {
            return Optional.empty();
        }
        return Optional.ofNullable(conversationMapper.selectById(conversationId));
    }

    public Optional<Conversation> findGroupConversation(Long groupId) {
        if (groupId == null || groupId <= 0) {
            return Optional.empty();
        }
        return Optional.ofNullable(findByBizKey(groupBizKey(groupId)));
    }

    public void addMember(Long conversationId, Long userId, String role, long joinSequence) {
        insertMemberIfAbsent(conversationId, userId, role, joinSequence);
    }

    public void markMemberLeft(Long conversationId, Long userId, long leaveSequence) {
        ConversationMember member = new ConversationMember();
        member.setStatus(STATUS_LEFT);
        member.setLeaveSequence(leaveSequence);
        member.setLeftAt(LocalDateTime.now());
        memberMapper.update(member, new LambdaQueryWrapper<ConversationMember>()
                .eq(ConversationMember::getConversationId, conversationId)
                .eq(ConversationMember::getUserId, userId));
    }

    private Conversation findByBizKey(String bizKey) {
        return conversationMapper.selectOne(new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getBizKey, bizKey)
                .last("limit 1"));
    }

    private void insertMemberIfAbsent(Long conversationId, Long userId, String role, long joinSequence) {
        ConversationMember member = new ConversationMember();
        member.setConversationId(conversationId);
        member.setUserId(userId);
        member.setRole(role);
        member.setStatus(STATUS_ACTIVE);
        member.setJoinSequence(joinSequence);
        member.setJoinedAt(LocalDateTime.now());
        try {
            memberMapper.insert(member);
        } catch (DuplicateKeyException ignored) {
            memberMapper.update(null, new LambdaUpdateWrapper<ConversationMember>()
                    .eq(ConversationMember::getConversationId, conversationId)
                    .eq(ConversationMember::getUserId, userId)
                    .set(ConversationMember::getRole, role)
                    .set(ConversationMember::getStatus, STATUS_ACTIVE)
                    .set(ConversationMember::getJoinSequence, joinSequence)
                    .set(ConversationMember::getLeaveSequence, null)
                    .set(ConversationMember::getLeftAt, null)
                    .set(ConversationMember::getJoinedAt, LocalDateTime.now()));
        }
    }
}
