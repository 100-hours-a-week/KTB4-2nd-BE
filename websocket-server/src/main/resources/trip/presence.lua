local operation = ARGV[1]
local connectionKey = ARGV[2]
local supplied = cjson.decode(ARGV[3])
local ttl = tonumber(ARGV[4])
local limit = tonumber(ARGV[5])
local epoch = redis.call('HGET', KEYS[3], 'epoch')
if operation == 'leave' and not epoch then
    return '{"changed":false,"snapshot":{"tripId":' .. ARGV[7]
        .. ',"epoch":"' .. ARGV[6] .. '","revision":0,"members":[]}}'
end
local revision = tonumber(redis.call('HGET', KEYS[3], 'revision') or '0')
local previous = redis.call('HGET', KEYS[3], 'members') or '[]'
local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
-- Heartbeats neither aggregate users nor rewrite unrelated connections.
if operation == 'heartbeat' then
    local encoded = epoch and redis.call('HGET', KEYS[1], connectionKey)
    local current = encoded and cjson.decode(encoded)
    if not current or current.deadline <= now then
        return cjson.encode({error = 'PRESENCE_NOT_JOINED'})
    end
    current.heartbeatDeadline = now + ttl
    current.deadline = math.min(current.heartbeatDeadline, current.accessValidUntil, current.tokenExpiresAt)
    redis.call('HSET', KEYS[1], connectionKey, cjson.encode(current))
    redis.call('ZADD', KEYS[2], current.deadline, connectionKey)
    return '{"changed":false,"snapshot":{"tripId":' .. ARGV[7]
        .. ',"epoch":"' .. epoch .. '","revision":' .. revision .. ',"members":' .. previous .. '}}'
end
local connections = {}
local count = 0
if epoch then
    local stored = redis.call('HGETALL', KEYS[1])
    for i = 1, #stored, 2 do
        local entry = cjson.decode(stored[i + 1])
        if entry.deadline > now then
            connections[stored[i]] = entry
            count = count + 1
        end
    end
else
    epoch = ARGV[6]
end
local current = connections[connectionKey]
if operation == 'join' or operation == 'refresh' then
    if supplied.accessValidUntil <= now or supplied.tokenExpiresAt <= now then
        return cjson.encode({error = 'UNAUTHORIZED'})
    end
    if operation == 'join' or current then
        if not current and count >= limit then
            return cjson.encode({error = 'PRESENCE_LIMIT_EXCEEDED'})
        end
        if not current or supplied.checkedAt >= current.checkedAt then
            supplied.heartbeatDeadline = current and current.heartbeatDeadline or now + ttl
            if operation == 'join' then
                supplied.heartbeatDeadline = now + ttl
            end
            supplied.deadline = math.min(supplied.heartbeatDeadline,
                supplied.accessValidUntil, supplied.tokenExpiresAt)
            connections[connectionKey] = supplied
        end
    end
elseif operation == 'leave' then
    connections[connectionKey] = nil
end
local users = {}
for key, entry in pairs(connections) do
    local selected = users[entry.userId]
    if not selected or entry.checkedAt > selected.checkedAt
        or (entry.checkedAt == selected.checkedAt and key < selected.connectionKey) then
        entry.connectionKey = key
        users[entry.userId] = entry
    end
end
local ordered = {}
for _, entry in pairs(users) do
    table.insert(ordered, entry)
end
table.sort(ordered, function(a, b)
    if #a.userId == #b.userId then
        return a.userId < b.userId
    end
    return #a.userId < #b.userId
end)
local members = {}
for _, entry in ipairs(ordered) do
    table.insert(members, {userId = entry.userId, nickname = entry.nickname,
        profileImageUrl = entry.profileImageUrl, permission = entry.permission})
end
local encodedMembers = #members == 0 and '[]' or cjson.encode(members)
local changed = encodedMembers ~= previous
if changed then
    revision = revision + 1
end
local snapshot = {tripId = ARGV[7], epoch = epoch, revision = revision, members = members}
local result = cjson.encode({changed = changed, snapshot = snapshot})
if #members == 0 then
    result = string.gsub(result, '"members":{}', '"members":[]')
end
if #result > 60000 then
    return cjson.encode({error = 'PRESENCE_LIMIT_EXCEEDED'})
end
redis.call('DEL', KEYS[1], KEYS[2])
local remaining = 0
for key, entry in pairs(connections) do
    redis.call('HSET', KEYS[1], key, cjson.encode(entry))
    redis.call('ZADD', KEYS[2], entry.deadline, key)
    remaining = remaining + 1
end
if remaining == 0 then
    redis.call('SREM', KEYS[4], ARGV[7])
else
    redis.call('SADD', KEYS[4], ARGV[7])
end
redis.call('HSET', KEYS[3], 'epoch', epoch, 'revision', revision, 'members', encodedMembers)
return result
