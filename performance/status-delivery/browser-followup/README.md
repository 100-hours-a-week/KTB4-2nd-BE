# 폴링 브라우저 추가 시험

## 목적과 범위

기존 9조건 부하 실험은 k6 수신 지연·서버 자원·DB 조회량을 측정했지만 실제 FE 표시와 오류 후 복구는 측정하지 않았다. 이 폴더는 빠진 부분을 한 브라우저의 작은 실험으로 보완한다. 기존 부하 결과를 대체하지 않는다.

로컬 `http://127.0.0.1:5179/?case=normal-1`에서 실행했다. BE는 기존 고정 코드의 로컬 Docker 2개·Redis·MySQL이며 합성 계정만 사용했다. 스테이징·운영 서버와 실계정은 사용하지 않았다. AI 연산 대신 기존 Stub과 스크립트의 DB 상태 변경을 사용했다.

FE의 실제 `useStoryGeneration`, `storyCreateQueries`, API client와 `StoryGenerationView`를 별도 Vite 화면으로 가져왔다. Next.js 전체 앱·라우팅·로그인 화면은 실행하지 않았고 `next/link`만 시험용 링크로 대체했다. FE 운영 파일은 수정하지 않았다. 기존 FE는 아직 progress를 요구해 처리 중 NaN 표시가 나올 수 있다. 이 시험은 상태 문구·완료 표시만 관찰하며 퍼센트 표시의 정상 동작을 증명하지 않는다.

## 실행과 재현

상위 README의 기준 BE 준비·기동·fixture를 먼저 수행한다. 이미 fixture가 있는 공유용 DB에는 재입력하지 않는다. Python은 상위 도구를 재사용하고 Vite·React 등은 FE의 설치된 의존성을 사용한다.

```bash
export FE_ROOT=/absolute/path/to/KTB4-2nd-FE
ln -s "$FE_ROOT/node_modules" performance/status-delivery/browser-followup/node_modules
python3 performance/status-delivery/browser-followup/bridge.py
```

다른 터미널에서 실행한다.

```bash
FE_ROOT="$FE_ROOT" node performance/status-delivery/browser-followup/node_modules/vite/bin/vite.js --config performance/status-delivery/browser-followup/vite.config.mjs
```

위 로컬 주소를 Chrome에서 열고 약 2분간 활성 탭을 유지한다. 정상 3회·HTTP 503 복구·새로고침을 순서대로 자동 실행한다. 단일 사용자 실행이며 서버가 시작 후 4초에 PROCESSING, 10초에 COMPLETED로 바꾼다. HTTP 오류 조건은 6~13초에 테스트 프록시가 503을 반환하고 BE는 계속 실행한다. 새로고침은 6초에 실제 location.reload를 수행한다. 결과는 로컬 `browser-results.json`에 저장하며 Git에서 제외한다. 다시 실행하려면 Vite를 재시작해 시험 상태를 초기화한다.

기존 결과를 검사하는 명령은 다음과 같다.

```bash
python3 performance/status-delivery/browser-followup/analyze.py
python3 -m unittest discover -s performance/status-delivery/browser-followup -p 'test_*.py'
```

시험 당시 FE 커밋·파일 해시는 `evidence/environment.json`, 실행한 원본 계측 코드는 `evidence/executed-sources.tar.gz`에 있다. 공유용 파일은 FE_ROOT와 상위 harness 경로로 바꿨으므로 원본과 해시가 다르다. 원본 안의 머신 경로는 재현용 경로로 사용하지 않는다. 의존성은 당시 FE의 package/lock 기준으로 준비하고 새 FE 버전과 혼합한 결과는 별도 기록한다.

## 측정 정의와 결과

DB 상태 변경 커밋 전후 시각으로 변경 구간을 잡고 호스트·Docker 시각을 보정했다. MutationObserver로 실제 상태 DOM 변경 시각을 기록했다. 두 번째 requestAnimationFrame도 보존했지만 이는 화면을 그릴 기회의 근사치이며 실제 픽셀 표시 시각을 측정한 값은 아니다.

| 조건 | 상태 GET 수 | 완료 커밋→DOM | 결과 |
|---|---:|---:|---|
| 정상 1 | 6 | 58.6ms | 중간·완료 표시 |
| 정상 2 | 7 | 2,018.5ms | 중간·완료 표시 |
| 정상 3 | 7 | 2,012.1ms | 중간·완료 표시 |
| HTTP 503 | 9 | 3,978.9ms | 503 5회 이후 완료 복구 |
| 새로고침 | 7 | 2,030.5ms | 페이지 2회 로드·작업 복원·완료 표시 |

정상 3회에서 커밋과 다음 폴링의 상대 시점에 따라 지연이 약 0~2초로 달라졌다. 소수 표본으로 p95나 운영 지연을 추정하지 않는다. 503 조건은 최종 상태를 복구했지만 PROCESSING DOM 표시는 관측되지 않았다. 현재 상태 폴링은 장애 동안 지나간 중간 상태를 재생하는 방식이 아님을 보여준다. 장애가 끝난 13초 시점부터 완료 DOM까지 약 1.08초였다. 새로고침 자료에는 탐색 과정의 짧은 hidden 구간도 기록돼 있다.

각 조건의 MySQL SELECT 차이는 117·157·121·177·159였다. 전용 DB여도 다른 BE 스케줄러 등 배경 조회가 포함되므로 FE 요청당 SQL 수나 이번 DOM 처리의 부하로 환산하지 않는다. 기존 100·500·1,000명 기준 부하는 상위 baseline 자료를 사용한다. 추가 시험의 Docker 자원 기록도 보존하되 단일 브라우저의 짧은 결과로 성능 개선을 주장하지 않는다.

`excluded-background*.json`은 백그라운드 탭·탐색 지연이 섞인 사전 시도다. 유효 결과와 합산하지 않는다. 백그라운드에서 폴링이 멈추는 현상을 관측했으며, 백그라운드 지원은 정상 활성 화면 시험과 별도 검증해야 한다.

## 개선 후 비교 방법

REST가 남아 있으면 구현 완료 후 폴링·웹소켓을 같은 장비에서 순차 실행해 비교할 수 있다. 다만 REST 내부가 DB 스냅샷으로 바뀌거나 AI 조회 경로·캐시·자료 구조가 달라지면 최신 REST와 WS 비교는 같은 버전의 전송 방식 비교다. 원래 구현 대비 전체 개선은 고정한 과거 BE 코드와도 비교해야 한다.

동일 사용자 수 100·500·1,000명, 상태 변경 횟수·시간표, BE 자원과 지도 5 RPS를 유지한다. 두 실험을 동시에 실행해 부하를 섞지 않는다. WS 전용 앱·Redis·인증 재검증·권한 재검증·60초 상태 대조 비용도 합산한다. 신규 브라우저 시험은 같은 FE 상태 표시 방식으로 양쪽을 비교한다.

아직 측정하지 않은 항목은 WS/Redis 알림 누락·구독 단절·재연결·60초 대조 복구, sequence/statusVersion 역전·중복 차단, 다중 서버 권한 회수다. 해당 기능 구현 후 검증한다. 물리적 네트워크 단절도 이번 503 주입과 구분한다. 1초 같은 절대 화면 목표는 추가하지 않고 지연·부하의 전후 변화와 최종 상태 일치를 기록한다.
