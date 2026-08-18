export interface LoginResult {
  userId: number;
  username: string;
  token: string;
  expiresAt: number;
}

export interface DemoUser {
  userId: number;
  username: string;
  label: string;
}

export type ConnectionStatus = 'Connecting' | 'Online' | 'Offline' | 'Reconnecting' | 'Syncing';

export interface ChatMessage {
  localId: string;
  clientMessageId: string;
  messageId?: string;
  conversationId?: number;
  sequence?: number;
  senderId: number;
  receiverId: number;
  content: string;
  messageType: 'TEXT';
  createdAt: number;
  status: 'sending' | 'sent' | 'received' | 'failed';
}

export interface WsEnvelope<T = unknown> {
  type: string;
  requestId?: string;
  timestamp?: number;
  payload?: T;
}

export interface SendResultPayload {
  clientMessageId: string;
  messageId: string;
  conversationId: number;
  sequence: number;
  createdAt: number;
}

export interface PushMessagePayload extends SendResultPayload {
  senderId: number;
  receiverId: number;
  content: string;
  messageType: 'TEXT';
}

export interface MessageAckPayload {
  messageId: string;
  conversationId: number;
  sequence: number;
}

export interface SyncRequestPayload {
  conversationId: number;
  lastSequence: number;
  limit: number;
}

export interface SyncResponsePayload {
  conversationId: number;
  messages: PushMessagePayload[];
  hasMore: boolean;
  nextSequence: number;
}

export interface SyncCompletePayload {
  conversationId: number;
  nextSequence: number;
}

export interface ErrorPayload {
  code: string;
  message: string;
}
