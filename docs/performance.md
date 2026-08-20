# Performance Baseline

## Environment

- OS: Windows 11 Home 10.0.22631
- CPU: 20 cores / 28 logical processors
- Memory: 15.78 GB
- Maven JDK: Java 21.0.8
- Maven: 3.9.11
- Docker: 29.2.1
- MySQL: 8.4.11
- Redis: 7.4.10
- RabbitMQ: 4.0.9
- HTTP port: 8080
- Netty TCP port: 9000
- Receiver server for cross-node test: HTTP 8081 / TCP 9002

## Method

- Load client: `tools/load-test/LoadTestClient.java`
- Run script: `tools/load-test/run.ps1`
- Metrics: connection latency, SEND_RESULT latency, PUSH latency, ACK count, duplicates

## Single-Node Connections

| Connections | Success | p50 | p95 | p99 | Max |
| --- | ---: | ---: | ---: | ---: | ---: |
| 10 | 10 | 4.99 ms | 159.55 ms | 159.55 ms | 159.55 ms |
| 50 | 50 | 4.59 ms | 5.82 ms | 147.56 ms | 147.56 ms |
| 100 | 100 | 3.46 ms | 4.95 ms | 6.20 ms | 168.41 ms |

## Single-Node Chat

32 B payload:

| MPS | Sent | SEND_RESULT p50 | SEND_RESULT p95 | PUSH p50 | PUSH p95 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 5 | 50 | 24.23 ms | 36.19 ms | 33.28 ms | 46.26 ms |
| 20 | 200 | 20.34 ms | 26.65 ms | 28.30 ms | 35.79 ms |
| 50 | 499 | 17.54 ms | 25.16 ms | 24.74 ms | 35.40 ms |

Payload size sensitivity at 20 MPS:

| Size | SEND_RESULT p50 | SEND_RESULT p95 | PUSH p50 | PUSH p95 |
| --- | ---: | ---: | ---: | ---: |
| 256 B | 19.74 ms | 26.73 ms | 28.06 ms | 36.45 ms |
| 1024 B | 21.12 ms | 42.55 ms | 30.37 ms | 57.73 ms |

## Group Fan-out

| Members | Messages | PUSH count | PUSH p50 | PUSH p95 | PUSH p99 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 5 | 5 | 25 | 43.70 ms | 62.82 ms | 62.82 ms |
| 20 | 5 | 100 | 159.22 ms | 242.36 ms | 290.85 ms |
| 50 | 4 | 200 | 217.72 ms | 365.84 ms | 408.00 ms |
| 100 | 3 | 300 | 382.84 ms | 680.27 ms | 718.81 ms |

## Cross-Node Chat

| Sender Server | Receiver Server | Sent | SEND_RESULT p50 | PUSH p50 | PUSH p95 |
| --- | --- | ---: | ---: | ---: | ---: |
| 8080 / 9000 | 8081 / 9002 | 10 | 38.18 ms | 80.32 ms | 284.76 ms |

## Notes

- Group fan-out cost grows roughly linearly with member count because each active member gets an individual delivery path.
- The 100-member group test is still interactive on this machine, but p95/p99 is materially worse than direct chat.
- Redis route and RabbitMQ add latency in the cross-node path, but the message still arrives and ACKs correctly.
- All data above is from real runs on this machine.
