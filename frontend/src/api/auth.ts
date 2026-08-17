import axios from 'axios';
import type { LoginResult } from '../types/im';

export async function login(username: string, password: string): Promise<LoginResult> {
  const response = await axios.post<LoginResult>('/api/auth/login', {
    username,
    password,
  });
  return response.data;
}
