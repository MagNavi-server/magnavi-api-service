# 건물·층·실내 장소 조회 API — 4-1단계

구현일: 2026-09-21 · 상태: Spring 로컬 구현·격리 테스트 완료

[실행 방법](../../README.md) · [장소 모듈](../modules/PLACE.md) · [DB 구조](../../DATABASE_SCHEMA.md)

## 1. 이번에 구현한 범위

기존 Flyway V2의 `buildings`, `floors`, `indoor_locations`를 읽어 앱에 전달한다. 건물·층·장소 데이터를 자동으로 만들거나 변경하지 않는다. 실제 장소 정보는 아직 등록하지 않았으므로 새 빈 DB에서 목록을 조회하면 `[]`가 정상 응답이다.

기존 FastAPI의 `/locations/`가 로그인 없이 제공하던 기준 정보 조회를 이어서, 아래 GET 경로만 공개했다. 회원 정보는 계속 JWT가 필요하며, 장소 등록·수정·삭제와 다른 경로는 열지 않았다.

| API | 기능 | 정상 응답 |
|---|---|---|
| `GET /buildings` | 건물 목록 | 200, 배열 |
| `GET /buildings/{buildingId}` | 건물 상세 | 200, 객체 |
| `GET /buildings/{buildingId}/floors` | 해당 건물의 층 목록 | 200, 배열 |
| `GET /floors/{floorId}` | 층 상세와 소속 건물 정보 | 200, 객체 |
| `GET /floors/{floorId}/locations` | 해당 층의 활성 장소 목록 | 200, 배열 |
| `GET /locations` | 전체 활성 장소 목록 | 200, 배열 |
| `GET /locations/{locationId}` | 활성 장소 상세 | 200, 객체 |

모든 경로는 끝의 `/` 유무를 모두 지원하며 리다이렉트하지 않는다. 공개 조회에는 Authorization 헤더가 필요 없다. 잘못된 Bearer 토큰을 붙이면 기존 Spring Security 정책대로 401로 거절한다.

## 2. 앱에서 사용하는 순서

```text
GET /buildings
    ↓ 사용자가 건물을 선택
GET /buildings/{buildingId}/floors
    ↓ 사용자가 층을 선택
GET /floors/{floorId}/locations
    ↓ 사용자가 장소를 선택
GET /locations/{locationId}
```

`buildingId`, `floorId`, `locationId`는 DB가 발급한 내부 식별자다. 특히 `floorId`는 실제 층 번호가 아니며, `locationId`는 Python 모델 출력 코드가 아니다. 모델 코드에서 장소를 찾는 기능은 [4-2단계 내부 조회](MODEL_LOCATION_MAPPING.md)로 구현했다. HTTP 경로는 추가하지 않았다.

서버 내부 흐름은 다음과 같다.

```text
PlaceController: 경로·쿼리 입력을 받음
    ↓
PlaceQueryService: ID·조회 범위를 검사
    ↓
PlacePersistenceAdapter: 짧은 읽기 전용 트랜잭션, 부모 존재 확인
    ↓
PlaceReadRepository: JPA의 JPQL로 건물·층·장소를 함께 조회
    ↓
필요한 값만 담은 DTO → JSON 응답
```

장소마다 건물·층을 다시 조회하지 않도록 JOIN으로 필요한 값을 함께 읽는다. `skip`·`limit`은 전체 목록을 읽은 뒤 Java에서 자르는 것이 아니라 DB 조회에 적용한다. 기존 Spring Data JPA 저장소와 엔티티는 그대로 유지했고, 복합 조회만 전용 저장소에 모았다.

## 3. 목록 범위와 정렬

네 종류의 목록 API에서 같은 쿼리 파라미터를 사용한다.

| 이름 | 기본값 | 허용 범위 | 의미 |
|---|---|---|---|
| `skip` | 0 | 0~10,000 | 앞에서 건너뛸 항목 수 |
| `limit` | 100 | 1~100 | 이번 응답에 포함할 최대 항목 수 |

예를 들어 `?skip=1&limit=2`는 첫 항목 한 개를 건너뛰고 다음 두 개를 가져온다. 페이지 번호로 바꾸지 않으므로 limit의 배수가 아닌 skip도 정확히 반영한다. 전체 개수 조회나 페이지 메타데이터는 이번 응답에 포함하지 않는다.

- 건물·장소 목록: 내부 ID 오름차순.
- 건물의 층 목록: 실제 층 번호 오름차순, 이후 ID 오름차순. 지하층의 음수 층 번호도 지원한다.
- 장소 목록: `is_active=true`인 행만 조회하며, 이 조건과 소속 층을 먼저 적용한 뒤 범위를 제한한다.
- 정상 범위지만 마지막 항목을 지나면 200과 빈 배열을 반환한다.
- `skip` 상한은 과도한 OFFSET 조회를 막기 위한 현재 구현값이다. 큰 목록 탐색이 필요해지면 커서 기반 조회를 별도 검토한다.

## 4. 응답 예시

아래 값은 설명용 가상 데이터이며 실제 서버에 자동 등록되지 않는다.

건물 상세:

```json
{"id": 1, "name": "A관", "address": "예시 주소"}
```

층 상세:

```json
{
  "id": 10,
  "buildingId": 1,
  "buildingName": "A관",
  "floorNumber": 2,
  "name": "2층"
}
```

장소 상세와 장소 목록의 각 항목:

```json
{
  "id": "31",
  "location_name": "205호 앞",
  "description": null,
  "floor": 2,
  "address": "예시 주소",
  "buildingId": 1,
  "buildingName": "A관",
  "floorId": 10,
  "floorName": "2층"
}
```

장소 응답은 기존 `id`, `location_name`, `description`, `floor`, `address`를 유지했다. 기존 스키마에 맞춰 장소 `id`만 문자열로 표현하며, 새 건물·층 API의 `id`와 `buildingId`, `floorId`는 숫자다. 장소의 `floor`는 층 번호, `address`는 소속 건물의 주소다. 엔티티의 생성·수정 시각이나 활성 여부 같은 내부 필드는 직접 반환하지 않는다.

경로·필드 형식 유지가 기존 데이터 ID의 일치를 뜻하지는 않는다. 이번 API의 장소 ID는 새 DB의 자동 생성 ID다. 예전 문자열 ID·모델 코드로 직접 조회하는 동작, 기존 데이터 이관, 실제 모바일 앱 전환은 아직 검증하지 않았다.

## 5. 오류 처리

| 상황 | 상태 / 코드 |
|---|---|
| 0·음수 ID, 범위를 벗어난 skip·limit | 400 / INVALID_REQUEST |
| 숫자가 아닌 ID·쿼리 값, 숫자 범위 초과 | 400 / INVALID_REQUEST |
| 존재하지 않는 건물·층·장소 | 404 / NOT_FOUND |
| 비활성 장소 상세 조회 | 404 / NOT_FOUND |
| 존재하는 건물에 층이 없음 | 200 / 빈 배열 |
| 존재하는 층에 활성 장소가 없음 | 200 / 빈 배열 |
| 잘못된 Bearer 헤더 | 401 / UNAUTHENTICATED |
| 이번에 열지 않은 변경 API | 미인증 401, 인증 후 403 |

오류는 회원 API와 같은 `code`, `message`, `traceId`, `errors` 형식을 사용한다. 서비스의 입력 검증 실패에는 `errors`에 필드명을 넣는다. 프레임워크가 숫자 변환을 거절한 경우에는 공통 오류 본문을 사용하며 입력 원문을 반사하지 않는다. `traceId`는 `X-Request-ID` 응답 헤더와 같다.

비활성 장소는 목록·상세에서 제외하지만 DB 행을 삭제하지 않으므로 기존 참조는 보존된다. 장소 활성 상태의 관리 API는 아직 없다.

## 6. 검증과 후속 작업

`PlaceApiIntegrationTests`의 18개 테스트로 계층별 범위, 같은 건물명·층 번호·장소명 처리, 지하층, 정확한 offset, 목록 최대 100개, 없는 대상과 빈 목록 구분, 비활성 제외, 문자열 ID와 기존 필드, 슬래시 호환, 쓰기 차단·잘못된 JWT 거절을 확인한다.

추가 장소 105개를 넣어도 전체 목록은 SQL 1회, 층별 목록은 부모 확인을 포함해 SQL 2회로 처리되는지 검증했다. 조회 전후 데이터 건수·내용도 보존된다. 테스트 자료는 임시 MySQL에만 생성하고 롤백하며 실제 건물 데이터와 운영 DB는 사용하지 않는다.

4-2단계 모델 코드 매핑 내부 조회는 별도로 구현했다. 후속 작업은 검증된 건물·층·장소·모델 매핑 기준 데이터 등록, 네이버 검색(4-3), 관리자 기능 포함 여부 결정이다. 실제 Python·모바일 연동과 운영 배포는 이번 범위에 포함하지 않는다.
