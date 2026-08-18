# My Wildfire Chat

## 项目介绍

这是一个用于 Java 后端学习和简历讲解的精简 IM 系统。项目参考
WildfireChat 的长连接、会话、投递和同步设计思想，但不复制其源码、
包名、MQTT 协议或工程结构。

当前已完成到 Phase 7：在单聊、ACK、离线增量同步和群聊基础上，实现
Redis 在线连接路由、Server 注册/心跳和多节点连接定位。

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

Phase 7 已能把 `userId + deviceId` 的在线位置写入 Redis，并区分本机、
远端和离线连接。RabbitMQ 跨节点转发留到 Phase 8。

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
- `chat_group`: 群资料表
- `group_member`: 群成员表

当前种子数据文件：`src/main/resources/db/data.sql`

- `alice / password123`, userId: `1001`
- `bob / password123`, userId: `1002`
- `charlie / password123`, userId: `1003`

`message` 关键约束：

- `UNIQUE(message_id)`: 服务端业务消息 ID 全局唯一
- `UNIQUE(sender_id, client_message_id)`: 客户端重试幂等
- `UNIQUE(conversation_id, sequence)`: 会话内 sequence 不重复

## Redis Key

当前使用：

- `im:seq:{conversationId}`: Conversation sequence counter，Phase 3 使用 Redis `INCR`
- `im:route:{userId}:{deviceId}`: Redis HASH，字段为 `userId`, `deviceId`,
  `serverId`, `connectionId`, `connectedAt`，带 TTL
- `im:user:devices:{userId}`: Redis SET，记录某个用户当前出现过的在线 deviceId，
  用于查找多端设备，带 TTL
- `im:server:registry`: Redis ZSET，member 为 `serverId`，score 为最近心跳时间戳
- `im:pending_ack:{userId}:{deviceId}`: Redis ZSET，member 为 `messageId`，
  score 为下一次重投时间戳
- `im:pending_ack_attempt:{userId}:{deviceId}`: Redis Hash，field 为
  `messageId`，value 为当前 retry attempt
- `im:pending_ack:index`: Redis ZSET，全局扫描索引，member 为
  `userId|base64url(deviceId)|base64url(messageId)`，score 为下一次重投时间戳

## RabbitMQ Exchange / Queue

当前 Docker Compose 已提供 RabbitMQ。Phase 8 中 RabbitMQ 只用于跨 IM
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
- `SYNC_REQUEST`, `SYNC_RESPONSE`, `SYNC_COMPLETE`
- `ERROR`

Phase 2 已实现 payload：

- `ConnectRequest(token, deviceId)`
- `ConnectAck(userId, deviceId, serverTime)`
- `ErrorPayload(code, message)`

Phase 3 已实现 payload：

- `SendMessageRequest(clientMessageId, receiverId, content, messageType)`
- `SendResult(clientMessageId, messageId, conversationId, sequence, createdAt)`
- `PushMessage(messageId, clientMessageId, conversationId, sequence, senderId, receiverId, content, messageType, createdAt)`

Phase 4 已实现 payload：

- `MessageAck(messageId, conversationId, sequence)`

Phase 5 已实现 payload：

- `SyncRequest(conversationId, lastSequence, optional limit)`
- `SyncResponse(conversationId, messages, hasMore, nextSequence)`
- `SyncComplete(conversationId, nextSequence)`

`SendResult` 只表示服务端已经成功接收并持久化，不表示接收方已经收到。
`MessageAck` 表示接收方某个 `userId + deviceId` 连接已经收到并处理了
`PUSH_MESSAGE`。

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
  -> ConnectionLocator finds receiver devices from local Session / Redis route
  -> PUSH_MESSAGE to each local Channel
  -> remote target waits for Phase 8 RabbitMQ forwarding
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

Phase 4 实现 Receiver ACK：

```text
Server
  -> PUSH_MESSAGE
  -> Receiver Client
  -> MESSAGE_ACK(messageId, conversationId, sequence)
  -> AckService removes Pending ACK
```

`SEND_RESULT` 和 `MESSAGE_ACK` 是两种不同确认：

- `SEND_RESULT`: 发送方 A 的消息已经被服务端持久化
- `MESSAGE_ACK`: 接收方 B 的某个设备已经收到并完成客户端处理

Pending ACK 是设备级别的。Bob 同时在线 `web` 和 `pc` 时，会分别记录：

```text
im:pending_ack:1002:web
im:pending_ack:1002:pc
```

`web` ACK 只删除 `web` 的 pending，不代表 `pc` 已收到。

ACK 删除天然幂等：重复 `MESSAGE_ACK(messageId)` 时，第一次删除 Pending ACK，
后续删除不到记录也视为成功。

## Message Delivery Semantics

当前在线投递语义是 At-Least-Once Delivery，不是 Exactly Once。

服务端向在线设备 Push 后写入 Redis Pending ACK。如果超时未收到 ACK，
`AckRetryScheduler` 会在独立线程中扫描 `im:pending_ack:index`，根据
`messageId` 回表读取 MySQL 消息，再推送给仍在线的同一设备。

默认重试策略：

```text
initial push
  -> 3s retry 1
  -> 3s retry 2
  -> 5s retry 3
  -> stop online retry
```

停止在线重试不代表消息丢失，消息已经在 MySQL 中。Phase 5 使用
Conversation Sequence 增量同步补偿断线和离线期间的消息。

如果设备断开连接，调度器会删除该设备的 Pending ACK，停止即时重投，等待
后续离线同步恢复。

当前 Web 前端收到 `PUSH_MESSAGE` 后，先按 `messageId` 检查本地消息列表：

- 未处理过：追加到页面消息状态
- 已处理过：不重复展示
- 两种情况都会继续发送 `MESSAGE_ACK`

当前 ACK 语义代表“当前页面进程已经接收并处理”，不是“消息已经永久写入客户端磁盘”。
后续如果引入 IndexedDB，再把 ACK 发送时机移动到本地持久化成功之后。

调度器使用独立 `ScheduledExecutorService`，不会在 Netty EventLoop 中
`sleep` 或阻塞等待 ACK。

多 Worker 抢同一条 Redis due item 的严格 Claim Lock 尚未实现；当前项目仍是
单节点在线投递。未来多节点阶段需要用 Lua 或短租约字段保证扫描任务的互斥领取。

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

Phase 5 不创建单独的“离线消息副本表”。所有消息统一保存在 `message` 表，
客户端根据每个 Conversation 的连续游标请求缺口：

```sql
SELECT ...
FROM message
WHERE conversation_id = ?
  AND sequence > ?
ORDER BY sequence ASC
LIMIT ?
```

TCP 和 WebSocket 在鉴权完成后都支持：

```text
SYNC_REQUEST
  -> SYNC_RESPONSE
  -> (hasMore = false) SYNC_COMPLETE
```

默认单页 100 条，最大 200 条。客户端请求超过 200 时服务端限制为 200，
避免一次读取无限历史。`SYNC_RESPONSE.nextSequence` 是当前页最后一条消息的
sequence；客户端用它请求下一页。没有新增消息时，`nextSequence` 保持请求中的
`lastSequence`。

服务端先校验当前 CONNECT Session 的 userId 是否属于该 Conversation，再执行
查询，不能相信客户端传入的 userId，也不会把不存在的 Conversation 与无权限
Conversation 的差异暴露给客户端。

Web 前端为每个 `userId + deviceId + conversationId` 保存游标：

```text
im:conversations:{userId}:{deviceId}
im:cursor:{userId}:{deviceId}:{conversationId}
```

游标使用 contiguous sequence，而不是简单的最大 sequence。收到 `103` 但
尚未收到 `102` 时，客户端暂存 `103`，游标仍停在 `101`；收到 `102` 后再
连续推进到 `103`。PUSH 和 SYNC 都经过 messageId 去重，并按 sequence 排序。

重连流程是：

```text
WebSocket open
  -> CONNECT
  -> CONNECT_ACK
  -> SYNC_REQUEST
  -> SYNC_RESPONSE / SYNC_COMPLETE
  -> Online
```

同步历史消息不会创建 `Pending ACK`。在线消息使用 `PUSH_MESSAGE + MESSAGE_ACK`
和有限重试；断线或离线消息使用 MySQL 持久化和 Sequence Sync。这两套机制互补：

- ACK 解决在线实时 Push 是否被某个设备接收，不能覆盖断线期间尚未 Push 的消息。
- Sequence Sync 解决断线、重启和 ACK 丢失后的消息缺口，但它是重新连接后的主动
  拉取，不能替代在线投递的低延迟确认和有限重试。

当前没有服务端 `device_conversation_cursor` 表，游标由客户端 localStorage
维护。这样实现简单、写放大较小，但清理浏览器数据会丢失游标，需要重新拉取历史。
首次打开尚未记录的 Conversation 也暂时从 sequence 0 开始，没有做“优先最近 N 条”
的历史加载优化。前端消息状态还没有 IndexedDB 持久化，因此当前 ACK 仍表示
“页面进程已接收并处理”，不代表消息已经永久写入客户端磁盘。

## 多节点路由

Phase 7 已实现 Redis 在线路由和 Server 注册心跳，但不做跨节点消息转发。

单机 `SessionManager` 只保存本进程 live Channel；Redis route 保存
`userId + deviceId -> serverId + connectionId` 并设置 TTL。连接鉴权成功后，
`ConnectionRouteService` 异步注册 route；收到 `PING` 时异步刷新 TTL；
Channel 断开时按 `connectionId` 条件删除 route。

Redis Route 数据结构：

```text
im:route:{userId}:{deviceId}
  userId       -> 1002
  deviceId     -> web
  serverId     -> im-server-2
  connectionId -> ws:...
  connectedAt  -> epoch millis

im:user:devices:{userId}
  web
  pc

im:server:registry
  ZSET member = serverId
  ZSET score  = lastHeartbeatTimestamp
```

`ServerHeartbeatScheduler` 在应用启动后定期写入 `im:server:registry`，并清理
超过 `im.server.offline-timeout-seconds` 的 serverId。优雅停机时会 best-effort
移除当前 server；进程崩溃时依靠 route TTL 和 server 心跳过期清理脏数据。

重复登录采用 Last Writer Wins：同一 `userId + deviceId` 新连接覆盖旧 route。
旧 Channel 后续断开时，Lua 脚本会比较 `connectionId`，只有 owner 匹配才删除，
避免旧连接误删新连接。`PING` 刷新同样按 owner 校验；如果 Redis key 因 TTL
消失，当前连接的下一次心跳会重新注册 route。

`ConnectionLocator` 查询某个接收方设备时会返回：

- `LOCAL`: route 指向当前 server 且本地 Session 存在，可以立即 Push
- `REMOTE`: route 指向其他 server，当前 Phase 只记录日志，Phase 8 通过 RabbitMQ 转发
- `OFFLINE`: Redis route 不存在，或者 route 指向当前 server 但本地 Session 不存在

Redis route 只是在线定位，不是消息可靠性的最终来源。消息是否丢失仍以 MySQL
持久化、ACK 和 Phase 5 Sequence Sync 共同保证。

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

开发环境前端从 `http://localhost:5173` 访问时，Vite 会把 `/ws/im` 代理到
后端 `ws://localhost:8080/ws/im`。直接连接后端时使用上面的地址。选择 Spring
WebSocket 挂在 8080，是因为当前项目已经有 Spring Boot Web。
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
- `MESSAGE_ACK`: 浏览器收到 `PUSH_MESSAGE` 后发回的接收方确认
- `SYNC_REQUEST`: CONNECT_ACK 后按 Conversation 游标请求增量消息
- `SYNC_RESPONSE`: 返回一页按 sequence 升序排列的历史消息
- `SYNC_COMPLETE`: 当前 Conversation 没有更多待同步消息
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
  -> ConnectionLocator
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

本地启动两个 IM Server 示例：

```text
# terminal 1
$env:IM_SERVER_ID="im-server-1"
$env:IM_HTTP_PORT="8080"
$env:IM_NETTY_PORT="9000"
mvn spring-boot:run

# terminal 2
$env:IM_SERVER_ID="im-server-2"
$env:IM_HTTP_PORT="8081"
$env:IM_NETTY_PORT="9002"
mvn spring-boot:run
```

前端默认连接 `http://localhost:8080`。需要连接第二个后端时，可以设置：

```text
cd frontend
$env:VITE_API_BASE_URL="http://localhost:8081"
$env:VITE_WS_URL="ws://localhost:8081/ws/im"
npm run dev
```

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
7. 关闭 Bob 页面后，Alice 继续发送消息
8. Bob 重新登录后，CONNECT_ACK 之后会自动同步已知 Conversation 的缺失消息
9. 同步中的消息按 sequence 排序，重复的 PUSH/SYNC messageId 不会重复展示

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
- TCP 接收方 `MESSAGE_ACK` 后 Pending ACK 被删除
- TCP ACK 丢失时服务端会超时重投，重投后 ACK 会停止继续重投
- 重复 ACK 不报错
- Bob 断线后停止在线重试
- `SEND_RESULT` 与接收方 `MESSAGE_ACK` 语义独立
- 同一用户多个 deviceId 独立维护 Pending ACK
- WebSocket `MESSAGE_ACK` 复用同一套 ACK 服务
- Bob 离线期间 Alice 发送消息，Bob 重连后通过 TCP `SYNC_REQUEST` 恢复
- SYNC 分页、`hasMore`、`nextSequence` 和超过最大 limit 的限制
- 非成员、非法 sequence、非法 limit 不能读取 Conversation 历史
- WebSocket `SYNC_REQUEST` 复用同一套 `SyncService`
- Redis route 注册、刷新、TTL 过期和 owner 条件删除
- Server heartbeat 注册和过期 server 清理
- CONNECT / PING / disconnect 会创建、恢复和删除 Redis route
- 同一 `userId + deviceId` 新连接不会被旧连接断开事件误删 route
- `ConnectionLocator` 可以区分本机、远端和离线连接

## 当前限制

- 登录密码当前使用 `{noop}` 种子数据，`{pbkdf2}` 校验能力已预留，后续需要提供注册或管理脚本生成安全密码
- 本机 Channel 仍由单进程 `SessionManager` 保存；Redis route 只保存跨节点在线位置
- Phase 3 集成测试使用 H2 MySQL mode；生产运行配置仍使用 MySQL
- Web 前端使用固定 Demo User List，不是好友系统
- 已实现在线接收方 ACK、有限重投、离线增量同步、群聊和 Redis route
- Phase 7 只发现远端连接，不向远端节点转发消息；RabbitMQ 跨节点转发留到 Phase 8
- 未实现服务端设备游标、多端跨设备同步进度
- 当前 CLI 客户端尚未提供
- Redis Pending ACK 的多 Worker Claim Lock 暂未实现，当前按单节点扫描器设计
- 前端自动同步的 Conversation 列表来自本地 localStorage，第一次打开未知 Conversation
  不会自动发现全部历史会话

## TODO

- Phase 8: RabbitMQ 跨节点转发
- Phase 9: 多端登录和独立同步位置

## 参考说明

WildfireChat 参考清单和差异说明记录在
`docs/wildfirechat-reference.md`。
