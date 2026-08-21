# IM TCP Load Test Client

This tool is deliberately outside the default Maven test source tree. It
compiles against the application's generated Protobuf classes and runtime
dependencies, then exercises the real TCP protocol.

Prerequisites:

1. Start MySQL, Redis, and RabbitMQ with `docker compose up -d`.
2. Start the application on the configured HTTP and TCP ports.
3. Use a Java 17+ runtime. The runner uses the Maven Java runtime for build
   and the `java` command available on `PATH` for execution.

Examples from the project root:

```powershell
.\tools\load-test\run.ps1 --mode=connections --connections=50 --duration=30 --warmup=5
.\tools\load-test\run.ps1 --mode=chat --connections=4 --senders=4 --mps=20 --duration=30 --warmup=5 --receiver-id=1002 --receiver-username=bob --receiver-password=password123 --message-size=128
```

Supported options:

- `--host`, `--port`, `--http-port`
- `--receiver-host`, `--receiver-port`, `--receiver-http-port`
- `--mode=connections|chat`
- `--connections`, `--duration`, `--warmup`
- `--senders`, `--mps`, `--message-size`
- `--receiver-id`, `--group-id`, `--conversation-type`
- `--username`, `--password`, `--device-prefix`
- `--receiver-username`, `--receiver-usernames`, `--receiver-password`, `--receiver-connections`, `--receiver-device-prefix`

For a group fan-out test, pass one username per online group member:

```powershell
.\tools\load-test\run.ps1 --mode=chat --connections=1 --senders=1 --mps=1 --duration=10 --group-id=123 --receiver-usernames=load001,load002,load003 --receiver-password=password123
```

The output is JSON containing connection, SEND_RESULT, PUSH, ACK, heartbeat,
error, and p50/p95/p99 latency counters. The tool does not create application
business data by itself other than messages sent in `chat` mode.
