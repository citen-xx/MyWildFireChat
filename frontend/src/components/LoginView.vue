<template>
  <main class="login-shell">
    <form class="login-panel" @submit.prevent="submit">
      <h1>My IM</h1>
      <label>
        Username
        <input v-model="username" autocomplete="username" />
      </label>
      <label>
        Password
        <input v-model="password" type="password" autocomplete="current-password" />
      </label>
      <p v-if="error" class="error">{{ error }}</p>
      <button type="submit" :disabled="loading">
        {{ loading ? 'Logging in...' : 'Login' }}
      </button>
      <p class="hint">Demo users: alice / password123, bob / password123</p>
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
