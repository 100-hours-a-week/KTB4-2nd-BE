# V1 혼합 한계 시험용 독립 계정

`generate_limit_test_accounts.py`는 4배수 시험에 필요한 생성 사용자 8명과 조회 사용자 1명의 SQL·Refresh Token을 **로컬에서만** 만든다. 조회 여행을 준비할 때 쓸 별도 세션도 발급한다. 카카오 계정이나 운영 사용자의 세션을 사용하지 않으며, DB 연결·등록·Commit은 이 도구가 하지 않는다. 생성 사용자는 여담의 일반 가입 과정과 달리 OAuth 연결 정보가 없으므로 카카오 로그인 자체를 검증하는 데 사용하지 않는다.

## 생성과 DB 등록

Mac의 BE 저장소에서 실행한다. 결과 디렉터리와 파일 권한은 각각 `0700`, `0600`이며 `/data/` 아래 내용은 Git에서 제외된다. 출력된 경로를 `AUTH_DIR`에 지정해 같은 터미널에서 사용한다.

```bash
cd /Users/lee-y.ch/Desktop/yeodam/KTB4-2nd-BE
python3 tools/generate_limit_test_accounts.py
AUTH_DIR='/출력된/절대/경로'
jq -r '.accounts[] | [.slot,.role,.email] | @tsv' "$AUTH_DIR/accounts.json"
pbcopy < "$AUTH_DIR/seed.sql"
```

`accounts.json`은 1~8번 생성 계정과 9번 조회 계정, 별도 `viewerFixtureSession`을 담는다. 사용자 ID는 운영 DB가 자동 발급하며 등록 후 SQL의 `user_id, email` 조회 결과로 확인한다. 토큰 값을 화면·채팅·Shell History에 출력하거나 S3에 올리지 않는다. `seed.sql`에는 Token 원문이 아닌 SHA-256 Hash만 있다.

```bash
aws sts get-caller-identity --profile yeodam-admin
aws ssm start-session --profile yeodam-admin --region ap-northeast-2 \
  --target i-051aaa0e3462d2da7
```

SSM 안에서 `sudo mysql --protocol=socket --user=root yeodam`으로 접속한다. `SET time_zone = '+00:00';`을 입력하고 클립보드의 `seed.sql` 전체를 붙여 넣는다. `conflicting_users=0`, 등록된 사용자·통계·동의가 각각 9, 세션이 10이며 SQL 오류가 없는지 확인한 뒤 **같은 MySQL 접속에서만** `COMMIT;`을 입력한다. 값이 다르거나 오류가 있으면 `ROLLBACK;`한다. 출력된 사용자 ID와 이메일의 대응을 기록한다.

## 조회 여행 준비와 4배수 실행

신규 조회 계정에는 완료된 여행이 없으므로 4배수 실행 전에 별도 Fixture 세션으로 150장 여행 한 건을 완료해야 한다. Fixture 세션은 본시험 조회 세션과 SID가 달라서, Fixture를 만드는 동안 Refresh Token이 회전해도 `accounts[8]`의 조회 Token은 영향을 받지 않는다. Mac에서 다음 값들을 한 번에 하나씩 클립보드로 복사하고, 발생기 EC2에서 `read -r -s`로 입력한다.

```bash
jq -r '.viewerFixtureSession.refreshToken' "$AUTH_DIR/accounts.json" | pbcopy
# Fixture 입력을 마친 뒤 본시험용 9개 Token을 복사한다.
jq -r '[.accounts[].refreshToken] | join(",")' "$AUTH_DIR/accounts.json" | pbcopy
```

Cloud 저장소의 `tests/load/scenarios/upload-baseline.js`로 Fixture를 만들고 `run_completed`의 `tripId`를 기록한다. 본시험은 `K6_LOAD_MULTIPLIER=4`, `K6_LIMIT_DURATION=30m`, 생성 Token 8개, 조회 Token 1개, `K6_VIEW_TRIP_IDS=<Fixture tripId>`로 `mixed-limit.js`를 실행한다. 먼저 `account-assignment-check.js`가 통과해야 한다. 30분간 목표 유입은 생성 8건·조회 22건이며 마지막 생성 완료까지 실행 시간이 더 걸릴 수 있다. 상세 실행 명령과 중단 기준은 Cloud 부하 테스트 운영 문서를 따른다.

## 시험 후 정리

결과 파일을 보존한 다음 Fixture와 본시험 생성 여행을 **해당 계정으로 API 삭제**한다. 모든 여행의 DB 삭제 상태를 확인하고, 이 여행들만 참조한 S3 객체·버전을 정확한 Key로 정리한다. DB에는 FK로 연결된 소프트 삭제 기록이 남는다. 여행·S3 정리가 끝나기 전에는 시험 사용자를 비활성화하지 않는다.

Mac에서 `pbcopy < "$AUTH_DIR/cleanup.sql"` 후 같은 App EC2의 MySQL에 접속해 `SET time_zone = '+00:00';`을 입력하고 붙여 넣는다. `safe_to_cleanup=1`, `removed_sessions=10`, 세 `soft_deleted_*`가 모두 9이고 SQL 오류가 없을 때에만 같은 접속에서 `COMMIT;`한다. 하나라도 다르면 `ROLLBACK;`하고 남은 여행·파일·세션을 조사한다. 정리 후 로컬 `accounts.json`, `seed.sql`, `cleanup.sql`이 든 **해당 출력 디렉터리만** 삭제한다. 이를 실행하면 세션은 즉시 무효가 되며, S3 객체 버전 삭제는 되돌릴 수 없다.
