#!/usr/bin/env python3
"""Generate SQL and refresh tokens for nine isolated mixed-limit test users.

This tool never connects to the database. The operator reviews and commits SQL.
"""

import argparse
import hashlib
import json
import os
import secrets
import shutil
import uuid
from pathlib import Path


ACCOUNT_COUNT = 9
CREATOR_COUNT = 8
ID_MIN = 1_000_000_000_000
ID_SPAN = 8_000_000_000_000


def write_private(path, content):
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
        stream.write(content)


def generate(output_dir):
    output_dir = Path(output_dir)
    run_id = str(uuid.uuid4())
    user_ids = set()
    while len(user_ids) < ACCOUNT_COUNT:
        user_ids.add(ID_MIN + secrets.randbelow(ID_SPAN))

    accounts = []
    for index, user_id in enumerate(sorted(user_ids), start=1):
        token = secrets.token_urlsafe(32)
        accounts.append({
            "slot": index,
            "role": "creator" if index <= CREATOR_COUNT else "viewer",
            "userId": user_id,
            "email": f"loadtest-{run_id}-{index:02d}@yeodam.invalid",
            "sid": str(uuid.uuid4()),
            "refreshToken": token,
        })

    fixture_session = {
        "role": "viewer_fixture",
        "userId": accounts[-1]["userId"],
        "sid": str(uuid.uuid4()),
        "refreshToken": secrets.token_urlsafe(32),
    }
    sessions_to_seed = [*accounts, fixture_session]

    ids = ", ".join(str(account["userId"]) for account in accounts)
    sids = ", ".join("'" + item["sid"] + "'" for item in sessions_to_seed)
    users = ",\n".join(
        f"({item['userId']}, '{item['email']}', '부하계정{item['slot']}')"
        for item in accounts
    )
    stats = ", ".join(f"({item['userId']})" for item in accounts)
    consents = ", ".join(
        f"({item['userId']}, TRUE, CURRENT_TIMESTAMP(6))" for item in accounts
    )
    sessions = ",\n".join(
        f"('{item['sid']}', {item['userId']}, "
        f"'{hashlib.sha256(item['refreshToken'].encode()).hexdigest()}', "
        "CURRENT_TIMESTAMP(6) + INTERVAL 7 DAY)"
        for item in sessions_to_seed
    )
    identities = " OR ".join(
        f"(user_id = {item['userId']} AND email = '{item['email']}')"
        for item in accounts
    )

    seed = f"""-- Run in the production yeodam DB with SET time_zone = '+00:00'.
-- This transaction creates eight creators and one viewer, but NO OAuth identity.
-- An additional viewer session is reserved for creating a completed fixture trip.
-- Review registered_users = 9, registered_stats = 9, registered_consents = 9,
-- registered_sessions = 10 and the role/ID manifest before COMMIT in this connection.
-- On any error or mismatched count, ROLLBACK instead. Do not paste accounts.json into MySQL.
START TRANSACTION;
SELECT COUNT(*) AS conflicting_users FROM users
WHERE user_id IN ({ids}) OR email LIKE 'loadtest-{run_id}-%@yeodam.invalid';
INSERT INTO users (user_id, email, nickname) VALUES
{users};
INSERT INTO user_stats (user_id) VALUES {stats};
INSERT INTO consents (user_id, is_agreed, agreed_at) VALUES {consents};
INSERT INTO login_sessions (sid, user_id, refresh_token_hash, expires_at) VALUES
{sessions};
SELECT COUNT(*) AS registered_users FROM users WHERE {identities};
SELECT COUNT(*) AS registered_stats FROM user_stats WHERE user_id IN ({ids}) AND deleted_at IS NULL;
SELECT COUNT(*) AS registered_consents FROM consents WHERE user_id IN ({ids}) AND deleted_at IS NULL;
SELECT COUNT(*) AS registered_sessions FROM login_sessions WHERE sid IN ({sids});
-- COMMIT or ROLLBACK must be entered manually in this same MySQL connection.
"""

    cleanup = f"""-- First delete every test trip through the API, then verify its S3 objects.
-- Run in the same DB/timezone as seed.sql. Commit ONLY when safe_to_cleanup = 1,
-- removed_sessions = 10 and each soft_deleted_* count = 9. Otherwise ROLLBACK.
-- Trip/file tombstones remain because production foreign keys prevent hard deletion.
START TRANSACTION;
SET @safe_to_cleanup = (
  (SELECT COUNT(*) FROM users WHERE {identities}) = 9
  AND NOT EXISTS (SELECT 1 FROM trips WHERE user_id IN ({ids}) AND deleted_at IS NULL)
  AND NOT EXISTS (SELECT 1 FROM files WHERE user_id IN ({ids}) AND deleted_at IS NULL)
  AND NOT EXISTS (
    SELECT 1 FROM trip_attachments AS attachment
    JOIN trips AS trip ON trip.trip_id = attachment.trip_id
    WHERE trip.user_id IN ({ids}) AND attachment.deleted_at IS NULL
  )
  AND NOT EXISTS (
    SELECT 1 FROM login_sessions WHERE user_id IN ({ids}) AND sid NOT IN ({sids})
  )
);
SELECT @safe_to_cleanup AS safe_to_cleanup;
DELETE FROM login_sessions WHERE sid IN ({sids}) AND @safe_to_cleanup = 1;
SELECT ROW_COUNT() AS removed_sessions;
UPDATE consents SET deleted_at = CURRENT_TIMESTAMP(6)
WHERE user_id IN ({ids}) AND deleted_at IS NULL AND @safe_to_cleanup = 1;
SELECT ROW_COUNT() AS soft_deleted_consents;
UPDATE user_stats SET deleted_at = CURRENT_TIMESTAMP(6)
WHERE user_id IN ({ids}) AND deleted_at IS NULL AND @safe_to_cleanup = 1;
SELECT ROW_COUNT() AS soft_deleted_stats;
UPDATE users SET deleted_at = CURRENT_TIMESTAMP(6)
WHERE ({identities}) AND deleted_at IS NULL AND @safe_to_cleanup = 1;
SELECT ROW_COUNT() AS soft_deleted_users;
-- COMMIT or ROLLBACK must be entered manually in this same MySQL connection.
"""

    output_dir.mkdir(mode=0o700, parents=True, exist_ok=False)
    try:
        write_private(output_dir / "accounts.json", json.dumps(
            {"runId": run_id, "accounts": accounts,
             "viewerFixtureSession": fixture_session}, indent=2) + "\n")
        write_private(output_dir / "seed.sql", seed)
        write_private(output_dir / "cleanup.sql", cleanup)
    except OSError:
        shutil.rmtree(output_dir)
        raise
    return output_dir


def main():
    parser = argparse.ArgumentParser(description="4배수 혼합 시험용 독립 계정 9개의 SQL·토큰 생성")
    parser.add_argument("--output-dir", type=Path,
                        help="새 출력 디렉터리 (기존 경로 덮어쓰기 금지)")
    args = parser.parse_args()
    output = args.output_dir or (
        Path(__file__).resolve().parents[1] / "data" / "limit-test-auth" / str(uuid.uuid4()))
    try:
        generate(output)
    except OSError:
        parser.exit(1, "생성 실패: 출력 경로 중복 또는 파일 쓰기 권한을 확인하세요.\n")
    print("시험 계정 9개 생성 준비 완료 (운영 DB 등록은 별도):")
    for name in ("accounts.json", "seed.sql", "cleanup.sql"):
        print(output.resolve() / name)


if __name__ == "__main__":
    main()
