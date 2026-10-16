local owner = redis.call('HGET', KEYS[2], ARGV[1])
if not owner or owner ~= ARGV[2] then
    return 0
end

local time = redis.call('TIME')
local now = time[1] * 1000 + math.floor(time[2] / 1000)
local leaseUntil = redis.call('ZSCORE', KEYS[1], ARGV[1])

if not leaseUntil or tonumber(leaseUntil) <= now then
    return 0
end

redis.call('ZREM', KEYS[1], ARGV[1])
redis.call('HDEL', KEYS[2], ARGV[1])

return 1
