# My Wildfire Chat

## 项目介绍

这是一个用于 Java 后端学习和简历讲解的精简 IM 系统。项目参考
WildfireChat 的长连接、会话、投递和同步设计思想，但不复制其源码、
包名、MQTT 协议或工程结构。

当前是 Phase 1：完成模块化单体 Spring Boot 工程初始化，并让 Spring
Boot 和 Netty 在同一进程内通过 Spring 生命周期启动。

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

Phase 1 只完成启动骨架、协议外壳和心跳占位链路。认证、消息持久化、
ACK、离线同步、Redis route、RabbitMQ 跨节点转发会在后续阶段逐步完成。

## 模块说明

```text
com.example.im
├── auth              # Phase 2: login, JWT, CONNECT auth
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

Phase 1 只接入 MySQL datasource 和 MyBatis-Plus。业务 schema 会在 Phase 3
前落地，至少包含 `user`, `conversation`, `conversation_member`, `message`,
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

TCP framing 使用 4 字节 length field，避免 TCP 粘包拆包问题。

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
- MySQL: `3306`
- Redis: `6379`
- RabbitMQ: `5672`
- RabbitMQ Management: `15672`

## 如何测试

```text
mvn test
```

Phase 1 测试覆盖：

- Protobuf `MessageEnvelope` 序列化/反序列化
- `PING` 产生 `PONG`
- Spring Boot 上下文启动时自动启动 Netty server

## 当前限制

- 未实现登录、JWT 和 CONNECT 鉴权
- 未实现用户、会话和消息表
- 未实现消息持久化、ACK、重投、离线同步
- 未实现群聊、多端同步、Redis route、RabbitMQ 跨节点转发
- 当前 CLI 客户端尚未提供

## TODO

- Phase 2: 用户登录、JWT、CONNECT 鉴权、SessionManager、PING/PONG
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
