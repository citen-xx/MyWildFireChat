<template>
  <main class="chat-shell">
    <header class="topbar">
      <div>
        <strong>My IM</strong>
        <span>{{ currentUser.username }} #{{ currentUser.userId }}</span>
      </div>
      <div class="top-actions">
        <span class="status" :class="status.toLowerCase()">{{ status }}</span>
        <button type="button" @click="logout">Logout</button>
      </div>
    </header>

    <section class="chat-layout">
      <aside class="sidebar">
        <div class="pane-tabs">
          <button type="button" class="tab-button" :class="{ active: activePane === 'DIRECT' }" @click="activePane = 'DIRECT'">
            Direct
          </button>
          <button type="button" class="tab-button" :class="{ active: activePane === 'GROUP' }" @click="activePane = 'GROUP'">
            Groups
          </button>
        </div>

        <template v-if="activePane === 'DIRECT'">
          <p class="section-title">Demo Users</p>
          <button
            v-for="user in directRecipients"
            :key="user.userId"
            type="button"
            class="user-button"
            :class="{ active: selectedUser.userId === user.userId }"
            @click="selectDirect(user)"
          >
            {{ user.label }}
          </button>
        </template>

        <template v-else>
          <p class="section-title">Create Group</p>
          <input v-model="groupName" class="group-input" placeholder="Group name" />
          <label v-for="user in groupCandidates" :key="user.userId" class="group-check">
            <input v-model="groupMemberIds" type="checkbox" :value="user.userId" />
            <span>{{ user.label }}</span>
          </label>
          <button type="button" class="group-action" :disabled="!canCreateGroup" @click="createGroup">
            Create Group
          </button>

          <p class="section-title">My Groups</p>
          <button
            v-for="group in groups"
            :key="group.groupId"
            type="button"
            class="user-button"
            :class="{ active: selectedGroup?.groupId === group.groupId }"
            @click="selectGroup(group)"
          >
            {{ group.groupName }} · {{ group.memberCount }}
          </button>
          <div v-if="selectedGroup" class="group-actions">
            <button v-if="selectedGroup.role === 'OWNER'" type="button" @click="disbandSelectedGroup">Disband</button>
            <button v-else type="button" @click="leaveSelectedGroup">Leave</button>
          </div>
        </template>
      </aside>

      <section class="conversation">
        <div class="conversation-header">
          <span>{{ conversationTitle }}</span>
          <small>{{ conversationSubTitle }}</small>
        </div>

        <div class="messages">
          <p v-if="status === 'Syncing'" class="syncing-text">正在同步历史消息...</p>
          <p v-if="activePane === 'GROUP' && selectedGroup" class="group-meta">
            Members: {{ selectedGroup.memberCount }} · {{ selectedGroup.role }}
          </p>
          <div v-if="activePane === 'GROUP' && groupMembers.length > 0" class="group-member-list">
            <span v-for="member in groupMembers" :key="member.userId">
              {{ member.username || `User ${member.userId}` }} · {{ member.role }}
            </span>
          </div>
          <div
            v-for="message in visibleMessages"
            :key="message.localId"
            class="message-row"
            :class="{ mine: message.senderId === currentUser.userId }"
          >
            <div class="bubble">
              <div class="message-meta">
                {{ senderName(message.senderId) }} · {{ formatTime(message.createdAt) }}
              </div>
              <div>{{ message.content }}</div>
              <div v-if="message.senderId === currentUser.userId" class="message-status">
                {{ message.status }}
              </div>
            </div>
          </div>
        </div>

        <form class="composer" @submit.prevent="send">
          <input v-model="draft" placeholder="Type a message..." />
          <button type="submit" :disabled="!canSend">Send</button>
        </form>
      </section>
    </section>
  </main>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue';
import { apiUrl } from '../api/config';
import { ImSocket } from '../services/imSocket';
import type {
  ChatMessage,
  ConnectionStatus,
  CreateGroupRequest,
  DemoUser,
  GroupMemberView,
  GroupSummary,
  LoginResult,
  PushMessagePayload,
  SendResultPayload,
  SyncResponsePayload,
} from '../types/im';

const props = defineProps<{
  currentUser: LoginResult;
}>();

const emit = defineEmits<{
  logout: [];
}>();

const demoUsers: DemoUser[] = [
  { userId: 1001, username: 'alice', label: 'Alice' },
  { userId: 1002, username: 'bob', label: 'Bob' },
  { userId: 1003, username: 'charlie', label: 'Charlie' },
];

const SYNC_PAGE_LIMIT = 100;

const activePane = ref<'DIRECT' | 'GROUP'>('DIRECT');
const selectedUser = ref<DemoUser>(directRecipientsDefault());
const selectedGroup = ref<GroupSummary | null>(null);
const groups = ref<GroupSummary[]>([]);
const groupMembers = ref<GroupMemberView[]>([]);
const groupName = ref('');
const groupMemberIds = ref<number[]>([]);
const status = ref<ConnectionStatus>('Offline');
const draft = ref('');
const messages = ref<ChatMessage[]>([]);
const error = ref('');
const deviceId = getDeviceId();
const messageIndex = new Map<string, ChatMessage>();
const pendingSequences = new Map<number, Map<number, ChatMessage>>();
const cursorCache = new Map<number, number>();
const knownConversationIds = new Set<number>();
let socket: ImSocket | undefined;
let syncVersion = 0;

const directRecipients = computed(() => demoUsers.filter((user) => user.userId !== props.currentUser.userId));
const groupCandidates = computed(() => demoUsers.filter((user) => user.userId !== props.currentUser.userId));
const canCreateGroup = computed(() => groupName.value.trim().length > 0 && groupMemberIds.value.length > 0);
const hasConversationTarget = computed(() =>
  activePane.value === 'DIRECT' ? Boolean(selectedUser.value) : Boolean(selectedGroup.value),
);
const canSend = computed(() => status.value === 'Online' && draft.value.trim().length > 0 && hasConversationTarget.value);
const visibleMessages = computed(() => {
  if (activePane.value === 'GROUP') {
    const conversationId = selectedGroup.value?.conversationId;
    if (!conversationId) {
      return [];
    }
    return messages.value.filter((message) => message.conversationId === conversationId);
  }
  return messages.value.filter(
    (message) =>
      message.senderId === selectedUser.value.userId ||
      message.receiverId === selectedUser.value.userId,
  );
});
const conversationTitle = computed(() => {
  if (activePane.value === 'GROUP' && selectedGroup.value) {
    return `Group: ${selectedGroup.value.groupName}`;
  }
  return `Chat with ${selectedUser.value.label}`;
});
const conversationSubTitle = computed(() => {
  if (activePane.value === 'GROUP' && selectedGroup.value) {
    return `groupId: ${selectedGroup.value.groupId} · conversationId: ${selectedGroup.value.conversationId}`;
  }
  return `receiverId: ${selectedUser.value.userId}`;
});

onMounted(() => {
  socket = new ImSocket({
    token: props.currentUser.token,
    deviceId,
    onStatusChange: (nextStatus) => {
      if (nextStatus === 'Offline' || nextStatus === 'Reconnecting' || nextStatus === 'Connecting') {
        syncVersion += 1;
      }
      status.value = nextStatus;
    },
    onConnected: () => {
      void recoverOfflineMessages();
    },
    onPushMessage: receivePush,
    onSendResult: applySendResult,
    onSyncComplete: () => {
      // informational only
    },
    onError: (nextError) => {
      error.value = `${nextError.code}: ${nextError.message}`;
      console.warn(error.value);
    },
  });
  socket.connect();
  void loadGroups();
});

onUnmounted(() => {
  syncVersion += 1;
  socket?.close();
});

function selectDirect(user: DemoUser) {
  activePane.value = 'DIRECT';
  selectedUser.value = user;
  groupMembers.value = [];
}

async function selectGroup(group: GroupSummary) {
  activePane.value = 'GROUP';
  selectedGroup.value = group;
  await loadGroupMembers(group.groupId);
}

async function loadGroups() {
  try {
    const response = await fetch(apiUrl('/api/groups'), { headers: authHeaders() });
    if (!response.ok) {
      return;
    }
    const payload = (await response.json()) as GroupSummary[];
    groups.value = payload;
    for (const group of payload) {
      rememberConversation(group.conversationId);
    }
    if (activePane.value === 'GROUP' && selectedGroup.value) {
      const refreshed = payload.find((group) => group.groupId === selectedGroup.value?.groupId);
      if (refreshed) {
        selectedGroup.value = refreshed;
      }
    }
  } catch (exception) {
    console.warn('failed to load groups', exception);
  }
}

async function loadGroupMembers(groupId: number) {
  try {
    const response = await fetch(apiUrl(`/api/groups/${groupId}/members`), { headers: authHeaders() });
    if (!response.ok) {
      groupMembers.value = [];
      return;
    }
    groupMembers.value = (await response.json()) as GroupMemberView[];
  } catch (exception) {
    console.warn('failed to load group members', exception);
    groupMembers.value = [];
  }
}

async function createGroup() {
  if (!canCreateGroup.value) {
    return;
  }
  try {
    const request: CreateGroupRequest = {
      name: groupName.value.trim(),
      memberIds: [...groupMemberIds.value],
    };
    const response = await fetch(apiUrl('/api/groups'), {
      method: 'POST',
      headers: {
        ...authHeaders(),
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(request),
    });
    if (!response.ok) {
      throw new Error(await response.text());
    }
    const created = (await response.json()) as GroupSummary;
    groupName.value = '';
    groupMemberIds.value = [];
    await loadGroups();
    selectedGroup.value = created;
    activePane.value = 'GROUP';
    await loadGroupMembers(created.groupId);
    rememberConversation(created.conversationId);
  } catch (exception) {
    console.warn('failed to create group', exception);
  }
}

async function leaveSelectedGroup() {
  if (!selectedGroup.value) {
    return;
  }
  try {
    await fetch(apiUrl(`/api/groups/${selectedGroup.value.groupId}/leave`), {
      method: 'POST',
      headers: authHeaders(),
    });
    selectedGroup.value = null;
    activePane.value = 'DIRECT';
    await loadGroups();
  } catch (exception) {
    console.warn('failed to leave group', exception);
  }
}

async function disbandSelectedGroup() {
  if (!selectedGroup.value) {
    return;
  }
  try {
    await fetch(apiUrl(`/api/groups/${selectedGroup.value.groupId}`), {
      method: 'DELETE',
      headers: authHeaders(),
    });
    selectedGroup.value = null;
    activePane.value = 'DIRECT';
    await loadGroups();
  } catch (exception) {
    console.warn('failed to disband group', exception);
  }
}

function send() {
  if (!canSend.value) {
    return;
  }
  const content = draft.value.trim();
  const clientMessageId = crypto.randomUUID();
  const baseMessage: ChatMessage = {
    localId: clientMessageId,
    clientMessageId,
    senderId: props.currentUser.userId,
    receiverId: activePane.value === 'GROUP' ? 0 : selectedUser.value.userId,
    conversationId: activePane.value === 'GROUP' ? selectedGroup.value?.conversationId : undefined,
    content,
    messageType: 'TEXT',
    createdAt: Date.now(),
    status: 'sending',
  };
  messages.value = sortMessages([...messages.value, baseMessage]);
  draft.value = '';
  if (activePane.value === 'GROUP' && selectedGroup.value) {
    socket?.sendGroupMessage(baseMessage, selectedGroup.value.groupId);
    return;
  }
  socket?.sendChatMessage(baseMessage);
}

function receivePush(payload: PushMessagePayload) {
  ingestDeliveredMessage(payload);
}

function receiveSyncResponse(payload: SyncResponsePayload) {
  for (const message of payload.messages) {
    ingestDeliveredMessage(message);
  }
}

function ingestDeliveredMessage(payload: PushMessagePayload) {
  mergeProcessedMessage({
    localId: payload.messageId,
    clientMessageId: payload.clientMessageId,
    messageId: payload.messageId,
    conversationId: payload.conversationId,
    sequence: payload.sequence,
    senderId: payload.senderId,
    receiverId: payload.receiverId,
    content: payload.content,
    messageType: payload.messageType,
    createdAt: payload.createdAt,
    status: payload.senderId === props.currentUser.userId ? 'sent' : 'received',
  });
}

function applySendResult(payload: SendResultPayload) {
  const message = messages.value.find((item) => item.clientMessageId === payload.clientMessageId);
  if (!message) {
    return;
  }
  message.messageId = payload.messageId;
  message.conversationId = payload.conversationId;
  message.sequence = payload.sequence;
  message.createdAt = payload.createdAt;
  message.status = 'sent';
  if (message.messageId) {
    messageIndex.set(message.messageId, message);
  }
  rememberConversation(payload.conversationId);
  registerSequence(message);
  messages.value = sortMessages(messages.value);
}

async function recoverOfflineMessages() {
  const conversationIds = loadKnownConversationIds();
  const currentSyncVersion = ++syncVersion;

  if (conversationIds.length === 0) {
    status.value = 'Online';
    return;
  }

  status.value = 'Syncing';
  try {
    for (const conversationId of conversationIds) {
      let lastSequence = readCursor(conversationId);
      while (currentSyncVersion === syncVersion) {
        const response = await socket?.requestSyncPage({
          conversationId,
          lastSequence,
          limit: SYNC_PAGE_LIMIT,
        });
        if (!response) {
          break;
        }
        receiveSyncResponse(response);
        lastSequence = response.nextSequence;
        if (!response.hasMore) {
          break;
        }
      }
    }
  } catch (exception) {
    console.warn('sync failed', exception);
  } finally {
    if (currentSyncVersion === syncVersion) {
      status.value = 'Online';
    }
  }
}

function mergeProcessedMessage(message: ChatMessage) {
  if (message.messageId && messageIndex.has(message.messageId)) {
    return false;
  }

  const localPending = messages.value.find(
    (item) => item.clientMessageId === message.clientMessageId && !item.messageId,
  );
  if (localPending) {
    Object.assign(localPending, {
      messageId: message.messageId,
      conversationId: message.conversationId,
      sequence: message.sequence,
      senderId: message.senderId,
      receiverId: message.receiverId,
      content: message.content,
      messageType: message.messageType,
      createdAt: message.createdAt,
      status: message.status,
    });
    if (message.messageId) {
      messageIndex.set(message.messageId, localPending);
    }
    rememberConversation(message.conversationId);
    registerSequence(localPending);
    messages.value = sortMessages(messages.value);
    return true;
  }

  messages.value = sortMessages([...messages.value, message]);
  if (message.messageId) {
    messageIndex.set(message.messageId, message);
  }
  rememberConversation(message.conversationId);
  registerSequence(message);
  return true;
}

function registerSequence(message: ChatMessage) {
  if (!message.conversationId || !message.sequence) {
    return;
  }
  const currentCursor = readCursor(message.conversationId);
  if (message.sequence > currentCursor) {
    const pending = pendingSequences.get(message.conversationId) ?? new Map<number, ChatMessage>();
    pending.set(message.sequence, message);
    pendingSequences.set(message.conversationId, pending);
  }
  advanceContiguousCursor(message.conversationId);
}

function advanceContiguousCursor(conversationId: number) {
  const pending = pendingSequences.get(conversationId);
  if (!pending) {
    return;
  }
  let cursor = readCursor(conversationId);
  while (pending.has(cursor + 1)) {
    cursor += 1;
    pending.delete(cursor);
  }
  writeCursor(conversationId, cursor);
}

function sortMessages(nextMessages: ChatMessage[]) {
  return [...nextMessages].sort((left, right) => {
    const leftSequence = left.sequence ?? Number.MAX_SAFE_INTEGER;
    const rightSequence = right.sequence ?? Number.MAX_SAFE_INTEGER;
    if (leftSequence !== rightSequence) {
      return leftSequence - rightSequence;
    }
    return left.createdAt - right.createdAt;
  });
}

function rememberConversation(conversationId?: number) {
  if (!conversationId) {
    return;
  }
  if (!knownConversationIds.has(conversationId)) {
    knownConversationIds.add(conversationId);
    localStorage.setItem(knownConversationKey(), JSON.stringify([...knownConversationIds]));
  }
}

function loadKnownConversationIds() {
  if (knownConversationIds.size > 0) {
    return [...knownConversationIds];
  }
  try {
    const raw = localStorage.getItem(knownConversationKey());
    const parsed = raw ? JSON.parse(raw) : [];
    if (Array.isArray(parsed)) {
      for (const item of parsed) {
        const conversationId = Number(item);
        if (Number.isFinite(conversationId) && conversationId > 0) {
          knownConversationIds.add(conversationId);
        }
      }
    }
  } catch {
    localStorage.removeItem(knownConversationKey());
  }
  return [...knownConversationIds];
}

function readCursor(conversationId: number) {
  const cached = cursorCache.get(conversationId);
  if (cached !== undefined) {
    return cached;
  }
  const value = Number(localStorage.getItem(cursorKey(conversationId)) ?? '0');
  const cursor = Number.isFinite(value) && value > 0 ? value : 0;
  cursorCache.set(conversationId, cursor);
  return cursor;
}

function writeCursor(conversationId: number, nextCursor: number) {
  const currentCursor = readCursor(conversationId);
  if (nextCursor <= currentCursor) {
    return;
  }
  cursorCache.set(conversationId, nextCursor);
  localStorage.setItem(cursorKey(conversationId), String(nextCursor));
}

function knownConversationKey() {
  return `im:conversations:${props.currentUser.userId}:${deviceId}`;
}

function cursorKey(conversationId: number) {
  return `im:cursor:${props.currentUser.userId}:${deviceId}:${conversationId}`;
}

function authHeaders() {
  return {
    Authorization: `Bearer ${props.currentUser.token}`,
  };
}

function logout() {
  syncVersion += 1;
  socket?.close();
  sessionStorage.removeItem('im.token');
  sessionStorage.removeItem('im.userId');
  sessionStorage.removeItem('im.username');
  emit('logout');
}

function getDeviceId() {
  const existing = sessionStorage.getItem('im.webDeviceId');
  if (existing) {
    return existing;
  }
  const webDeviceId = `web-${crypto.randomUUID()}`;
  sessionStorage.setItem('im.webDeviceId', webDeviceId);
  return webDeviceId;
}

function senderName(userId: number) {
  if (userId === props.currentUser.userId) {
    return 'Me';
  }
  return demoUsers.find((user) => user.userId === userId)?.label ?? `User ${userId}`;
}

function formatTime(timestamp: number) {
  return new Date(timestamp).toLocaleTimeString();
}

function directRecipientsDefault() {
  return demoUsers.find((user) => user.userId !== props.currentUser.userId) ?? demoUsers[0];
}
</script>
