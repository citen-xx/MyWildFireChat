package com.example.im.group.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.im.auth.model.UserAccount;
import com.example.im.auth.repository.UserAccountMapper;
import com.example.im.conversation.model.Conversation;
import com.example.im.conversation.service.ConversationService;
import com.example.im.group.model.ChatGroup;
import com.example.im.group.model.GroupMember;
import com.example.im.group.repository.ChatGroupMapper;
import com.example.im.group.repository.GroupMemberMapper;
import com.example.im.message.model.ChatMessage;
import com.example.im.message.repository.ChatMessageMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@ConditionalOnProperty(name = "im.chat.enabled", havingValue = "true", matchIfMissing = true)
public class GroupService {

    public static final int MAX_GROUP_MEMBERS = 100;
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DISBANDED = "DISBANDED";
    public static final String STATUS_LEFT = "LEFT";
    public static final String ROLE_OWNER = "OWNER";
    public static final String ROLE_MEMBER = "MEMBER";

    private final ChatGroupMapper groupMapper;
    private final GroupMemberMapper groupMemberMapper;
    private final UserAccountMapper userAccountMapper;
    private final ConversationService conversationService;
    private final ChatMessageMapper messageMapper;

    public GroupService(
            ChatGroupMapper groupMapper,
            GroupMemberMapper groupMemberMapper,
            UserAccountMapper userAccountMapper,
            ConversationService conversationService,
            ChatMessageMapper messageMapper) {
        this.groupMapper = groupMapper;
        this.groupMemberMapper = groupMemberMapper;
        this.userAccountMapper = userAccountMapper;
        this.conversationService = conversationService;
        this.messageMapper = messageMapper;
    }

    @Transactional
    public GroupSummary createGroup(Long ownerId, CreateGroupCommand command) {
        validateOwner(ownerId);
        String name = requireName(command == null ? null : command.name());
        List<Long> memberIds = normalizeMemberIds(command == null ? List.of() : command.memberIds(), ownerId);
        ensureUsersExist(memberIds);

        LocalDateTime now = LocalDateTime.now();
        ChatGroup group = new ChatGroup();
        group.setGroupName(name);
        group.setOwnerId(ownerId);
        group.setStatus(STATUS_ACTIVE);
        group.setMemberCount(memberIds.size());
        group.setCreatedAt(now);
        group.setUpdatedAt(now);
        groupMapper.insert(group);

        Conversation conversation = conversationService.getOrCreateGroupConversation(group.getId());
        for (Long memberId : memberIds) {
            String role = ownerId.equals(memberId) ? ROLE_OWNER : ROLE_MEMBER;
            insertGroupMember(group.getId(), memberId, role, 0L, now);
            conversationService.addMember(conversation.getId(), memberId, role, 0L);
        }
        return toSummary(group, conversation.getId(), ROLE_OWNER);
    }

    public List<GroupSummary> listGroups(Long userId) {
        validateOwner(userId);
        List<GroupMember> memberships = groupMemberMapper.selectList(new LambdaQueryWrapper<GroupMember>()
                .eq(GroupMember::getUserId, userId)
                .eq(GroupMember::getStatus, STATUS_ACTIVE));
        if (memberships.isEmpty()) {
            return List.of();
        }
        return memberships.stream()
                .map(member -> {
                    ChatGroup group = groupMapper.selectById(member.getGroupId());
                    if (group == null || !STATUS_ACTIVE.equalsIgnoreCase(group.getStatus())) {
                        return null;
                    }
                    Long conversationId = conversationService.findGroupConversation(group.getId())
                            .map(Conversation::getId)
                            .orElse(null);
                    return toSummary(group, conversationId, member.getRole());
                })
                .filter(item -> item != null)
                .toList();
    }

    public List<GroupMemberView> listMembers(Long groupId, Long userId) {
        requireActiveMember(groupId, userId);
        List<GroupMember> members = groupMemberMapper.selectList(new LambdaQueryWrapper<GroupMember>()
                .eq(GroupMember::getGroupId, groupId)
                .orderByAsc(GroupMember::getId));
        Map<Long, UserAccount> users = userAccountMapper.selectBatchIds(
                        members.stream().map(GroupMember::getUserId).toList())
                .stream()
                .collect(Collectors.toMap(UserAccount::getId, Function.identity()));
        return members.stream()
                .map(member -> new GroupMemberView(
                        member.getUserId(),
                        users.get(member.getUserId()) == null ? "" : users.get(member.getUserId()).getUsername(),
                        member.getRole(),
                        member.getStatus(),
                        member.getJoinSequence(),
                        member.getLeaveSequence()))
                .toList();
    }

    @Transactional
    public GroupSummary addMembers(Long groupId, Long operatorUserId, AddGroupMembersCommand command) {
        ChatGroup group = requireActiveGroup(groupId);
        requireOwner(group, operatorUserId);
        List<Long> candidateIds = normalizeMemberIds(command == null ? List.of() : command.memberIds(), null);
        if (candidateIds.isEmpty()) {
            return toSummary(group, conversationIdOf(groupId), ROLE_OWNER);
        }
        ensureUsersExist(candidateIds);

        List<Long> activeMemberIds = findActiveMemberIds(groupId);
        Set<Long> existing = new LinkedHashSet<>(activeMemberIds);
        List<Long> newMembers = candidateIds.stream()
                .filter(memberId -> !existing.contains(memberId))
                .toList();
        if (activeMemberIds.size() + newMembers.size() > MAX_GROUP_MEMBERS) {
            throw new IllegalArgumentException("group member count exceeds " + MAX_GROUP_MEMBERS);
        }

        Long conversationId = conversationIdOf(groupId);
        long joinSequence = findConversationMaxSequence(conversationId);
        LocalDateTime now = LocalDateTime.now();
        for (Long memberId : newMembers) {
            insertGroupMember(groupId, memberId, ROLE_MEMBER, joinSequence, now);
            conversationService.addMember(conversationId, memberId, ROLE_MEMBER, joinSequence);
        }
        refreshMemberCount(group);
        return toSummary(groupMapper.selectById(groupId), conversationId, ROLE_OWNER);
    }

    @Transactional
    public void leaveGroup(Long groupId, Long userId) {
        ChatGroup group = requireActiveGroup(groupId);
        GroupMember member = requireActiveMember(groupId, userId);
        if (ROLE_OWNER.equalsIgnoreCase(member.getRole())) {
            throw new IllegalArgumentException("owner cannot leave group");
        }
        long leaveSequence = findConversationMaxSequence(conversationIdOf(groupId));
        markMemberLeft(member, leaveSequence);
        conversationService.markMemberLeft(conversationIdOf(groupId), userId, leaveSequence);
        refreshMemberCount(group);
    }

    @Transactional
    public void disbandGroup(Long groupId, Long ownerId) {
        ChatGroup group = requireActiveGroup(groupId);
        requireOwner(group, ownerId);
        long leaveSequence = findConversationMaxSequence(conversationIdOf(groupId));

        ChatGroup update = new ChatGroup();
        update.setId(groupId);
        update.setStatus(STATUS_DISBANDED);
        update.setMemberCount(0);
        update.setUpdatedAt(LocalDateTime.now());
        groupMapper.updateById(update);

        for (GroupMember member : groupMemberMapper.selectList(new LambdaQueryWrapper<GroupMember>()
                .eq(GroupMember::getGroupId, groupId)
                .eq(GroupMember::getStatus, STATUS_ACTIVE))) {
            markMemberLeft(member, leaveSequence);
            conversationService.markMemberLeft(conversationIdOf(groupId), member.getUserId(), leaveSequence);
        }
    }

    public GroupSendContext requireSendContext(Long groupId, Long senderId) {
        requireActiveGroup(groupId);
        requireActiveMember(groupId, senderId);
        Long conversationId = conversationIdOf(groupId);
        return new GroupSendContext(groupId, conversationId, findActiveMemberIds(groupId));
    }

    public List<Long> findActiveMemberIds(Long groupId) {
        if (groupId == null || groupId <= 0) {
            return List.of();
        }
        return groupMemberMapper.selectList(new LambdaQueryWrapper<GroupMember>()
                        .eq(GroupMember::getGroupId, groupId)
                        .eq(GroupMember::getStatus, STATUS_ACTIVE)
                        .orderByAsc(GroupMember::getId))
                .stream()
                .map(GroupMember::getUserId)
                .toList();
    }

    private ChatGroup requireActiveGroup(Long groupId) {
        if (groupId == null || groupId <= 0) {
            throw new IllegalArgumentException("groupId is required");
        }
        ChatGroup group = groupMapper.selectById(groupId);
        if (group == null || !STATUS_ACTIVE.equalsIgnoreCase(group.getStatus())) {
            throw new IllegalArgumentException("group is not active");
        }
        return group;
    }

    private GroupMember requireActiveMember(Long groupId, Long userId) {
        validateOwner(userId);
        GroupMember member = groupMemberMapper.selectOne(new LambdaQueryWrapper<GroupMember>()
                .eq(GroupMember::getGroupId, groupId)
                .eq(GroupMember::getUserId, userId)
                .eq(GroupMember::getStatus, STATUS_ACTIVE)
                .last("limit 1"));
        if (member == null) {
            throw new IllegalArgumentException("user is not an active group member");
        }
        return member;
    }

    private void requireOwner(ChatGroup group, Long userId) {
        validateOwner(userId);
        if (!group.getOwnerId().equals(userId)) {
            throw new IllegalArgumentException("only group owner can perform this operation");
        }
    }

    private Long conversationIdOf(Long groupId) {
        return conversationService.findGroupConversation(groupId)
                .map(Conversation::getId)
                .orElseThrow(() -> new IllegalStateException("group conversation is missing"));
    }

    private void insertGroupMember(Long groupId, Long userId, String role, long joinSequence, LocalDateTime now) {
        GroupMember member = new GroupMember();
        member.setGroupId(groupId);
        member.setUserId(userId);
        member.setRole(role);
        member.setStatus(STATUS_ACTIVE);
        member.setJoinSequence(joinSequence);
        member.setJoinedAt(now);
        try {
            groupMemberMapper.insert(member);
        } catch (DuplicateKeyException exception) {
            groupMemberMapper.update(null, new LambdaUpdateWrapper<GroupMember>()
                    .eq(GroupMember::getGroupId, groupId)
                    .eq(GroupMember::getUserId, userId)
                    .set(GroupMember::getRole, role)
                    .set(GroupMember::getStatus, STATUS_ACTIVE)
                    .set(GroupMember::getJoinSequence, joinSequence)
                    .set(GroupMember::getLeaveSequence, null)
                    .set(GroupMember::getLeftAt, null)
                    .set(GroupMember::getJoinedAt, now));
        }
    }

    private void markMemberLeft(GroupMember member, long leaveSequence) {
        GroupMember update = new GroupMember();
        update.setId(member.getId());
        update.setStatus(STATUS_LEFT);
        update.setLeaveSequence(leaveSequence);
        update.setLeftAt(LocalDateTime.now());
        groupMemberMapper.updateById(update);
    }

    private void refreshMemberCount(ChatGroup group) {
        long count = groupMemberMapper.selectCount(new LambdaQueryWrapper<GroupMember>()
                .eq(GroupMember::getGroupId, group.getId())
                .eq(GroupMember::getStatus, STATUS_ACTIVE));
        ChatGroup update = new ChatGroup();
        update.setId(group.getId());
        update.setMemberCount((int) count);
        update.setUpdatedAt(LocalDateTime.now());
        groupMapper.updateById(update);
    }

    private GroupSummary toSummary(ChatGroup group, Long conversationId, String role) {
        return new GroupSummary(
                group.getId(),
                conversationId,
                group.getGroupName(),
                role,
                group.getMemberCount() == null ? 0 : group.getMemberCount(),
                group.getStatus());
    }

    private String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("group name is required");
        }
        String trimmed = name.trim();
        if (trimmed.length() > 128) {
            throw new IllegalArgumentException("group name is too long");
        }
        return trimmed;
    }

    private List<Long> normalizeMemberIds(List<Long> requestedIds, Long ownerId) {
        LinkedHashSet<Long> uniqueIds = new LinkedHashSet<>();
        if (ownerId != null) {
            uniqueIds.add(ownerId);
        }
        if (requestedIds != null) {
            for (Long memberId : requestedIds) {
                if (memberId == null || memberId <= 0) {
                    throw new IllegalArgumentException("memberIds contains invalid userId");
                }
                uniqueIds.add(memberId);
            }
        }
        if (uniqueIds.size() > MAX_GROUP_MEMBERS) {
            throw new IllegalArgumentException("group member count exceeds " + MAX_GROUP_MEMBERS);
        }
        return new ArrayList<>(uniqueIds);
    }

    private void validateOwner(Long ownerId) {
        if (ownerId == null || ownerId <= 0) {
            throw new IllegalArgumentException("userId is required");
        }
    }

    private void ensureUsersExist(List<Long> userIds) {
        if (userIds.isEmpty()) {
            return;
        }
        List<UserAccount> users = userAccountMapper.selectBatchIds(userIds).stream()
                .filter(user -> "ACTIVE".equalsIgnoreCase(user.getStatus()))
                .toList();
        Set<Long> foundIds = users.stream().map(UserAccount::getId).collect(Collectors.toSet());
        for (Long userId : userIds) {
            if (!foundIds.contains(userId)) {
                throw new IllegalArgumentException("member user does not exist or is inactive: " + userId);
            }
        }
    }

    private long findConversationMaxSequence(Long conversationId) {
        ChatMessage message = messageMapper.selectOne(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getConversationId, conversationId)
                .orderByDesc(ChatMessage::getSequence)
                .last("limit 1"));
        return message == null || message.getSequence() == null ? 0L : message.getSequence();
    }
}
