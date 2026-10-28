# V1 인증 사용자별 조회 제한

이 변경은 Nginx 방어 시험에 쓰는 V1 이미지의 소스 `816d69ba26c31614dab371bb5867f2ba687b19d8`에만 추가한다. 기준 브랜치는 `279-be-v1-db-마이그레이션-시험-세팅`이다. V2 main으로 합치거나 DB 마이그레이션용 복제 Source에 배포하지 않는다.

같은 공인 IP에서 무인증 목록 조회 20RPS와 정상 계정 1RPS를 동시에 보내자 정상 요청 30건 중 21건이 Nginx의 IP별 조회 한도에서 거절됐다. 다른 IP의 정상 요청은 30건 모두 200이었다(Cloud #456). 이는 사용자 간 한도 공유의 근거이며, 서버 용량이나 새 IP 상한을 측정한 결과는 아니다.

## 제한 위치와 대상

기존 JWT 서명·만료·SID·활성 사용자 검증과 인가를 통과한 뒤 `UserReadRateLimitFilter`를 실행한다. 검증된 JWT subject를 양의 사용자 ID로 변환해 사용한다. IP·쿠키 문자열·SID·요청 헤더의 사용자 ID는 키가 아니다. 동일 사용자가 여러 세션을 쓰거나 토큰을 갱신해도 한도를 공유하고 서로 다른 사용자는 분리된다.

GET/HEAD API 조회를 한 사용자당 하나의 한도로 묶는다. 서버 context path `/api`를 제외한 정확한 `/actuator/health`, `/auth/csrf`, `/auth/kakao/authorize`, `/auth/kakao/callback`은 제외한다. POST/PUT/PATCH/DELETE/OPTIONS, 기존 여행 생성·사진 업로드 흐름은 바꾸지 않는다. HTML·정적 파일은 기존 FE/Nginx 경로다.

필터를 Servlet Bean으로 중복 등록하지 않고 SecurityFilterChain 안에만 등록한다. 인증되지 않은 요청은 기존 401 처리에 맡기며 사용자 한도를 생성하지 않는다. 이 변경이 인증 검증 자체의 CPU·DB 비용이나 무인증 폭주를 보호하는 것은 아니다.

## 설정

```ini
V1_USER_READ_RATE_LIMIT_ENABLED=false
V1_USER_READ_RATE_LIMIT_RPS=5
V1_USER_READ_RATE_LIMIT_BURST=30
V1_USER_READ_RATE_LIMIT_MAX_USERS=10000
```

기본 비활성이다. 새 이미지를 올리는 것만으로 제한이 켜지지 않는다. 5r/s·burst30은 기존 IP 정책의 속도·순간 허용량을 사용자별로 옮겨 비교하기 위한 초기 후보이며 사용자별 최적값이나 SLO가 아니다. 활성화 시 잘못된 수치는 기동 단계에서 실패한다.

단일 V1 BE 프로세스의 synchronized GCRA로 단조 시각을 이용한다. 5r/s이면 허용 간격은 200ms, burst30이면 누적 초과 허용 시간은 6초다. 완전히 회복한 상태에서 같은 시각에 첫 요청과 초과 30건이 허용되고 다음 요청은 429다. 거절된 요청은 회복 시간을 연장하지 않는다. 대기·재시도·요청 저장을 하지 않는다.

사용자 ID와 다음 허용 시각만 최대 10,000개 보관한다. 원본 요청·IP·JWT·SID를 보관하지 않는다. 상태 수는 서버 용량 실측값이 아니라 메모리 증가를 제한하는 설정이다. 완전히 회복한 항목만 주기적으로 또는 공간 부족 시 정리한다. 아직 회복되지 않은 사용자를 퇴출해 한도를 초기화하지 않는다. 공간이 모두 사용 중이면 새 사용자는 503으로 실패하고 기존 한도는 유지한다.

초과 응답:

```http
HTTP/1.1 429 Too Many Requests
Retry-After: 1
X-RateLimit-Scope: user-read
Cache-Control: no-store
Content-Type: application/json;charset=UTF-8

{"message":"READ_RATE_LIMIT_EXCEEDED","data":null}
```

Retry-After는 다음 한 건이 허용될 때까지의 초를 올림한 값이다. HEAD는 상태·헤더만 반환한다. 상태 저장 공간 부족은 503 / `READ_RATE_LIMIT_STORE_FULL`로 구분한다. 기존 RequestIdFilter의 X-Request-ID는 그대로 남긴다. 사용자 ID·토큰을 응답에 싣지 않는다.

## Cloud 적용 순서와 검증 경계

1. 이 V1 전용 PR의 대상은 `279-be-v1-db-마이그레이션-시험-세팅`이다. 해당 base에도 기존 Backend PR Gate가 실행되도록 범위만 추가했다. V1 코드를 최신 V2 main으로 병합하지 않는다.
2. 기존 Backend Image workflow를 이 변경 커밋에서 `rehearsal=true`로 수동 실행한다. `migration-probe-v1-sha-<commit>`과 Digest를 사용하고 운영/V2 Manifest 이벤트를 보내지 않는다.
3. Cloud가 기존 Nginx 시험 App `i-05d320e3a87200d5a`에만 이미지를 적용하고 사용자 제한 활성화를 확인한다. 기존 이미지 Digest와 환경 설정을 원복 근거로 보관한다.
4. BE 제한 확인 전에는 Nginx IP별 5r/s를 해제하지 않는다. Nginx를 교체하지 않으면 같은 IP의 정상 요청이 BE 이전에 거절돼 사용자별 분리 효과가 나타나지 않는다.
5. 보조 IP 상한은 아직 미선정이다. 이 변경에서 임의의 10/50/100r/s를 확정하지 않는다. 생성·TCP 보호와 라우팅·timeout은 유지한다.
6. 발생기를 다시 쓸 수 있을 때 같은 IP의 서로 다른 사용자, 동일 사용자 다중 SID, 무인증 요청과 다른 IP 대조군을 함께 검증한다. 로컬 동시성 테스트는 이 실측을 대신하지 않는다.

원복은 IP 보호를 먼저 복구한 뒤 사용자 제한을 끄거나 이전 V1 이미지로 돌리는 순서다. 기존 IP 제한을 제거한 상태에서 BE 보호만 먼저 끄지 않는다.

이 구현은 단일 프로세스 한도다. 재시작하면 한도가 회복되고 BE를 여러 대로 늘리면 프로세스마다 별도 한도가 생긴다. V2 다중 Task의 공통 한도로 재사용하려면 별도 공유 상태 설계가 필요하다. 이번 작업에는 Redis 추가나 V2 변경을 포함하지 않는다.

## 로컬 검증

단조 시각을 고정한 단위 시험에서 순간 허용량·200ms 회복 경계·다른 사용자 분리·동시 요청·공간 부족·회복된 항목 정리를 확인한다. 실제 SecurityConfig를 사용하는 MVC 시험에서는 서로 다른 SID의 합산, 위조 토큰·헤더의 인증 우회 불가, HEAD 응답, 쓰기·헬스 제외, 요청 ID 보존과 기본 비활성을 확인한다. MVC 시험의 JwtDecoder는 mock이므로 실제 서명 검증 회귀는 기존 CookieJwtAuthenticationIntegrationTest로 구분한다.

```bash
# 이 BE 작업 브랜치에서 Java 25로 실행
./gradlew test --tests 'com.yeodam.yeodambe.user.security.ratelimit.*' \
  --tests '*MetricsSecurityTest' --tests '*RequestIdFilterTest' \
  --tests '*CookieJwtAuthenticationIntegrationTest' \
  --tests '*AuthenticationStoreFailureIntegrationTest' \
  --tests '*TripCreationSecurityIntegrationTest' bootJar --no-daemon
```

검증 결과와 배포 상태는 Cloud 로컬 기록에 남긴다. AWS 부하 시험이나 실제 방어 성공으로 해석하지 않는다.

참고: [Spring Security 필터 구조](https://docs.spring.io/spring-security/reference/servlet/architecture.html), [Nginx limit_req](https://nginx.org/en/docs/http/ngx_http_limit_req_module.html).
