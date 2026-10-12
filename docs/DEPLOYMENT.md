# Spring·MySQL ECR 배포 안내

이 문서는 Spring EC2 한 대에서 **Spring 컨테이너 1개와 신규 MySQL 컨테이너 1개**를 실행하는 방법과 각 설정의 이유를 설명한다. Python 모델 서버는 별도 EC2에 배치하며 이 Compose에는 포함하지 않는다.

현재는 **ECR 이미지를 이용한 EC2 수동 기동과 Nginx를 통한 외부 readiness 확인까지 완료**한 단계다. 원격 결과는 사용자가 제공한 실행 출력으로 확인했다. Spring 포트는 호스트의 `127.0.0.1`에만 게시하며, Nginx가 HTTP readiness 경로만 전달한다. HTTPS·일반 API 외부 공개·기존 FastAPI 데이터 이관은 후속 작업이다. 이후 추가한 GitHub Actions 코드와 첫 자동 배포 검증 절차는 [CI/CD 안내](CI_CD.md)를 따른다. 자동 배포가 시작된 서버의 상태 확인·복구에는 그 문서의 버전별 Compose 경로를 사용한다.

이미지 전달 방식은 **서울 리전(`ap-northeast-2`)의 비공개 Amazon ECR**을 기준으로 한다. ECR 업로드·EC2 역할을 이용한 다운로드·Compose 기동 결과를 확인했으며, 아래 절차는 그 구성을 다시 확인하거나 새 환경을 준비할 때 참고한다. 기존 환경에서 최초 초기화 단계를 반복하지 않는다.

```text
Mac에서 검증한 Spring 이미지 → ECR에 업로드(push)
                                     ↓
EC2에서 이미지 다운로드(pull) → Docker Compose로 Spring·MySQL 실행
```

이미지는 ECR로 전달하므로 tar 파일을 만들 필요가 없다. Compose 파일은 실행 방법을 적은 별도 파일이어서 EC2에 복사하고, 실제 비밀번호는 EC2의 `.env.prod`에서 관리한다.

## 1. 파일과 용어

| 파일·용어 | 하는 일과 필요한 이유 |
|---|---|
| `Dockerfile` | 테스트를 통과한 Spring JAR와 Java 21 실행 환경을 하나의 이미지로 묶는다. EC2에서는 빌드 없이 이 이미지를 실행한다. |
| 이미지 / 컨테이너 | 이미지는 실행에 필요한 파일 묶음이고, 컨테이너는 그 이미지를 실제로 실행한 것이다. |
| ECR | Spring 실행 이미지를 버전별로 보관하는 AWS 저장소다. 회원·장소 등 실제 MySQL 데이터는 저장하지 않는다. |
| 태그 / digest | 태그는 `96265a0` 같은 버전 이름이고, digest는 이미지 내용으로 정해지는 `sha256:...` 식별값이다. |
| `.dockerignore` | JAR와 Dockerfile만 빌드 환경으로 전달한다. `.env`, SSH 키, Git, IDE 설정의 유입을 막는다. |
| `compose.prod.yaml` | 두 컨테이너의 이미지, 환경변수, 통신, 상태 확인, 메모리, 로그, 볼륨을 관리한다. |
| `.env.prod.example` | 필요한 설정의 이름과 작성법이다. 실제 비밀번호가 없는 공유용 예시다. |
| `.env.prod` | 실제 이미지 이름·DB 비밀번호·JWT 키를 보관하는 배포 환경 전용 파일이다. Git·이미지·채팅에 넣지 않는다. |
| 볼륨 | MySQL 파일을 컨테이너 수명과 별도로 보관하는 저장 공간이다. 컨테이너 교체 후에도 데이터가 남는다. |

Docker 배포 설정은 [PR #22](https://github.com/MagNavi-server/magnavi-api-service/pull/22)로 중앙 `develop`의 `96265a0`에 반영됐다. 최초 수동 배포에는 `magnavi-api:96265a0`을 ECR에 올려 digest로 내려받았다. 이미지와 별도로 전달하는 Compose에는 이후 선택한 root 계정 구성을 적용했다. 이후 배포는 실제 검증한 커밋과 새 태그를 사용하며, 태그 이름만으로 배포 성공을 판단하지 않는다.

## 2. 연결·메모리·데이터 설정

```text
외부 HTTP readiness 요청
  → EC2 Nginx :80
  → EC2 내부 127.0.0.1:8080 → Spring 컨테이너
                               → mysql:3306 → MySQL 컨테이너
                                                → magnavi-api-prod_mysql-data 볼륨
```

- MySQL의 `ports`는 선언하지 않는다. 인터넷이나 호스트에 DB 포트를 게시하지 않고 같은 Docker 네트워크에서 접근한다.
- Spring의 DB 주소는 `localhost`가 아닌 서비스 이름 `mysql`이다. 컨테이너 안의 `localhost`는 그 컨테이너 자신을 가리킨다.
- DB 연결은 `sslMode=REQUIRED`로 TLS 암호화를 요구한다. 이 설정만으로 서버 인증서의 신원까지 검증하지는 않는다. 다른 호스트·외부 DB로 연결을 바꾸면 인증서와 주소 검증도 함께 설계한다.
- Spring의 `127.0.0.1:8080`은 호스트 내부 연결용이다. 외부에서는 Nginx의 80번 포트로 readiness만 확인한다. 보안 그룹의 443번 포트를 여는 것만으로 HTTPS가 구성되지는 않으며 일반 API 공개에는 프록시·인증서 설정이 추가로 필요하다.
- 현재는 사용자가 선택한 MySQL `root` 계정 하나를 Spring과 DB 상태 검사에 사용한다. 예시 양식의 `MYSQL_PASSWORD`를 MySQL 컨테이너에는 `MYSQL_ROOT_PASSWORD`, Spring에는 `DB_PASSWORD`로 전달한다. MySQL이 요구하는 환경변수 이름과 사용자가 작성하는 이름의 차이다.
- MySQL은 root 계정으로 `SELECT 1`이 성공한 뒤 준비 완료가 된다. Compose는 이를 기다린 뒤 Spring을 시작한다. 이후 Spring의 readiness는 DB 연결까지 확인한다.
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
# 아래는 최초 수동 검증에 사용할 버전입니다. 다른 코드라면 해당 버전의 태그를 사용합니다.
docker buildx build --platform linux/amd64 --tag magnavi-api:96265a0 --load .

# 만들어진 이미지가 Linux/AMD64인지와 내용 식별값을 확인합니다.
docker image inspect magnavi-api:96265a0 --format '{{.Os}}/{{.Architecture}} {{.Id}}'
```

JAR 기본 경로는 `build/libs/MagNavi_SpringServer-0.0.1-SNAPSHOT.jar`다. 버전을 변경했다면 Dockerfile의 `JAR_FILE` 빌드 인수에 새 실행 JAR 경로를 전달한다. `*-plain.jar`는 선택하지 않는다.

이미 빌드·검증한 `magnavi-api:96265a0`이 있다면 이 절을 반복하지 않고 다음 절로 진행한다. ECR을 사용하기 위해 JAR와 이미지를 새로 만들 필요는 없다.

Java 기반 이미지와 MySQL 이미지는 digest로 고정했다. Dockerfile에서 설치하는 OS 패키지는 빌드 시점의 저장소를 이용하므로 재빌드한 전체 이미지가 항상 바이트 단위로 같다는 뜻은 아니다. 배포 때는 **검증한 최종 Spring 이미지 자체**를 ECR에 올리고, ECR에서 조회한 최종 digest로 식별한다. `docker image inspect`의 로컬 이미지 ID를 ECR digest 대신 사용하지 않는다.

## 4. Mac에서 ECR에 이미지 올리기

### 4.1 저장소와 접근 권한 준비

AWS 콘솔에서 서울 리전을 선택하고 ECR의 비공개 저장소를 확인한다. 최초 생성 시 이름은 `magnavi-api`, 태그 변경 가능성은 `Immutable`(같은 태그 덮어쓰기 방지), 암호화는 기본 `AES-256`을 사용한다. 같은 이름의 저장소가 이미 있으면 설정을 확인하고 재사용한다. 이미지 삭제·수명 주기 정책은 별도로 정하며 이 절차에서 자동 삭제하지 않는다.

AWS 권한은 다음처럼 나눈다. 같은 계정의 IAM 정책을 기준으로 하며 저장소를 공개하거나 다른 계정에 공유할 필요는 없다.

| 주체 | 허용할 일 |
|---|---|
| Mac의 배포용 AWS 로그인 | `magnavi-api` 저장소에 이미지 업로드와 digest 조회 |
| Spring EC2에 연결한 IAM 역할 | `magnavi-api` 저장소에서 이미지 다운로드 |
| GitHub Actions | `release` 배포용 OIDC 역할 사용. 현재 수동 로그인 정보를 복사하지 않음. [자동 배포 안내](CI_CD.md) 참고 |

두 주체 모두 `ecr:GetAuthorizationToken`은 `Resource: "*"`에 허용해야 로그인할 수 있다. 실제 이미지 작업은 `arn:aws:ecr:ap-northeast-2:ACCOUNT_ID:repository/magnavi-api`로 한정한다. `ACCOUNT_ID`는 실제 AWS 계정 ID로 바꾸는 설명용 표시다.

- Mac: `ecr:BatchCheckLayerAvailability`, `ecr:InitiateLayerUpload`, `ecr:UploadLayerPart`, `ecr:CompleteLayerUpload`, `ecr:PutImage`, `ecr:BatchGetImage`, `ecr:DescribeImages`.
- EC2: `ecr:BatchCheckLayerAvailability`, `ecr:GetDownloadUrlForLayer`, `ecr:BatchGetImage`.

EC2 역할은 EC2 서비스가 사용할 수 있는 신뢰 정책과 인스턴스 프로파일이 필요하다. 기존 역할이 있다면 다른 권한을 잃지 않도록 확인한 뒤 필요한 ECR 권한만 추가한다. EC2에 Mac의 AWS 액세스 키를 복사하거나 `aws login`을 실행하지 않는다.

### 4.2 Mac의 AWS CLI 로그인

AWS CLI는 터미널에서 AWS에 요청하는 도구다. [공식 설치 안내](https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html)에 따라 AWS CLI v2를 준비한다. 아래 `aws login`은 v2.32.0 이상과 `SignInLocalDevelopmentAccess` 권한이 필요하다. IAM Identity Center 계정이라면 해당 조직의 `aws configure sso`·`aws sso login` 절차를 사용한다.

```bash
# Mac 터미널에서 실행합니다. AWS CLI가 설치됐는지 확인합니다.
aws --version

# 브라우저 인증으로 임시 자격 증명을 받습니다. PEM 키를 사용하는 SSH와는 별개입니다.
# magnavi-deploy는 이 작업에 사용할 로컬 로그인 프로필 이름입니다.
aws login --profile magnavi-deploy --region ap-northeast-2

# 의도한 AWS 계정으로 로그인했는지 확인합니다. 오류가 나면 다음 단계로 진행하지 않습니다.
aws sts get-caller-identity --profile magnavi-deploy
```

### 4.3 기존 이미지에 ECR 주소를 붙여 업로드

다음 명령은 **같은 Mac 터미널**에서 순서대로 실행한다. 각 명령이 실패하면 원인을 해결한 뒤 계속한다. 저장소와 위 권한이 먼저 준비돼 있어야 한다.

```bash
# 서울 리전과 저장소, 이미 빌드한 버전을 이번 터미널의 변수에 담습니다.
ECR_REGION=ap-northeast-2
ECR_REPOSITORY=magnavi-api
ECR_TAG=96265a0

# 로그인한 계정의 ID로 업로드할 ECR 주소를 만듭니다.
ECR_ACCOUNT_ID=$(aws sts get-caller-identity --profile magnavi-deploy --query Account --output text)
ECR_REGISTRY="${ECR_ACCOUNT_ID:?AWS 계정 확인 필요}.dkr.ecr.${ECR_REGION}.amazonaws.com"
ECR_IMAGE="${ECR_REGISTRY}/${ECR_REPOSITORY}:${ECR_TAG}"

# AWS에서 받은 임시 로그인 값을 Docker에 직접 전달합니다. 값을 출력하거나 복사하지 않습니다.
aws ecr get-login-password --profile magnavi-deploy --region "$ECR_REGION" \
  | docker login --username AWS --password-stdin "$ECR_REGISTRY"

# 기존 이미지에 ECR용 이름을 추가합니다. 이미지를 다시 빌드하는 명령은 아닙니다.
docker tag "magnavi-api:${ECR_TAG}" "$ECR_IMAGE"

# 이 단계에서 실제 이미지 파일들이 ECR로 업로드됩니다.
docker push "$ECR_IMAGE"

# ECR에 저장된 이미지의 내용 식별값을 조회합니다.
ECR_DIGEST=$(aws ecr describe-images --profile magnavi-deploy --region "$ECR_REGION" \
  --repository-name "$ECR_REPOSITORY" --image-ids "imageTag=${ECR_TAG}" \
  --query 'imageDetails[0].imageDigest' --output text)

# sha256:...가 포함된 전체 주소를 EC2의 SPRING_IMAGE 값으로 사용합니다.
printf '%s/%s@%s\n' "$ECR_REGISTRY" "$ECR_REPOSITORY" "$ECR_DIGEST"
```

`Immutable` 저장소에서 같은 태그가 이미 존재하면 덮어쓰거나 삭제하지 않는다. 이미 업로드한 동일 이미지인지 확인하고, 다른 빌드라면 새 태그를 사용한다. ECR 로그인 토큰은 12시간 유효하므로 인증 만료 시 로그인 명령을 다시 실행한다.

EC2에 따로 복사할 파일은 `compose.prod.yaml`과 `.env.prod.example` 두 개다. SSH/SCP의 `-i`에는 Mac에 보관한 PEM 키의 경로를 사용하고, 대상은 현재 EC2 주소와 `ubuntu` 계정이다. **PEM 파일 자체는 EC2에 복사하지 않는다.** 배포 폴더는 EC2의 `~/magnavi`로 만들고 두 파일을 둔다. 실제 `.env.prod`는 아래에서 별도로 작성한다.

## 5. EC2의 환경 설정과 최초 실행

아래는 **실제 배포 단계에서 사용할 명령**이다. 먼저 배포 대상과 새 DB 볼륨 사용 여부를 확인한다. EC2에 AWS CLI v2와 ECR 다운로드 권한을 가진 IAM 역할이 준비되어야 하며, ECR·S3 및 MySQL 공식 이미지 다운로드에 필요한 외부 통신이 가능해야 한다. EC2에 SSH로 접속한 뒤 실행한다.

```bash
# Compose 파일 두 개를 복사해 둔 EC2의 배포 폴더로 이동합니다.
cd ~/magnavi

# EC2에 연결한 IAM 역할로 AWS에 접근되는지 확인합니다.
aws sts get-caller-identity --region ap-northeast-2

# 처음 배포할 때만 예시를 복사합니다. 기존 설정 파일은 덮어쓰지 않습니다.
cp -n .env.prod.example .env.prod

# 실제 비밀번호 파일은 소유자만 읽고 쓸 수 있게 합니다.
chmod 600 .env.prod

# 필요한 실제 값을 작성합니다. 비밀값을 채팅이나 로그에 공유하지 않습니다.
nano .env.prod
```

설정 항목:

- `SPRING_IMAGE`: 4.3절에서 출력한 `계정ID.dkr.ecr.ap-northeast-2.amazonaws.com/magnavi-api@sha256:...` 전체 주소. 실제 조회한 digest를 넣는다.
- `MYSQL_PASSWORD`: 사용자가 직접 정한 root 비밀번호 하나. 실제 `.env.prod`에 입력한다. 별도의 `MYSQL_ROOT_PASSWORD` 항목은 작성하지 않는다. `$` 같은 문자가 있으면 Compose 해석 규칙에 맞게 값을 작은따옴표로 감싸며, 파일을 셸 스크립트로 실행하지 않는다.
- `JWT_SECRET_BASE64`: `openssl rand -base64 32`로 생성한 키. 재배포 때도 같은 값을 유지한다.
- `SPRING_HTTP_PORT`: 기본 8080. 바꾸면 아래 상태 확인 URL의 포트도 맞춘다.

Compose는 `--env-file`에서 값을 읽으므로 `source .env.prod`는 필요 없다. 셸에 이미 같은 이름의 환경변수가 있으면 그것이 우선한다. 특히 로컬 검증에서는 기존 개발 환경변수가 섞이지 않도록 확인한다. 예시 파일의 필수 값은 비어 있어 그대로 사용하면 Compose가 실행을 거절한다.

```bash
# ECR은 Mac에서 확인한 것과 같은 계정·서울 리전의 저장소를 사용합니다.
ECR_ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
ECR_REGISTRY="${ECR_ACCOUNT_ID:?AWS 계정 확인 필요}.dkr.ecr.ap-northeast-2.amazonaws.com"

# Docker를 sudo로 실행하므로 로그인도 sudo docker로 해야 같은 로그인 정보를 사용합니다.
# AWS CLI는 ubuntu 사용자가 EC2 역할에서 임시 자격 증명을 받아 실행합니다.
aws ecr get-login-password --region ap-northeast-2 \
  | sudo docker login --username AWS --password-stdin "$ECR_REGISTRY"

# 설정 문법과 필수 값 유무를 검사합니다. -q는 비밀번호가 포함된 전체 설정 출력을 막습니다.
sudo docker compose --env-file .env.prod -f compose.prod.yaml config -q

# .env.prod의 Spring 이미지와 Compose에 고정한 MySQL 이미지를 다운로드합니다.
sudo docker compose --env-file .env.prod -f compose.prod.yaml pull

# 백그라운드에서 실행하고 두 컨테이너가 healthy가 될 때까지 기다립니다.
# 최초 실행에서는 새 DB 초기화와 기존 Flyway 마이그레이션이 진행됩니다.
sudo docker compose --env-file .env.prod -f compose.prod.yaml up -d --wait --wait-timeout 300

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

### 5.1 Nginx로 외부에서 readiness 확인

Nginx는 외부 요청을 EC2 내부의 Spring에 전달하는 프로그램이다. 현재는 도메인·HTTPS 준비 전 연결 확인을 위해 `/actuator/health/readiness` 하나만 HTTP로 공개한다. 로그인 등 나머지 경로는 404를 반환한다.

아래는 EC2의 `/etc/nginx/sites-available/magnavi`에 적용한 설정을 설명하는 예시다. `EC2_PUBLIC_IP`는 실제 EC2 공인 IP로 바꾼다. 실제 주소·PEM 키·비밀값은 저장소에 기록하지 않는다. 이 파일은 JAR나 Compose에 포함되지 않으므로 Nginx 설치·설정은 EC2 호스트에서 별도로 관리한다.

```nginx
server {
    # 외부 HTTP 요청을 받습니다. HTTPS는 별도 설정이 필요합니다.
    listen 80;

    # 설명용 표시를 현재 EC2 공인 IP로 바꿉니다.
    server_name EC2_PUBLIC_IP;

    # 상태 확인 요청만 EC2 내부의 Spring으로 전달합니다.
    location = /actuator/health/readiness {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
    }

    # 일반 API는 HTTPS 설정 후 공개합니다.
    location / {
        return 404;
    }
}
```

설정 파일을 `/etc/nginx/sites-enabled/magnavi`에 심볼릭 링크로 연결한 뒤 아래 명령을 사용한다. 이미 연결된 환경에서는 링크를 다시 만들지 않는다.

```bash
# EC2에서 실행합니다. 문법 검사가 성공한 경우에만 설정을 다시 읽습니다.
if sudo nginx -t; then
  sudo systemctl reload nginx
fi
```

```bash
# Mac 등 EC2 외부에서 실행합니다. EC2_PUBLIC_IP를 실제 주소로 바꿉니다.
# 이 위치에서 127.0.0.1을 사용하면 EC2가 아니라 현재 컴퓨터를 검사합니다.
curl --fail --silent --show-error --max-time 10 \
  http://EC2_PUBLIC_IP/actuator/health/readiness
```

`{"status":"UP"}`이면 외부 → Nginx → Spring의 상태 확인 경로가 연결된 것이다. 전체 API·모델 연동·HTTPS·부하 검증까지 끝났다는 뜻은 아니다.

## 6. 종료·재배포와 데이터 보존

```bash
# 최근 Spring 로그를 확인합니다. 외부 공유 전 민감한 값이 없는지 확인합니다.
sudo docker compose --env-file .env.prod -f compose.prod.yaml logs --tail 100 spring

# 컨테이너를 중지하되 DB 데이터와 실행 설정을 보존합니다.
sudo docker compose --env-file .env.prod -f compose.prod.yaml stop

# 중지했던 컨테이너를 다시 실행하고 준비 상태를 확인합니다.
sudo docker compose --env-file .env.prod -f compose.prod.yaml up -d --wait --wait-timeout 300
```

새 Spring 이미지로 바꿀 때는 먼저 이미지 전달·검증과 DB 마이그레이션 호환성·백업을 확인한다. 그다음 `.env.prod`의 `SPRING_IMAGE`만 새 식별자로 변경하고 다음 명령으로 Spring만 교체한다. MySQL이 정상 실행 중인 상태를 전제로 한다.

```bash
# ECR 로그인이 만료됐다면 5절의 로그인 명령을 다시 실행합니다.
# 새 Spring 이미지만 먼저 내려받습니다. 실패하면 실행 중인 서버를 교체하지 않습니다.
sudo docker compose --env-file .env.prod -f compose.prod.yaml pull spring

# --no-deps로 MySQL을 교체하지 않고 Spring만 새 이미지로 실행합니다.
# 기존 연결은 종료될 수 있습니다. 무중단 배포를 보장하는 구성은 아닙니다.
sudo docker compose --env-file .env.prod -f compose.prod.yaml up -d --no-deps --wait --wait-timeout 300 spring
```

- 운영 프로젝트 이름 `magnavi-api-prod`를 유지해야 같은 `magnavi-api-prod_mysql-data` 볼륨을 사용한다. 다른 이름으로 실행하면 다른 빈 DB가 생성될 수 있다.
- **`docker compose down -v`, 운영 볼륨 삭제, volume prune을 배포 절차에 사용하지 않는다.** DB 파일이 삭제될 수 있다.
- 볼륨은 백업이 아니다. 실제 운영 전에는 EC2 밖의 백업 보관 위치·주기·복원 검증을 마련해야 한다.
- 기존 볼륨에서 `.env.prod`의 DB 비밀번호만 바꿔도 DB 계정 비밀번호는 바뀌지 않는다. 계정 변경과 앱 설정 변경을 함께 계획한다.
- 이전 앱 전용 계정 구성으로 만든 DB를 그대로 사용하는 경우에도 root 계정의 실제 접속 권한·비밀번호를 먼저 확인해야 한다. 이 변경은 기존 계정을 삭제하거나 비밀번호를 자동 변경하지 않으며, 문제 해결을 위해 볼륨을 초기화하지 않는다.
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

위 표는 2026-10-03의 이전 앱 전용 계정 구성에 대한 기록이다. root 계정 구성의 확인 범위와 혼동하지 않는다.

2026-10-12 정리 기준, 사용자 제공 실행 결과에서 다음을 확인했다. 실제 실행일이 명시되지 않은 과거 결과를 정리한 것이며 에이전트가 이번 PR 작업에서 운영 서버에 접속해 재검증한 것은 아니다.

| 수동 배포 확인 항목 | 제공된 실행 결과 |
|---|---|
| ECR 이미지 전달 | Mac에서 push 성공, EC2에서 동일 digest의 pull 성공 |
| 최초 Compose 기동 | 새 DB 볼륨 생성, MySQL·Spring 모두 Healthy |
| EC2 내부 readiness | `127.0.0.1`에서 `{"status":"UP"}` |
| Nginx 설정·외부 readiness | `nginx -t` 성공 후 reload, Mac에서 HTTP readiness `UP` |

root 구성의 로컬 설정 검증은 실제 env 자동 로딩을 끄고 `--env-file /dev/null`과 검사용 값으로 수행한다. 두 Compose의 필수 값 검사·root 비밀번호 전달·DB 비공개와 Spring 루프백 게시·볼륨 식별자 유지를 확인한다. Java 21 기존 테스트는 Testcontainers의 격리 DB를 사용하며 실제 env나 운영 DB에 연결하지 않는다.

로컬 검증·수동 기동 성공은 EC2의 CPU·메모리·네트워크 부하 검증을 대신하지 않는다. HTTPS·일반 API 외부 연결·실제 앱/모델 연동·백업/복원·부하 검증은 남아 있다. 위 수동 배포 기록과 이후 추가한 [CI/CD 코드·검증 범위](CI_CD.md)는 구분한다. 실제 첫 release 자동 배포는 별도 확인 대상이다.

## 8. 참고 문서

- [AWS CLI 브라우저 로그인](https://docs.aws.amazon.com/cli/latest/userguide/cli-configure-sign-in.html)
- [ECR 이미지 업로드](https://docs.aws.amazon.com/AmazonECR/latest/userguide/docker-push-ecr-image.html)
- [ECR 이미지 다운로드](https://docs.aws.amazon.com/AmazonECR/latest/userguide/docker-pull-ecr-image.html)
- [ECR 저장소별 IAM 권한](https://docs.aws.amazon.com/AmazonECR/latest/userguide/security_iam_id-based-policy-examples.html)
- [Dockerfile 명령 설명](https://docs.docker.com/reference/dockerfile/)
- [Compose 서비스 설정](https://docs.docker.com/reference/compose-file/services/)
- [Compose 시작 순서와 준비 상태](https://docs.docker.com/compose/how-tos/startup-order/)
- [Compose 환경변수 해석](https://docs.docker.com/compose/how-tos/environment-variables/variable-interpolation/)
- [Ubuntu Nginx 구성](https://ubuntu.com/server/docs/how-to/web-services/configure-nginx/)
- [MySQL JDBC TLS 설정](https://dev.mysql.com/doc/connector-j/en/connector-j-connp-props-security.html)
