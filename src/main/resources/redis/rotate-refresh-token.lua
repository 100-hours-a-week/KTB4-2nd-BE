local sid = redis.call('GET', KEYS[1])
if not sid or sid ~= ARGV[3] then
    return 0
end

local fields = redis.call(
    'HMGET', KEYS[3], 'userId', 'refreshTokenHash'
)

if not fields[1] and not fields[2] then
    return 0
end

if not fields[1] or not fields[2] then
    return redis.error_reply('SESSION_DATA_INCOMPLETE')
end

if fields[1] ~= ARGV[4] or fields[2] ~= ARGV[1] then
    return 0
end

local indexType = redis.call('TYPE', KEYS[4]).ok
if indexType ~= 'none' and indexType ~= 'zset' then
    return redis.error_reply('USER_SESSION_INDEX_INVALID')
end

if redis.call('EXISTS', KEYS[2]) == 1 then
    return redis.error_reply('NEW_REFRESH_INDEX_EXISTS')
end

local time = redis.call('TIME')
local now = time[1] * 1000 + math.floor(time[2] / 1000)
local expiresAt = now + tonumber(ARGV[5])
local expiration = string.format('%.0f', expiresAt)

redis.call(
    'HSET', KEYS[3],
    'refreshTokenHash', ARGV[2],
    'expiresAt', expiration
)
redis.call('PEXPIREAT', KEYS[3], expiration)

redis.call('DEL', KEYS[1])
redis.call('SET', KEYS[2], ARGV[3], 'PXAT', expiration)

redis.call('ZREMRANGEBYSCORE', KEYS[4], '-inf', now)
redis.call('ZADD', KEYS[4], expiration, ARGV[3])
redis.call('PEXPIREAT', KEYS[4], expiration)

return 1