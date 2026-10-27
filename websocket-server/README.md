# 여담 WebSocket 전용 앱

기존 BE와 같은 저장소에서 별도로 빌드하고 실행하는 Spring Boot 앱이다. Java 25를 사용하며 기존 BE의 DB·S3·Kafka 설정 없이 실행한다.

현재 범위는 독립 실행·Docker 이미지 구성과 BE 내부 인증 API 호출 클라이언트다. WebSocket handshake, STOMP destination, 연결 인증·세션 관리, Redis Pub/Sub는 후속 작업이다. 현재 `/api/ws`는 등록되지 않았다.

## 로컬 실행

아래 명령은 모두 저장소 루트에서 실행한다.

```bash
./gradlew -p websocket-server bootRun
```

`SERVER_PORT`의 기본값은 `8081`이고 context path는 `/api`다. 기존 BE의 `.env`를 자동으로 읽는 설정은 없다.

## BE 내부 인증 연동 설정

| 환경변수 | 기본값 | 역할 |
|---|---|---|
| `BACKEND_INTERNAL_BASE_URL` | `http://localhost:8080` | BE의 내부 기본 주소. `/api`를 포함하지 않는다. |
| `WEBSOCKET_INTERNAL_SERVICE_TOKEN` | 빈 값 | BE와 웹소켓 앱에 동일하게 주입할 서비스 인증 키. |

컨테이너에서 `localhost`는 해당 컨테이너를 가리킨다. 배포 환경에서는 웹소켓 앱에서 접근할 수 있는 BE 내부 주소를 지정한다. 서비스 키는 환경별로 주입하며 저장소에 실제 값을 기록하지 않는다.

클라이언트는 `GET /api/internal/websocket/authentication`에 서비스 키와 `accessToken`·`CSRF_CONTEXT` 쿠키를 전달한다. 사용자 ID·로그인 세션 ID·토큰 만료 시각·기존 CSRF 값을 확인한다. 현재 이 클라이언트를 호출하는 WebSocket 연결 처리는 아직 없다.

연결 제한은 1초, 응답 제한은 2초이며 `websocket.backend-auth.connect-timeout`·`read-timeout`으로 설정한다. 리다이렉트를 따르지 않고 애플리케이션 차원의 자동 재시도를 추가하지 않았다.

서비스 키가 비어 있어도 앱은 기동하지만 인증 호출은 거절한다. 사용자 인증 실패, CSRF 오류, 서비스 접근 거절, BE 장애, 잘못된 응답을 구분하며 쿠키·서비스 키는 로그에 남기지 않는다.

## 기동 확인

```bash
curl -i http://localhost:8081/api/actuator/health
```

정상 기동하면 HTTP 200과 `status: UP`을 반환한다. health 상세 정보와 Prometheus endpoint는 공개하지 않는다.

## 테스트와 빌드

```bash
./gradlew -p websocket-server test
./gradlew -p websocket-server build
```

기동 테스트는 실제 HTTP 서버로 health 경로와 노출 범위를 확인하고, BE 앱·DB·S3·Kafka 라이브러리가 실행 기반에 포함되지 않았는지 검사한다.

인증 호출 테스트는 로컬 HTTP 서버를 BE 대신 사용해 요청 경로·헤더·쿠키와 성공·실패 응답을 검증한다. 응답 시간 초과·연결 거절·리다이렉트 차단도 확인한다. 실제 배포 BE·Redis·DB 연동과 WebSocket 연결을 검증하는 테스트는 아니다.

이 앱은 독립 Gradle 프로젝트이므로 루트의 `./gradlew test`와 `./gradlew build`에 포함되지 않는다. 현재 기존 BE의 CI도 이 앱을 별도로 검사하지 않으므로 위 명령으로 검증한다.

## Docker 실행

루트 Gradle wrapper를 복사하므로 빌드 context는 저장소 루트인 `.`으로 지정한다.

```bash
docker build -f websocket-server/Dockerfile -t yeodam-websocket:local .
docker run --rm --name yeodam-websocket-local \
  -p 127.0.0.1:8081:8081 yeodam-websocket:local
```

이미지는 빌드 단계에서 JDK로 실행 JAR를 만들고, 실행 단계에서는 JRE와 `spring` 사용자로 실행한다. Docker 빌드는 `bootJar`만 실행하므로 테스트는 별도로 수행한다.

다른 터미널에서 위 health 요청으로 기동을 확인한다. 컨테이너 종료는 실행 터미널의 `Ctrl+C` 또는 다음 명령을 사용한다.

```bash
docker stop yeodam-websocket-local
```
