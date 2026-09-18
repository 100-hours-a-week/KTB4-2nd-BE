local indexedSid = redis.call('GET', KEYS[1])
if indexedSid ~= ARGV[1] then
    return nil
end

local previousJson = redis.call('GET', KEYS[2])
if not previousJson then
    return nil
end

local previous = cjson.decode(previousJson)
if previous.refreshTokenHash ~= ARGV[2] then
    return nil
end

if redis.call('EXISTS', KEYS[3]) == 1 then
    return redis.error_reply('NEW_REFRESH_INDEX_EXISTS')
end

redis.call('SET', KEYS[2], ARGV[3], 'EX', ARGV[4])
redis.call('DEL', KEYS[1])
redis.call('SET', KEYS[3], ARGV[1], 'EX', ARGV[4])

return 1