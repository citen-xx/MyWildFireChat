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
        <p class="section-title">Demo Users</p>
        <button
          v-for="user in recipients"
          :key="user.userId"
          type="button"
          class="user-button"
          :class="{ active: user.userId === selectedUser.userId }"
          @click="selectedUser = user"
        >
          {{ user.label }}
        </button>
      </aside>

      <section class="conversation">
        <div class="conversation-header">
          <span>Chat with {{ selectedUser.label }}</span>
          <small>receiverId: {{ selectedUser.userId }}</small>
        </div>

        <div class="messages">
          <p v-if="status === 'Syncing'" class="syncing-text">正在同步历史消息...</p>
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
import { ImSocket } from '../services/imSocket';
import type {
  ChatMessage,
  ConnectionStatus,
  DemoUser,
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
];

const SYNC_PAGE_LIMIT = 100;

const recipients = computed(() => demoUsers.filter((user) => user.userId !== props.currentUser.userId));
const selectedUser = ref<DemoUser>(recipients.value[0] ?? demoUsers[0]);
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

const canSend = computed(() => status.value === 'Online' && draft.value.trim().length > 0);
const visibleMessages = computed(() =>
  messages.value.filter(
    (message) =>
      message.senderId === selectedUser.value.userId ||
      message.receiverId === selectedUser.value.userId,
  ),
);

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
      // SYNC_COMPLETE is informational; cursor advances only from processed messages.
    },
    onError: (nextError) => {
      error.value = `${nextError.code}: ${nextError.message}`;
      console.warn(error.value);
    },
  });
  socket.connect();
});

onUnmounted(() => {
  syncVersion += 1;
  socket?.close();
});

function send() {
  if (!canSend.value) {
    return;
  }
  const content = draft.value.trim();
  const clientMessageId = crypto.randomUUID();
  const message: ChatMessage = {
    localId: clientMessageId,
    clientMessageId,
    senderId: props.currentUser.userId,
    receiverId: selectedUser.value.userId,
    content,
    messageType: 'TEXT',
    createdAt: Date.now(),
    status: 'sending',
  };
  messages.value = sortMessages([...messages.value, message]);
  draft.value = '';
  socket?.sendChatMessage(message);
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
  const deviceId = `web-${crypto.randomUUID()}`;
  sessionStorage.setItem('im.webDeviceId', deviceId);
  return deviceId;
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
</script>
