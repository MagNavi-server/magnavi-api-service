# Spring CI/CD 사용 안내

CI는 코드가 빌드되고 테스트를 통과하는지 자동 확인하는 작업이다. CD는 통과한 코드를 이미지로 만들고 EC2에서 실행하는 작업이다.

이 저장소에는 워크플로와 배포 스크립트를 구현했다. **실제 GitHub OIDC 인증과 첫 `release` 자동 배포 성공은 아직 확인하지 않았다.** 기존 EC2 수동 배포 결과와 자동 배포 검증을 구분한다.

## 1. 언제 무엇이 실행되는가

```text
작업 브랜치 → develop PR → Java 21 빌드·테스트 (CI)
develop → release PR    → Java 21 빌드·테스트 (CI)
release에 머지          → 빌드·테스트 → 이미지 생성 → ECR 업로드
                                                  → SSM → EC2 Spring 교체·readiness 확인 (CD)
```

| 파일 | 역할 |
|---|---|
| [ci.yml](../.github/workflows/ci.yml) | `develop`·`release` 대상 PR 검사. AWS 권한 없이 Testcontainers의 임시 MySQL로 테스트한다. |
| [deploy.yml](../.github/workflows/deploy.yml) | 중앙 저장소의 `release` push만 배포한다. 포크·develop·main push는 배포하지 않는다. |
| [deploy_ssm.py](../scripts/deploy_ssm.py) | Actions에서 실행. 배포 파일을 SSM으로 전달하고 EC2 명령 완료까지 기다린다. |
| [deploy_ec2.py](../scripts/deploy_ec2.py) | EC2에서 실행. 기존 DB를 확인하고 Spring만 교체한다. |
| [test_deployment.py](../scripts/tests/test_deployment.py) | AWS·운영 Docker를 호출하지 않고 배포 실패와 데이터 보존 동작을 검증한다. |

`build-and-test`가 PR 검사 이름이다. 필수 검사로 강제하는 GitHub Ruleset 설정은 별도이며 이 작업에서는 변경하지 않는다. 작업 PR은 Squash, `develop → release`는 Merge commit을 사용한다. `release` 머지는 실제 운영 배포를 시작하므로 DB 변경·실행 설정·미완성 기능 포함 여부를 먼저 확인한다.

## 2. 한 번 준비할 설정

중앙 저장소의 **Settings → Secrets and variables → Actions → Variables**에 아래 Repository Variables를 둔다. 실제 값은 저장소 문서에 적지 않는다.

| 변수 | 입력할 내용 |
|---|---|
| `AWS_REGION` | EC2·ECR을 만든 리전 |
| `AWS_DEPLOY_ROLE_ARN` | GitHub Actions가 사용할 IAM 역할 ARN |
| `ECR_REPOSITORY` | ECR 저장소 이름. 전체 URL이 아닌 이름만 입력 |
| `EC2_INSTANCE_ID` | 배포할 Spring EC2의 인스턴스 ID |

역할은 다음처럼 나눈다.

- **GitHub 배포 역할**: GitHub OIDC의 대상(`aud`)과 중앙 저장소 `release`의 주체(`sub`)를 검사하고, 해당 ECR push와 해당 EC2의 SSM 명령 실행·결과 조회만 허용한다.
- **EC2 실행 역할**: 해당 ECR pull과 SSM Agent 연결 권한을 가진다. EC2에 별도 AWS 사용자 비밀번호나 액세스 키를 저장하지 않는다.

OIDC는 GitHub가 이번 실행의 신원을 증명하고 AWS가 임시 권한을 주는 방식이다. 공급자 URL은 `https://token.actions.githubusercontent.com`, 대상은 `sts.amazonaws.com`이다. 저장소의 OIDC `sub` 형식에 맞춰 신뢰 정책을 설정한다. 이 저장소는 소유자·저장소의 불변 ID가 포함된 형식을 사용한다. GitHub Environment를 추가하면 `sub`가 바뀌므로 현재 워크플로에는 `environment`를 지정하지 않는다. [GitHub OIDC 안내](https://docs.github.com/en/actions/how-tos/secure-your-work/security-harden-deployments/oidc-in-aws), [주체 형식](https://docs.github.com/en/actions/reference/security/oidc).

EC2에는 Python 3, AWS CLI v2, Docker와 Compose가 필요하다. SSM 관리 노드는 `Online`이어야 한다. 현재 수동 배포 폴더는 `/home/ubuntu/magnavi`이며, 기존 `compose.prod.yaml`, `.env.prod`, 정상 실행 중인 Spring·MySQL이 있어야 한다. 자동 배포는 신규 EC2 초기 설치를 대신하지 않는다.

`.env.prod`의 비밀번호·JWT 키는 EC2에 그대로 둔다. GitHub에 Secrets로 복사하거나 새로 생성하지 않는다. 스크립트는 이 파일을 직접 읽거나 출력하지 않고, EC2의 Docker Compose가 실행할 때 사용한다. 이전에 선택한 root 계정·`MYSQL_PASSWORD` 변수도 유지한다.

## 3. 배포가 하는 일

1. 현재 실행의 커밋이 중앙 `release`의 최신 커밋인지 확인한다. 과거 실행을 다시 눌러 최신 버전을 덮어쓰는 것을 막는다.
2. 배포 스크립트 테스트와 Java 21 `clean build`를 실행한다. 실패하면 AWS 인증·업로드·배포로 넘어가지 않는다.
3. OIDC로 임시 권한을 받은 뒤 `linux/amd64` 이미지를 ECR에 올린다. 태그는 **전체 커밋 SHA–실행 ID–시도 번호**다. 같은 커밋을 재시도해도 기존 태그를 덮어쓰지 않는다.
4. 업로드 결과의 **digest**를 배포 주소로 사용한다. EC2가 나중에 다른 내용의 태그를 받는 것을 막는다.
5. Compose·배포 스크립트·비밀값 없는 요청 JSON만 작게 묶어 SSM으로 보낸다. 이미지 자체는 ECR에서 받는다. SSH 포트나 PEM 키를 GitHub Actions에 제공하지 않는다.
6. EC2가 잠금을 얻고 배포 순서를 검사한다. 기존 MySQL이 `healthy`이고 예상 볼륨을 사용하는지, Compose 설정이 유효한지 확인한다. 최초 자동 배포에서는 현재 수동 배포의 이미지·Compose를 복구용으로 남긴다.
7. EC2 역할로 ECR에 로그인하고 이미지를 내려받은 뒤 아래와 같은 Compose 동작으로 **Spring만** 교체한다.

   ```bash
   # 앞부분에는 배포 버전의 Compose와 환경 파일 경로가 지정됩니다.
   # --no-deps: MySQL을 함께 생성·재시작하지 않습니다.
   # --pull never: 바로 앞에서 내려받은 digest 이미지로만 실행합니다.
   # --wait: Spring의 DB 연결을 포함한 readiness가 정상인지 기다립니다.
   docker compose ... up -d --no-deps --pull never --wait --wait-timeout 300 spring
   ```

8. 실행 이미지·Spring 상태·기존 MySQL 컨테이너 보존을 확인한다. 성공한 버전만 `current`에 기록한다. Actions도 SSM `Success`와 종료 코드 `0`까지 기다린다. SSM 명령을 보냈다는 이유만으로 성공 처리하지 않는다. [SSM 결과 조회](https://docs.aws.amazon.com/systems-manager/latest/APIReference/API_GetCommandInvocation.html).

동시 배포는 GitHub concurrency와 EC2 파일 잠금으로 막는다. EC2는 이미 시작한 실행 번호·시도 번호보다 오래되거나 같은 명령을 거절한다. 실패 후 재시도는 Actions의 **Re-run jobs**로 새 시도 번호를 받아 진행한다. 실패한 버전보다 이전 실행을 다시 돌리는 방식은 복구 절차가 아니다.

MySQL·볼륨 삭제, DB 비밀번호 변경, 자동 이미지 정리, Nginx 변경은 하지 않는다. 다만 **Spring 기동 시 기존 설정대로 Flyway가 실행**되므로 새 SQL이 포함된 배포는 스키마 호환성을 먼저 검토해야 한다. 단일 Spring 교체에는 짧은 중단이 생길 수 있다.

## 4. 성공 확인과 서버 파일 위치

GitHub **Actions → Spring release deployment → 실행 상세**에서 `verify-and-deploy`의 성공과 요약의 커밋·이미지 digest·SSM 명령 ID를 확인한다. 이후 Nginx 경유 readiness도 확인한다. 이 자동 검사만으로 회원 API·앱·Python 실제 모델 통신·부하 검증까지 완료되는 것은 아니다.

EC2에서 자동 배포가 시작되면 다음 파일을 관리한다. `.deploy`는 SSM 실행 사용자(root)만 접근한다.

```text
/home/ubuntu/magnavi/
├── .env.prod                 # 기존 비밀 파일, 그대로 유지
├── compose.prod.yaml         # 기존 수동 배포 파일, 그대로 유지
└── .deploy/
    ├── deploy.lock           # 동시 배포 방지
    ├── last-started.json     # 늦게 온 이전 실행 거절
    ├── current -> releases/… # 마지막 성공 버전 (실패 후 실제 실행 상태와 다를 수 있음)
    ├── rollback -> releases/…# 이번 교체 전 마지막 성공 버전
    └── releases/
        ├── manual-…/        # 최초 자동 배포 직전의 수동 배포 기록
        └── 실행ID-시도번호/
            ├── compose.prod.yaml
            ├── image.env    # SPRING_IMAGE만 저장, 비밀번호 없음
            └── deployment.json
```

자동 배포 이후에는 예전 루트 Compose·`.env.prod`만으로 `up`을 실행하지 않는다. 거기에 적힌 과거 이미지로 돌아갈 수 있다. 현재 버전을 확인할 때는 다음처럼 **현재 배포의 Compose와 image.env를 함께 사용**한다.

```bash
# EC2에서 실행합니다. 비밀번호는 출력하지 않고 컨테이너 상태만 확인합니다.
sudo env -u SPRING_IMAGE docker compose \
  --project-name magnavi-api-prod --project-directory /home/ubuntu/magnavi \
  --env-file /home/ubuntu/magnavi/.env.prod \
  --env-file /home/ubuntu/magnavi/.deploy/current/image.env \
  -f /home/ubuntu/magnavi/.deploy/current/compose.prod.yaml ps -a
```

배포 기록과 로컬 Docker 이미지는 자동 삭제하지 않는다. 디스크·ECR 저장량을 확인하고 필요한 복구 버전을 보존한 상태에서 보관 정책을 별도로 정한다.

## 5. 실패했을 때

| 실패 지점 | 의미와 다음 확인 |
|---|---|
| 빌드·테스트 | EC2는 변경하지 않았다. 실패 검사부터 수정한다. |
| OIDC 인증 | 역할 ARN·aud·중앙 저장소 release sub 조건을 확인한다. 장기 액세스 키를 추가해 우회하지 않는다. |
| ECR 로그인·push/pull | GitHub push 역할과 EC2 pull 역할을 구분해 해당 저장소 권한·리전을 확인한다. |
| SSM 접수·조회 | 실행 요약의 명령 ID로 AWS Systems Manager → Run Command를 확인한다. EC2 Online·권한·네트워크를 확인한다. |
| EC2 사전 검사·다운로드 | Spring 교체 전이면 기존 컨테이너가 유지된다. DB·볼륨·설정·다운로드 문제를 해결한다. |
| Spring 교체·readiness | 새 Spring이 실패한 상태일 수 있다. 서버에서 컨테이너 상태와 제한된 로그를 확인하고 아래 복구 절차를 검토한다. |
| Actions 취소·조회 시간 초과 | 원격 명령이 계속 실행 중일 수 있다. 완료/실패 여부를 확인한 뒤 재시도한다. |

서버 설정·비밀값이 로그에 섞일 수 있어 원격 명령의 전체 출력이나 컨테이너 로그를 Actions에 자동 복사하지 않는다. `docker inspect` 전체 출력, 비밀 파일 `cat`, 값이 펼쳐지는 `docker compose config` 결과를 공유하지 않는다. 스크립트의 오류는 실패 단계와 종료 코드만 남긴다.

### 수동 복구

먼저 원격 배포가 끝났는지, `rollback` 버전과 현재 DB 스키마가 호환되는지 확인한다. 아래 명령은 **실제로 Spring 버전을 되돌리는 운영 작업**이다. 검토 후 필요할 때만 EC2에서 실행하며 평상시 설정 명령으로 실행하지 않는다.

```bash
# 배포와 같은 잠금을 잡아 동시에 컨테이너를 바꾸지 않습니다.
# rollback에 기록된 이전 Compose·이미지를 사용하고 MySQL은 건드리지 않습니다.
sudo bash <<'BASH'
set -euo pipefail
cd /home/ubuntu/magnavi
exec 9> .deploy/deploy.lock
flock -n 9
rollback_dir=$(readlink -f .deploy/rollback)
env -u SPRING_IMAGE docker compose \
  --project-name magnavi-api-prod --project-directory /home/ubuntu/magnavi \
  --env-file /home/ubuntu/magnavi/.env.prod \
  --env-file "$rollback_dir/image.env" -f "$rollback_dir/compose.prod.yaml" \
  up -d --no-deps --pull never --wait --wait-timeout 300 spring
# 정상 복구된 경우에만 현재 버전 기록을 맞춥니다.
ln -sfn "$rollback_dir" .deploy/current.next
mv -Tf .deploy/current.next .deploy/current
BASH
```

복구용 이미지는 EC2에 남겨두는 전제다. 없으면 EC2 역할로 ECR에 로그인해 해당 digest를 먼저 내려받는다. 위 명령은 성공한 경우에만 `current`를 복구 버전으로 바꾸고 배포 순서 기록은 유지한다. 수동 복구 사실을 기록하고 수정 PR → 새로운 `release` 배포로 정상 상태를 다시 확정한다. `last-started.json`을 삭제해 오래된 실행을 강제로 돌리지 않는다. 이전 이미지로 돌아가도 Flyway SQL·데이터는 되돌아가지 않는다.

## 6. 검증 범위와 첫 적용 순서

로컬 검증 명령:

```bash
# AWS·운영 Docker 없이 배포 실패·잠금·버전 보존을 검사합니다.
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts/tests -v

# Java 21과 Docker가 필요합니다. Testcontainers 전용 MySQL을 사용합니다.
./gradlew --no-daemon clean build --console=plain
```

2026-10-13 로컬 검증에서 배포 테스트 15개, Java 테스트 218개와 전체 빌드, actionlint/ShellCheck 워크플로 검사, 가짜 값만 주입한 Compose 문법 검사를 통과했다. 실제 EC2·AWS 자격 증명을 사용한 배포는 이 검증에 포함하지 않는다.

첫 적용은 **작업 PR → develop 머지 → develop에서 release PR 생성 → CI 성공 확인 → release 머지 → Actions·SSM·외부 readiness 확인** 순서다. 이 작업에서는 develop 대상 PR까지만 만들며, 실제 배포와 `release → main` 반영은 성공 확인 뒤 진행한다.
