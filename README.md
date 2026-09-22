# MagNavi API Service

MagNavi의 회원·장소·즐겨찾기와 실시간 측정 연결을 담당할 Spring 서버다. 서버 역할과 목표 구조는 [프로젝트 개요](PROJECT_OVERVIEW.md), 후속 작업은 [구현 계획](IMPLEMENTATION_PLAN.md)을 참고한다.

현재는 **1단계: 실행·테스트 기반**을 구현했다. 환경별 설정, MySQL 개발 환경, 공통 오류 응답, 요청 추적, 기본 접근 정책을 제공한다. 업무 API·JWT·업무 테이블·WebSocket·gRPC 통신은 후속 단계다.

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

Spring은 `.env`를 자동으로 읽지 않는다. 직접 작성한 로컬 `.env`를 다음과 같이 환경변수로 내보내거나, IDE 실행 설정에 `MYSQL_PASSWORD`와 필요한 경우 `MAGNAVI_MYSQL_PORT`를 넣는다.

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
| 그 외 요청 | 미인증 401, 인증 주체가 있어도 명시적으로 허용하기 전에는 403 |

상태 확인 응답에는 DB 주소·구성 요소·예외 상세를 공개하지 않는다. 현재 준비 상태에 Python 모델 서비스는 포함되지 않는다.

기본 로그인 화면·임시 사용자·HTTP Basic·세션 인증은 사용하지 않는다. JWT 발급·검증도 아직 연결하지 않았다. 회원 단계에서 필요한 경로와 인증 처리를 함께 추가한다. 현재 쿠키 인증을 사용하지 않아 CSRF 검사는 비활성화했으며, 향후 쿠키 인증 도입 시 다시 설계한다.

## 4. 환경과 DB 설정

| 환경 | 설정 | DB 주입 방식 |
|---|---|---|
| 공통 | `application.yaml` | `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` 필수 |
| 개발 | `application-local.yaml` | 개발용 주소·계정, `MYSQL_PASSWORD` 필수 |
| 테스트 | `src/test/resources/application-test.yaml` | Spring의 `@ServiceConnection`으로 임시 MySQL 정보 주입 |
| 배포 | `application-prod.yaml` | `SPRING_PROFILES_ACTIVE=prod`와 공통 DB 변수 외부 주입 |

테스트 프로필의 기본 DB 주소는 연결할 수 없는 대체값이다. 통합 테스트는 `MySqlTestConfiguration`을 가져와 전용 컨테이너에 연결한다. 로컬 `.env`나 운영 DB를 테스트에 사용하지 않는다. 웹 계층 테스트는 DB를 실행하지 않는다.

스키마 변경은 Flyway가 담당하고, JPA는 `ddl-auto=validate`로 매핑을 검증한다. `open-in-view=false`로 요청 전체에 DB 접근 범위를 넓히지 않는다. Flyway의 `clean`과 자동 baseline은 비활성화했고, `schema.sql` 같은 별도 SQL 초기화도 사용하지 않는다.

**아직 업무 엔티티와 마이그레이션 SQL은 없다.** 기동 시 Flyway 이력 테이블은 생성될 수 있지만 업무 테이블은 만들지 않는다. `No migrations found` 경고는 이 단계에서 예상된 상태이며, 실제 테이블 생성·제약·JPA 매핑 검증은 2단계에서 수행한다. 배포 프로필을 준비한 것이 운영 DB 접속·마이그레이션·배포를 승인하거나 수행했다는 뜻은 아니다.

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

서버는 요청마다 추적 ID를 생성하고 `X-Request-ID` 응답 헤더와 오류 JSON, 로그를 연결한다. 입력 헤더의 ID는 신뢰하지 않는다. 공통 요청 로그에는 상태와 처리 시간만 남기며 URL·쿼리·본문·토큰을 기록하지 않는다. 요청 종료 시 스레드에 남은 추적 정보를 정리한다. 비동기 WebSocket·gRPC 추적과 모듈별 업무 오류는 후속 구현 범위다.

## 6. 검증

Docker를 실행한 상태에서 다음 명령을 사용한다. 로컬 개발 DB를 먼저 실행할 필요는 없다.

```bash
./gradlew test
./gradlew build
```

Testcontainers는 테스트용 MySQL을 임시 포트로 실행하고 종료 시 정리한다. Docker를 사용할 수 없으면 통합 테스트는 실패하며, 임의로 건너뛰지 않는다.

검증 범위는 실제 MySQL 연결과 Flyway 초기화, 최소 상태 확인, 401·403 접근 정책, MVC 오류 변환, 입력·내부 오류 비노출, 요청 ID 및 동시 요청의 로그 정보 분리다. 빌드 결과는 `build/libs/`, 테스트 보고서는 `build/reports/tests/test/index.html`에서 확인한다. 인증 업무·DB 제약·실제 모델·배포 검증은 각 후속 단계에서 추가한다.

2026-09-18 검증에서는 Java 21로 `./gradlew build`를 실행해 테스트 19개와 실행 JAR 빌드를 통과했다. 별도 임시 Compose 프로젝트와 메모리 DB 저장소로 healthcheck·로컬 JAR 기동·401 응답·DB 장애 시 readiness 503/liveness 200을 확인했다. `prod`의 DB 설정을 비우면 DataSource 생성 단계에서 기동이 실패하는 것도 확인했다. 검증용 프로세스·컨테이너·네트워크는 정리했으며 기존 DB 볼륨은 사용하거나 삭제하지 않았다.

## 7. 관련 문서

- [프로젝트 개요](PROJECT_OVERVIEW.md)
- [구현 계획](IMPLEMENTATION_PLAN.md)
- [DB 스키마 설계](DATABASE_SCHEMA.md)
- [모듈 안내서](docs/modules/README.md)
- [공통 지원 영역](docs/modules/SHARED.md)
