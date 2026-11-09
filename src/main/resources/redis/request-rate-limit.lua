redis.replicate_commands()

local capacity = tonumber(ARGV[1])
local refill_per_second = tonumber(ARGV[2])
local ttl_millis = tonumber(ARGV[3])
local scale = 1000

if not capacity or capacity < 1 or not refill_per_second or refill_per_second < 1
        or not ttl_millis or ttl_millis < 1 then
    return redis.error_reply('INVALID_REQUEST_RATE_LIMIT_ARGUMENT')
end

local capacity_milli_tokens = capacity * scale
local time = redis.call('TIME')
local now_millis = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
local stored_tokens = redis.call('HGET', KEYS[1], 'tokens_milli')
local stored_at = redis.call('HGET', KEYS[1], 'updated_at_millis')
local tokens
local updated_at_millis

if not stored_tokens and not stored_at then
    tokens = capacity_milli_tokens
    updated_at_millis = now_millis
else
    tokens = tonumber(stored_tokens)
    updated_at_millis = tonumber(stored_at)
    if not tokens or not updated_at_millis then
        return redis.error_reply('INVALID_REQUEST_RATE_LIMIT_STATE')
    end
end

local effective_now = math.max(now_millis, updated_at_millis)
local elapsed_millis = effective_now - updated_at_millis
tokens = math.min(capacity_milli_tokens, tokens + elapsed_millis * refill_per_second)

local result
if tokens >= scale then
    tokens = tokens - scale
    result = 1
else
    result = -math.max(1, math.ceil((scale - tokens) / (refill_per_second * scale)))
end

redis.call('HSET', KEYS[1], 'tokens_milli', tokens, 'updated_at_millis', effective_now)
redis.call('PEXPIRE', KEYS[1], ttl_millis)
return result
