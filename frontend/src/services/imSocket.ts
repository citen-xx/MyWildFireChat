import type {
  ChatMessage,
  ConnectionStatus,
  ErrorPayload,
  MessageAckPayload,
  PushMessagePayload,
  SendResultPayload,
  WsEnvelope,
} from '../types/im';

interface ImSocketOptions {
  token: string;
  deviceId: string;
  onStatusChange: (status: ConnectionStatus) => void;
  onPushMessage: (message: PushMessagePayload) => void;
  onSendResult: (result: SendResultPayload) => void;
  onError: (error: ErrorPayload) => void;
}

export class ImSocket {
  private socket?: WebSocket;
  private retryCount = 0;
  private readonly maxRetries = 5;
  private heartbeatTimer?: number;
  private manuallyClosed = false;

  constructor(private readonly options: ImSocketOptions) {}

  connect() {
    this.manuallyClosed = false;
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

  close() {
    this.manuallyClosed = true;
    this.stopHeartbeat();
    this.socket?.close();
    this.options.onStatusChange('Offline');
  }

  private handleMessage(raw: string) {
    const envelope = JSON.parse(raw) as WsEnvelope;
    if (envelope.type === 'CONNECT_ACK') {
      this.retryCount = 0;
      this.options.onStatusChange('Online');
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
    if (envelope.type === 'ERROR') {
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
    window.setTimeout(() => this.connect(), 3000);
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

  private send(message: Record<string, unknown>) {
    if (this.socket?.readyState === WebSocket.OPEN) {
      this.socket.send(JSON.stringify(message));
    }
  }
}
