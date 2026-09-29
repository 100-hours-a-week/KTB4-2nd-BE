# 여행 목록 조회 API 명세

> Description: V1 여행 목록 조회의 요청, 응답, 정렬·페이지네이션, 노출 상태와 오류를 현재 백엔드 작업 코드 기준으로 요약합니다.

- 기준: 2026-09-29 `104-be-4` 브랜치의 백엔드 코드. 배포 반영 여부는 확인하지 않았습니다.
- `GET /api/trips` (로그인 필요, `accessToken` 쿠키)

## 요청

| 쿼리 | 값 | 기본값 |
| --- | --- | --- |
| `sort` | `LATEST` 또는 `OLDEST` (생성 시각순) | `LATEST` |
| `favorite` | `true` 또는 `false`; `true`면 즐겨찾기 여행을 먼저 정렬 | `false` |
| `cursor` | 이전 응답의 `nextCursor` | 없음 |

한 번에 최대 7개를 반환합니다. `favorite=true`에서는 즐겨찾기·일반 그룹 각각에 `sort`를 적용합니다. 정렬이나 `favorite` 값을 바꾸면 커서를 버리고 첫 페이지부터 조회해야 합니다. 잘못된 커서 또는 현재 필터와 맞지 않는 커서는 `400`입니다.

## 성공 응답 `200`

```json
{
  "message": "TRIP_LIST_FOUND",
  "data": {
    "items": [
      {
        "tripId": 12,
        "status": "PROCESSING",
        "tripName": "제주 여행",
        "startDate": "2026-09-01",
        "endDate": "2026-09-03",
        "placeSummary": "제주",
        "attachmentCount": 0,
        "isFavorite": false,
        "thumbnailUrl": null
      },
      {
        "tripId": 11,
        "status": "COMPLETED",
        "tripName": "부산 여행",
        "startDate": "2026-08-01",
        "endDate": "2026-08-02",
        "placeSummary": "부산",
        "attachmentCount": 3,
        "isFavorite": true,
        "thumbnailUrl": "https://example.com/thumbnail"
      }
    ],
    "hasNext": false,
    "nextCursor": null
  }
}
```

본인의 미삭제 여행 중 `PROCESSING`·`COMPLETED`만 조회합니다. `FAILED`·`CANCELED`는 제외합니다. `PROCESSING`은 사진 수가 0일 수 있으며 `thumbnailUrl`은 `null`입니다. 상세 조회는 `COMPLETED` 이후 가능합니다. 다음 페이지가 있으면 `hasNext=true`와 `nextCursor`가 내려오고, 빈 목록은 `items=[]`, `hasNext=false`, `nextCursor=null`입니다.

## 오류

| HTTP | `message` | 조건 |
| --- | --- | --- |
| `400` | `INVALID_TRIP_LIST_FILTER` | 잘못된 `sort`·`favorite`·`cursor` 또는 필터와 커서 불일치 |
| `401` | `UNAUTHORIZED` | 인증되지 않은 요청 |
| `500` | `INTERNAL_SERVER_ERROR` | 서버 내부 오류 |

오류 응답의 `data`는 `null`입니다.
