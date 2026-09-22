# MagNavi API Service

MagNavi의 회원·장소·즐겨찾기와 실시간 측정 연결을 담당할 Spring 서버다. 서버 역할과 목표 구조는 [프로젝트 개요](PROJECT_OVERVIEW.md), 후속 작업은 [구현 계획](IMPLEMENTATION_PLAN.md)을 참고한다.

현재는 **1단계 실행·테스트 기반, 2단계 DB 기반, 3-1단계 일반 회원·JWT, 4-1단계 실내 장소 조회, 4-2단계 모델 코드 매핑 조회**를 구현했다. 회원 API 4개와 건물·층·활성 장소 조회 API 7개를 제공한다. 모델 코드 매핑은 Spring 내부 메서드로 제공한다. 네이버 검색·소셜 로그인·즐겨찾기 API·실시간 통신은 후속 단계다. [회원 API 안내](docs/api/MEMBER_API.md)와 [장소 조회 안내](docs/api/PLACE_API.md)에 요청·응답·오류를 정리했다.

## 1. 준비 환경

- JDK 21: Gradle 실행 JDK와 IDE의 Gradle JVM을 모두 확인한다.
- Docker Engine 또는 Docker Desktop과 Docker Compose v2 이상: 로컬 DB와 통합 테스트에서 사용한다.
- 처음 실행할 때 Gradle 의존성과 `mysql:8.4.8` 이미지를 내려받을 수 있는 환경.

Gradle은 저장소의 Wrapper를 사용한다. 현재 Spring Boot는 4.1.1이며, 이번 단계에서 기존 버전을 변경하지 않았다.

```bash
java -version
./gradlew --version
docker info
docker compose version
```

## 2. 로컬 MySQL 실행

프로젝트 루트에서 예시 파일을 복사한다.

```bash
cp .env.example .env
```

`.env`의 `MYSQL_PASSWORD`와 `MYSQL_ROOT_PASSWORD`를 **서로 다른 로컬 전용 값**으로 채운다. 이 파일은 Git에서 제외한다. 뒤의 터미널 실행 예제처럼 읽으려면 셸에서도 유효한 `이름='값'` 형식으로 작성한다.

```bash
docker compose -f compose.local.yaml up -d --wait
docker compose -f compose.local.yaml ps
```

MySQL은 `127.0.0.1:13306`, DB 이름은 `magnavi_local`, 앱 계정은 `magnavi`다. 포트가 사용 중이면 `.env`의 `MAGNAVI_MYSQL_PORT`를 바꾼다. healthcheck는 앱 계정으로 `SELECT 1`을 실행해 실제 쿼리 가능 여부를 확인한다.

데이터는 `magnavi-api-local_mysql-data` 이름의 Docker 볼륨에 유지된다. 작업 후에는 다음 명령으로 멈춘다.

```bash
docker compose -f compose.local.yaml stop
```

기존 볼륨이 있으면 `.env`의 비밀번호를 바꾸는 것만으로 MySQL 계정 비밀번호가 변경되지는 않는다. 기존 데이터와 계정을 확인하고 조정한다. 이 Compose는 개발용 MySQL 설정이며 운영 배포 구성은 별도 단계다.

## 3. Spring 실행

서버 실행 전에 `openssl rand -base64 32`로 개발 전용 난수 키를 만들어 `.env`의 `JWT_SECRET_BASE64`에 넣는다. 키를 비워 두거나 Base64를 풀었을 때 32바이트보다 짧으면 서버가 시작되지 않는다. 키는 서버에서만 보관하고 앱·Git·로그에 넣지 않는다. 재시작마다 새 키를 만들면 기존 토큰이 무효가 되므로 같은 환경에서는 설정한 키를 유지한다.

Spring은 `.env`를 자동으로 읽지 않는다. 직접 작성한 로컬 `.env`를 다음과 같이 환경변수로 내보내거나, IDE 실행 설정에 `MYSQL_PASSWORD`, `JWT_SECRET_BASE64`와 필요한 경우 `MAGNAVI_MYSQL_PORT`를 넣는다.

```bash
set -a
source .env
set +a
./gradlew bootRun --args='--spring.profiles.active=local'
```

`local` 프로필은 HTTP와 MySQL 연결을 로컬 주소로 제한한다. 서버 기본 포트는 8080이며 필요하면 `SERVER_PORT`로 변경한다.

```bash
curl -i http://127.0.0.1:8080/actuator/health/liveness
curl -i http://127.0.0.1:8080/actuator/health/readiness
curl -i http://127.0.0.1:8080/users/me
```

| 확인 경로 | 현재 동작 |
|---|---|
| `GET /actuator/health/liveness` | 애플리케이션 생존 상태. 정상 시 `200 {"status":"UP"}` |
| `GET /actuator/health/readiness` | 애플리케이션 준비 상태와 DB 확인. DB 장애 시 503 |
| `POST /users/signup`, `POST /users/login` | 로그인 전 호출 가능. JSON 가입·폼 로그인 |
| `GET /users/me`, `PUT /users/me/username` | 유효한 JWT와 존재하는 회원 필요 |
| 건물·층·장소 GET 7개 | 로그인 없이 조회. 정확한 경로는 [장소 API](docs/api/PLACE_API.md) 참고 |
| 그 외 요청 | 미인증 401, 인증했어도 명시적으로 허용하기 전에는 403 |

상태 확인 응답에는 DB 주소·구성 요소·예외 상세를 공개하지 않는다. 현재 준비 상태에 Python 모델 서비스는 포함되지 않는다.

기본 로그인 화면·임시 사용자·HTTP Basic·세션 인증은 사용하지 않는다. 로그인 성공 시 30분 액세스 JWT를 발급하며 이후 `Authorization: Bearer` 헤더로 검증한다. 재발급·로그아웃 API는 아직 없다. 현재 쿠키 인증을 사용하지 않아 CSRF 검사는 비활성화했으며, 향후 쿠키 인증 도입 시 다시 설계한다.

실제 건물·층·장소를 자동 등록하는 SQL은 추가하지 않았다. 새 DB의 `GET /buildings`와 `GET /locations`는 데이터가 등록되기 전까지 `[]`를 반환한다. 조회 기능 검증에는 임시 MySQL의 합성 자료만 사용한다.

## 4. 환경과 DB 설정

| 환경 | 설정 | DB 주입 방식 |
|---|---|---|
| 공통 | `application.yaml` | `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `JWT_SECRET_BASE64` 필수 |
| 개발 | `application-local.yaml` | 개발용 주소·계정, `MYSQL_PASSWORD` 필수 |
| 테스트 | `src/test/resources/application-test.yaml` | Spring의 `@ServiceConnection`으로 임시 MySQL 정보 주입 |
| 배포 | `application-prod.yaml` | `SPRING_PROFILES_ACTIVE=prod`와 공통 DB 변수 외부 주입 |

테스트 프로필의 기본 DB 주소는 연결할 수 없는 대체값이다. 통합 테스트는 `MySqlTestConfiguration`을 가져와 전용 컨테이너에 연결한다. 테스트용 JWT 키도 실행마다 메모리에서 생성한다. 로컬 `.env`나 운영 DB·키를 테스트에 사용하지 않는다. 웹 계층 테스트는 DB를 실행하지 않는다.

스키마 변경은 Flyway가 담당하고, JPA는 `ddl-auto=validate`로 매핑을 검증한다. `open-in-view=false`로 요청 전체에 DB 접근 범위를 넓히지 않는다. Flyway의 `clean`과 자동 baseline은 비활성화했고, `schema.sql` 같은 별도 SQL 초기화도 사용하지 않는다.

기동 시 Flyway가 [마이그레이션 폴더](src/main/resources/db/migration)의 V1(회원) → V2(실내 장소·모델 매핑) → V3(즐겨찾기)를 순서대로 적용한다. 이후 Hibernate가 JPA 매핑을 검증한다. 이미 적용한 버전은 다시 실행하지 않는다.

2026-09-20 확정 정책은 다음과 같다.

- 별도 신규 DB의 구조부터 구현한다. 기존 FastAPI DB는 유지하며 데이터 이관은 후속 결정·작업이다.
- 로그인 ID는 앞뒤 공백을 제거하고 **대소문자를 구분**한다. `Alice`와 `alice`는 서로 다른 ID다.
- 이메일·전화번호는 선택값이며 이메일 중복을 허용한다. 같은 이메일만으로 회원을 연결하지 않는다.
- 즐겨찾기는 실내 장소·버스·정류장의 식별 구조를 제공한다. 외부 장소 스냅샷·기존 앱 ID 변환·교통 제공처 연동은 후속 작업이다.

빈 전용 DB 또는 이 프로젝트의 Flyway 이력이 있는 DB에서 사용한다. 이 SQL은 기존 FastAPI 테이블을 변환하는 이관 스크립트가 아니다. 배포 프로필을 준비한 것이 운영 DB 접속·마이그레이션·배포를 수행했다는 뜻은 아니다. 컬럼·제약·저장소 경계는 [DB 스키마 설계](DATABASE_SCHEMA.md)를 참고한다.

## 5. 오류 응답과 요청 추적

일반 MVC 오류, 보안 오류, 서블릿 오류는 다음 형태를 공유한다. 상태 확인은 Actuator의 `status` 형식을 유지한다.

```json
{
  "code": "UNAUTHENTICATED",
  "message": "인증이 필요합니다.",
  "traceId": "서버가 생성한 UUID",
  "errors": []
}
```

검증 실패 시 `errors`에는 필드명과 안전한 안내만 포함한다. 입력 원문, 예외 메시지, SQL, 스택 트레이스는 응답에 넣지 않는다. 404·405·415 등 기존 HTTP 상태와 `Allow` 같은 필요한 헤더를 보존한다.

서버는 요청마다 추적 ID를 생성하고 `X-Request-ID` 응답 헤더와 오류 JSON, 로그를 연결한다. 입력 헤더의 ID는 신뢰하지 않는다. 공통 요청 로그에는 상태와 처리 시간만 남기며 URL·쿼리·본문·토큰을 기록하지 않는다. 요청 종료 시 스레드에 남은 추적 정보를 정리한다. 회원·장소의 예상 오류는 각 모듈에서 같은 형식으로 변환한다. 비동기 WebSocket·gRPC 추적은 후속 구현 범위다.

## 6. 검증

Docker를 실행한 상태에서 다음 명령을 사용한다. 로컬 개발 DB를 먼저 실행할 필요는 없다.

```bash
./gradlew test
./gradlew build
```

Testcontainers는 테스트용 MySQL을 임시 포트로 실행하고 종료 시 정리한다. Docker를 사용할 수 없으면 통합 테스트는 실패하며, 임의로 건너뛰지 않는다.

검증 범위는 실제 MySQL 연결과 V1~V3 생성·JPA validate, V1에서 최신 버전으로 확장·재실행 시 데이터 보존, 8개 엔티티 저장/조회, 대소문자·선택 연락처·회원별 즐겨찾기·고유/FK/NOT NULL/CHECK 제약·트랜잭션 롤백·UTC 시각 저장이다. 최소 상태 확인, 401·403, 오류 비노출, 동시 요청 추적 테스트도 유지한다. 빌드 결과는 `build/libs/`, 테스트 보고서는 `build/reports/tests/test/index.html`에서 확인한다. 일반 회원 API·실제 JWT 인증·본인 정보 접근·비밀번호 해시 호환을 추가 검증했다. 소셜 인증·기존 데이터 이관·실제 앱·모델·운영 배포 검증은 후속 단계다.

2026-09-18 검증에서는 Java 21로 `./gradlew build`를 실행해 테스트 19개와 실행 JAR 빌드를 통과했다. 별도 임시 Compose 프로젝트와 메모리 DB 저장소로 healthcheck·로컬 JAR 기동·401 응답·DB 장애 시 readiness 503/liveness 200을 확인했다. `prod`의 DB 설정을 비우면 DataSource 생성 단계에서 기동이 실패하는 것도 확인했다. 검증용 프로세스·컨테이너·네트워크는 정리했으며 기존 DB 볼륨은 사용하거나 삭제하지 않았다.

2026-09-20에는 2단계 반영 후 Java 21의 `./gradlew build`에서 테스트 **42개**와 실행 JAR 빌드가 통과했다. 검증에는 일회용 MySQL 8.4.8만 사용했다. 기존 개발/운영 DB에는 마이그레이션이나 데이터 이관을 실행하지 않았다.

2026-09-21에는 3-1단계 반영 후 Java 21의 `./gradlew test`와 `./gradlew build`에서 **65개 테스트**와 실행 JAR 빌드를 통과했다. 새 테스트 23개는 회원 API 16개, JWT 기동 설정 4개, BCrypt 저장·Python 호환 3개다. 기존 Python과 같은 passlib 1.7.4·bcrypt 4.3.0을 임시 폴더에서 사용해 합성 해시를 만들었으며 Python 프로젝트와 기존 DB는 변경하지 않았다.

2026-09-21의 4-1단계에서는 장소 통합 테스트 18개를 추가해 전체 **83개 테스트**와 `./gradlew build`를 통과했다. 계층별 조회·활성 장소 필터·입력 범위·슬래시 호환·변경 API 차단을 확인했고, 장소 100개 응답도 전체 목록 SQL 1회·층별 목록 SQL 2회로 처리됨을 검증했다. 기존 DB·Flyway SQL·Python은 변경하지 않았다.

2026-09-22의 4-2단계에서는 모델 매핑 통합 테스트 14개를 추가해 전체 **97개 테스트**와 `./gradlew build`를 통과했다. 모델 키·버전·코드의 정확한 구분, 입력 제한, 연결 누락·비활성 처리, SQL 2회 조회와 쓰기 없음을 확인했다. [내부 조회 사용 안내](docs/api/MODEL_LOCATION_MAPPING.md)에 호출 방법과 오류를 정리했다. 실제 매핑 데이터 등록·Python/gRPC 통신·캐시는 후속 작업이며 기존 SQL·API는 유지했다.

## 7. 관련 문서

- [일반 회원 API 요청·응답·오류](docs/api/MEMBER_API.md)
- [건물·층·실내 장소 조회 API](docs/api/PLACE_API.md)
- [모델 코드 → 장소 내부 조회](docs/api/MODEL_LOCATION_MAPPING.md)

- [프로젝트 개요](PROJECT_OVERVIEW.md)
- [구현 계획](IMPLEMENTATION_PLAN.md)
- [DB 스키마 설계](DATABASE_SCHEMA.md)
- [모듈 안내서](docs/modules/README.md)
- [공통 지원 영역](docs/modules/SHARED.md)
