# WildfireChat Reference Notes

This project does not copy WildfireChat source files, packages, generated protocol
classes, or MQTT behavior. The reference repository is read locally at:

`D:\code6\WIldfire\WildFireChat\im-server`

## Relevant reference files

| Reference file | Main class | What it demonstrates | Difference in this project |
| --- | --- | --- | --- |
| `broker/src/main/java/io/moquette/server/netty/NettyAcceptor.java` | `NettyAcceptor` | Creates boss/worker event loops, binds the socket, and assembles the Netty pipeline. | `NettyServer` is a Spring `SmartLifecycle`; it uses NIO plus a length-prefixed Protobuf envelope instead of MQTT codecs. |
| `broker/src/main/java/io/moquette/server/netty/NettyMQTTHandler.java` | `NettyMQTTHandler` | Dispatches decoded protocol messages by message type and reacts to inactive channels. | `MessageHandler` will dispatch our small business protocol in later phases; no MQTT topic or broker semantics are reused. |
| `broker/src/main/java/io/moquette/server/netty/MoquetteIdleTimeoutHandler.java` | `MoquetteIdleTimeoutHandler` | Closes an idle TCP channel and triggers connection cleanup. | `HeartbeatHandler` closes reader-idle channels and responds to our `PING` with `PONG`. |
| `broker/src/main/java/io/moquette/spi/impl/ProtocolProcessor.java` | `ProtocolProcessor` | Coordinates CONNECT validation, duplicate client handling, session creation, ACKs, and delivery. | Later phases split this into `AuthService`, `SessionManager`, `MessageService`, and focused handlers. |
| `broker/src/main/java/io/moquette/server/ConnectionDescriptorStore.java` | `ConnectionDescriptorStore` | Uses a concurrent map to route a client identifier to a live channel. | Later phases use a `(userId, deviceId)` key and Redis route records for cross-node lookup. |
| `broker/src/main/java/io/moquette/persistence/MemorySessionStore.java` | `MemorySessionStore` | Maintains client sessions, user-to-session indexes, queues, and in-flight messages. | Later phases use explicit device sessions and Redis-backed ACK state; the in-process map only owns live Channels. |
| `broker/src/main/java/io/moquette/spi/ClientSession.java` | `ClientSession` | Separates outbound/inbound in-flight messages and pending queues. | Our ACK manager will track `messageId` per device, with at-least-once redelivery semantics. |
| `broker/src/main/java/io/moquette/spi/impl/PersistentQueueMessageSender.java` | `PersistentQueueMessageSender` | Sends a message to a connected channel and preserves delivery-oriented flow. | Our message is persisted in MySQL before push; RabbitMQ is reserved for cross-node forwarding. |
| `broker/src/main/java/io/moquette/imhandler/SendMessageHandler.java` | `SendMessageHandler` | Validates and persists outgoing messages before publishing to recipients. | `MessageService` will implement only single chat first, with database uniqueness on `message_id`. |
| `broker/src/main/java/io/moquette/imhandler/PullMessageHandler.java` | `PullMessageHandler` | Pulls queued messages for a client session. | `SYNC_REQUEST` will query `conversation_id + sequence` in ascending order with a fixed page size. |
| `broker/src/main/java/io/moquette/imhandler/LoadRemoteMessagesHandler.java` | `LoadRemoteMessagesHandler` | Loads historical messages with count and cursor-like parameters. | Our first sync API has an explicit `lastSequence` cursor and maximum page size of 100. |
| `broker/src/main/java/io/moquette/imhandler/RouteHandler.java` | `RouteHandler` | Resolves server routing information associated with a client session. | Later phases store `im:route:{userId}:{deviceId}` in Redis with a heartbeat TTL. |
| `broker/src/main/java/io/moquette/imhandler/DisconnectHandler.java` | `DisconnectHandler` | Explicitly clears or disables a client session. | Our disconnect path will remove the exact device session and its Redis route. |
| `broker/migrate/mysql/V2__create_table.sql` | SQL schema | Shows durable message, user, group, member, and user-session persistence plus uniqueness/indexing. | Our schema uses clear domain names and a conversation-level sequence index, without the original sharded user-message tables. |

## License and reuse boundary

The reference checkout contains Apache License 2.0 and Eclipse Public License
1.0 notices for its upstream modules, and also contains a project-level
`LICENSE` file. This project does not reuse any reference implementation file,
so no WildfireChat source license is being incorporated into the implementation.
Only general architecture observations are documented above.

## Phase 2 applied notes

- WildfireChat uses Netty pipeline handlers to decode protocol frames, process
  CONNECT-like state transitions, and clean resources on channel inactivity.
  This project keeps that idea, but uses a self-defined length-prefixed Protobuf
  `MessageEnvelope` instead of the original MQTT protocol and classes.
- WildfireChat keeps connection descriptors in concurrent structures and handles
  duplicate client connection replacement. This project uses
  `SessionKey(userId, deviceId)` with two maps: one for live channel lookup and
  one for channel-to-session cleanup. The code is newly written and scoped to
  device-level sessions only.
- WildfireChat closes idle channels through a Netty idle timeout handler. This
  project uses Netty `IdleStateHandler` plus a small `HeartbeatHandler` that
  responds to `PING` with `PONG` and closes reader-idle channels.

## Phase 3 applied notes

- WildfireChat's `SendMessageHandler` demonstrates the idea that inbound send
  requests should be validated, persisted, and then dispatched to recipients.
  This project keeps that flow but implements a new `SendMessageRequest`,
  `MessageService`, schema, and persistence model without copying its handler
  code or MQTT packet types.
- WildfireChat's schema shows durable message storage and uniqueness/indexing
  concerns. This project defines its own `conversation`, `conversation_member`,
  and `message` tables with `UNIQUE(biz_key)`,
  `UNIQUE(sender_id, client_message_id)`, and
  `UNIQUE(conversation_id, sequence)`.
- WildfireChat separates connection/session lookup from message processing.
  This project keeps the same responsibility split: `MessageHandler` parses the
  protocol, `MessageService` owns persistence and idempotency, and
  `MessageDeliveryService` pushes to local online device channels.
