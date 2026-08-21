# Failure Testing

## Scope

This file records only actual fault-injection observations from Phase 10.

## Verified Cases

### ACK race fix

Before the fix, a fast receiver ACK could arrive before the server wrote the Pending ACK entry.
That caused duplicate retries and duplicate PUSHes.

After the fix:

- Pending ACK is written before `sendPush`
- Immediate ACK removes the pending record correctly
- Repeated PUSH count stayed at 0 in the regression run

### Cross-node delivery

Verified with:

- sender server: `8080 / 9000`
- receiver server: `8081 / 9002`
- RabbitMQ relay enabled

Observed:

- message persisted on the sender node
- RabbitMQ relay event published
- receiver node delivered `PUSH_MESSAGE`
- receiver node ACK returned successfully

### Multi-device receiver

Verified with Bob online on two devices:

- each device received its own PUSH
- each device ACK was tracked independently

## Known shutdown noise before the fix

During application shutdown, the ACK retry scheduler could log a Redis `LettuceConnectionFactory has been STOPPED` warning if a scan raced with context shutdown.
This was reduced by setting a shutdown flag before stopping the scheduler.

## Not Covered

- formal chaos testing
- network packet loss injection
- RabbitMQ broker crash with live replay
- MySQL mid-transaction kill during a long benchmark
