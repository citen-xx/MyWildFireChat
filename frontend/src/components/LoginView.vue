<template>
  <main class="login-shell">
    <form class="login-panel" @submit.prevent="submit">
      <header class="login-heading">
        <p class="eyebrow">Enterprise IM Demo</p>
        <h1>My IM</h1>
        <p>登录后可使用单聊、群聊和断线同步演示。</p>
      </header>

      <label>
        Username
        <input v-model="username" autocomplete="username" placeholder="Enter username" required />
      </label>
      <label>
        Password
        <input v-model="password" type="password" autocomplete="current-password" placeholder="Enter password" required />
      </label>

      <p v-if="error" class="error" role="alert">{{ error }}</p>
      <button type="submit" class="primary-button" :disabled="loading">
        {{ loading ? 'Logging in...' : 'Login' }}
      </button>

      <section class="demo-accounts" aria-label="Demo accounts">
        <div class="demo-accounts-header">
          <strong>Demo Accounts</strong>
          <span>Click to fill</span>
        </div>
        <button
          v-for="account in demoAccounts"
          :key="account.username"
          type="button"
          class="demo-account"
          :disabled="loading"
          @click="selectDemoAccount(account.username)"
        >
          <span>
            <strong>{{ account.name }}</strong>
            <small>{{ account.username }}</small>
          </span>
          <code>{{ account.password }}</code>
        </button>
      </section>
    </form>
  </main>
</template>

<script setup lang="ts">
import { ref } from 'vue';
import { login } from '../api/auth';
import type { LoginResult } from '../types/im';

const emit = defineEmits<{
  authenticated: [result: LoginResult];
}>();

const username = ref('alice');
const password = ref('password123');
const loading = ref(false);
const error = ref('');
const demoAccounts = [
  { name: 'Alice', username: 'alice', password: 'password123' },
  { name: 'Bob', username: 'bob', password: 'password123' },
  { name: 'Charlie', username: 'charlie', password: 'password123' },
];

function selectDemoAccount(nextUsername: string) {
  username.value = nextUsername;
  password.value = 'password123';
  error.value = '';
}

async function submit() {
  error.value = '';
  loading.value = true;
  try {
    const result = await login(username.value.trim(), password.value);
    sessionStorage.setItem('im.token', result.token);
    sessionStorage.setItem('im.userId', String(result.userId));
    sessionStorage.setItem('im.username', result.username);
    emit('authenticated', result);
  } catch {
    error.value = 'Login failed';
  } finally {
    loading.value = false;
  }
}
</script>
