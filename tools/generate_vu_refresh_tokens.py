#!/usr/bin/env python3
"""Generate seven VU refresh tokens and SQL offline; never connect to a DB."""

import argparse
import hashlib
import json
import os
import secrets
import shutil
import uuid
from pathlib import Path


def write_private(path, content):
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
        stream.write(content)


def generate(user_ids, output_dir):
    if (len(user_ids) != 7
            or any(type(user_id) is not int or not 2 <= user_id <= 2**63 - 1
                   for user_id in user_ids)
            or len(set(user_ids)) != 7):
        raise ValueError("사용자 ID는 1을 제외한 서로 다른 양의 BIGINT 7개여야 합니다.")

    tokens = [{"vu": index, "userId": user_id, "sid": str(uuid.uuid4()),
               "refreshToken": secrets.token_urlsafe(32)}
              for index, user_id in enumerate(user_ids, start=1)]
    ids = ", ".join(map(str, user_ids))
    sids = ", ".join("'" + item["sid"] + "'" for item in tokens)
    rows = "\nUNION ALL\n".join(
        "SELECT '{sid}' AS sid, {userId} AS user_id, '{hash}' AS refresh_token_hash".format(
            **item, hash=hashlib.sha256(item["refreshToken"].encode("utf-8")).hexdigest())
        for item in tokens
    )
    seed = f"""-- Confirm the DB session timezone matches the application before running.
-- In the SAME interactive connection, commit only after 7 rows are verified.
-- On any SQL error or registered_sessions != 7, roll back.
START TRANSACTION;
SELECT user_id, email, deleted_at FROM users
WHERE user_id IN ({ids}) ORDER BY user_id FOR UPDATE;
INSERT INTO login_sessions (sid, user_id, refresh_token_hash, expires_at)
SELECT seed.sid, seed.user_id, seed.refresh_token_hash,
       CURRENT_TIMESTAMP(6) + INTERVAL 7 DAY
FROM (
{rows}
) AS seed
JOIN users AS owner ON owner.user_id = seed.user_id AND owner.deleted_at IS NULL
CROSS JOIN (
    SELECT COUNT(*) AS active_count FROM users
    WHERE user_id IN ({ids}) AND deleted_at IS NULL
) AS valid_users
WHERE valid_users.active_count = 7;
SELECT COUNT(*) AS registered_sessions FROM login_sessions WHERE sid IN ({sids});
SELECT sid, user_id, expires_at FROM login_sessions WHERE sid IN ({sids}) ORDER BY user_id;
"""
    cleanup = f"""-- Only remove this run's sessions, including tokens already rotated.
-- Review removed_sessions, then commit or roll back in the SAME connection.
START TRANSACTION;
DELETE FROM login_sessions WHERE sid IN ({sids});
SELECT ROW_COUNT() AS removed_sessions;
"""
    # Redis V2 output is separate: preserve the existing RDB baseline SQL artifacts.
    redis_rows = ",\n".join(
        "{sid='%s', userId='%s', hash='%s'}" % (
            item["sid"], item["userId"], hashlib.sha256(item["refreshToken"].encode()).hexdigest())
        for item in tokens
    )
    redis_seed = """-- Verify all seven active users in MySQL before applying this file.
-- redis-cli --eval seed.redis.lua , <auth.session.key-prefix>
local prefix = ARGV[1]
if not prefix or prefix == '' then return redis.error_reply('Missing namespace') end
local items = {
""" + redis_rows + """
}
-- Validate the entire batch before writing any session.
for _, item in ipairs(items) do
    if redis.call('EXISTS', prefix .. 'session:' .. item.sid, prefix .. 'refresh:' .. item.hash) > 0 then
        return redis.error_reply('Session collision')
    end
    local kind = redis.call('TYPE', prefix .. 'user-sessions:' .. item.userId).ok
    if kind ~= 'none' and kind ~= 'zset' then return redis.error_reply('Invalid index type') end
end
local time = redis.call('TIME')
local now = time[1] * 1000 + math.floor(time[2] / 1000)
local ttl = 604800000
for _, item in ipairs(items) do
    local session = prefix .. 'session:' .. item.sid
    local index = prefix .. 'user-sessions:' .. item.userId
    redis.call('HSET', session, 'userId', item.userId, 'refreshTokenHash', item.hash, 'expiresAt', now + ttl)
    redis.call('PEXPIRE', session, ttl)
    redis.call('SET', prefix .. 'refresh:' .. item.hash, item.sid, 'PX', ttl)
    redis.call('ZREMRANGEBYSCORE', index, '-inf', now)
    redis.call('ZADD', index, now + ttl, item.sid)
    redis.call('PEXPIRE', index, ttl)
end
return #items
"""
    redis_cleanup = """-- Removes only this run's sessions, including rotated tokens.
-- redis-cli --eval cleanup.redis.lua , <auth.session.key-prefix>
local prefix = ARGV[1]
if not prefix or prefix == '' then return redis.error_reply('Missing namespace') end
local items = {
""" + redis_rows + """
}
for _, item in ipairs(items) do
    local kind = redis.call('TYPE', prefix .. 'session:' .. item.sid).ok
    if kind ~= 'none' and kind ~= 'hash' then return redis.error_reply('Invalid session type') end
    local indexKind = redis.call('TYPE', prefix .. 'user-sessions:' .. item.userId).ok
    if indexKind ~= 'none' and indexKind ~= 'zset' then return redis.error_reply('Invalid index type') end
end
local count = 0
for _, item in ipairs(items) do
    local session = prefix .. 'session:' .. item.sid
    if redis.call('HGET', session, 'userId') == item.userId then
        local hash = redis.call('HGET', session, 'refreshTokenHash')
        if hash then redis.call('DEL', prefix .. 'refresh:' .. hash) end
        count = count + redis.call('DEL', session)
        local index = prefix .. 'user-sessions:' .. item.userId
        redis.call('ZREM', index, item.sid)
        if redis.call('ZCARD', index) == 0 then redis.call('DEL', index) end
    end
end
return count
"""
    output_dir = Path(output_dir)
    output_dir.mkdir(mode=0o700, parents=True, exist_ok=False)
    try:
        write_private(output_dir / "tokens.json", json.dumps(
            {"runId": output_dir.name, "tokens": tokens}, indent=2) + "\n")
        write_private(output_dir / "seed.sql", seed)
        write_private(output_dir / "cleanup.sql", cleanup)
        write_private(output_dir / "seed.redis.lua", redis_seed)
        write_private(output_dir / "cleanup.redis.lua", redis_cleanup)
    except OSError:
        shutil.rmtree(output_dir)
        raise
    return output_dir


def main():
    parser = argparse.ArgumentParser(description="기존 테스트 사용자 7명의 VU 토큰·SQL 생성")
    parser.add_argument("--user-ids", type=int, nargs=7, required=True)
    parser.add_argument("--output-dir", type=Path, help="새 출력 디렉터리 (기존 경로 덮어쓰기 금지)")
    args = parser.parse_args()
    output = args.output_dir or (
        Path(__file__).resolve().parents[1] / "data" / "vu-auth" / str(uuid.uuid4()))
    try:
        generate(args.user_ids, output)
    except ValueError as error:
        parser.error(str(error))
    except OSError:
        parser.exit(1, "생성 실패: 출력 경로 중복 또는 파일 쓰기 권한을 확인하세요.\n")
    print("VU 토큰 7개 생성 완료 (DB 등록은 별도):")
    for name in ("tokens.json", "seed.sql", "cleanup.sql", "seed.redis.lua", "cleanup.redis.lua"):
        print(output.resolve() / name)


if __name__ == "__main__":
    main()
