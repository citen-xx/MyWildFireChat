import axios from 'axios';
import { apiUrl } from './config';
import type { LoginResult } from '../types/im';

export async function login(username: string, password: string): Promise<LoginResult> {
  const response = await axios.post<LoginResult>(apiUrl('/api/auth/login'), {
    username,
    password,
  });
  return response.data;
}
