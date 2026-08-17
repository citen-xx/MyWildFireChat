<template>
  <LoginView v-if="!currentUser" @authenticated="setCurrentUser" />
  <ChatView v-else :current-user="currentUser" @logout="currentUser = null" />
</template>

<script setup lang="ts">
import { ref } from 'vue';
import LoginView from './components/LoginView.vue';
import ChatView from './components/ChatView.vue';
import type { LoginResult } from './types/im';

const savedToken = localStorage.getItem('im.token');
const savedUserId = localStorage.getItem('im.userId');
const savedUsername = localStorage.getItem('im.username');

const currentUser = ref<LoginResult | null>(
  savedToken && savedUserId && savedUsername
    ? {
        token: savedToken,
        userId: Number(savedUserId),
        username: savedUsername,
        expiresAt: 0,
      }
    : null,
);

function setCurrentUser(user: LoginResult) {
  currentUser.value = user;
}
</script>
