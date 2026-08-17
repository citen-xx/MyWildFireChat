# My Wildfire Chat

## 项目介绍

这是一个用于 Java 后端学习和简历讲解的精简 IM 系统。项目参考
WildfireChat 的长连接、会话、投递和同步设计思想，但不复制其源码、
包名、MQTT 协议或工程结构。

当前已完成 Phase 2：Spring Boot 登录接口、JWT 生成与校验、Netty
`CONNECT` 鉴权、设备级 Session 管理、`PING/PONG` 心跳和空闲连接清理。

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

Phase 2 完成连接层闭环。消息持久化、ACK、离线同步、Redis route、
RabbitMQ 跨节点转发会在后续阶段逐步完成。

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

Phase 2 已创建：

- `user_account`: 登录用户表，包含 `id`, `username`, `password_hash`, `status`
- `uk_user_account_username`: 保证用户名唯一

当前种子数据文件：`src/main/resources/db/data.sql`

- `alice / password123`, userId: `1001`
- `bob / password123`, userId: `1002`

后续 Phase 3-6 会继续加入 `conversation`, `conversation_member`, `message`,
`chat_group`, `group_member`。

`message.message_id` 会建立唯一索引，`conversation_id + sequence` 会建立
唯一索引或强约束索引，用于顺序读取和增量同步。

## Redis Key

Phase 1 只接入 Redis 配置。计划 key：

- `im:seq:{conversationId}`: conversation sequence counter
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
- `SEND_MESSAGE`, `MESSAGE_ACK`
- `SYNC_REQUEST`, `SYNC_RESPONSE`
- `ERROR`

Phase 2 已实现 payload：

- `ConnectRequest(token, deviceId)`
- `ConnectAck(userId, deviceId, serverTime)`
- `ErrorPayload(code, message)`

TCP framing 使用 4 字节 length field，避免 TCP 粘包拆包问题。

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

TODO: Phase 3 实现。

计划使用 Redis `INCR im:seq:{conversationId}` 分配单调递增 sequence。允许
sequence 空洞，因为 Redis 分配成功后 MySQL 持久化可能失败；空洞不影响
严格单调、排序和 `sequence > lastSequence` 增量同步。

## 离线同步机制

TODO: Phase 5 实现。

客户端发送 `SYNC_REQUEST(conversationId, lastSequence, limit)`，服务端查询
`sequence > lastSequence` 的消息，按 sequence 升序返回，单页最多 100 条。

## 多节点路由

TODO: Phase 7-8 实现。

单机 `SessionManager` 只保存本进程 live Channel；Redis route 保存
`userId + deviceId -> serverId` 并设置 TTL。跨节点消息通过 RabbitMQ 转发到
目标 `serverId` 所在实例。

## 如何运行

启动依赖：

```text
docker compose up -d mysql redis rabbitmq
```

启动应用：

```text
mvn spring-boot:run
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

当前 Phase 2 的 Netty 手动验证主要通过集成测试完成：测试里会启动两个真实
Netty 客户端，分别以 `alice-web` 和 `bob-web` 连接，完成 `CONNECT_ACK` 和
`PING/PONG` 验证。正式 CLI 客户端会在消息发送链路进入 Phase 3 后补齐。

## 如何测试

```text
mvn test
```

Phase 2 测试覆盖：

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

## 当前限制

- 登录密码当前使用 `{noop}` 种子数据，`{pbkdf2}` 校验能力已预留，后续需要提供注册或管理脚本生成安全密码
- 当前只实现单进程内存 Session，Redis route 还未实现
- 未实现消息持久化、ACK、重投、离线同步
- 未实现群聊、多端同步、Redis route、RabbitMQ 跨节点转发
- 当前 CLI 客户端尚未提供

## TODO

- Phase 3: 单聊、messageId 幂等、conversation sequence、MySQL 持久化
- Phase 4: ACK、重复发送幂等、消息重投
- Phase 5: 离线消息和 `SYNC_REQUEST`
- Phase 6: 群聊
- Phase 7: Redis route 和多节点启动
- Phase 8: RabbitMQ 跨节点转发
- Phase 9: 多端登录和独立同步位置

## 参考说明

WildfireChat 参考清单和差异说明记录在
`docs/wildfirechat-reference.md`。
