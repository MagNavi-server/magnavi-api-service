# 실내 장소 즐겨찾기 API — 5-1단계

구현일: 2026-09-25. 로그인한 회원의 실내 장소 등록·목록·삭제를 제공한다. 실제 앱 전환·기존 데이터 이관·운영 배포는 별도 작업이다.

## 1. 범위와 인증

모든 요청은 `Authorization: Bearer <액세스 토큰>`이 필요하다. JWT의 서명·만료와 현재 회원의 존재를 확인한다. 요청에서 회원 번호를 받아 소유자를 바꾸지 않는다.

이번 API의 대상은 `INDOOR_PLACE`뿐이다. 기존 DB의 BUS·BUS_STOP 행은 목록에서 제외하고 삭제하지 않는다. 버스·정류장은 5-2단계, 네이버 결과 저장은 허용 범위와 식별 정책 확인 뒤 별도로 구현한다.

| 동작 | 경로 | 성공 응답 |
|---|---|---|
| 등록 | `POST /favorites` | 201과 생성 항목 |
| 내 목록 | `GET /favorites?skip=0&limit=100` | 200과 배열 |
| 내 항목 삭제 | `DELETE /favorites/{favoriteId}` | 204, 본문 없음 |

세 경로 모두 끝에 `/`를 붙여도 리다이렉트 없이 처리한다. 상세 GET·수정 API는 제공하지 않는다. 정상 응답에는 `Cache-Control: no-store`를 넣으며 서버에 별도 목록 캐시를 만들지 않는다.

## 2. 등록 요청

```json
{
  "type": "INDOOR_PLACE",
  "indoorLocationId": 10
}
```

- `type`: 대소문자를 구분하며 `INDOOR_PLACE`만 허용한다.
- `indoorLocationId`: 양수인 DB 장소 번호다. 모델 코드·층 번호·즐겨찾기 번호가 아니다. JSON 정수만 허용하며 문자열·소수·불리언·null·Long 범위 초과는 400이다.
- `id`, `memberId`, `name`, `address` 등 계약에 없는 필드는 400이다.
- 현재 존재하는 활성 장소만 등록한다. 같은 회원·장소의 반복 등록은 409다. 다른 회원은 같은 장소를 각각 저장할 수 있다.

등록 결과의 합성 예시:

```json
{
  "id": 501,
  "type": "INDOOR_PLACE",
  "indoorLocationId": 10,
  "createdAt": "2026-09-25T00:00:00Z",
  "available": true,
  "location": {
    "id": 10,
    "buildingId": 1,
    "buildingName": "합성 건물",
    "address": "합성 주소",
    "floorId": 2,
    "floorNumber": 2,
    "floorName": "2층",
    "name": "205호 앞",
    "description": null
  }
}
```

`id=501`은 서버가 새로 발급한 즐겨찾기 번호이고 `indoorLocationId=10`은 원래 장소 번호다. 삭제에는 501을 사용한다. DB의 `target_key`나 소유 회원 번호는 응답에 포함하지 않는다.

## 3. 목록과 사용 불가 항목

`skip`은 건너뛸 항목 수로 0~10,000, `limit`은 최대 반환 개수로 1~100이다. 기본값은 각각 0과 100이다. 다른 쿼리 파라미터와 중복 입력은 400으로 거절한다.

본인의 실내 항목만 거른 뒤 생성 시각 내림차순, 같은 시각이면 즐겨찾기 ID 내림차순으로 정렬한다. `skip=1&limit=2`는 정확히 한 항목을 건너뛰고 최대 두 항목을 가져온다. 항목이 없거나 마지막 페이지를 지나면 `[]`다. 등록·삭제가 진행되는 동안의 페이지 전체를 고정하는 방식은 아니다.

즐겨찾기 한 페이지를 조회한 후 최대 100개 장소의 이름·층·건물을 묶어서 읽는다. 항목마다 SQL이나 외부 API를 반복 호출하지 않는다. 저장된 장소 이름 복사본이 아니라 조회 시점의 최신 장소 정보를 반환한다.

등록 후 장소가 비활성화되면 항목을 자동 삭제하지 않고 아래처럼 반환한다. 앱은 ‘사용 불가’로 표시하고 삭제 버튼을 제공하면 된다.

```json
{
  "id": 501,
  "type": "INDOOR_PLACE",
  "indoorLocationId": 10,
  "createdAt": "2026-09-25T00:00:00Z",
  "available": false,
  "location": null
}
```

일반 장소 API는 비활성 장소를 계속 404로 처리한다. 즐겨찾기의 조회·삭제를 위해 비활성 장소 상세를 공개하지 않는다.

## 4. 삭제와 오류

삭제는 `favoriteId + 로그인한 memberId + INDOOR_PLACE` 조건을 한 SQL에 넣는다. 성공하면 해당 즐겨찾기 행 하나만 삭제한다. 장소 원본과 다른 회원의 즐겨찾기는 유지한다. 같은 번호로 다시 삭제하면 404다.

| 상황 | HTTP | 공통 오류 코드 |
|---|---|---|
| 형식·유형·번호·페이지 오류 | 400 | `INVALID_REQUEST` |
| 토큰 없음·위조·만료·현재 회원 없음 | 401 | `UNAUTHENTICATED` |
| 구현하지 않은 경로·동작에 인증 후 접근 | 403 | `ACCESS_DENIED` |
| 등록 대상 없음·비활성, 삭제 대상 없음·타인 소유·다른 유형 | 404 | `NOT_FOUND` |
| 같은 회원의 같은 장소 중복 등록 | 409 | `DUPLICATE_FAVORITE` |

오류 형식은 기존 `code`, `message`, `traceId`, `errors`를 사용한다. 입력 원문이나 SQL 정보를 응답하지 않는다.

## 5. 코드 흐름과 DB

등록: Controller → FavoriteService → 회원·활성 장소 확인 → 중복 사전 조회 → FavoritePersistenceAdapter → 기존 Favorite.indoor → INSERT.

목록: Controller → 현재 회원 확인 → 본인의 실내 항목 페이지 → PlaceQueryService.getLocationsByIds → 응답 조합.

삭제: Controller → 현재 회원·번호 확인 → 소유자 조건 DELETE → 삭제된 행이 없으면 404.

FavoriteService 전체에 긴 트랜잭션을 걸지 않고 DB 작업마다 짧게 처리한다. 사전 중복 조회 사이의 동시 요청은 V3의 UNIQUE로 막고 실패 트랜잭션을 롤백한다. 장소·회원 확인 뒤 삭제되는 경우의 잘못된 참조는 FK가 막는다. 활성 상태는 조회 시점에 확인하며 등록 뒤 비활성화될 수 있으므로 목록의 `available`도 확인해야 한다.

기존 V1~V3, 테이블, 엔티티, 의존성은 유지한다. 새 Flyway SQL은 없으며 운영 DB·기존 FastAPI 데이터를 변경하지 않았다.

## 6. 기존 앱과의 차이

기존 FastAPI 요청은 앱의 문자열 `id`, `type=place`, `name`, 주소·분류 등을 받았다. 이 API는 서버가 ID를 발급하고 DB 장소를 참조하는 새 계약이다. 기존 앱을 그대로 연결해 동작한다고 가정하지 않는다.

- 앱의 장소 선택에서 `/locations` 응답의 내부 ID를 JSON 정수 `indoorLocationId`로 전달한다. 기존 장소 응답의 문자열 ID 변환이 필요하다.
- 새 즐겨찾기 응답의 `id`를 삭제용으로 보관한다. 장소 ID를 삭제 경로에 넣지 않는다.
- 표시 정보는 `location`에서 읽으며 `available=false`를 처리한다. 사용자 지정 이름·집/회사 분류 저장은 이번 범위에 포함하지 않는다.
- 삭제 응답은 기존 200과 삭제 객체에서 **204와 빈 본문**으로 바뀐다.
- 기존 데이터의 ID 대응표, 버스·정류장 계약, 실제 앱 전환은 후속 작업이다.

관련 문서: [즐겨찾기 모듈](../modules/FAVORITE.md) · [DB 설계](../../DATABASE_SCHEMA.md) · [장소 API](PLACE_API.md).
