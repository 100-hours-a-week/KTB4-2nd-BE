# 여담 WebSocket 전용 앱

기존 BE와 같은 저장소에서 별도로 빌드하고 실행하는 Spring Boot 앱이다. Java 25를 사용하며 기존 BE의 DB·S3·Kafka 설정 없이 실행한다.

현재 범위는 독립 실행과 Docker 이미지 구성이다. WebSocket handshake, STOMP destination, 인증, Redis Pub/Sub는 후속 작업이다. 현재 `/api/ws`는 등록되지 않았다.

## 로컬 실행

아래 명령은 모두 저장소 루트에서 실행한다.

```bash
./gradlew -p websocket-server bootRun
```

`SERVER_PORT`의 기본값은 `8081`이고 context path는 `/api`다. 기존 BE의 `.env`를 자동으로 읽는 설정은 없다.

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
