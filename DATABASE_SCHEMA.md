# MagNavi API Service — 데이터베이스 스키마 설계

최초 작성일: 2026-09-10 · 구현 반영일: 2026-09-20 · 상태: 2단계 DB 기반 로컬 구현·격리 검증 완료

[프로젝트 개요](PROJECT_OVERVIEW.md) · [구현 계획](IMPLEMENTATION_PLAN.md) · [모듈 안내](docs/modules/README.md)

이 문서는 Spring 서버가 무엇을 저장하고, 테이블을 어떻게 연결하며, 어떤 규칙으로 데이터를 보호할지 설명한다. 기본 8개 테이블은 Flyway SQL과 JPA 엔티티·저장소로 구현했고 격리 MySQL에서 생성·검증했다. 기존 개발/운영 DB에 적용한 상태는 아니며, 선택 기능·데이터 이관은 후속 계획으로 구분한다. 3-1단계 일반 회원·JWT, 4-1단계 실내 장소 조회, 4-2단계 모델 매핑 내부 조회는 기존 테이블을 사용하며 SQL 변경은 없다. 이 문서가 스키마 상세의 기준이고, 구현 순서와 기능 흐름은 연결된 문서에서 관리한다.

## 1. 이번 설계의 전제

- Spring은 하나의 MySQL 업무 DB를 사용한다. 모듈마다 DB를 분리하지 않는다.
- 기존 일반 로그인을 유지하는 안에 카카오·구글 로그인을 추가한다. 일반 로그인 제거는 합의하지 않았다.
- 앱은 지도를 표시하고, Spring의 place 모듈은 네이버 지역 검색을 중계한다.
- 네이버 검색 결과 전체를 자체 장소 DB로 수집하지 않는다.
- 직접 관리하는 실외 장소·접근성 정보가 필요할지는 별도로 결정한다.
- 센서 원본·실시간 예측 결과·사용자 이동 이력은 DB와 일반 운영 로그에 저장하지 않는다.
- 외부 검색 결과의 캐시·즐겨찾기 저장 허용 범위는 아직 미확정이다.

계정 연결, 재발급 토큰, 탈퇴, 자체 실외 장소 관리는 아래에서 제안·선택 사항으로 구분한다. 문서에 등장한다고 전부 초기 필수 기능으로 확정된 것은 아니다.

### 1.1 2단계에서 확정한 범위

- 새 빈 DB의 테이블 구조부터 구현한다. 기존 FastAPI DB의 데이터는 그대로 보존하고 실제 이관은 후속 작업으로 결정한다.
- 일반 로그인 ID는 `String.strip()`으로 앞뒤 공백을 제거하고 대소문자를 구분한다. `Alice`와 `alice`는 다른 ID이며 저장·조회 모두 같은 공백 정리를 사용한다.
- 이메일·전화번호는 선택값이다. 이메일 중복을 허용하고 이메일만으로 회원을 합치지 않는다.
- [V1 회원 SQL](src/main/resources/db/migration/V1__create_member_tables.sql), [V2 장소 SQL](src/main/resources/db/migration/V2__create_indoor_place_tables.sql), [V3 즐겨찾기 SQL](src/main/resources/db/migration/V3__create_favorites.sql)이 실제 테이블·인덱스·제약의 기준이다.
- 각 모듈의 `domain`에 8개 JPA 엔티티, `infrastructure/persistence`에 8개 저장소를 구현했다. 모듈 간 참조는 다른 모듈의 엔티티 객체 대신 ID로 표현하고 DB FK로 보호한다. 3-1단계에서 MemberPersistenceAdapter와 일반 회원 API·서비스를 연결했다. 4-1단계에서 PlacePersistenceAdapter와 건물·층·활성 장소 조회를 연결하고 전용 PlaceReadRepository를 추가했다. 4-2단계에서는 ModelLocationQueryService와 ModelLocationPersistenceAdapter로 모델 키·버전·코드에 연결된 활성 장소를 조회한다. 다른 업무 API는 후속 구현이다.

## 2. 먼저 알아둘 용어

| 용어 | 쉬운 설명 |
|---|---|
| 테이블 | 같은 종류의 정보를 모아 둔 표 |
| 컬럼 | 표의 항목. 예: 이름, 회원 번호 |
| PK | 한 행을 구분하는 고유 번호 |
| FK | 다른 표의 실제 행을 가리키는 연결 |
| NOT NULL | 반드시 값이 있어야 한다는 규칙 |
| UNIQUE | 지정한 값 또는 값의 조합이 중복될 수 없다는 규칙 |
| 인덱스 | 자주 조회하는 조건을 빠르게 찾기 위한 색인 |
| 트랜잭션 | 관련 DB 변경을 함께 성공시키거나 함께 되돌리는 범위 |
| 마이그레이션 | DB 구조와 데이터를 정해진 순서로 변경하는 작업 |
| 스냅샷 | 특정 시점의 표시 정보를 복사해 둔 데이터 |

## 3. 전체 테이블과 소유 모듈

| 구분 | 테이블 | 소유 모듈 | 역할 |
|---|---|---|---|
| 기본 | `members` | member | MagNavi 회원 자체 |
| 기본 | `local_credentials` | member | 로그인 ID·비밀번호 해시 |
| 기본 | `social_accounts` | member | 카카오·구글 계정과 회원 연결 |
| 기본 | `buildings` | place | 건물 |
| 기본 | `floors` | place | 건물 안의 층 |
| 기본 | `indoor_locations` | place | 실내 장소 기준 정보 |
| 기본 | `model_location_mappings` | place | 모델 출력 코드와 실내 장소 연결 |
| 기본 | `favorites` | favorite | 회원별 저장 대상 |
| 선택 | `outdoor_places` | place | 우리가 직접 관리하는 실외 장소 |
| 선택 | `place_facilities` | place | 자체 실외 장소의 여러 시설 |

일반 로그인을 포함해 구현한 업무 테이블은 8개다. 선택 테이블과 Flyway 자체 이력 테이블은 이 개수에 포함하지 않는다. 외부 장소 즐겨찾기 관련 컬럼은 저장 정책 확정 후 적용한다.

```text
members 1 ── 0..1 local_credentials
members 1 ── 0..N social_accounts
members 1 ── 0..N favorites

buildings 1 ── N floors 1 ── N indoor_locations
indoor_locations 1 ── N model_location_mappings
indoor_locations 1 ── N favorites         # 내부 실내 장소를 저장한 경우

선택: outdoor_places 1 ── N place_facilities
선택: outdoor_places 1 ── N favorites     # 자체 실외 장소를 저장한 경우
```

1은 하나, N은 여러 개, 0..1은 없거나 하나라는 뜻이다. 회원 생성 중에는 로그인 연결이 잠시 없을 수 있지만, 가입 완료 시 선택한 로그인 수단이 있어야 한다. 이 규칙은 가입 트랜잭션에서도 검증한다.

FK가 모듈 사이를 연결해도 데이터 수정 책임이 합쳐지는 것은 아니다. favorite는 회원 번호를 참조할 수 있지만 member의 내부 저장소로 회원 이름을 수정하지 않는다.

## 4. 공통 컬럼·자료형 규칙

- 일반 내부 PK는 `BIGINT` 자동 증가다. `local_credentials.member_id`만 기존 회원 ID를 PK/FK로 사용한다. 외부 제공자의 ID는 문자열로 따로 보관한다.
- 8개 테이블 모두 생성·수정 시각을 `DATETIME(6)`으로 저장한다. JPA의 `AuditedEntity`는 `Instant`를 UTC·마이크로초 정밀도로 기록하고 생성 시각은 UPDATE에서 제외한다. 직접 SQL을 작성할 때도 UTC 값을 넣어야 하며 JPA 콜백은 직접 SQL에 적용되지 않는다.
- 이름·주소는 UTF-8 문자 저장이 가능한 `utf8mb4`를 사용한다.
- 로그인 ID의 대소문자·공백 처리와 DB 비교 규칙을 맞췄다. 테이블과 로그인 ID는 `utf8mb4_0900_bin`을 사용해 대소문자를 구분하며 악센트나 외부 코드를 임의로 바꾸지 않는다.
- 소셜 사용자 ID와 외부 코드는 임의로 소문자로 바꾸지 않는다. 대소문자를 구분해야 하는 값에는 맞는 collation을 선택한다.
- 위도·경도는 `DECIMAL(10,7)`을 제안한다. 이는 저장 형식이지 측정 정확도 보장이 아니다.
- 종류·상태는 우선 문자열과 허용값 검증으로 표현한다. 필요한 DB CHECK 제약은 사용할 MySQL 버전에서 검증한다.
- 아래 기본 테이블의 길이와 인덱스는 MySQL 8.4.8에서 검증했다. 실제 이관 데이터·외부 제공처 계약은 후속 단계에서 길이·식별 범위를 다시 확인한다. 선택 테이블의 길이는 아직 제안이다.

회원·장소·즐겨찾기 ID, 소셜 ID, 모델 코드는 서로 다른 식별자다. 값이 우연히 같아도 같은 것으로 취급하지 않는다.

## 5. 회원과 로그인 수단

### 5.1 members — 서비스 회원

| 컬럼 | 자료형 | 필수 여부 | 의미 |
|---|---|---|---|
| `id` | BIGINT | 필수·PK | 내부 회원 번호 |
| `display_name` | VARCHAR(50) | 필수 | 앱에 표시할 이름 |
| `email` | VARCHAR(254) | 선택 | 연락용 이메일 |
| `phone_number` | VARCHAR(20) | 선택 | 연락처. 기존 값은 이관 검토 |
| `role` | VARCHAR(20) | 필수 | USER 또는 ADMIN, 새 객체 기본값 USER |
| `status` | VARCHAR(20) | 필수 | 현재 허용값 ACTIVE. 정지·탈퇴는 업무 정책과 후속 SQL로 확장 |
| `created_at`, `updated_at` | DATETIME(6) | 필수 | 생성·수정 시각 |

로그인 ID와 비밀번호는 이 테이블에서 분리한다. 소셜 전용 회원에게 가짜 비밀번호를 만들지 않는다.

이메일은 소셜 계정 식별자가 아니며 전역 UNIQUE를 두지 않는다. 이메일·전화번호는 선택값으로 확정했다. 같은 이메일이라는 이유만으로 서로 다른 계정을 자동 연결하지 않는다. 이는 기존 일반 가입 규격의 변경점이므로 API 구현 때 앱의 입력·응답 규격도 맞춘다.

### 5.2 local_credentials — 일반 로그인

| 컬럼 | 자료형 | 규칙 |
|---|---|---|
| `member_id` | BIGINT | PK이면서 members.id를 참조하는 FK |
| `login_id` | VARCHAR(50) | NOT NULL·UNIQUE |
| `password_hash` | VARCHAR(255) | NOT NULL. 원문 비밀번호 저장 금지 |
| `created_at`, `updated_at` | DATETIME(6) | NOT NULL |

member_id 자체가 PK이므로 한 회원에게 일반 로그인 정보는 최대 하나다. 일반 로그인 계정은 이 행이 있고, 소셜 전용 회원은 없어도 된다.

일반 가입 흐름:

1. 입력·로그인 ID 중복을 확인하고 비밀번호 해시를 준비한다.
2. 짧은 트랜잭션에서 members와 local_credentials를 함께 저장한다.
3. 로그인 ID 중복 등으로 두 번째 저장이 실패하면 회원 생성도 되돌린다.
4. 비밀번호 정보를 제외한 응답을 반환한다.

로그인은 local_credentials로 비밀번호를 검증하고 연결된 회원의 사용 가능 상태를 확인한다.

### 5.3 social_accounts — 소셜 계정 연결

| 컬럼 | 자료형 | 규칙 |
|---|---|---|
| `id` | BIGINT | PK |
| `member_id` | BIGINT | NOT NULL·members.id FK |
| `provider` | VARCHAR(20) | NOT NULL. KAKAO 또는 GOOGLE |
| `provider_user_id` | VARCHAR(255) | NOT NULL. 제공자가 검증한 사용자 ID |
| `created_at`, `updated_at` | DATETIME(6) | NOT NULL |

구현한 고유 제약:

- `UNIQUE(provider, provider_user_id)`: 같은 소셜 계정을 두 회원에게 연결하지 않는다.
- `UNIQUE(member_id, provider)`: 초기에는 회원당 제공자별 계정 하나만 연결한다.

이는 제공자별 서비스 앱·식별 범위를 설정으로 고정하는 초기안이다. 카카오 앱을 여러 개 사용하거나 독립된 식별 범위를 한 DB에서 운영한다면, 앱/issuer 범위 컬럼과 고유 키를 함께 확장해야 한다. 개발·운영 인증 설정과 DB를 혼용하지 않는다.

구글의 sub와 카카오의 앱별 회원번호를 사용한다. 이메일은 변경되거나 제공되지 않을 수 있으므로 대신 사용하지 않는다. 참고: [Google 식별자](https://developers.google.com/identity/openid-connect/openid-connect), [Kakao 회원 식별](https://developers.kakao.com/docs/ko/kakaologin/common).

소셜 로그인 흐름:

1. 앱 SDK 또는 서버 인증 흐름에 맞는 인증 결과를 받는다.
2. 제공자별 서명·발급자·대상 앱·만료 등을 검증하거나 정해진 서버 API로 확인한다.
3. 검증한 provider와 provider_user_id로 연결을 조회한다.
4. 기존 연결이면 해당 회원을 사용한다. 신규이면 회원과 소셜 연결을 같은 트랜잭션으로 생성한다.
5. 회원 상태 확인 후 MagNavi 자체 액세스 토큰을 발급한다.

외부 검증 통신 동안 DB 트랜잭션을 길게 유지하지 않는다. 동시에 같은 소셜 계정의 최초 로그인이 들어오면 고유 제약으로 중복을 막고, 실패한 신규 회원 생성은 롤백한 뒤 확정된 연결을 다시 조회한다.

앱이 보낸 이메일·사용자 ID만 믿고 회원을 찾지 않는다. 구글 토큰 검증의 참고 자료는 [백엔드 인증 가이드](https://developers.google.com/identity/sign-in/web/backend-auth)다. 제공자 토큰·인가 코드를 로그인 이력처럼 기본 보관하지 않는다.

### 5.4 계정 연결은 자동 병합이 아니다

회원 101에 카카오와 구글을 연결하면 두 로그인 수단으로 같은 즐겨찾기를 사용할 수 있다.

연결 기능을 제공할 경우 기존 회원으로 재인증한 뒤 새 소셜 계정도 인증하고, 그 계정이 타 회원에 연결되어 있지 않은지 검사한다. 이메일 일치만으로 연결하지 않는다. 다른 회원의 연결을 새 회원에게 덮어쓰지 않는다.

연결 기능 없이 두 제공자로 별도 가입하면 회원도 별개가 될 수 있다. 초기 연결 UI·API 포함 여부와 연결 해제 시 마지막 로그인 수단 보호는 결정 사항이다.

## 6. 실내 장소와 모델 코드

### 6.1 buildings, floors, indoor_locations

| 테이블 | 구현한 주요 컬럼 | 중요한 관계 |
|---|---|---|
| buildings | id, name VARCHAR(255), address VARCHAR(255), 생성·수정 시각 | 이름·주소 필수, 건물 이름의 전역 UNIQUE 없음 |
| floors | id, building_id, floor_number INT, name VARCHAR(50), 생성·수정 시각 | 모두 필수, building_id FK, UNIQUE(building_id, floor_number) |
| indoor_locations | id, floor_id, name VARCHAR(255), description TEXT, is_active BIT(1), 생성·수정 시각 | description만 선택, floor_id FK, 이름의 전역 UNIQUE 없음 |

예시: 건물 1=A관, 층 10=A관 2층, 장소 31=205호 앞. 장소 31은 floor_id=10으로 어느 층인지 알 수 있다. 건물 주소를 모든 장소에 복사하지 않는다.

층 번호는 INT로 두어 지하층도 표현할 수 있게 한다. 0층 등 표기 규칙은 실제 건물 기준으로 정한다. legacy_location_code는 이번 SQL에 넣지 않았다. 이후 데이터 이관에서 필요하면 전역/구역별 의미와 제약을 확인해 추가한다. 설명은 Java에서 최대 10,000자로 제한해 utf8mb4 TEXT의 바이트 한도 안에 둔다.

### 6.2 model_location_mappings

| 컬럼 | 의미 |
|---|---|
| id | 매핑 자체 PK |
| model_key | 모델 종류·적용 구역을 구분하는 키 |
| model_version | 해당 모델의 버전 |
| location_code | 모델이 반환하는 코드, 문자열 |
| indoor_location_id | 실제 실내 장소 FK |
| created_at, updated_at | 매핑 생성·수정 시각 |

model_key·model_version·location_code는 필수이며 이 세 값의 조합을 UNIQUE로 둔다. indoor_location_id도 필수다. 구현한 길이는 각각 VARCHAR(128)·VARCHAR(64)·VARCHAR(50)이다. 실제 모델 계약·적용 구역 데이터의 검증은 후속 단계다.

예시: IT관 모델 / v1 / 코드 205 → 장소 31.

이 테이블은 사용자가 205에 있다고 기록하는 이력이 아니다. 코드가 무엇을 뜻하는지 설명하는 사전이다. 모델 코드와 장소 PK를 같다고 가정하지 않는다.

4-2단계 내부 조회는 세 식별자를 대소문자·공백·앞자리 0까지 그대로 비교한다. 매핑 누락과 비활성 장소는 대상 없음으로 처리하며, 성공 시 매핑과 장소·층·건물을 SQL 2회로 조회한다. 실제 매핑 데이터는 아직 등록하지 않았고 모델 키에서 건물을 자동 추론하지 않는다.

실제 등록 시에는 매핑의 적용 구역과 장소의 소속 건물을 확인해야 한다. 모델 교체 시 해당 버전의 매핑 행을 먼저 준비하고 이전 버전은 보존할 수 있다. 같은 구조의 새 모델 버전은 테이블 변경 대신 데이터 등록으로 대응한다. 향후 gRPC 연결에서 선택한 모델 범위·수신 버전을 확인하고 호출 빈도를 검증한다. 현재 자체 캐시는 없으며, 도입한다면 모델 버전과 매핑·장소 활성 상태 변경 시 갱신 정책을 함께 설계한다.

## 7. 실외 장소와 네이버 검색

### 7.1 검색은 기본적으로 조회·전달 기능

앱 지도 표시와 Spring의 지역 검색 호출은 서로 다른 역할이다. 검색은 place 모듈이 담당하되, 검색할 때마다 outdoor_places에 INSERT하지 않는다.

2026-09-10 확인 기준 신규 Search API 신청은 NAVER API HUB를 사용한다. 기존 개발자센터의 신규 신청은 2026-07-31부터 중단되었다. [공식 이관 공지](https://developers.naver.com/notice/article/32530)

현재 지역 검색 명세는 최대 5개 결과와 start=1을 지원하고, 고유 장소 ID 필드를 명시하지 않는다. 따라서 naver_place_id가 항상 온다고 가정하지 않는다. 지도 앱의 모든 검색 기능을 그대로 제공하는 API도 아니다. [지역 검색 명세](https://api.ncloud-docs.com/docs/naver-api-hub-search-local)

흐름은 앱 검색어 → Spring 입력·한도 검사 → 네이버 호출 → 앱 응답이다. 검색용 Client Secret은 서버 설정에만 둔다. 좌표의 축·표현과 표시 데이터의 안전한 처리는 실제 응답으로 검증한다. API 호출 결과를 원본 그대로 로그에 남기지 않는다.

### 7.2 outdoor_places, place_facilities는 선택

자체 장소 관리가 필요할 때만 다음 테이블을 만든다.

| 테이블 | 주요 컬럼 제안 |
|---|---|
| outdoor_places | id, name, category, address, latitude, longitude, is_accessible, is_active, 생성·수정 시각 |
| place_facilities | id, outdoor_place_id FK, type, name, location_description, 접근성 정보, 생성·수정 시각 |

좌표는 latitude -90~90, longitude -180~180 범위의 유한한 수치여야 한다. 실제 조사한 접근성 정보와 외부 업체 검색 결과를 같은 출처로 취급하지 않는다.

시설이 여러 개 필요하면 place_facilities로 분리한다. 이 경우 has_facility 같은 값은 시설 행의 존재로 계산하는 방안을 우선 검토해 서로 모순되는 중복 저장을 줄인다.

검색만 필요하다면 두 테이블과 기존 실외 장소 CRUD는 보류할 수 있다. 하지만 기존 앱·DB의 실외 장소·시설 데이터를 확인하지 않고 삭제하거나 전환을 완료했다고 처리하지 않는다.

### 7.3 저장·캐시 정책은 미확정

네이버 검색 결과의 조회, 임시 캐시, 사용자 즐겨찾기용 저장은 각각 검토해야 한다. 현재 확인한 공개 명세만으로 영구 저장의 허용 여부·필드·기간을 확정하지 않는다.

적용되는 API HUB 이용 조건에서 저장·캐시·표시·갱신·삭제 조건을 확인하고, 불명확하면 제공처에 문의한 뒤 구현한다. 신청 과정에서 확인할 약관은 [API HUB 이용 안내](https://guide.ncloud-docs.com/docs/apihub-application)를 참고한다.

아래 즐겨찾기 스냅샷 설계는 이 허용 조건을 충족했을 때 적용할 후보이며, 설계 문서 자체가 저장 허가를 의미하지 않는다.

## 8. 즐겨찾기

### 8.1 공통 정보

| 컬럼 | 자료형 | 의미 |
|---|---|---|
| id | BIGINT | 즐겨찾기 자체 PK |
| member_id | BIGINT | 소유 회원 FK, 필수 |
| target_type | VARCHAR(32) | 대상 유형, 필수 |
| target_key | VARCHAR(255) | 서버가 만든 중복 판별 키, 필수 |
| indoor_location_id | BIGINT | 내부 실내 장소를 저장한 경우의 FK |
| outdoor_place_id | BIGINT | 자체 실외 장소 기능을 채택했을 때만 추가할 FK |
| provider, provider_scope, external_id | VARCHAR(32), VARCHAR(128), VARCHAR(255) | 교통 제공처·식별 범위·실제 외부 ID. BUS/BUS_STOP에서 필수 |
| legacy_id | VARCHAR(255) | 기존 앱 ID 호환이 필요할 때만 추가 |
| created_at, updated_at | DATETIME(6) | 생성·수정 시각 |

기본 UNIQUE는 (member_id, target_type, target_key)다. 키를 임의로 바꿔도 중복을 막도록 (member_id, indoor_location_id)와 (member_id, target_type, provider, provider_scope, external_id)에도 UNIQUE를 둔다. A와 B는 같은 장소를 각각 저장할 수 있고, A의 동일 대상 중복만 차단한다.

target_key는 실제 FK를 대신하지 않는다. 내부 장소는 실제 FK로도 보호하며, 키는 그 FK 값에서 서버가 일관되게 만든다. 앱이 임의 키를 보내 중복 제한을 우회하게 두지 않는다.

선택 테이블 outdoor_places를 만들지 않는 초기 마이그레이션에는 outdoor_place_id와 그 FK도 넣지 않는다.

### 8.2 유형별 대상 연결

현재 구현한 내부 유형은 INDOOR_PLACE·BUS·BUS_STOP이다. OUTDOOR_PLACE·EXTERNAL_PLACE는 허용하지 않는다. 기존 앱의 place·bus·busStop과 새 유형·ID의 변환은 API 전환 때 합의한다.

| 내부 유형 (선택 사항 구분) | 대상 연결 | 키 예시·주의 |
|---|---|---|
| INDOOR_PLACE | indoor_location_id 필수 | indoor:31 |
| OUTDOOR_PLACE — 선택 | outdoor_place_id 필수 | outdoor:88 |
| EXTERNAL_PLACE — 조건부 | 제공처와 허용된 최소 표시 정보 | NAVER 공식 장소 ID가 없을 수 있음 |
| BUS | 제공처·식별 범위·노선 ID | 같은 버스 번호라도 지역·노선이 다를 수 있음 |
| BUS_STOP | 제공처·식별 범위·정류장 ID | 표시 이름만으로 식별하지 않음 |

한 행이 실내 장소와 버스를 동시에 가리킬 수 없게 필수·금지 컬럼 조합을 정의한다. 서비스 검증과 DB CHECK를 함께 적용하되 실제 MySQL에서 NULL 조합과 제약 동작을 테스트한다.

교통의 식별 범위는 provider_scope에 필수로 받는다. 실제 제공처별 지역·방향·데이터셋 범위와 허용 값은 API 계약 때 확정한다. 현재는 문자열 저장 구조와 합성 데이터 검증만 제공하며 실제 교통 API 연동은 없다. external_id는 실제 제공자 ID이며 외부 FK가 있다고 가정하지 않는다.

실내 target_key는 `indoor:<장소 ID>`다. 교통 키는 제공처·범위·외부 ID 각각에 길이 접두어를 붙여 결합한 값의 SHA-256 앞에 `BUS:` 또는 `BUS_STOP:`을 붙인다. [Favorite](src/main/java/com/example/magnavi_springserver/favorite/domain/Favorite.java)의 팩터리만 키를 만들며 입력 키를 받지 않는다. 해시는 긴 식별 조합을 고정 길이로 표현하기 위한 것이며 실제 대상의 의미는 원본 세 컬럼에 보존한다. DB의 원본 조합 UNIQUE도 키 조작에 의한 중복을 막는다.

### 8.3 네이버 장소에 고유 ID가 없으면?

즐겨찾기 자체 id는 서버에서 발급할 수 있다. 그러나 같은 실제 장소를 다시 선택했는지 완벽하게 판별하는 것은 별개의 문제다.

저장 허용 후 후보 방식은 표시 이름·주소·좌표를 정한 규칙으로 정규화하고 중복 판별 키를 만드는 것이다. 이 키는 자체 비교 도구이며 네이버의 공식 장소 ID가 아니다. 이전·이름 변경·같은 건물의 여러 업체 때문에 오탐·중복이 생길 수 있다. 해시를 써도 이 의미상의 문제는 없어지지 않는다.

서버가 발급한 즐겨찾기 ID를 수정·삭제에 사용하고, 장소 동일성 판단의 한계와 갱신 정책은 별도로 확정한다. URL에서 임의로 장소 번호를 추출해 안정적인 식별자로 간주하지 않는다.

### 8.4 표시 정보의 저장 후보

저장 허용 범위 내에서 display_name, address, latitude, longitude, category, source_fetched_at 같은 최소 스냅샷을 검토한다. 버스·정류장은 기존 bus_number·station_name·station_id의 호환을 확인한다.

초기에는 favorites의 유형별 선택 컬럼으로 표현할 수 있으며, 정보가 커지면 유형별 상세 테이블 분리를 검토한다. 전체 외부 응답 JSON을 무조건 보관하는 안은 채택하지 않는다. 허용 필드와 보관 조건이 확정되기 전 외부 스냅샷 저장은 보류한다.

내부 장소는 place에서 최신 표시 정보를 조회할 수 있다. 외부 스냅샷은 저장 시점 정보임을 구분하고, 목록을 열 때 항목별 외부 API 호출을 무조건 반복하지 않는다.

### 8.5 등록·목록·삭제 흐름

1. 인증 결과의 member_id를 사용한다. 요청 본문의 타인 ID를 신뢰하지 않는다.
2. 내부 장소는 place의 공개 조회로 확인하고, 외부 대상은 제공처·저장 허용·입력 규격을 확인한다.
3. 서버가 대상 키를 만들고 짧은 트랜잭션에서 저장한다.
4. 동시 중복은 UNIQUE 제약으로 차단하고 합의한 중복 응답으로 변환한다.
5. 목록은 본인의 행만 조회한다.
6. 삭제는 id와 member_id를 함께 조건으로 사용한다. 장소 원본이나 다른 회원의 행은 삭제하지 않는다.

기존 legacy_id를 유지한다면 회원과 함께 조회·구분하며, 새 내부 PK와 응답 필드의 전환 규칙을 정한다.

## 9. 조회·삭제·트랜잭션 규칙

| 대상 | 구현한 제약·인덱스 | 목적 |
|---|---|---|
| local_credentials | login_id UNIQUE | 일반 로그인 조회·중복 차단 |
| social_accounts | provider+provider_user_id UNIQUE, member_id+provider UNIQUE | 로그인 조회·연결 충돌 방지 |
| floors | building_id+floor_number UNIQUE | 같은 건물의 층 중복 방지 |
| model_location_mappings | model_key+model_version+location_code UNIQUE | 올바른 매핑 조회 |
| favorites | member_id+target_type+target_key UNIQUE | 회원별 중복 방지 |
| favorites | member_id+created_at+id 조회 인덱스 | 내 목록의 생성 시각·PK 내림차순 Slice 조회 |

이미 PK·UNIQUE로 지원되는 조회에 같은 인덱스를 중복 추가하지 않는다. 실제 쿼리와 실행 계획으로 필요한 인덱스를 확정한다. 소유권 검사는 인덱스의 존재만으로 해결되지 않는다.

참조 중인 부모 행은 FK의 기본 NO ACTION 동작으로 물리 삭제를 거절하며 연쇄 삭제하지 않는다. MySQL InnoDB에서 즉시 참조를 확인한다. 실내 장소에는 비활성화 메서드가 있으며 4-1 조회 API는 활성 장소만 반환하고 비활성 장소 상세는 404로 처리한다. 장소 등록·상태 변경 API는 후속 결정 사항이다. 회원 탈퇴 시 인증 정보·즐겨찾기의 정리 범위와 보관 정책은 별도로 확정한다.

장소 비활성화는 물리 FK만으로 막을 수 없으므로 신규 즐겨찾기 등록과 표시 정책에서도 검사한다. 회원 상태 변경 후 이미 발급한 토큰의 효력은 별도 인증 정책이다. 상태 컬럼을 바꿨다고 모든 연결이 즉시 취소되는 것은 아니다.

가입의 두 테이블 저장, 소셜 신규 회원·연결 저장, 즐겨찾기 등록·삭제처럼 짧은 업무 단위로 트랜잭션을 적용한다. 소셜 검증·네이버 검색·gRPC·WebSocket 연결 전체를 DB 트랜잭션으로 감싸지 않는다.

## 10. Flyway와 JPA의 역할

- Flyway: 테이블·제약·인덱스 변경 SQL과 적용 이력.
- JPA: Java 객체를 통한 데이터 조회·저장.
- ddl-auto=validate: 엔티티와 DB 구조가 맞는지 기동 시 검증한다. 모든 UNIQUE·FK·CHECK 의미까지 검증하는 것은 아니므로 실제 SQL 실패 테스트를 함께 둔다.

스키마 변경 수단은 Flyway로 통일한다. 운영에서 JPA update와 Flyway가 각각 구조를 바꾸게 하지 않는다. 기본 경로는 src/main/resources/db/migration이며, 버전 SQL로 변경을 쌓는다. [Spring Boot DB 초기화 안내](https://docs.spring.io/spring-boot/how-to/data-initialization.html)

다음 세 버전 SQL을 구현했다. Spring Boot 기동 시 Flyway가 적용한 다음 JPA 매핑을 검증한다.

- V1__create_member_tables.sql: 회원·일반 인증·소셜 연결.
- V2__create_indoor_place_tables.sql: 건물·층·실내 장소·모델 매핑.
- V3__create_favorites.sql: 확정한 대상 유형·제약·인덱스.
- 후속 버전: 선택 실외 장소와 허용된 외부 스냅샷 등.

공유 환경에 적용한 SQL은 덮어쓰기보다 새 버전으로 수정한다. 기존 DB에 초기 생성 SQL을 무조건 실행하지 않는다. Flyway 이력은 업무 데이터 백업을 대체하지 않는다.

## 11. 기존 FastAPI 데이터 이관 — 후속 작업

2026-09-20에 새 스키마부터 구현하고 기존 데이터 이관은 나중에 결정하기로 했다. 기존 DB를 변경·비우거나 원본 데이터를 옮기지 않았다. 아래는 향후 이관을 선택할 때의 계획이다. Flyway V1→V3 확장 테스트는 새 스키마의 버전 확장 검증이며 FastAPI 데이터 변환 검증은 아니다.

| 원본 | 목표 | 확인할 내용 |
|---|---|---|
| users | members + local_credentials | 프로필·인증 분리, 기존 내부 ID 유지 또는 변환 |
| predicted_locations | buildings + floors + indoor_locations + model_location_mappings | 원래 문자열 ID와 모델 코드 보존 |
| favorites | favorites | 자체 PK 도입, 소유자·유형·대상 키·표시 정보 변환 |
| outdoor_place | 선택 outdoor_places·place_facilities 또는 별도 보존 절차 | 직접 관리할지 결정, 데이터 무단 폐기 금지 |
| 없음 | social_accounts | 검증된 신규 로그인·명시적 연결로 생성 |

원본 이메일만 보고 소셜 연결 행을 미리 만들지 않는다. 기존 bcrypt 해시의 검증과 기존 JWT sub의 의미를 테스트한다.

이관 순서는 기존 데이터 유지 여부 결정 → 백업·건수·중복 확인 → ID 매핑 준비 → 격리 DB 시험 → 로그인·즐겨찾기·모델 매핑 비교 → 쓰기 주체 전환이다.

원본 즐겨찾기는 장소 FK와 제공자 구분이 부족할 수 있다. 이름만 보고 임의 장소와 연결하지 않고 미해결 데이터 목록을 만든다. 외부 저장 정책이 미확정인 기존 데이터도 별도 검토한다.

앱의 필드명·일반 로그인 폼·즐겨찾기 삭제 ID·실외 장소 API를 언제 전환할지 합의한다. 두 서버가 같은 업무 데이터를 제각각 쓰는 기간을 만들지 않는다. 롤백 시 DB도 자동 복구된다고 가정하지 않는다.

## 12. 저장하지 않는 정보

- 센서 샘플·예측 이력·이동 경로.
- 전체 네이버 검색 결과와 무제한 검색 이력.
- 비밀번호 원문, 불필요한 소셜 인가 코드·토큰.
- DB 비밀번호, JWT 서명 키, 외부 API Client Secret.

측정 세션은 제한된 메모리 자원으로 관리한다. 우리 서비스의 refresh_tokens는 재발급·폐기 정책을 정할 때 별도로 설계하며 소셜 제공자 토큰과 혼동하지 않는다.

## 13. 확정한 정책과 후속 결정 사항

| 항목 | 결정할 내용 |
|---|---|
| 일반 로그인 | ID 공백 제거·대소문자 구분 확정. 기존 폼·토큰 호환은 후속 작업 |
| 회원 프로필 | 이메일·전화번호 선택·이메일 중복 허용 확정. 표시 이름 수집은 가입 흐름에서 연결 |
| 소셜 로그인 | 앱 SDK·서버 인증 방식·허용 client/앱·계정 연결 초기 포함 여부 |
| 계정 수명 | 연결 해제·탈퇴·재발급·상태 변경 후 토큰 처리 |
| 네이버 검색 | API HUB 신청·실제 응답·검색 화면에 필요한 결과 수 |
| 외부 저장 | 캐시·스냅샷 허용 필드·기간·표시·갱신·삭제 조건 |
| 장소 범위 | 자체 실외 장소·접근성 관리 여부와 원본 데이터 보존 |
| 즐겨찾기 | 내부/외부 구분·네이버 중복 판별 한계·교통 식별 범위 |
| DB 이관 | 새 빈 DB 구조 구현 확정. 기존 DB 보존·이관 여부와 ID 변환·복구는 후속 결정 |
| DB 환경 | MySQL 8.4.8에서 문자 비교·CHECK·UTC·제약 검증 완료. 운영 용량·쿼리 성능은 후속 검증 |

## 14. 완료 확인

- [x] 빈 테스트 DB를 Flyway로 재현하고 JPA 검증을 통과한다.
- [x] V1 데이터가 V3 확장·최신 SQL 재실행 뒤에도 유지된다.
- [x] 로그인 ID 대소문자·공백 처리와 선택 연락처·이메일 중복 허용을 검증한다.
- [x] 저장소 트랜잭션에서 일반 인증 중복 실패 시 신규 회원 저장도 롤백한다.
- [x] 소셜 전용 회원 저장, 모델 키·버전·코드 매핑, 회원별 즐겨찾기 조회·중복 제약을 검증한다.
- [x] 생성·수정 시각의 UTC 저장·복원과 수정 시 생성 시각 보존을 검증한다.
- [ ] 일반 가입 실패 시 회원과 인증 정보가 부분 저장되지 않는다.
- [ ] 소셜 전용 회원은 일반 비밀번호 없이 로그인한다.
- [ ] 같은 소셜 계정의 동시 가입·타 회원 연결을 차단한다.
- [ ] 같은 이메일을 이유로 계정을 자동 병합하지 않는다.
- [ ] 기존 bcrypt·JWT·일반 로그인·회원 정보 전환을 테스트한다.
- [x] 모델 키·버전·코드의 내부 조회와 누락·비활성·입력 오류를 합성 데이터로 검증한다.
- [ ] 실제 모델 코드·적용 구역에 맞는 장소 매핑 데이터를 준비하고 연동 검증한다.
- [ ] 네이버 검색만으로 DB에 결과가 누적되지 않는다.
- [ ] 외부 스냅샷 저장은 허용 범위 확정 후 검증한다.
- [ ] A와 B의 동일 대상 저장, A의 중복, 타인 항목 삭제를 테스트한다.
- [x] 대상별 NULL 조합·FK·UNIQUE·참조 중 삭제를 실제 MySQL에서 테스트한다.
- [ ] 이관 전후 회원·즐겨찾기·장소 건수와 ID 관계를 비교한다.
- [ ] 센서·예측 결과·비밀정보가 DB와 일반 로그에 남지 않는다.
