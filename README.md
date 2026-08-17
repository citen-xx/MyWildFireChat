# My Wildfire Chat

## 项目介绍

这是一个用于 Java 后端学习和简历讲解的精简 IM 系统。项目参考
WildfireChat 的长连接、会话、投递和同步设计思想，但不复制其源码、
包名、MQTT 协议或工程结构。

当前已完成 Phase 3：在 Phase 2 基础上实现单聊、Conversation、MySQL
消息持久化、`clientMessageId` 幂等、Conversation sequence 和在线设备推送。

## Architecture

```text
Client
  -> Netty TCP 9000
  -> LengthFieldBasedFrameDecoder
  -> Protobuf MessageEnvelope
  -> AuthHandler
  -> HeartbeatHandler
  -> MessageHandler
  -> Application Service
  -> MySQL / Redis / RabbitMQ

REST Client
  -> Spring Boot HTTP 8080
  -> Controller
  -> Application Service
```

Phase 3 完成单节点单聊核心链路。接收方 ACK、消息重投、离线同步、Redis
route、RabbitMQ 跨节点转发会在后续阶段逐步完成。

## 模块说明

```text
com.example.im
├── auth              # login, JWT, user credential loading
├── conversation      # Phase 3: conversation creation and members
├── group             # Phase 6: group and group members
├── message           # Phase 3-5: persistence, sequence, ack, sync
├── mq                # Phase 8: RabbitMQ cross-node forwarding
├── netty
│   ├── handler       # Netty inbound handlers
│   ├── protocol      # generated Protobuf Java classes
│   ├── server        # Netty bootstrap and lifecycle
│   └── session       # Phase 2: live channel sessions
├── route             # Phase 7: Redis online routing
└── common            # shared response, error, utilities
```

## 数据库设计

当前 schema 文件：`src/main/resources/db/schema.sql`

已创建：

- `user_account`: 登录用户表，包含 `id`, `username`, `password_hash`, `status`
- `uk_user_account_username`: 保证用户名唯一
- `conversation`: 会话表，`biz_key` 唯一
- `conversation_member`: 会话成员表，`(conversation_id, user_id)` 联合主键
- `message`: 消息表

当前种子数据文件：`src/main/resources/db/data.sql`

- `alice / password123`, userId: `1001`
- `bob / password123`, userId: `1002`

后续 Phase 6 会加入 `chat_group`, `group_member`。

`message` 关键约束：

- `UNIQUE(message_id)`: 服务端业务消息 ID 全局唯一
- `UNIQUE(sender_id, client_message_id)`: 客户端重试幂等
- `UNIQUE(conversation_id, sequence)`: 会话内 sequence 不重复

## Redis Key

当前和后续计划使用：

- `im:seq:{conversationId}`: Conversation sequence counter，Phase 3 使用 Redis `INCR`
- `im:route:{userId}:{deviceId}`: online route with TTL
- `im:pending_ack:{userId}:{deviceId}`: pending ACK messages

## RabbitMQ Exchange / Queue

Phase 1 只提供 Docker Compose RabbitMQ。Phase 8 中 RabbitMQ 只用于跨 IM
节点消息转发，不用于普通异步落库或延迟队列。

## Message Protocol

当前 Protobuf 文件：`src/main/proto/im_protocol.proto`

`MessageEnvelope` 字段：

- `message_type`
- `request_id`
- `timestamp`
- `payload`

已定义类型：

- `CONNECT`, `CONNECT_ACK`
- `PING`, `PONG`
- `SEND_MESSAGE`, `SEND_RESULT`, `PUSH_MESSAGE`
- `MESSAGE_ACK`
- `SYNC_REQUEST`, `SYNC_RESPONSE`
- `ERROR`

Phase 2 已实现 payload：

- `ConnectRequest(token, deviceId)`
- `ConnectAck(userId, deviceId, serverTime)`
- `ErrorPayload(code, message)`

Phase 3 已实现 payload：

- `SendMessageRequest(clientMessageId, receiverId, content, messageType)`
- `SendResult(clientMessageId, messageId, conversationId, sequence, createdAt)`
- `PushMessage(messageId, clientMessageId, conversationId, sequence, senderId, receiverId, content, messageType, createdAt)`

`SendResult` 只表示服务端已经成功接收并持久化，不表示接收方已经收到。
真正的消息投递 ACK 放到 Phase 4。

TCP framing 使用 4 字节 length field，避免 TCP 粘包拆包问题。

## 单聊发送链路

```text
Client A
  -> SEND_MESSAGE(clientMessageId, receiverId, content, messageType)
  -> AuthHandler confirms CONNECT
  -> MessageHandler reads senderId from Session
  -> MessageService
  -> ConversationService get-or-create single conversation
  -> Redis INCR im:seq:{conversationId}
  -> MySQL insert message
  -> SEND_RESULT to Client A
  -> SessionManager finds all receiver devices
  -> PUSH_MESSAGE to each local Channel
```

客户端不能在 `SendMessageRequest` 中提供 `senderId`。服务端只使用
`SessionManager` 中 CONNECT 时绑定的 userId。

如果接收方离线，本阶段只完成 MySQL 落库，不主动补偿；离线同步放到 Phase 5。

## Conversation 唯一性

单聊使用规范化 key：

```text
single:min(userIdA, userIdB):max(userIdA, userIdB)
```

因此 `1001 -> 1002` 和 `1002 -> 1001` 都得到：

```text
single:1001:1002
```

Java 层先查询只是优化；最终依靠 `UNIQUE(biz_key)` 处理两个线程首次并发
创建的情况。插入冲突后重新查询已有 Conversation，不使用 JVM 锁替代数据库约束。

## clientMessageId 幂等

`clientMessageId` 是客户端发送前生成的请求 ID；`messageId` 是服务端成功落库
后生成的全局业务消息 ID。

服务端先查询 `(sender_id, client_message_id)`。并发重复请求即使同时通过了
第一次查询，也会被数据库唯一约束拦住；服务端随后查询并返回第一次落库的
`messageId` 和 `sequence`，不会重复插入消息。

## 登录与 CONNECT 鉴权

HTTP 登录：

```text
POST /api/auth/login
Content-Type: application/json

{
  "username": "alice",
  "password": "password123"
}
```

成功后返回：

```text
{
  "userId": 1001,
  "username": "alice",
  "token": "...",
  "expiresAt": 1786968000
}
```

Netty 客户端建立 TCP 连接后，第一条业务消息必须是：

```text
MessageEnvelope(
  messageType = CONNECT,
  payload = ConnectRequest(token, deviceId)
)
```

服务端链路：

```text
LengthFieldBasedFrameDecoder
  -> ProtobufDecoder(MessageEnvelope)
  -> IdleStateHandler
  -> AuthHandler
  -> HeartbeatHandler
  -> MessageHandler
  -> SessionCleanupHandler
```

`AuthHandler` 只负责协议入口判断；JWT 校验和 Session 绑定下沉到
`NettyAuthService`。鉴权成功返回 `CONNECT_ACK`，鉴权失败返回 `ERROR` 并关闭
Channel。

## SessionManager

Session 是设备级别，而不是用户级别：

```text
SessionKey(userId, deviceId) -> Channel
ChannelId -> SessionKey
Channel.attr("im.session") -> ImSession
```

实现文件：`src/main/java/com/example/im/netty/session/SessionManager.java`

并发设计：

- 使用 `ConcurrentHashMap` 存储在线 Channel 和反向索引
- `bind` / `remove` 使用 `synchronized` 保护跨 Map 的复合更新
- 相同 `userId + deviceId` 重复登录时，新 Channel 替换旧 Channel，并关闭旧连接
- 旧 Channel 后续触发 `channelInactive` 时，只会移除自己对应的旧映射，不会误删新连接
- Channel 断开时由 `SessionCleanupHandler` 调用 `SessionManager.remove(channel)`

## 心跳机制

客户端发送 `PING`，服务端返回相同 `requestId` 的 `PONG`。

`IdleStateHandler` 使用 `im.netty.reader-idle-seconds` 配置读空闲时间，默认
60 秒。如果服务端在这段时间内没有读到任何消息，就关闭 Channel，随后触发
Session 清理。

## ACK 机制

TODO: Phase 4 实现。

目标语义是 At-Least-Once Delivery + `messageId` 去重，不宣称 Exactly Once。

## Sequence 机制

Phase 3 使用 Redis `INCR im:seq:{conversationId}` 分配 sequence。

sequence 只要求同一 Conversation 内严格递增和唯一，不要求严格连续。
例如 Redis 分配了 `3`，但随后 MySQL 插入失败，下一条消息可能使用 `4`，
因此出现 `1, 2, 4` 的空洞。

空洞是可接受的，因为 sequence 的用途是排序和增量游标，不是数据库行号。
MySQL 通过 `UNIQUE(conversation_id, sequence)` 作为最终约束。

Redis 和 MySQL 之间不是强一致事务：Redis INCR 成功后，MySQL 可能失败。
本阶段不回收 sequence，也不宣称 Redis/MySQL 分布式事务。

## 离线同步机制

TODO: Phase 5 实现。

客户端发送 `SYNC_REQUEST(conversationId, lastSequence, limit)`，服务端查询
`sequence > lastSequence` 的消息，按 sequence 升序返回，单页最多 100 条。

## 多节点路由

TODO: Phase 7-8 实现。

单机 `SessionManager` 只保存本进程 live Channel；Redis route 保存
`userId + deviceId -> serverId` 并设置 TTL。跨节点消息通过 RabbitMQ 转发到
目标 `serverId` 所在实例。

## Web Client

Phase 3 之后新增一个最小可用 Web 前端，用于浏览器演示 Alice/Bob 单聊。

前端技术栈：

- Vue 3
- Vite
- TypeScript
- Axios
- 原生 CSS

前端目录：`frontend/`

WebSocket 地址：

```text
ws://localhost:8080/ws/im
```

选择 Spring WebSocket 挂在 8080，是因为当前项目已经有 Spring Boot Web。
这样浏览器 WebSocket 可以直接注入并复用 `JwtService`、`SessionManager`、
`MessageService`、`ConversationService`，不需要再启动一个独立的 Netty
WebSocket Server，也不会复制一套聊天业务。

WebSocket JSON 协议：

```json
{
  "type": "CONNECT",
  "requestId": "uuid",
  "token": "jwt-token",
  "deviceId": "web-device-id"
}
```

```json
{
  "type": "SEND_MESSAGE",
  "requestId": "client-message-id",
  "payload": {
    "clientMessageId": "uuid",
    "receiverId": 1002,
    "content": "hello bob",
    "messageType": "TEXT"
  }
}
```

服务端返回：

- `CONNECT_ACK`: WebSocket 鉴权成功
- `SEND_RESULT`: 服务端已接收并持久化
- `PUSH_MESSAGE`: 在线接收方收到推送
- `ERROR`: 协议或鉴权错误
- `PONG`: 心跳响应

WebSocket 只是协议适配层：

```text
Browser JSON
  -> ImWebSocketHandler
  -> SendMessageCommand
  -> MessageService
  -> ConversationService / MySQL / Redis sequence
  -> MessageDeliveryService
  -> SessionManager
  -> WebSocketClientConnection or NettyClientConnection
```

TCP 仍走：

```text
Protobuf
  -> MessageHandler
  -> SendMessageCommand
  -> MessageService
```

也就是说，TCP 和 WebSocket 最终复用同一套业务服务。

## 如何运行

启动依赖：

```text
docker compose up -d mysql redis rabbitmq
```

启动应用：

```text
mvn spring-boot:run
```

启动前端：

```text
cd frontend
npm install
npm run dev
```

浏览器打开：

```text
http://localhost:5173
```

默认端口：

- HTTP: `8080`
- Netty TCP: `9000`
- MySQL: `3307`
- Redis: `6380`
- RabbitMQ: `5672`
- RabbitMQ Management: `15672`

登录测试：

```text
Invoke-RestMethod -Method Post `
  -Uri http://localhost:8080/api/auth/login `
  -ContentType 'application/json' `
  -Body '{"username":"alice","password":"password123"}'
```

Web 聊天测试：

1. 浏览器窗口 A 登录 `alice / password123`
2. 浏览器窗口 B 或无痕窗口登录 `bob / password123`
3. Alice 选择 Bob，发送 `hello bob`
4. Bob 页面实时出现 Alice 的消息
5. Bob 回复 `hello alice`
6. Alice 页面实时收到 Bob 的消息
7. 关闭 Bob 页面后，Alice 继续发送消息；当前 Phase 3 只保证消息落库，不做离线补偿

当前 Phase 2/3 的 Netty 验证主要通过集成测试完成：测试会启动真实 Netty
客户端，完成登录、CONNECT、SEND_MESSAGE、SEND_RESULT、PUSH_MESSAGE 和
数据库断言。正式 CLI 客户端仍未提供。

## 如何测试

```text
mvn test
```

Phase 3 测试覆盖：

- Protobuf `MessageEnvelope` 序列化/反序列化
- `PING` 产生 `PONG`
- Spring Boot 上下文启动时自动启动 Netty server
- JWT 生成、校验和过期拒绝
- 登录成功和密码错误拒绝
- 未 CONNECT 先发送业务消息会被拒绝
- CONNECT 成功后绑定 `userId + deviceId` Session
- 相同设备重复登录会替换旧 Channel
- 两个用户通过真实 HTTP 登录，再通过真实 Netty 客户端 CONNECT 和心跳
- Channel 关闭后 Session 会被清理
- Alice 给 Bob 发送单聊，Bob 收到 `PUSH_MESSAGE`
- 消息写入数据库，senderId 从 Session 获取
- 重复 `clientMessageId` 只产生一条消息并返回原结果
- 两个方向并发首次发消息只创建一个 Conversation
- Conversation sequence 递增
- Bob 离线时消息仍然落库
- Bob 多设备同时在线时每个设备都收到 Push
- 未 CONNECT 的客户端无法发送消息

## 当前限制

- 登录密码当前使用 `{noop}` 种子数据，`{pbkdf2}` 校验能力已预留，后续需要提供注册或管理脚本生成安全密码
- 当前只实现单进程内存 Session，Redis route 还未实现
- Phase 3 集成测试使用 H2 MySQL mode；生产运行配置仍使用 MySQL
- Web 前端使用固定 Demo User List: Alice/Bob，不是好友系统
- 未实现接收方 ACK、消息重投、离线同步
- 未实现群聊、多端同步、Redis route、RabbitMQ 跨节点转发
- 当前 CLI 客户端尚未提供

## TODO

- Phase 4: ACK、重复发送幂等、消息重投
- Phase 5: 离线消息和 `SYNC_REQUEST`
- Phase 6: 群聊
- Phase 7: Redis route 和多节点启动
- Phase 8: RabbitMQ 跨节点转发
- Phase 9: 多端登录和独立同步位置

## 参考说明

WildfireChat 参考清单和差异说明记录在
`docs/wildfirechat-reference.md`。
