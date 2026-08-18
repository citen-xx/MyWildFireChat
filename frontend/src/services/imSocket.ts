import type {
  ChatMessage,
  ConnectionStatus,
  ErrorPayload,
  MessageAckPayload,
  PushMessagePayload,
  SendResultPayload,
  SyncRequestPayload,
  SyncResponsePayload,
  WsEnvelope,
} from '../types/im';

interface ImSocketOptions {
  token: string;
  deviceId: string;
  onStatusChange: (status: ConnectionStatus) => void;
  onConnected: () => void;
  onPushMessage: (message: PushMessagePayload) => void;
  onSendResult: (result: SendResultPayload) => void;
  onSyncComplete: (conversationId: number, nextSequence: number) => void;
  onError: (error: ErrorPayload) => void;
}

interface PendingSyncRequest {
  resolve: (payload: SyncResponsePayload) => void;
  reject: (reason: Error) => void;
}

export class ImSocket {
  private socket?: WebSocket;
  private retryCount = 0;
  private readonly maxRetries = 5;
  private heartbeatTimer?: number;
  private reconnectTimer?: number;
  private manuallyClosed = false;
  private readonly pendingSyncRequests = new Map<string, PendingSyncRequest>();

  constructor(private readonly options: ImSocketOptions) {}

  connect() {
    this.manuallyClosed = false;
    this.clearReconnectTimer();
    this.options.onStatusChange(this.retryCount === 0 ? 'Connecting' : 'Reconnecting');

    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const url = `${protocol}//${window.location.host}/ws/im`;
    this.socket = new WebSocket(url);

    this.socket.onopen = () => {
      this.send({
        type: 'CONNECT',
        requestId: crypto.randomUUID(),
        token: this.options.token,
        deviceId: this.options.deviceId,
      });
      this.startHeartbeat();
    };

    this.socket.onmessage = (event) => this.handleMessage(event.data);
    this.socket.onclose = () => this.handleClose();
    this.socket.onerror = () => {
      this.options.onError({ code: 'WEBSOCKET_ERROR', message: 'WebSocket connection error' });
    };
  }

  sendChatMessage(message: ChatMessage) {
    this.send({
      type: 'SEND_MESSAGE',
      requestId: message.clientMessageId,
      payload: {
        clientMessageId: message.clientMessageId,
        receiverId: message.receiverId,
        content: message.content,
        messageType: message.messageType,
      },
    });
  }

  requestSyncPage(payload: SyncRequestPayload) {
    if (this.socket?.readyState !== WebSocket.OPEN) {
      return Promise.reject(new Error('WebSocket is not open'));
    }
    const requestId = crypto.randomUUID();
    this.send({
      type: 'SYNC_REQUEST',
      requestId,
      payload,
    });

    return new Promise<SyncResponsePayload>((resolve, reject) => {
      this.pendingSyncRequests.set(requestId, { resolve, reject });
    });
  }

  close() {
    this.manuallyClosed = true;
    this.clearReconnectTimer();
    this.rejectPendingSyncRequests('WebSocket closed');
    this.stopHeartbeat();
    this.socket?.close();
    this.options.onStatusChange('Offline');
  }

  private handleMessage(raw: string) {
    const envelope = JSON.parse(raw) as WsEnvelope;
    if (envelope.type === 'CONNECT_ACK') {
      this.retryCount = 0;
      this.options.onConnected();
      return;
    }
    if (envelope.type === 'PONG') {
      return;
    }
    if (envelope.type === 'SEND_RESULT') {
      this.options.onSendResult(envelope.payload as SendResultPayload);
      return;
    }
    if (envelope.type === 'PUSH_MESSAGE') {
      this.handlePushMessage(envelope.payload as PushMessagePayload);
      return;
    }
    if (envelope.type === 'SYNC_RESPONSE') {
      this.resolveSyncResponse(envelope);
      return;
    }
    if (envelope.type === 'SYNC_COMPLETE') {
      const payload = envelope.payload as { conversationId: number; nextSequence: number };
      this.options.onSyncComplete(payload.conversationId, payload.nextSequence);
      return;
    }
    if (envelope.type === 'ERROR') {
      this.rejectSyncError(envelope);
      this.options.onError(envelope.payload as ErrorPayload);
    }
  }

  private handlePushMessage(payload: PushMessagePayload) {
    this.options.onPushMessage(payload);
    this.sendMessageAck({
      messageId: payload.messageId,
      conversationId: payload.conversationId,
      sequence: payload.sequence,
    });
  }

  private sendMessageAck(payload: MessageAckPayload) {
    this.send({
      type: 'MESSAGE_ACK',
      requestId: crypto.randomUUID(),
      payload,
    });
  }

  private handleClose() {
    this.stopHeartbeat();
    this.rejectPendingSyncRequests('WebSocket closed');
    if (this.manuallyClosed) {
      this.options.onStatusChange('Offline');
      return;
    }
    if (this.retryCount >= this.maxRetries) {
      this.options.onStatusChange('Offline');
      return;
    }
    this.retryCount += 1;
    this.options.onStatusChange('Reconnecting');
    this.clearReconnectTimer();
    this.reconnectTimer = window.setTimeout(() => this.connect(), 3000);
  }

  private startHeartbeat() {
    this.stopHeartbeat();
    this.heartbeatTimer = window.setInterval(() => {
      this.send({ type: 'PING', requestId: crypto.randomUUID() });
    }, 25000);
  }

  private stopHeartbeat() {
    if (this.heartbeatTimer) {
      window.clearInterval(this.heartbeatTimer);
      this.heartbeatTimer = undefined;
    }
  }

  private clearReconnectTimer() {
    if (this.reconnectTimer) {
      window.clearTimeout(this.reconnectTimer);
      this.reconnectTimer = undefined;
    }
  }

  private resolveSyncResponse(envelope: WsEnvelope) {
    const requestId = envelope.requestId ?? '';
    const pending = this.pendingSyncRequests.get(requestId);
    if (!pending) {
      return;
    }
    this.pendingSyncRequests.delete(requestId);
    pending.resolve(envelope.payload as SyncResponsePayload);
  }

  private rejectSyncError(envelope: WsEnvelope) {
    const requestId = envelope.requestId ?? '';
    const pending = this.pendingSyncRequests.get(requestId);
    if (!pending) {
      return;
    }
    this.pendingSyncRequests.delete(requestId);
    const error = envelope.payload as ErrorPayload;
    pending.reject(new Error(`${error.code}: ${error.message}`));
  }

  private rejectPendingSyncRequests(message: string) {
    for (const pending of this.pendingSyncRequests.values()) {
      pending.reject(new Error(message));
    }
    this.pendingSyncRequests.clear();
  }

  private send(message: Record<string, unknown>) {
    if (this.socket?.readyState === WebSocket.OPEN) {
      this.socket.send(JSON.stringify(message));
    }
  }
}
