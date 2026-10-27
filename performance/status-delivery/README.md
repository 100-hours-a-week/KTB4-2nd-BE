# 상태 조회 폴링 기준값과 웹소켓 비교 재현 환경

이 디렉터리는 퍼센트 없는 상태 응답을 사용하는 폴링 기준값(B)을 공유한다. 웹소켓(C) 구현과 부하 스크립트는 아직 포함하지 않는다. 운영 코드는 변경하지 않는다. B는 폴링, C는 같은 상태 응답의 웹소켓 전달을 뜻한다.

## 보존한 기준값

- `baseline/report.md`에 측정 조건과 해석, `baseline/summary.csv`와 `baseline/analysis.json`에 9개 조건의 결과가 있다.
- `baseline/measurements.tar.gz`는 k6 관측 시각·요약, 상태 변경 시각, Prometheus 시계열, DB·AI 호출 차이와 Docker 자원 기록 73개를 보존한다. `baseline/checksums.json`으로 파일별 SHA-256을 검사한다.
- `baseline/environment.json`은 당시 코드·JAR 해시와 환경, `baseline/verification.json`은 9개 조건 검증 결과다. 실험은 조건별 1회이며 총 상태 조회 요청은 249,304회다.
- 복사본의 과거 해시는 당시 스크립트 기준이다. 아래 공유용 스크립트는 경로·이미지·fixture 생성을 이식했으므로 과거 스크립트와 해시가 다르다.

## 실행 조건

Docker Compose, Python 3.9 이상, 호스트의 k6가 필요하다. 기존 측정은 Docker CPU 10개·메모리 약 7.75GiB에서 실행했다. BE는 2개이며 각각 CPU 1개·메모리 2GiB·최대 힙 512MiB를 사용한다.

모든 명령은 저장소 루트에서 실행한다. 처음 이미지 빌드에는 인터넷과 시간이 필요하다. Git에 있는 기준 코드만 별도 폴더에 내보내므로 현재 브랜치를 이동하거나 수정하지 않는다.

```bash
python3 performance/status-delivery/prepare.py
docker compose -p yeodam-pollbase -f performance/status-delivery/compose.json build be1
docker compose -p yeodam-pollbase -f performance/status-delivery/compose.json up -d
docker compose -p yeodam-pollbase -f performance/status-delivery/compose.json ps
curl --fail http://127.0.0.1:18081/api/actuator/health
curl --fail http://127.0.0.1:18082/api/actuator/health
python3 performance/status-delivery/run.py fixture
python3 performance/status-delivery/run.py smoke
```

BE 두 개가 기동된 뒤 fixture를 넣는다. 초기 migration 경합으로 한 BE가 종료됐다면 로그를 확인하고 해당 BE만 다시 시작한다. fixture는 **migration의 시스템 사용자 외에 업무 자료가 없는 전용 DB에 한 번만** 넣는다. smoke는 API별 2명으로 상태 변경·완료 수신·진행률 필드 제거와 지도 조회를 검사한다.

전용 Compose 프로젝트 외의 DB·Redis나 로그인 쿠키를 사용하지 않는다. DB·Prometheus 볼륨은 기존 측정과 구분한 `yeodam-pollbase-share-*` 이름이다. 합성 사용자 1,000명과 여행 3,000개를 생성하고 테스트용 JWT·Redis 로그인 세션을 만든다. JWT는 각 조건 시작 시 재발급한다. 실계정이나 AWS 접근 권한은 필요 없다.

## 본 측정과 결과 확인

```bash
rm -f performance/status-delivery/STOP_STATS
python3 performance/status-delivery/record_resources.py &
python3 performance/status-delivery/run.py
touch performance/status-delivery/STOP_STATS
wait
python3 performance/status-delivery/analyze.py
```

`runs/`에 조건별 원자료, `summary.json`에 실행 결과, `summary.csv`와 `analysis.json`에 집계가 생긴다. `docker-stats.jsonl`은 추가 기록 방식이다. 새 실험 전 기존 결과를 다른 곳에 보존하고 `runs/`, 요약 파일, 자원 기록을 비워야 한다. 실행기는 기존 유효한 결과를 재사용한다. 서로 다른 코드의 결과를 같은 폴더에 섞지 않는다.

Prometheus는 `http://127.0.0.1:19090`에서 확인한다. `prom_queries.json`의 쿼리로 BE CPU·힙·커넥션 풀, Redis 처리량·자원을 집계한다. Grafana는 포함하지 않으며, 필요하면 이 Prometheus를 데이터 소스로 연결한다. API 응답 시간과 상태 발견 지연은 k6 기록에서 계산한다.

비교 실험마다 `k6 version`, `docker info`, Compose의 `images` 출력과 `.runtime/source.json`을 결과와 함께 보존한다. 호스트·이미지·JDK·라이브러리 버전이 바뀌면 같은 소스라도 결과가 달라질 수 있다.

종료는 다음 명령을 사용한다. `down`은 전용 DB 볼륨을 보존한다. 초기화가 필요할 때만 결과를 보존한 뒤 같은 명령에 `-v`를 붙여 전용 볼륨을 삭제한다.

```bash
docker compose -p yeodam-pollbase -f performance/status-delivery/compose.json down
python3 -m unittest discover -s performance/status-delivery -p 'test_*.py'
```

## 웹소켓 개선 후 그대로 유지할 조건

1. 스토리 생성·여행 생성·여행 수정, 각각 100·500·1,000명으로 총 9개 조건을 실행한다. 사용자는 자기 작업 하나를 관찰한다.
2. 워밍업 30초, 측정 120초로 유지한다. 시작 후 60초에 중간 상태, 135초에 완료 상태로 바꾼다. 사용자 시작 시점은 2초 구간에 분산한다. 완료 후 추가 폴링은 멈춘다.
3. 폴링은 응답을 받은 뒤 2초 기다린다. 웹소켓도 같은 상태 변경 횟수와 본문 의미를 사용한다. 퍼센트 제거 전 응답과 비교하지 않는다.
4. BE 수·CPU·메모리·DB·Redis 제한과 지도 조회 5 RPS를 유지한다. 웹소켓 서버와 추가 인증·heartbeat·복구 조회의 자원은 별도로 합산한다.
5. 변경 확정부터 클라이언트 관측까지 p50·p95·p99, 누락 사용자 수, HTTP 요청 수, BE·WS·Redis CPU/메모리, DB SELECT 증가량, 지도 API 지연을 비교한다.
6. 연결 단절과 Redis 구독 단절 복구 실험은 정상 상태 비교와 분리한다. 웹소켓 연결만 유지되고 Redis 구독이 끊기는 경우도 검사한다.

## 웹소켓 담당자가 연결할 부분

- `polling.js`의 GET 반복을 실제 STOMP 연결·인증·구독·수신으로 대체한다. 기존 `observed` 로그의 `userId`, `status`, `step`, `epochMs`를 유지하면 관측 시각을 같은 기준으로 비교할 수 있다.
- `run.py`의 `transition()`은 현재 DB와 AI Stub 상태를 직접 바꾼다. **이 동작만으로 Redis나 웹소켓 이벤트는 발행되지 않는다.** C 실험에서는 같은 변경 시점에 실제 구현된 DB 저장 후 발행 경로를 실행하도록 어댑터를 연결해야 한다. 테스트용 운영 API를 임의로 추가하지 않는다.
- `prepare.py --revision <비교할-커밋>`으로 다른 코드를 내보낼 수 있다. 기존 `.runtime/backend-source`는 결과를 보존한 뒤 먼저 제거한다. DB 스키마가 달라지면 fixture 호환성을 확인한다.
- 현재 분석기는 폴링 지표 이름을 사용한다. C의 연결·재연결·수신 지표를 별도 수집하고 동일한 상태 발견 지연 정의로 비교한다. 기존 폴링 요청 수를 웹소켓 메시지 수로 이름만 바꾸어 해석하지 않는다.

이 환경은 실제 JWT·Redis·MySQL과 상태 조회 API를 실행한다. 사진 업로드·변환·실제 AI 연산·Kafka 결과 저장·브라우저 렌더링은 측정 대상이 아니다. 공유용 환경은 동일 소스에서 이미지를 새로 빌드하므로, 다른 장비에서는 B도 다시 실행해 그 장비의 C와 비교한다.

브라우저 표시·503 오류 후 복구·새로고침의 추가 소규모 시험과 원자료는 [browser-followup/README.md](browser-followup/README.md)에 별도로 기록했다. 이 자료는 위 부하 기준값과 구분한다.

## 공유 환경 검증 기록

2026-10-27, Python 3.9·호스트 k6 2.3.0에서 기준 코드 Docker 이미지 빌드와 두 BE의 health 200을 확인했다. 자동 검사 7건이 통과했고, 보존한 원자료를 재집계한 결과도 기존 CSV 9행과 일치했다.

새 공유용 DB에서 세 API를 각각 2명으로 smoke 실행했다. 조건별 측정 GET 12회, 요청 오류 0회, 중간·완료 상태 모두 2/2명 수신, progress 필드 없음과 지도 조회 성공을 확인했다. Prometheus 응답·힙 시계열과 Docker 자원 표본 91개도 수집했다. 본 측정 9조건은 다시 실행하지 않았으며 웹소켓 C 실험은 구현 후 진행한다.
