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

const recipients = computed(() => demoUsers.filter((user) => user.userId !== props.currentUser.userId));
const selectedUser = ref<DemoUser>(recipients.value[0] ?? demoUsers[0]);
const status = ref<ConnectionStatus>('Offline');
const draft = ref('');
const messages = ref<ChatMessage[]>([]);
const error = ref('');
let socket: ImSocket | undefined;

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
    deviceId: getDeviceId(),
    onStatusChange: (nextStatus) => {
      status.value = nextStatus;
    },
    onPushMessage: receivePush,
    onSendResult: applySendResult,
    onError: (nextError) => {
      error.value = `${nextError.code}: ${nextError.message}`;
      console.warn(error.value);
    },
  });
  socket.connect();
});

onUnmounted(() => {
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
  messages.value.push(message);
  draft.value = '';
  socket?.sendChatMessage(message);
}

function receivePush(payload: PushMessagePayload) {
  messages.value.push({
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
    status: 'received',
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
}

function logout() {
  socket?.close();
  localStorage.removeItem('im.token');
  localStorage.removeItem('im.userId');
  localStorage.removeItem('im.username');
  emit('logout');
}

function getDeviceId() {
  const existing = localStorage.getItem('im.webDeviceId');
  if (existing) {
    return existing;
  }
  const deviceId = `web-${crypto.randomUUID()}`;
  localStorage.setItem('im.webDeviceId', deviceId);
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
