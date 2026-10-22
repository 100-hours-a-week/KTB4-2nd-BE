# 여담(旅談) Backend

> Description: 여담 백엔드의 기술 구성, 로컬 실행 방법, AI 연동 조건과 테스트·빌드 방법을 안내합니다.

여담은 사진의 촬영 시각·위치·이미지 유사도를 바탕으로 여행 사진을 정리하고 여행 기록 생성을 돕는 서비스입니다.

백엔드는 회원 인증, 여행·사진 관리, 여행 기록 생성과 조회를 담당합니다. 사진 분석과 기록 생성에 필요한 AI 기능은 별도 AI 서버와 연동합니다.

## 기술 구성

| 구분 | 사용 기술 |
| --- | --- |
| 실행·빌드 | Java 25, Gradle Wrapper 9.5.1 |
| 애플리케이션 | Spring Boot 4.1.1, Spring MVC, Spring Security |
| 데이터 | Spring Data JPA, MySQL, Flyway, Redis |
| 비동기 연동 | Kafka, DB Outbox, Debezium CDC |
| 사진 저장·처리 | S3, libvips, libheif, ExifTool |
| 테스트 | JUnit 5, H2, Testcontainers |

## 로컬 실행

JDK 25와 Docker Compose가 필요합니다. Docker 엔진을 켠 뒤 저장소 루트에서 명령을 실행하세요. Gradle은 저장소에 포함된 Wrapper를 사용합니다.

### 1. 사진 처리 도구 설치

사진 변환에는 JVM과 같은 OS·아키텍처의 libvips·libheif와 HEIC 디코더가 필요합니다. EXIF 추출에는 `exiftool`을 사용합니다.

macOS(Homebrew):

```sh
brew install vips libheif exiftool
java -version
exiftool -ver
```

Linux의 설치 패키지는 [Dockerfile](./Dockerfile)을 참고하세요. Gradle의 실행·테스트 태스크에는 `--enable-native-access=ALL-UNNAMED`가 설정되어 있습니다.

### 2. 환경변수 설정

저장소 루트의 `.env`에 아래 값을 입력합니다. `.env`는 Git에서 제외됩니다.

아래 예시는 서버 기동용입니다. S3·AI 기능을 사용하려면 실제 연결 정보로 바꿔야 합니다.

```dotenv
AWS_REGION=ap-northeast-2
ATTACHMENT_S3_BUCKET=yeodam-local

AI_SERVER_BASE_URL=http://localhost:9999
AI_SERVER_API_KEY=local-placeholder
AI_EC2_INSTANCE_ID=i-local-placeholder

REDIS_HOST=localhost
REDIS_PORT=6379
KAFKA_BOOTSTRAP_SERVERS=localhost:9092
```

추가 설정은 [`.env.example`](./.env.example)과 [`application.yaml`](./src/main/resources/application.yaml)에 있습니다. 빈 항목은 값을 채우거나 삭제하세요. 빈 문자열이 설정의 기본값을 덮어쓸 수 있습니다. Spring이 `.env`를 속성 파일로 읽으므로 셸에서 `source`하지 않아도 됩니다.

| 기능 | 필요한 설정 |
| --- | --- |
| 카카오 로그인 | `KAKAO_CLIENT_ID`, `KAKAO_CLIENT_SECRET`, `KAKAO_REDIRECT_URI`, `FRONTEND_OAUTH_CALLBACK_URI` 및 카카오 앱의 Redirect URI 등록 |
| 카카오 장소 조회 | 로그인과 같은 `KAKAO_CLIENT_ID`에 카카오 REST API 키를 설정합니다. `.env.example`의 `KAKAO_REST_API_KEY`는 코드에서 사용하지 않습니다. |
| 행정구역 카탈로그 갱신 | `DATA_GO_KR_SERVICE_KEY`, `DATA_GO_KR_LEGAL_DISTRICT_API_URL` |
| S3 업로드·조회 | 실제 `ATTACHMENT_S3_BUCKET`, 해당 버킷에 접근할 AWS 자격증명 및 브라우저 직접 업로드용 S3 CORS 설정 |
| AI 연동 | 실제 `AI_SERVER_BASE_URL`, `AI_SERVER_API_KEY`, `AI_EC2_INSTANCE_ID` 및 필요한 AWS 권한 |

AWS SDK는 기본 자격증명 체인을 사용합니다. 환경변수로 전달할 때는 실행 프로세스에 `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`를 설정하고, 임시 자격증명이면 `AWS_SESSION_TOKEN`도 설정하세요. 키와 비밀번호는 커밋하지 않습니다.

### 3. Redis와 Kafka 실행

Redis가 이미 `localhost:6379`에서 실행 중이면 Redis 생성 명령은 생략합니다.

```sh
docker run -d --name yeodam-local-redis -p 127.0.0.1:6379:6379 redis:7
docker compose -f docker-compose.kafka.yml up -d
docker compose -f docker-compose.kafka.yml ps -a
```

Kafka가 정상 상태인지, `kafka-init`이 종료 코드 `0`으로 끝났는지 확인합니다. Compose가 로컬 업무 토픽 6개와 각 토픽의 DLT를 생성합니다.

### 4. 백엔드 실행

```sh
./gradlew bootRun --args='--spring.profiles.active=local'
```

Windows에서는 `gradlew.bat`을 사용합니다. 기본 프로필은 `local`입니다. 메모리 H2와 `create-drop`을 사용해 서버를 종료하면 데이터가 사라집니다. Flyway는 비활성화되어 있습니다.

| 항목 | 기본 주소 |
| --- | --- |
| API 기본 경로 | `http://localhost:8080/api` |
| 상태 확인 | `http://localhost:8080/api/actuator/health` |
| 로컬 프론트엔드 허용 Origin | `http://localhost:5173` |

```sh
curl -i http://localhost:8080/api/actuator/health
```

상태 점검을 통과하면 `200`과 `{"status":"UP"}`을 반환합니다. 업무 API는 인증이 필요하고, 변경 요청에는 CSRF 검증도 적용합니다.

### 5. 종료와 재실행

백엔드는 실행 터미널에서 `Ctrl+C`로 종료합니다.

```sh
docker compose -f docker-compose.kafka.yml stop
docker stop yeodam-local-redis
```

다시 실행할 때는 Kafka Compose의 `up -d`와 `docker start yeodam-local-redis`를 사용합니다.

## AI 분석까지 확인하려면

여행 사진 분석과 기록 생성에는 S3, AI 서버, MySQL, Debezium/Kafka Connect가 필요합니다. H2에서는 MySQL CDC를 사용할 수 없으며, Kafka Compose에는 AI 서버와 CDC 커넥터가 없습니다.

- MySQL 연결과 Flyway 설정은 `performance` 프로필을 참고하세요. [`docker-compose.performance.yml`](./docker-compose.performance.yml)에는 MySQL과 S3용 LocalStack만 있습니다. Redis·Kafka·AI·CDC는 별도로 구성해야 합니다.
- [`src/test/resources/cdc/connector.json`](./src/test/resources/cdc/connector.json)은 CDC 통합 테스트용입니다. 운영에 사용하려면 연결 주소·권한·토픽과 커넥터 플러그인을 환경에 맞게 설정해야 합니다.
- 초기 사진 업로드는 Presigned URL 발급 → 클라이언트의 S3 업로드 → 완료 접수 순서입니다. 접수 후 사진 변환과 AI 처리는 비동기로 진행합니다. 결과는 상태 조회로 확인합니다.

## 테스트와 빌드

Kafka 설정과 기본 검색 카탈로그 테스트:

```sh
./gradlew test --tests '*KafkaEnvironmentTest' --tests '*PlaceSearchCatalogSeedTest'
```

전체 테스트와 실행 JAR 빌드:

```sh
./gradlew test
./gradlew bootJar
```

통합 테스트에는 Docker가 필요하며, 사진 처리 테스트에는 네이티브 라이브러리가 필요합니다. 실행 JAR은 `build/libs/`에 생성됩니다. JAR로 직접 실행할 때는 네이티브 접근 옵션을 전달하세요.

```sh
java --enable-native-access=ALL-UNNAMED -jar build/libs/yeodam-be-0.0.1-SNAPSHOT.jar --spring.profiles.active=local
```

## 실행 중 확인할 사항

| 증상 | 확인할 내용 |
| --- | --- |
| Java·Gradle 실행 실패 | `java -version`과 `JAVA_HOME`이 JDK 25를 가리키는지 확인합니다. |
| 빈 URL·Duration 설정 오류 | `.env`에서 빈 선택 항목을 지우거나 올바른 값을 넣습니다. |
| Redis 연결 실패·상태 점검 실패 | Redis 실행 상태와 `REDIS_HOST`, `REDIS_PORT`를 확인합니다. |
| Kafka 연결 실패·토픽 없음 | 브로커 상태, `kafka-init` 로그, 앱과 Compose의 토픽 설정을 확인합니다. |
| 이미지 라이브러리 로드 실패 | libvips·libheif와 종속 라이브러리의 설치 경로 및 JVM 아키텍처를 확인합니다. 필요하면 `vipsffm.libpath.vips.override`, `app.photo.libheif.path` JVM 속성에 절대 경로를 지정합니다. |
| AI 작업이 완료되지 않음 | AI 서버 연결, MySQL CDC 커넥터, 요청·결과 토픽과 소비자 상태를 확인합니다. |
