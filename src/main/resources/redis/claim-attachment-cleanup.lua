local leaseMillis = tonumber(ARGV[2])
if not leaseMillis or leaseMillis <= 0 or not ARGV[1] or ARGV[1] == '' then
    return redis.error_reply('INVALID_CLEANUP_CLAIM_ARGUMENT')
end

for index, expected in ipairs({'zset', 'zset', 'hash'}) do
    local actual = redis.call('TYPE', KEYS[index]).ok
    if actual ~= 'none' and actual ~= expected then
        return redis.error_reply('INVALID_CLEANUP_KEY_TYPE')
    end
end

local time = redis.call('TIME')
local now = time[1] * 1000 + math.floor(time[2] / 1000)
local leaseUntil = now + leaseMillis
local expired = redis.call('ZRANGEBYSCORE', KEYS[2], '-inf', now, 'LIMIT', 0, 1)
local attachmentId = expired[1]
local fromScheduled = false

if not attachmentId then
    local candidates = redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', now, 'LIMIT', 0, 100)
    for _, candidate in ipairs(candidates) do
        if not redis.call('ZSCORE', KEYS[2], candidate) then
            attachmentId = candidate
            fromScheduled = true
            break
        end
    end
end

if not attachmentId then
    return nil
end

redis.call('ZADD', KEYS[2], leaseUntil, attachmentId)
redis.call('HSET', KEYS[3], attachmentId, ARGV[1])
if fromScheduled then
    redis.call('ZREM', KEYS[1], attachmentId)
end

return attachmentId