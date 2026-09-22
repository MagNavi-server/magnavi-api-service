# MagNavi API Service — 프로젝트 개요

작성일: 2026-09-10 · Git 규칙 정리일: 2026-09-18 · 기반 구현일: 2026-09-18

이 문서는 `magnavi-api-service`가 담당할 기능, 현재 생성된 Spring 프로젝트와 목표 구조를 설명한다. 상세 작업은 [Spring 구현 계획](IMPLEMENTATION_PLAN.md)을 참고한다.

각 모듈의 역할과 처리 흐름은 [모듈별 안내서](docs/modules/README.md)에서 설명한다. 테이블·컬럼·관계·저장 정책의 상세 기준은 [DB 스키마 설계](DATABASE_SCHEMA.md)다.

개발 작업의 공통 기준은 [커밋 컨벤션](docs/convention/commit-convention.md)과 [브랜치·PR·배포 규칙](docs/convention/branch-convention.md)을 따른다.

이번 설계에는 기존 일반 로그인 유지안, 카카오·구글 로그인, 네이버 지역 검색을 반영했다. 외부 검색 결과의 저장과 자체 실외 장소 관리는 조건부 항목이다.

현재는 실행·테스트 기반, 8개 업무 테이블, 일반 회원가입·로그인·JWT·내 정보·이름 변경을 구현했다. 소셜 로그인·장소·즐겨찾기·실시간 API는 후속 구현 목표다. 개발 실행 방법과 현재 검증 범위는 [README](README.md)를 참고한다.

## 1. 이 서버는 어떤 역할인가?

MagNavi는 센서값으로 사용자의 실내 위치를 예측하는 서비스다. Spring 서버는 앱이 이용하는 백엔드의 입구로서 회원·인증·장소·즐겨찾기와 업무 DB를 관리한다.

실시간 위치 예측에서는 앱의 센서값을 내부 모델 서비스로 전달하고, 예측 결과를 해당 앱에 돌려준다. 이 서버가 모델 파일을 읽거나 자기장 보정·모델 계산을 직접 구현하지는 않는다.

주요 목표:

- 기존 FastAPI의 회원·실내 장소·즐겨찾기 기능을 이관한다.
- 일반 로그인에 카카오·구글 로그인을 추가하고 회원과 로그인 수단을 분리한다.
- 앱의 지도 표시와 구분하여 Spring에서 네이버 지역 검색을 중계한다.
- 자체 실외 장소 CRUD는 필요성과 기존 앱·데이터 보존 정책을 확인한 뒤 이관 여부를 정한다.
- 업무 데이터의 변경과 트랜잭션을 Spring에서 관리한다.
- 인증된 앱과 WebSocket으로 연결한다.
- Python 모델 서비스와 비동기 gRPC 스트리밍으로 통신한다.
- 여러 사용자의 연결·권한·결과 전달을 분리한다.
- 센서 원본과 예측 결과를 DB에 저장하지 않는다.

개발자는 한 명이지만 앱 사용자는 여러 명일 수 있다는 전제다.

## 2. 현재 프로젝트 상태

```text
MagNavi_SpringServer/
├── build.gradle
├── settings.gradle
├── gradlew / gradlew.bat
├── gradle/wrapper/
├── compose.local.yaml    # 개발용 MySQL, 로컬 포트·볼륨·쿼리 healthcheck
├── .env.example          # 값 없는 개발 환경변수 예시
├── src/main/java/com/example/magnavi_springserver/
│   ├── MagNaviSpringServerApplication.java
│   ├── member/            # 일반 회원 API·JWT·엔티티·저장소, 소셜 인증은 빈 골격
│   ├── place/             # 실내 장소·모델 매핑의 엔티티·저장소
│   ├── favorite/          # 유형별 즐겨찾기 엔티티·소유 회원 조회 저장소
│   ├── positioning/       # 4개 계층의 빈 클래스
│   ├── shared/            # 오류·요청 추적·공통 ID/UTC 시각·값 검사
│   └── config/            # 접근 정책·JWT 설정 구현, WebSocket·gRPC는 빈 골격
├── src/main/resources/
│   ├── application.yaml
│   ├── application-local.yaml
│   ├── application-prod.yaml
│   └── db/migration/      # V1 회원·V2 장소·V3 즐겨찾기 SQL
├── src/test/java/com/example/magnavi_springserver/
│   ├── MagNaviSpringServerApplicationTests.java # 격리 MySQL·상태·접근 정책
│   ├── support/           # Testcontainers MySQL 설정
│   ├── persistence/       # 마이그레이션·8개 엔티티·DB 제약·트랜잭션 검증
│   ├── member/            # 회원 API·JWT 설정·BCrypt 검증 23개
│   ├── place/
│   ├── favorite/
│   ├── positioning/
│   ├── shared/            # 오류·추적 테스트
│   └── config/
├── src/test/resources/application-test.yaml
├── PROJECT_OVERVIEW.md
├── IMPLEMENTATION_PLAN.md
├── DATABASE_SCHEMA.md     # 테이블·관계·제약·저장·이관 정책
└── docs/modules/
    ├── README.md          # 모듈 구조·용어·읽는 순서
    ├── MEMBER.md          # 회원과 인증
    ├── PLACE.md           # 장소 정보
    ├── FAVORITE.md        # 개인 즐겨찾기
    ├── POSITIONING.md     # 실시간 측정 연결
    └── SHARED.md          # 최소 공통 지원 영역
```

초기에는 역할별 빈 클래스 39개를 준비했고, 1단계에서 `SecurityConfig`, `ErrorResponse`, `TraceIdGenerator`를 구현하고 오류 변환·요청 추적 클래스를 추가했다. 2단계에서는 회원·장소·즐겨찾기 도메인 8개를 JPA 엔티티로 구현하고 각 모듈의 `infrastructure/persistence`에 저장소 8개를 추가했다. 3-1단계에서는 일반 회원 서비스·API·MemberPersistenceAdapter·비밀번호 해싱·JWT 발급/검증·`AuthenticatedMember`를 구현했다. 소셜 인증, 다른 업무 API·어댑터, 실시간 클래스와 WebSocket·gRPC 설정은 빈 골격이다.

공통·local·test·prod 설정과 개발용 MySQL Compose, Testcontainers 테스트를 추가했다. 기본 테스트는 MySQL 연결·상태 확인·접근 정책을 검증하도록 바꿨고 오류 변환·동시 요청 추적 테스트를 추가했다. JPA는 `validate`, 스키마 변경은 Flyway 기준이다. V1~V3 SQL과 엔티티를 실제 MySQL 8.4.8에서 검증했다. 새 DB 구조부터 구현하며 기존 FastAPI 데이터 이관은 후속 작업으로 분리했다.

생존·준비 상태 확인 GET 두 개와 일반 가입·로그인 POST를 공개한다. 내 정보 GET·이름 변경 PUT는 JWT로 보호하고 나머지 경로는 기본 거절한다. 임시 로그인 사용자·세션 인증은 사용하지 않는다. 준비 상태는 DB 연결을 포함하며, 모델 서비스 연결은 아직 포함하지 않는다. 운영 배포용 이미지·Compose·CI/CD는 구현 전이다.

### 2.1 선언된 기술과 의존성

| 항목 | 현재 선언과 목적 |
|---|---|
| Java | 21 |
| Spring Boot | 4.1.1 |
| Gradle | Wrapper 기반 빌드 |
| Spring MVC | REST API |
| Spring Data JPA·MySQL | 업무 데이터 저장·조회 |
| Flyway | DB 스키마 변경 이력 |
| Spring Security | 인증과 권한 검사 |
| OAuth2 Resource Server | Bearer JWT 검증 기반 |
| Validation | 요청 형식 검증 |
| gRPC Client | 내부 모델 서비스 호출 기반 |
| protobuf Gradle 플러그인 | 계약으로부터 통신 코드 생성 기반 |
| Actuator | 상태 확인과 운영 정보 |
| Lombok·테스트 의존성 | 코드 작성 보조와 검증 |
| Spring Boot Testcontainers·MySQL 모듈 | 운영 설정과 분리한 실제 MySQL 통합 테스트 |

소셜 로그인 방식에 따라 OAuth2 Client 등 추가 의존성을 검토한다. 현재 Resource Server 의존성만으로 카카오·구글 가입·계정 연결이 완성되지는 않는다.

의존성을 추가했다고 해당 기능이 완성된 것은 아니다. 일반 로그인·JWT는 별도 구현을 마쳤고 WebSocket 연결, 비동기 gRPC stub과 protobuf 생성 설정은 후속 작업이다. Java 21에서 전체 Gradle 빌드와 기반·DB·회원 테스트 65개를 통과했다. 이 검증은 아직 없는 계약 생성이나 실제 모델 연동을 포함하지 않는다.

## 3. 제공할 기능

### 3.1 기존 기능 이관

| 이관할 기능 | 기존 API | Spring 구현 방향 |
|---|---|---|
| 회원가입 | `POST /users/signup` | 3-1단계 구현, [회원 API 안내](docs/api/MEMBER_API.md) |
| 로그인·JWT 발급 | `POST /users/login` | 3-1단계 구현, [회원 API 안내](docs/api/MEMBER_API.md) |
| 내 정보 조회 | `GET /users/me` | 3-1단계 구현, [회원 API 안내](docs/api/MEMBER_API.md) |
| 내 이름 변경 | `PUT /users/me/username` | 3-1단계 구현, [회원 API 안내](docs/api/MEMBER_API.md) |
| 즐겨찾기 등록 | `POST /favorites/` | 동등 기능 구현 |
| 내 즐겨찾기 목록 | `GET /favorites/` | 동등 기능 구현 |
| 내 즐겨찾기 삭제 | `DELETE /favorites/{favorite_id}` | 동등 기능 구현 |
| 실내 위치 목록 | `GET /locations/` | 동등 기능 구현 |
| 실내 위치 상세 | `GET /locations/{location_id}` | 동등 기능 구현 |
| 실외 장소 등록 | `POST /outdoor-places/` | 자체 관리·기존 앱 호환 확인 후 결정 |
| 실외 장소 목록 | `GET /outdoor-places/` | 자체 관리·기존 앱 호환 확인 후 결정 |
| 실외 장소 상세 | `GET /outdoor-places/{place_id}` | 자체 관리·기존 앱 호환 확인 후 결정 |
| 실외 장소 수정 | `PATCH /outdoor-places/{place_id}` | 자체 관리·기존 앱 호환 확인 후 결정 |
| 실외 장소 삭제 | `DELETE /outdoor-places/{place_id}` | 자체 관리·기존 앱 호환 확인 후 결정 |

이 표는 기존 FastAPI의 API 형식을 이관 기준으로 정리한 것이다. URL·필드·응답 호환 여부를 확인하면서 Spring API를 구현한다.

실내 위치 조회는 DB에 등록한 장소 정보를 조회하는 기능이다. 사용자 이동 기록을 조회하는 기능이 아니다. 기존 즐겨찾기는 장소뿐 아니라 버스·정류장 유형도 지원하므로 이관 과정에서 유지한다.

### 3.2 소셜 로그인과 네이버 검색

member는 members·local_credentials·social_accounts를 나누고, 검증한 소셜 계정을 서비스 회원에게 연결한다. 모든 로그인 방식은 이후 MagNavi 자체 토큰을 사용한다. 이메일 일치만으로 자동 병합하지 않는다.

place는 네이버 검색을 중계한다. 앱이 지도를 표시하고 Spring이 검색 입력·외부 호출·실패 처리를 담당한다. 검색 결과 전체를 자체 DB에 쌓지 않는다.

신규 신청은 NAVER API HUB를 기준으로 한다. 현재 지역 검색은 최대 5개 결과·start=1이며 고유 장소 ID가 명시되어 있지 않다. 저장·캐시 허용 범위는 별도 확인 후 구현한다. [지역 검색 명세](https://api.ncloud-docs.com/docs/naver-api-hub-search-local)

### 3.3 새로 구현할 실시간 기능

1. 앱 연결과 사용자를 인증한다.
2. 측정 세션을 만들고 Python으로 향하는 gRPC 스트림을 연다.
3. 센서 형식·순번·전송량을 검증해 순서대로 전달한다.
4. 모델의 워밍업·예측 결과·오류를 앱 메시지로 변환한다.
5. 결과를 연결된 해당 사용자에게 보낸다.
6. 앱 종료·토큰 만료·모델 장애·재접속 시 연결과 대기 데이터를 정리한다.

센서 16개를 모으는 모델용 윈도우는 Python이 관리한다. Spring은 전달을 위해 필요할 때만 크기가 제한된 큐를 사용한다.

### 3.4 초기 범위에 자동으로 포함하지 않는 기능

로그아웃·토큰 재발급·회원 탈퇴·계정 연결/해제 UI·관리자용 실내 장소 변경 API의 초기 범위는 별도로 정한다. 자체 실외 장소·시설 관리도 선택 사항이다. 외부 검색 결과의 스냅샷 저장은 허용 조건 확인 전 보류한다.

모바일 UI 개발은 범위 밖이다. 앱 연결 규격과 오류·재접속 동작은 앱 담당자와 합의한다.

## 4. 목표 구조

```text
앱
├── HTTPS REST → Spring Controller → 회원·장소·즐겨찾기 → MySQL
├── 장소 검색 → Spring place → 네이버 지역 검색 API
├── 소셜 인증 → Spring member → 카카오·구글 인증 검증
└── WSS WebSocket ↔ Spring 측정 세션 ↔ 비동기 gRPC Client ↔ 모델 서비스
```

### 4.1 가벼운 DDD 기반 모듈러 모놀리스

하나의 Spring 애플리케이션 안에서 기능별 책임을 나눈다.

| 모듈·지원 영역 | 책임 |
|---|---|
| [member](docs/modules/MEMBER.md) | 회원·일반/소셜 로그인·인증 |
| [place](docs/modules/PLACE.md) | 실내 장소·모델 매핑·네이버 검색·선택적 자체 실외 장소 |
| [favorite](docs/modules/FAVORITE.md) | 즐겨찾기와 소유권 |
| [positioning](docs/modules/POSITIONING.md) | 앱 측정 연결과 모델 서비스 연동 |
| [shared](docs/modules/SHARED.md) | 공통 오류·인증 정보 표현 등 최소 공통 코드 |

각 업무 모듈은 요청을 받는 계층, 업무 흐름, 업무 규칙, DB·외부 통신 구현으로 구분한다. 처음부터 모듈별로 서버를 따로 배포하는 구조는 아니다. `shared`는 업무 모듈이 아닌 지원 영역이며 같은 계층 구조를 기계적으로 반복하지 않는다.

Spring Security로 공통 인증과 접근 규칙을 연결하고, 각 업무 모듈에서 소유권 등 세부 권한을 검사한다. DB 변경은 업무 서비스의 트랜잭션으로 관리한다. 보안 필터와 모듈을 연결하는 설정은 루트 `config/` 같은 조립 지점에 두어 shared와 업무 모듈의 순환 의존을 피한다.

### 4.2 외부 모델 서비스와의 경계

Spring이 보내는 것은 세션 정보와 순서가 있는 센서 메시지다. 받는 것은 처리 상태, 입력 순번, 모델 버전, 위치 코드와 후보 점수다.

모델 버전과 위치 코드를 장소 정보에 연결하는 규칙은 Spring에서 관리한다. 모델 서비스의 파일 구성·보정·학습·추론 구현은 해당 저장소 문서에서 다룬다.

## 5. 데이터 관리 범위

이관 원본의 ORM에는 다음 네 테이블이 있다. 실제 운영 DB 조회 결과는 아니며, Spring에 이미 생성된 테이블이라는 뜻도 아니다.

| 원본 테이블 | 이관할 정보 |
|---|---|
| `users` | 회원·로그인 ID·연락처·비밀번호 해시 |
| `favorites` | 회원별 장소·버스·정류장 즐겨찾기 |
| `predicted_locations` | 예측 가능한 실내 장소의 기준 정보 |
| `outdoor_place` | 실외 장소·좌표·시설·접근성 정보 |

구현한 기본 테이블은 members·local_credentials·social_accounts, buildings·floors·indoor_locations·model_location_mappings, favorites의 8개다. 자체 outdoor_places·place_facilities는 선택 사항으로 아직 생성하지 않았다.

기존 users는 프로필과 일반 인증 정보로 나눈다. 로그인 ID는 앞뒤 공백 제거 후 대소문자를 구분한다. 이메일·전화번호는 선택이며 이메일에 전역 UNIQUE를 두지 않는다. 이메일은 소셜 계정 고유 식별자로 사용하지 않는다. 네이버 검색 결과와 자체 장소 원본은 구분한다. 상세 컬럼·제약·미확정 정책·이관은 [DB 스키마 설계](DATABASE_SCHEMA.md)에서 관리한다.

센서 샘플·예측 이력 테이블은 만들지 않는다. 로그에도 센서 원본이나 상세 위치를 기본으로 보관하지 않고, 연결 수·처리 지연·오류 수 같은 운영 지표를 남긴다.

## 6. 저장소와 개발 흐름

- 중앙 레포: `MagNavi-server/magnavi-api-service`.
- 개인 포크: `ychoik/magnavi-api-service`.
- 로컬 `origin`: 개인 포크.
- 로컬 `upstream`: 중앙 레포.
- 기본 브랜치: `develop`.
- 작업 흐름: 중앙 이슈 생성 → 최신 develop 기준 이슈 번호 포함 작업 브랜치 → 개인 포크 push → 중앙 develop PR.
- 작업 PR은 Squash and merge, 공용 브랜치 간 PR은 Create a merge commit을 사용한다. 개인 작업 브랜치는 보존한다.
- 배포 방향: develop → release PR에서 CI → release 반영 후 GitHub Actions 검증·빌드·배포 → 정상 배포한 버전을 main에 PR로 반영.

중앙의 main·develop·release 보호 규칙과 필수 승인 0명은 운영할 정책이다. 실제 저장·활성화 여부는 별도로 확인한다. 문서 생성만으로 브랜치나 CI/CD 설정이 적용되는 것은 아니다.
