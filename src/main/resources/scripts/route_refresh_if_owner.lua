local currentConnectionId = redis.call('HGET', KEYS[1], 'connectionId')
if currentConnectionId == ARGV[1] then
    redis.call('EXPIRE', KEYS[1], ARGV[2])
    redis.call('SADD', KEYS[2], ARGV[3])
    redis.call('EXPIRE', KEYS[2], ARGV[2])
    return 1
end
if currentConnectionId == false then
    redis.call('HSET', KEYS[1],
        'userId', ARGV[6],
        'deviceId', ARGV[3],
        'serverId', ARGV[4],
        'connectionId', ARGV[1],
        'connectedAt', ARGV[5])
    redis.call('EXPIRE', KEYS[1], ARGV[2])
    redis.call('SADD', KEYS[2], ARGV[3])
    redis.call('EXPIRE', KEYS[2], ARGV[2])
    return 2
end
return 0
