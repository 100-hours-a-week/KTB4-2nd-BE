# 여행 생성 입력 임시 저장 API Implementation Plan

> Description: 승인된 여행 생성 초안 설계를 `yeodam-be`의 DB, 초안 API, 기존 여행 생성, 사진 처리 수명 주기에 적용하기 위한 작업 순서와 검증 기준입니다. 계획이며 구현 완료 기록이 아닙니다.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 로그인한 사용자가 이름·지역·기간을 사용자당 한 초안으로 저장하고 다른 기기에서 복원하며, 기존 `POST /trips`로 안전하게 제출한다.

**Architecture:** `trip_drafts`가 미완성 입력과 연결된 `tripId`를 보관한다. 초안 서비스가 저장·조회·삭제·제출을 담당하고 기존 여행 생성 서비스를 재사용한다. AI 완료·생성 취소·실패 여행 삭제·탈퇴는 각 기존 트랜잭션에서 초안 연결을 갱신한다.

**Tech Stack:** Java 25(현재 `build.gradle`), Spring Boot 4.1.1, Spring MVC/Security, JPA, Flyway/MySQL, Jackson, JUnit/MockMvc/Testcontainers.

**Spec:** [2026-09-29-trip-draft-design.md](./2026-09-29-trip-draft-design.md)

## Global Constraints

- 백엔드 `yeodam-be/`만 구현한다. 프론트엔드는 새 API 연동 시 기존 `localStorage` 초안을 제거해야 하며, 사진은 최종 제출 전 서버에 저장하지 않는다.
- `draft_id` 기본키와 `user_id` 유니크 제약을 둔다. 사용자당 초안은 1개다.
- PUT은 전체 표현 교체이며 같은 본문 반복 시 행·ID·값·`updated_at`이 바뀌지 않는다. 다른 기기 동시 수정은 마지막 성공한 PUT이 남는다.
- 초안 제출은 기존 `POST /trips`의 `{ "draftId": 12 }` 형태다. 기존 전체 필드 요청은 그대로 지원하고 혼합 요청은 `400`이다.
- `POST /trips` 성공만으로 초안을 지우지 않는다. `COMPLETED` 커밋에 삭제, 취소·실패 여행 삭제에 연결 해제를 같은 트랜잭션으로 묶는다.
- 기존 인증·CSRF·소유권·지역 기준 목록·이름 1~10자·지역 1~10개·기간 최대 92일 검증을 유지한다. 추가 의존성은 쓰지 않는다.
- 현재 `build.gradle`의 Java 25와 상위 `AGENTS.md`의 Java 21 설명이 다르다(`협의 필요`). 이 기능에서 빌드 도구 버전을 변경하지 않는다.

## Review Focus

1. PUT에서 `regionCodes` 누락 또는 한쪽 날짜만 전송 → `400`, 기존 초안 불변 (Task 2).
2. 같은 사용자의 동시 첫 PUT → 초안 1행과 동일 `draftId`, 중복 키 오류가 외부에 노출되지 않음 (Task 2).
3. `{draftId, tripName}` 혼합 제출 또는 타인 `draftId` → 각각 `400`·`404`, 여행 행 미생성 (Task 3).
4. 저장 후 지역 기준 목록이 바뀌어 제출 시 코드가 무효 → `400`, 초안과 여행 수 불변 (Task 3).
5. 완료·취소·삭제 대상 `tripId`와 초안의 연결값이 다름 → 다른 여행의 초안을 바꾸지 않음 (Task 4).

---

## File map

- `src/main/resources/db/migration/V8__create_trip_drafts.sql`: 단일 초안 테이블과 FK·유니크 제약.
- `trip/entity/TripDraft.java`, `trip/repository/TripDraftRepository.java`: 영속 상태와 소유자·연결 여행 조건부 조회/갱신.
- `trip/service/request/TripDraftSaveRequest.java`, `trip/service/response/TripDraftResponse.java`: PUT 전체 표현과 GET/PUT 응답.
- `trip/service/TripDraftService.java`, `trip/controller/TripDraftController.java`: 초안 검증·저장·조회·삭제·제출 및 HTTP 연결.
- `trip/service/response/TripDraftSubmission.java`: 새 제출 여부와 기존 `TripCreateResponse`를 묶어 `201/200`을 선택.
- `trip/controller/TripController.java`: 기존 `POST /trips`의 두 요청 형태 분기. `TripService`의 기존 전체 필드 생성 로직을 그대로 재사용.
- `user/repository/UserRepository.java`: 동시 최초 PUT을 사용자 행 잠금으로 직렬화.
- `trip/service/TripAnalysisResultService.java`, `TripProcessingCancellationService.java`, `TripDeletionService.java`, `TripWithdrawalService.java`: 기존 트랜잭션 안의 초안 정리.
- `common/response/{ErrorMessage,SuccessMessage}.java`, `common/exception/GlobalExceptionHandler.java`: 초안 전용 응답·오류 매핑.

### Task 1: 초안 테이블과 영속 모델

**Files:**
- Create: `src/main/resources/db/migration/V8__create_trip_drafts.sql`
- Create: `src/main/java/com/yeodam/yeodambe/trip/entity/TripDraft.java`
- Create: `src/main/java/com/yeodam/yeodambe/trip/repository/TripDraftRepository.java`
- Test: `src/test/java/com/yeodam/yeodambe/trip/migration/TripDraftMigrationTest.java`

**Interfaces:** `TripDraftRepository.findByUserId(Long): Optional<TripDraft>`, `findByIdAndUserIdForUpdate(Long, Long): Optional<TripDraft>`, `findByUserIdForUpdate(Long): Optional<TripDraft>`, `deleteBySubmittedTripIdAndUserId(Long, Long): int`, `clearSubmittedTripId(Long, Long): int`, `deleteByUserId(Long): int`. `TripDraft.replace(String tripName, String regionCodesJson, LocalDate startDate, LocalDate endDate): boolean` and `attach(Long tripId): void`; `replace` returns false for identical values so the timestamp stays fixed.

- [ ] **Step 1: Write the failing migration test.** Assert `draft_id` PK, `user_id` unique, `submitted_trip_id` FK, nullable partial fields, and rejection of a second row for one user using Testcontainers MySQL.
- [ ] **Step 2: Run the focused test and confirm failure.** `./gradlew test --tests '*TripDraftMigrationTest'`; expect missing table.
- [ ] **Step 3: Add V8 migration, entity, and repository.** Store region codes as a JSON array in `VARCHAR(128)`; use the existing Flyway/JPA naming conventions. Conditional link update/delete queries must include both user ID and linked trip ID.
- [ ] **Step 4: Run the focused test; expect pass.** `./gradlew test --tests '*TripDraftMigrationTest'`.
- [ ] **Step 5: Commit only Task 1 files.** `feat: 여행 생성 초안 저장 구조 추가`.

### Task 2: 초안 저장·조회·폐기 API

**Files:**
- Create: `src/main/java/com/yeodam/yeodambe/trip/service/request/TripDraftSaveRequest.java`
- Create: `src/main/java/com/yeodam/yeodambe/trip/service/response/TripDraftResponse.java`
- Create: `src/main/java/com/yeodam/yeodambe/trip/service/TripDraftService.java`
- Create: `src/main/java/com/yeodam/yeodambe/trip/controller/TripDraftController.java`
- Modify: `src/main/java/com/yeodam/yeodambe/user/repository/UserRepository.java`
- Create: `src/main/java/com/yeodam/yeodambe/common/exception/TripDraftNotFoundException.java`
- Create: `src/main/java/com/yeodam/yeodambe/common/exception/InvalidTripDraftRequestException.java`
- Create: `src/main/java/com/yeodam/yeodambe/common/exception/TripDraftAlreadySubmittedException.java`
- Modify: `src/main/java/com/yeodam/yeodambe/common/response/ErrorMessage.java`
- Modify: `src/main/java/com/yeodam/yeodambe/common/response/SuccessMessage.java`
- Modify: `src/main/java/com/yeodam/yeodambe/common/exception/GlobalExceptionHandler.java`
- Test: `src/test/java/com/yeodam/yeodambe/trip/service/TripDraftServiceTest.java`
- Test: `src/test/java/com/yeodam/yeodambe/trip/service/TripDraftServicePersistenceTest.java`
- Test: `src/test/java/com/yeodam/yeodambe/trip/controller/TripDraftSecurityIntegrationTest.java`

**Interfaces:** `TripDraftService.find(Long): TripDraftResponse`, `save(Long, TripDraftSaveRequest): TripDraftResponse`, `delete(Long): void`. `UserRepository.findActiveByIdForUpdate(Long): Optional<User>` locks the user before a PUT looks for an existing draft. `TripDraftSaveRequest.fromJson(JsonNode, ObjectMapper): TripDraftSaveRequest` requires all four keys, then produces `String tripName`, `List<String> regionCodes`, `LocalDate startDate`, `LocalDate endDate`. `TripDraftResponse` has `draftId`, those four fields, `submittedTripId`, `updatedAt`. Controller methods implement `GET/PUT/DELETE /trip-drafts/me` with JWT subject.

- [ ] **Step 1: Write failing service and HTTP tests.** Pin partial name-only PUT/GET across requests and `TRIP_DRAFT_SAVED`/`TRIP_DRAFT_FOUND` response bodies; identical PUT keeps ID and `updatedAt`; missing `regionCodes`, half dates, blank/overlong name, duplicate/unknown codes and future/>92-day dates give `400` without changing stored values; unauthenticated `401`, CSRF failure `403`, no draft GET `404`, repeated DELETE `204`, linked draft PUT/DELETE `409`. Add a concurrent first-PUT persistence case asserting one row and no exposed duplicate-key error.
- [ ] **Step 2: Run the focused tests; expect failure.** `./gradlew test --tests '*TripDraftServiceTest' --tests '*TripDraftServicePersistenceTest' --tests '*TripDraftSecurityIntegrationTest'`.
- [ ] **Step 3: Implement DTO, service, controller, and error mapping.** Reuse `RegionCatalog` and Jackson `ObjectMapper`; check required JSON keys and validate before writing. Serialize only code arrays, not names/coordinates. Lock the user row before finding or creating a draft so racing first PUTs serialize; keep the `user_id` unique constraint as DB protection. Make `replace` a no-op when equal. Map new messages `TRIP_DRAFT_FOUND`, `TRIP_DRAFT_SAVED`, `TRIP_DRAFT_NOT_FOUND`, `INVALID_TRIP_DRAFT_REQUEST`, `TRIP_DRAFT_ALREADY_SUBMITTED`.
- [ ] **Step 4: Run the focused tests; expect pass.** `./gradlew test --tests '*TripDraftServiceTest' --tests '*TripDraftServicePersistenceTest' --tests '*TripDraftSecurityIntegrationTest'`.
- [ ] **Step 5: Commit only Task 2 files.** `feat: 여행 생성 초안 저장 조회 삭제 API 추가`.

### Task 3: 기존 여행 생성 API로 초안 제출

**Files:**
- Create: `src/main/java/com/yeodam/yeodambe/trip/service/response/TripDraftSubmission.java`
- Modify: `src/main/java/com/yeodam/yeodambe/trip/service/TripDraftService.java`
- Modify: `src/main/java/com/yeodam/yeodambe/trip/controller/TripController.java`
- Test: `src/test/java/com/yeodam/yeodambe/trip/service/TripDraftSubmissionPersistenceTest.java`
- Modify test: `src/test/java/com/yeodam/yeodambe/trip/controller/TripCreationSecurityIntegrationTest.java`
- Modify test: `src/test/java/com/yeodam/yeodambe/trip/controller/TripControllerTest.java`

**Interfaces:** `TripDraftService.submit(Long userId, Long draftId): TripDraftSubmission`; `TripDraftSubmission(TripCreateResponse trip, boolean created)`. Reuse `TripService.createTrip(Long, TripCreateRequest)` inside the outer submission transaction. Lock the active user row before the draft row, matching PUT and withdrawal. `TripController.createTrip` distinguishes exact `{draftId}` from the existing four-field request and returns `201` for a new trip, `200` for replay.

- [ ] **Step 1: Write failing persistence and HTTP tests.** Assert draft row lock/ownership, complete-value validation, repeated POST returns the same ID and current `PROCESSING` or `FAILED` status, mixed payload `400`, foreign ID `404`, invalidated region code `400` with zero trip rows, duplicate name `409`, and existing full-field POST still returns `201` with its prior validation/response.
- [ ] **Step 2: Run focused tests; expect failure.** `./gradlew test --tests '*TripDraftSubmissionPersistenceTest' --tests '*TripCreationSecurityIntegrationTest' --tests '*TripControllerTest'`.
- [ ] **Step 3: Implement submission and controller dispatch.** Inspect JSON shape at the controller boundary, map the direct branch to the existing `TripCreateRequest` and run its bean constraints before calling `TripService`; reject mixed/unknown draft fields. In a single transaction lock the active user and owned draft in that order, return the linked active trip on replay, otherwise construct `TripCreateRequest` from the draft, run its bean constraints and existing service validation, then call existing creation before `attach(tripId)`.
- [ ] **Step 4: Run focused tests; expect pass.** Use the Step 2 command.
- [ ] **Step 5: Commit only Task 3 files.** `feat: 기존 여행 생성 API에 초안 제출 연결`.

### Task 4: 완료·취소·삭제·탈퇴 수명 주기와 계약 문서

**Files:**
- Modify: `src/main/java/com/yeodam/yeodambe/trip/service/TripAnalysisResultService.java`
- Modify: `src/main/java/com/yeodam/yeodambe/trip/service/TripProcessingCancellationService.java`
- Modify: `src/main/java/com/yeodam/yeodambe/trip/service/TripDeletionService.java`
- Modify: `src/main/java/com/yeodam/yeodambe/trip/service/TripWithdrawalService.java`
- Modify test: `src/test/java/com/yeodam/yeodambe/trip/service/TripAnalysisResultServiceTest.java`
- Modify test: `src/test/java/com/yeodam/yeodambe/trip/service/TripAttachmentServiceTest.java`
- Modify test: `src/test/java/com/yeodam/yeodambe/trip/service/TripProcessingCancellationServiceTest.java`
- Modify test: `src/test/java/com/yeodam/yeodambe/trip/service/TripDeletionServiceTest.java`
- Modify test: `src/test/java/com/yeodam/yeodambe/trip/service/TripDeletionPersistenceTest.java`
- Modify test: `src/test/java/com/yeodam/yeodambe/trip/service/TripAttachmentTransactionPersistenceTest.java`
- Modify test: `src/test/java/com/yeodam/yeodambe/user/service/WithdrawalServiceTest.java`
- Modify: `src/main/java/com/yeodam/yeodambe/user/service/WithdrawalService.java`
- Modify: `../V1-API-명세.md`, `../ERD-설계-문서.md`, `../project-requirements-analysis.md` (상위 문서 경로, 백엔드 Git 밖)

**Interfaces:** Task 1 repository의 `deleteBySubmittedTripIdAndUserId(tripId, userId)`, `clearSubmittedTripId(tripId, userId)`, `deleteByUserId(userId)`를 기존 서비스 트랜잭션에서 호출한다. 연결이 없는 일반 여행에서는 0행 변경을 정상으로 취급한다.

- [ ] **Step 1: Write failing lifecycle tests.** AI `COMPLETED` 성공 때만 초안 삭제; 결과 저장 롤백·`FAILED` 때 유지; `PROCESSING` 취소 및 `FAILED` 여행 삭제 때 연결만 해제; 다른 `tripId` 초안은 불변; 탈퇴 때 초안 삭제를 검증한다.
- [ ] **Step 2: Run focused tests; expect failure.** `./gradlew test --tests '*TripAnalysisResultServiceTest' --tests '*TripAttachmentServiceTest' --tests '*TripProcessingCancellationServiceTest' --tests '*TripDeletionServiceTest' --tests '*WithdrawalServiceTest'`.
- [ ] **Step 3: Add minimal repository calls inside existing transactions and update impacted constructor-based tests.** 완료 후 삭제가 실패하면 완료 트랜잭션도 롤백한다. 취소·삭제의 초안 연결 해제는 여행 상태 변경과 원자적으로 커밋한다. 탈퇴는 Task 2의 활성 사용자 행 잠금을 얻은 뒤 초안을 같은 트랜잭션에서 삭제해 동시 PUT·제출이 탈퇴 후 초안을 남기지 못하게 한다.
- [ ] **Step 4: Update V1 API/ERD/요구사항 원문에 새 계약과 구현 확인 범위를 구분해 기록한다.** 상위 문서는 별도 Git 저장소가 아니므로 백엔드 커밋에 포함하지 않는다. V1 동기 완료 기준과 기존 V3 기능정의서의 화면 요구를 섞지 않는다.
- [ ] **Step 5: Run focused tests and full backend suite; expect pass.** `./gradlew test --tests '*TripAnalysisResultServiceTest' --tests '*TripAttachmentServiceTest' --tests '*TripProcessingCancellationServiceTest' --tests '*TripDeletionServiceTest' --tests '*WithdrawalServiceTest'`; then `./gradlew test`. Docker/MySQL 또는 Gradle 캐시 문제로 실행하지 못하면 실패 출력과 미검증 범위를 정확히 보고한다.
- [ ] **Step 6: Commit only backend Task 4 files.** `feat: 여행 생성 초안 수명 주기 연결`.

## Completion checks

- `git diff --check`, 관련 Gradle 테스트 및 전체 `./gradlew test` 결과를 각각 기록한다. 실제 S3·AI·다른 기기 브라우저 동작은 별도 런타임 확인으로 분리한다.
- 사용자 소유의 기존 변경과 무관한 미추적 파일은 보존한다. 초안 설계·계획과 코드 변경의 커밋을 구분한다.
