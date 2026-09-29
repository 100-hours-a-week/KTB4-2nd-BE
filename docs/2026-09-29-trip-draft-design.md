# 여행 생성 입력 임시 저장 API 설계

> Description: V1 여행 생성의 이름·지역·기간을 사용자 계정에 임시 저장하고 다른 기기에서 복원하는 API와 `trip_drafts` 데이터 구조, 기존 `POST /trips`를 통한 제출 및 사진 처리 완료 전까지의 수명 주기를 정의합니다. 구현 완료를 뜻하지 않습니다.

## 1. 목적과 합의 범위

- 로그인한 사용자가 여행 생성의 이름·지역·기간을 단계별 확인 시 서버에 저장하고, 다른 브라우저·기기에서 이어 쓴다. 사용자당 초안은 하나다.
- 선택한 사진은 초안에 업로드하거나 DB에 기록하지 않는다. 같은 생성 화면에서는 프론트엔드 메모리로 유지하며, 새로고침·재로그인·다른 기기에서는 다시 선택한다. 사진은 최종 제출 뒤 기존 초기 첨부 API에서 S3에 저장한다.
- 프론트엔드가 새 API에 연동할 때 현재 `localStorage` 기반 여행 초안 저장·복원을 제거한다. 이 설계 작업의 변경 범위는 백엔드이며 프론트엔드 수정은 별도 연동 작업이다.
- V3 기능정의서의 단계 이동 시 입력 유지와 요구사항 `FR-TRIP-019`를 따르되, 기존 `FE-TRIP-002`의 새로고침 시 소실 기록은 현재 프론트엔드 코드 및 이번 서버 복원 결정과 다르다. 관련 문서 반영은 구현 시 정합성을 확인한다.

## 2. 현재 구조와 선택 근거

- 기존 `trips`는 이름·시작일·종료일이 `NOT NULL`이고 생성 직후 `PROCESSING`이다. 미완성 입력을 저장하려고 `DRAFT` 상태를 더하면 여행 목록·통계·삭제의 기존 조건도 변경해야 하므로 별도 `trip_drafts`를 쓴다.
- 기존 `POST /trips`는 완성된 이름·기간·지역 코드를 받아 여행과 지역을 저장하고 `tripId`를 돌려준다. 이 경로를 유지하고 초안 참조 요청 형태를 추가한다.
- 초기 사진은 `POST /trips/{tripId}/initial-attachments`에서 서버를 거쳐 저장되며, V1은 동기 AI 결과가 DB에 `COMPLETED`로 반영될 때 완료된다. `POST /trips`의 `201`만으로 초안을 지우지 않는다.
- 현재 프론트엔드는 텍스트 값을 `localStorage`에 저장하고 사진은 복원하지 않는다. 새 API의 목적은 계정 단위·기기 간 복원이다.

## 3. 데이터 구조

Flyway 마이그레이션으로 `trip_drafts`를 추가한다. 기존 `trips`, `files`, `trip_attachments`의 필수 컬럼은 완화하지 않는다.

| 컬럼 | 제약 | 용도 |
| --- | --- | --- |
| `draft_id` | `BIGINT` 기본키, 자동 증가 | 초안 식별자 |
| `user_id` | `BIGINT NOT NULL`, `users.user_id` 외래키, 유니크 | 사용자당 초안 1개 및 소유권 |
| `trip_name` | `VARCHAR(10) NULL` | 이름 단계 전에는 없음 |
| `region_codes` | `VARCHAR(128) NOT NULL` | 최대 10개 5자리 코드를 JSON 배열로 저장. 지역명·좌표는 저장하지 않음 |
| `start_date`, `end_date` | `DATE NULL` | 기간 단계 전에는 둘 다 없음 |
| `submitted_trip_id` | `BIGINT NULL`, `trips.trip_id` 외래키 | 제출 후 생성된 여행. 사진 처리 완료 전까지 유지 |
| `created_at`, `updated_at` | `DATETIME(6) NOT NULL` | 생성·실제 값 변경 시각 |

지역 코드는 초안에서 검색·조인하지 않으므로 한 행에 직렬화한다. 읽기·쓰기 경계에서 JSON 배열 형태와 코드 목록을 검증하고, 여행 생성 시에는 기존 `RegionCatalog`를 사용해 `trip_regions`를 저장한다. 초안은 임시 데이터라 별도 소프트 삭제 컬럼을 두지 않는다. 사용자 탈퇴 트랜잭션에서는 해당 초안을 삭제한다.

## 4. API 계약

모든 경로는 기존 JWT 인증을 사용한다. 변경 요청은 기존 CSRF 보호를 따른다. `me`는 JWT 사용자 ID를 뜻하며 다른 사용자의 `draftId`는 `404`로 숨긴다.

### `GET /trip-drafts/me`

초안이 없으면 `404 TRIP_DRAFT_NOT_FOUND`. 있으면 `200 TRIP_DRAFT_FOUND`로 기존 `ApiResponse` 형식의 아래 데이터를 반환한다. `submittedTripId`가 있으면 프론트엔드는 기존 처리 상태 조회 API로 여행 상태를 확인한다.

```json
{
  "message": "TRIP_DRAFT_FOUND",
  "data": {
    "draftId": 12,
    "tripName": "제주 여행",
    "regionCodes": ["50110"],
    "startDate": null,
    "endDate": null,
    "submittedTripId": null,
    "updatedAt": "2026-09-29T10:00:00"
  }
}
```

### `PUT /trip-drafts/me`

각 단계의 확인 버튼에서 초안 **전체 표현**을 보낸다. 네 속성은 모두 보내야 하며, 없는 단계는 `tripName: null`, `regionCodes: []`, 날짜 둘 다 `null`로 표현한다. 요청의 `draftId`는 필요 없으며, 최초 요청에만 새 행을 만들고 이후에는 같은 행을 교체한다. 같은 본문을 반복하면 `draftId`, 저장값, `updatedAt`이 바뀌지 않는다. 저장 성공은 `200 TRIP_DRAFT_SAVED`와 `GET`의 `data`와 같은 형태다.

```json
{
  "tripName": "제주 여행",
  "regionCodes": ["50110"],
  "startDate": "2026-09-01",
  "endDate": "2026-09-03"
}
```

비어 있지 않은 이름은 공백만으로 구성될 수 없고 Unicode 코드 포인트 기준 1~10자다. 지역 코드는 현재 기준 목록의 시·도/시·군 코드만 허용하며 중복 없이 최대 10개다. 날짜는 둘 다 있거나 둘 다 없고, 있으면 기존 생성 규칙인 종료일 ≥ 시작일, 미래일 불가, 시작일 포함 최대 92일을 따른다. 위반은 `400 INVALID_TRIP_DRAFT_REQUEST`다. 제출돼 `submittedTripId`가 연결된 초안은 수정할 수 없고 `409 TRIP_DRAFT_ALREADY_SUBMITTED`다. 서로 다른 기기에서 저장이 겹치면 마지막으로 성공한 전체 PUT이 남는다. 별도 편집 잠금·버전 비교는 이번 범위에 넣지 않는다.

### `DELETE /trip-drafts/me`

제출 전 초안을 폐기한다. 초안이 없어도 `204`로 응답해 반복 호출의 효과를 같게 한다. `submittedTripId`가 연결됐으면 `409 TRIP_DRAFT_ALREADY_SUBMITTED`이며, 먼저 해당 여행을 취소하거나 삭제해야 한다. 이 API는 여행·사진을 삭제하지 않는다.

### 기존 `POST /trips`의 초안 요청 형태

기존의 전체 필드 요청·응답을 유지한다. 초안 제출은 다음처럼 `draftId`만 보낸다. `draftId`와 기존 입력 필드를 섞으면 `400 INVALID_TRIP_REQUEST`다. 서버가 초안의 저장값을 읽어 기존 생성 검증과 여행명 중복 검사를 수행한다.

```json
{"draftId": 12}
```

이름·지역·날짜가 하나라도 빠진 초안은 `400 INVALID_TRIP_REQUEST`, 없거나 타인 소유 초안은 `404 TRIP_DRAFT_NOT_FOUND`다. 최초 제출은 초안 행을 쓰기 잠그고 여행·지역 생성과 `submitted_trip_id` 설정을 한 DB 트랜잭션에서 수행해 `201 TRIP_CREATED`와 기존 `{tripId, status: "PROCESSING"}`을 반환한다. 같은 초안의 반복 제출은 여행을 새로 만들지 않고 같은 `tripId`를 `200 TRIP_CREATED`와 현재 여행 상태를 담은 기존 응답 형태로 반환한다. 다른 여행명 중복은 기존 `409 TRIP_NAME_DUPLICATED`를 유지한다. 초안 없이 기존 전체 필드로 생성하는 호출은 이전과 같이 동작한다.

## 5. 제출 이후 수명 주기

1. 프론트엔드는 `POST /trips`의 `tripId`로 기존 초기 첨부 API를 호출한다. 사진은 이때 처음 서버에 업로드된다.
2. 업로드·분석 실패 시 초안의 `submitted_trip_id`를 유지한다. 다시 로그인하면 사진을 다시 선택하고 같은 여행에 업로드를 재시도한다. 제출 후 이름·지역·날짜 수정은 허용하지 않는다.
3. AI 결과 검증과 여행의 `COMPLETED` 반영에 성공하면 같은 DB 트랜잭션에서 해당 `submitted_trip_id`의 초안을 삭제한다. 완료 전 장애나 롤백에는 초안이 남는다.
4. `PROCESSING` 여행의 기존 생성 취소는 여행을 소프트 삭제하고 같은 트랜잭션에서 초안의 연결만 해제한다. `FAILED` 여행의 기존 삭제도 같은 방식이다. 이름·지역·날짜는 남아 다시 제출할 수 있다.
5. 사용자가 제출 전 초안을 버리면 `DELETE /trip-drafts/me`로 삭제한다. 회원 탈퇴 시에는 소유한 초안을 같은 탈퇴 트랜잭션에서 삭제한다.

여행과 초안 상태가 서로 어긋나지 않도록 제출·완료·취소·삭제에서 소유권과 연결된 `tripId`를 조건으로 검사한다. DB 변경이 실패하면 해당 트랜잭션을 롤백한다. S3 객체 정리는 기존 여행 취소·삭제 흐름을 따른다.

## 6. 구현 범위와 검증

- 백엔드: Flyway 마이그레이션, `trip_drafts` 엔티티·저장소, 여행 도메인의 요청·응답 DTO와 서비스·컨트롤러, 기존 여행 생성·완료·취소·삭제·회원 탈퇴 흐름의 최소 연결, 오류 응답.
- 프론트엔드 연동 계약: 단계 확인 시 PUT, 진입 시 GET, 최종 생성 시 `POST /trips`에 `draftId`, 사진은 기존 첨부 API, 기존 `localStorage` 초안 사용 제거. 프론트엔드 코드는 이 작업에서 수정하지 않는다.
- 검증: 최초·반복 PUT의 행 수·ID·시각, 부분 입력 및 잘못된 지역·기간, 기기 간 GET에 해당하는 재인증 조회, 타인 초안 접근, 동시 최초 저장의 사용자당 유일성, 제출 재시도 시 동일 `tripId`, 미완성·중복 이름, 사진 실패 후 재시도, 완료·취소·삭제·탈퇴 후 초안 상태, 기존 전체 필드 `POST /trips` 회귀.
- API·DB 계약 변경 사항을 V1 API 명세와 ERD 설계 문서에 반영할 때 현재 코드·계약과 `협의 필요` 항목을 구분한다. 실제 배포 DB와 프론트엔드 연동은 별도 확인이 필요하다.
