# Spring·MySQL Docker 배포 준비 안내

이 문서는 Spring EC2 한 대에서 **Spring 컨테이너 1개와 신규 MySQL 컨테이너 1개**를 실행하는 방법과 각 설정의 이유를 설명한다. Python 모델 서버는 별도 EC2에 배치하며 이 Compose에는 포함하지 않는다.

현재 단계는 배포 파일 작성과 로컬 검증이다. **실제 EC2 배포, HTTPS 연결, 기존 FastAPI 데이터 이관, CI/CD는 아직 수행하지 않았다.** Spring HTTP는 호스트의 `127.0.0.1`에서만 확인하도록 구성했다.

## 1. 파일과 용어

| 파일·용어 | 하는 일과 필요한 이유 |
|---|---|
| `Dockerfile` | 테스트를 통과한 Spring JAR와 Java 21 실행 환경을 하나의 이미지로 묶는다. EC2에서는 빌드 없이 이 이미지를 실행한다. |
| 이미지 / 컨테이너 | 이미지는 실행에 필요한 파일 묶음이고, 컨테이너는 그 이미지를 실제로 실행한 것이다. |
| `.dockerignore` | JAR와 Dockerfile만 빌드 환경으로 전달한다. `.env`, SSH 키, Git, IDE 설정의 유입을 막는다. |
| `compose.prod.yaml` | 두 컨테이너의 이미지, 환경변수, 통신, 상태 확인, 메모리, 로그, 볼륨을 관리한다. |
| `.env.prod.example` | 필요한 설정의 이름과 작성법이다. 실제 비밀번호가 없는 공유용 예시다. |
| `.env.prod` | 실제 이미지 이름·DB 비밀번호·JWT 키를 보관하는 배포 환경 전용 파일이다. Git·이미지·채팅에 넣지 않는다. |
| 볼륨 | MySQL 파일을 컨테이너 수명과 별도로 보관하는 저장 공간이다. 컨테이너 교체 후에도 데이터가 남는다. |

이 배포 설정은 중앙 `develop`의 `058ca4b`(6단계 gRPC 구현 포함)를 기준으로 준비했다. **배포할 코드 버전은 실제 배포 전에 별도로 확정한다.** 아래 `ec2-001`은 명령을 설명하기 위한 이미지 태그 예시이며, 이미 배포된 버전을 뜻하지 않는다.

## 2. 연결·메모리·데이터 설정

```text
EC2 내부 127.0.0.1:8080
          │
          ▼
Spring 컨테이너 ── mysql:3306 ── MySQL 컨테이너
                                    │
                                    ▼
                     magnavi-api-prod_mysql-data 볼륨
```

- MySQL의 `ports`는 선언하지 않는다. 인터넷이나 호스트에 DB 포트를 게시하지 않고 같은 Docker 네트워크에서 접근한다.
- Spring의 DB 주소는 `localhost`가 아닌 서비스 이름 `mysql`이다. 컨테이너 안의 `localhost`는 그 컨테이너 자신을 가리킨다.
- DB 연결은 `sslMode=REQUIRED`로 TLS 암호화를 요구한다. 이 설정만으로 서버 인증서의 신원까지 검증하지는 않는다. 다른 호스트·외부 DB로 연결을 바꾸면 인증서와 주소 검증도 함께 설계한다.
- Spring의 `127.0.0.1:8080`은 호스트 내부 확인용이다. 보안 그룹의 80·443번 포트가 열려 있어도 현재 앱에 연결되는 것은 아니다. 앱의 인터넷 접속에는 HTTPS 프록시·인증서 설정이 추가로 필요하다.
- MySQL은 앱 계정으로 `SELECT 1`이 성공한 뒤 준비 완료가 된다. Compose는 이를 기다린 뒤 Spring을 시작한다. 이후 Spring의 readiness는 DB 연결까지 확인한다.
- healthcheck의 `unhealthy` 표시는 자동 재시작 명령이 아니다. `unless-stopped`는 프로세스 종료·Docker 재시작 시의 정책이며, 수동으로 중지한 컨테이너는 자동으로 다시 켜지지 않는다.

| 항목 | 초기 설정 | 이유와 한계 |
|---|---|---|
| Spring 전체 메모리 | 768MiB | 힙뿐 아니라 클래스·스레드·네이티브 메모리를 포함한 한도다. |
| Java 힙 | 초기 128MiB / 최대 384MiB | 컨테이너 한도 안에 다른 Java 메모리의 여유를 남긴다. |
| Java 메타스페이스 / 직접 메모리 | 최대 192MiB / 64MiB | 클래스 정보와 일부 네이티브 버퍼의 크기를 제한한다. |
| MySQL 전체 메모리 | 640MiB | Spring과 운영체제를 위한 공간을 남긴다. |
| MySQL 데이터 캐시 / 접속 수 | 128MiB / 최대 30 | 작은 서버에서 초기 메모리 사용을 줄인다. Spring 연결 풀은 최대 10개다. |
| MySQL Performance Schema | 비활성 | 상세 성능 계측의 메모리를 줄인다. 분석이 필요하면 용량과 함께 재검토한다. |
| 컨테이너 로그 | 서비스별 10MB × 3개 | 로그가 무한히 늘어나 디스크를 채우지 않도록 한다. |

두 컨테이너 메모리 한도의 합은 1408MiB다. 2GiB 서버에서 나머지는 운영체제·Docker 등에 필요하다. 이 값은 **초기 기동용 설정이며 실제 EC2 부하를 검증한 수치가 아니다.** 실제 트래픽에서 메모리·CPU·재시작 여부를 확인하고 조정한다. 스왑 파일은 이 작업에서 생성하지 않는다.

기존 `application.yaml`의 Flyway와 JPA 검증을 그대로 사용한다. **새 전용 MySQL 볼륨**에서 Spring이 시작될 때 V1~V3 테이블이 생성된다. 기존 FastAPI DB나 개발용 볼륨을 연결하지 않는다. 새 SQL·테이블·데이터 이관 로직은 추가하지 않았다.

현재 Compose에는 네이버 검색·Python 모델 접속 설정을 주입하지 않는다. 두 연동은 기존 애플리케이션의 기본값대로 비활성이다. `.env.prod`에 변수만 추가해도 컨테이너로 자동 전달되지 않으므로, 실제 연동 단계에서 확인한 값과 통신 정책을 Compose에 명시해야 한다. 관련 기준은 [외부 검색 안내](api/PLACE_SEARCH_API.md)와 [모델 통신 안내](api/MODEL_GRPC.md)를 참고한다.

## 3. 빌드 환경에서 이미지 준비

이 절차는 **Mac의 Spring 프로젝트 폴더** 또는 Java 21과 Docker가 있는 빌드 환경에서 실행한다. EC2에 소스를 복사해서 빌드하는 절차가 아니다. 현재 Mac은 ARM이고 t3 EC2는 AMD64이므로 이미지 빌드의 `--platform linux/amd64`가 필요하다.

```bash
# macOS에 설치된 Java 21을 이번 터미널의 Gradle 실행에 사용합니다.
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"

# 이전 빌드 결과를 지우고, 격리된 테스트와 실행 JAR 생성을 완료합니다.
# Docker가 실행 중이어야 Testcontainers가 새 테스트용 MySQL을 만들 수 있습니다.
./gradlew clean build

# EC2 CPU에 맞는 이미지로 포장하고, 로컬 Docker에 결과를 불러옵니다.
# ec2-001은 예시 태그입니다. 배포마다 새 식별자를 사용하고 이전 이미지를 보존합니다.
docker buildx build --platform linux/amd64 --tag magnavi-api:ec2-001 --load .

# 만들어진 이미지가 Linux/AMD64인지와 내용 식별값을 확인합니다.
docker image inspect magnavi-api:ec2-001 --format '{{.Os}}/{{.Architecture}} {{.Id}}'
```

JAR 기본 경로는 `build/libs/MagNavi_SpringServer-0.0.1-SNAPSHOT.jar`다. 버전을 변경했다면 Dockerfile의 `JAR_FILE` 빌드 인수에 새 실행 JAR 경로를 전달한다. `*-plain.jar`는 선택하지 않는다.

Java 기반 이미지와 MySQL 이미지는 digest로 고정했다. Dockerfile에서 설치하는 OS 패키지는 빌드 시점의 저장소를 이용하므로 재빌드한 전체 이미지가 항상 바이트 단위로 같다는 뜻은 아니다. 배포 때는 **검증한 최종 Spring 이미지 자체**를 전달하고, 레지스트리를 정하면 최종 digest로 식별한다.

## 4. EC2에 전달할 산출물

이미지 레지스트리는 아직 확정하지 않았다. 최초 수동 전달을 선택할 경우 아래처럼 이미지 파일을 만들 수 있다. 이미지는 비밀값을 포함하지 않는다.

```bash
# 빌드 환경에서 실행합니다. 검증한 이미지를 운반할 수 있는 tar 파일로 저장합니다.
docker image save --output /tmp/magnavi-api-ec2-001.tar magnavi-api:ec2-001

# 파일을 옮긴 뒤 동일한지 비교할 SHA-256을 확인합니다.
shasum -a 256 /tmp/magnavi-api-ec2-001.tar
```

전달 대상은 이미지 tar, `compose.prod.yaml`, `.env.prod.example`이다. 실제 전송과 EC2에서의 실행은 대상·코드 버전을 확인한 뒤 진행한다. 운영 주소나 키 경로를 문서에 저장하지 않는다. 실제 `.env.prod`는 EC2에서 별도로 작성하며, 개발 환경의 비밀번호를 재사용하지 않는다.

## 5. EC2의 환경 설정과 최초 실행

아래는 **실제 배포 단계에서 사용할 명령**이다. 먼저 배포 대상과 새 DB 볼륨 사용 여부를 확인한다. EC2에서 이미지 tar와 Compose 파일을 배치한 배포 폴더를 기준으로 실행한다.

```bash
# 전송한 파일의 SHA-256이 빌드 환경에서 확인한 값과 같은지 비교합니다.
sha256sum magnavi-api-ec2-001.tar

# 검증한 이미지를 EC2의 Docker 저장소에 불러옵니다.
sudo docker image load --input magnavi-api-ec2-001.tar

# 처음 배포할 때만 예시를 복사합니다. 기존 설정 파일은 덮어쓰지 않습니다.
cp -n .env.prod.example .env.prod

# 실제 비밀번호 파일은 소유자만 읽고 쓸 수 있게 합니다.
chmod 600 .env.prod

# 필요한 실제 값을 작성합니다. 비밀값을 채팅이나 로그에 공유하지 않습니다.
nano .env.prod
```

설정 항목:

- `SPRING_IMAGE`: 위 예시로 빌드했다면 `magnavi-api:ec2-001`. 실제 검증한 태그와 일치해야 한다.
- `MYSQL_PASSWORD`, `MYSQL_ROOT_PASSWORD`: 서로 다른 난수 비밀번호. 각각 `openssl rand -hex 32`로 한 번씩 생성할 수 있다.
- `JWT_SECRET_BASE64`: `openssl rand -base64 32`로 생성한 키. 재배포 때도 같은 값을 유지한다.
- `SPRING_HTTP_PORT`: 기본 8080. 바꾸면 아래 상태 확인 URL의 포트도 맞춘다.

Compose는 `--env-file`에서 값을 읽으므로 `source .env.prod`는 필요 없다. 셸에 이미 같은 이름의 환경변수가 있으면 그것이 우선한다. 특히 로컬 검증에서는 기존 개발 환경변수가 섞이지 않도록 확인한다. 예시 파일의 필수 값은 비어 있어 그대로 사용하면 Compose가 실행을 거절한다.

```bash
# 설정 문법과 필수 값 유무를 검사합니다. -q는 비밀번호가 포함된 전체 설정 출력을 막습니다.
sudo docker compose --env-file .env.prod -f compose.prod.yaml config -q

# 백그라운드에서 실행하고 두 컨테이너가 healthy가 될 때까지 기다립니다.
# 최초 실행에서는 새 DB 초기화와 기존 Flyway 마이그레이션이 진행됩니다.
sudo docker compose --env-file .env.prod -f compose.prod.yaml up -d --wait --wait-timeout 240

# 컨테이너 상태와 호스트에 게시된 포트를 확인합니다.
sudo docker compose --env-file .env.prod -f compose.prod.yaml ps

# Spring 프로세스의 생존 상태를 확인합니다.
curl --fail --silent --show-error http://127.0.0.1:8080/actuator/health/liveness

# Spring이 DB까지 연결해서 요청을 처리할 준비가 됐는지 확인합니다.
curl --fail --silent --show-error http://127.0.0.1:8080/actuator/health/readiness

# 현재 메모리 사용량을 한 번 확인합니다. 운영 부하에서도 별도 측정해야 합니다.
sudo docker stats --no-stream
```

두 상태 응답이 `{"status":"UP"}`이면 이 범위의 기동 확인이 끝난다. 이 응답에 Python 모델 서버의 준비 상태는 포함되지 않는다. `up --wait`가 실패하면 실제 준비 완료가 아니므로 로그와 상태를 확인한다.

## 6. 종료·재배포와 데이터 보존

```bash
# 최근 Spring 로그를 확인합니다. 외부 공유 전 민감한 값이 없는지 확인합니다.
sudo docker compose --env-file .env.prod -f compose.prod.yaml logs --tail 100 spring

# 컨테이너를 중지하되 DB 데이터와 실행 설정을 보존합니다.
sudo docker compose --env-file .env.prod -f compose.prod.yaml stop

# 중지했던 컨테이너를 다시 실행하고 준비 상태를 확인합니다.
sudo docker compose --env-file .env.prod -f compose.prod.yaml up -d --wait --wait-timeout 240
```

새 Spring 이미지로 바꿀 때는 먼저 이미지 전달·검증과 DB 마이그레이션 호환성·백업을 확인한다. 그다음 `.env.prod`의 `SPRING_IMAGE`만 새 식별자로 변경하고 다음 명령으로 Spring만 교체한다. MySQL이 정상 실행 중인 상태를 전제로 한다.

```bash
# --no-deps로 MySQL을 교체하지 않고 Spring만 새 이미지로 실행합니다.
# 기존 연결은 종료될 수 있습니다. 무중단 배포를 보장하는 구성은 아닙니다.
sudo docker compose --env-file .env.prod -f compose.prod.yaml up -d --no-deps --wait --wait-timeout 240 spring
```

- 운영 프로젝트 이름 `magnavi-api-prod`를 유지해야 같은 `magnavi-api-prod_mysql-data` 볼륨을 사용한다. 다른 이름으로 실행하면 다른 빈 DB가 생성될 수 있다.
- **`docker compose down -v`, 운영 볼륨 삭제, volume prune을 배포 절차에 사용하지 않는다.** DB 파일이 삭제될 수 있다.
- 볼륨은 백업이 아니다. 실제 운영 전에는 EC2 밖의 백업 보관 위치·주기·복원 검증을 마련해야 한다.
- 기존 볼륨에서 `.env.prod`의 DB 비밀번호만 바꿔도 DB 계정 비밀번호는 바뀌지 않는다. 계정 변경과 앱 설정 변경을 함께 계획한다.
- 이전 Spring 이미지를 다시 실행해도 Flyway가 적용한 DB 구조는 자동으로 되돌아가지 않는다. 호환성과 복구 방법을 먼저 확인한다.

## 7. 격리 검증과 확인 범위

개발·운영 환경과 다른 Compose 프로젝트 이름과 호스트 포트, 임시 난수 비밀값, 새 볼륨을 사용한다. 검증 후에는 **이번 검증에서 생성한 자원만** 정리한다. 기존 DB를 이용하거나 데이터 이관을 실행하지 않는다.

검증 항목은 다음과 같다.

- Java 21의 전체 Gradle 빌드·기존 테스트.
- 필수 환경변수가 없을 때 Compose 실행 거절, 값이 있을 때 문법 통과.
- `linux/amd64` Spring 이미지 빌드와 두 컨테이너의 준비 상태.
- MySQL 포트 미게시, Spring 포트의 루프백 바인딩, 일반 사용자·메모리·로그 제한 적용.
- 준비 상태·생존 상태, 비인증 요청 거절, 합성 회원의 가입·로그인.
- MySQL 중단 시 readiness 실패·liveness 유지와 복구, 컨테이너 재생성 후 합성 데이터 보존.

2026-10-03 중앙 `develop`의 `058ca4b`를 기준으로 배포 파일을 적용한 별도 작업 폴더에서 다음을 실제 확인했다.

| 실행한 검증 | 결과 |
|---|---|
| Java 21 `./gradlew --no-daemon clean build --console=plain` | 기존 테스트 218개 통과, 실패·오류·건너뜀 0, 실행 JAR 생성 |
| `docker buildx build --platform linux/amd64 ... --load .` | AMD64 이미지 빌드 성공, 실행 사용자 `10001:10001` 확인 |
| Compose 설정 | 필수 값 4개 각각 누락 시 거절, 정상 설정 문법 통과 |
| 실제 컨테이너 설정 | Spring 768MiB·MySQL 640MiB 한도, 로그 제한, MySQL 포트 미게시, Spring 루프백 게시, 읽기 전용 파일 시스템 확인 |
| 새 DB에서 기동 | 두 컨테이너 healthy, liveness/readiness 200, 보호 API 미인증 401, 빈 건물 목록 확인 |
| 합성 회원 | 가입 201, 로그인 200, 발급 JWT로 본인 조회 200 |
| DB 장애·복구 | DB 중단 시 readiness 503·liveness 200, 재시작 후 readiness 200 |
| 컨테이너 교체·데이터 유지 | 볼륨을 남기고 두 컨테이너를 제거·재생성한 뒤 같은 회원·기존 JWT 유효성 확인 |
| 정리 | 이번 검증의 고유 프로젝트 컨테이너·네트워크·합성 DB 볼륨과 임시 비밀값 파일만 정리 |

검증 이미지는 로컬의 `magnavi-api:verify-develop-20261003`이며 레지스트리에 게시하지 않았다. 실행 스크립트는 임시 검증 폴더에서 사용했고, 위 결과는 영구 CI 테스트 추가를 뜻하지 않는다. 기존 개발·운영 DB에는 연결하지 않았다.

로컬 AMD64 에뮬레이션의 통과는 EC2의 CPU·메모리·네트워크 성능 검증을 대신하지 않는다. 실제 배포할 코드 버전 확정, EC2 기동, HTTPS, 백업·복원, 부하 검증, CI/CD는 남아 있다.

## 8. 참고 문서

- [Dockerfile 명령 설명](https://docs.docker.com/reference/dockerfile/)
- [Compose 서비스 설정](https://docs.docker.com/reference/compose-file/services/)
- [Compose 시작 순서와 준비 상태](https://docs.docker.com/compose/how-tos/startup-order/)
- [Compose 환경변수 해석](https://docs.docker.com/compose/how-tos/environment-variables/variable-interpolation/)
- [MySQL JDBC TLS 설정](https://dev.mysql.com/doc/connector-j/en/connector-j-connp-props-security.html)
