local currentConnectionId = redis.call('HGET', KEYS[1], 'connectionId')
if currentConnectionId == ARGV[1] then
    redis.call('DEL', KEYS[1])
    redis.call('SREM', KEYS[2], ARGV[2])
    return 1
end
return 0
