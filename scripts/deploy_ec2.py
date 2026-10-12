#!/usr/bin/env python3
"""EC2에서 검증한 이미지로 Spring만 교체한다. Python 표준 라이브러리만 사용한다."""

import fcntl
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile

PROJECT = "magnavi-api-prod"
DEPLOY_ROOT = Path("/home/ubuntu/magnavi")


def validate_request(request):
    """쉘 명령에 쓰기 전에 이미지 digest·커밋·배포 순서의 형식을 검사한다."""
    region = request.get("region", "")
    image = request.get("image", "")
    if not re.fullmatch(r"[a-z]{2}(?:-[a-z]+)+-\d", region):
        raise ValueError("AWS 리전 형식이 올바르지 않습니다.")
    pattern = rf"\d{{12}}\.dkr\.ecr\.{re.escape(region)}\.amazonaws\.com/[a-z0-9][a-z0-9._/-]*@sha256:[a-f0-9]{{64}}"
    if not re.fullmatch(pattern, image):
        raise ValueError("같은 리전 ECR의 digest 주소가 필요합니다.")
    if not re.fullmatch(r"[a-f0-9]{40}", request.get("commit", "")):
        raise ValueError("전체 커밋 SHA가 필요합니다.")
    for key in ("run_number", "run_attempt", "run_id"):
        if not re.fullmatch(r"[1-9][0-9]{0,19}", str(request.get(key, ""))):
            raise ValueError("배포 실행 번호가 올바르지 않습니다.")


def run(command, *, phase, stdin=None, env=None, timeout=60):
    """출력을 수집하되 오류에 설정·비밀번호·컨테이너 로그를 재노출하지 않는다."""
    try:
        result = subprocess.run(command, input=stdin, env=env, text=True,
                                capture_output=True, timeout=timeout, check=False)
    except subprocess.TimeoutExpired as error:
        raise RuntimeError(f"{phase}: 제한 시간을 초과했습니다.") from error
    if result.returncode:
        raise RuntimeError(f"{phase}: 실패했습니다 (종료 코드 {result.returncode}).")
    return result.stdout.strip()


def atomic_text(path, text):
    """중간에 종료되어도 상태 파일이 반만 기록되지 않게 같은 폴더에서 교체한다."""
    temporary = path.with_name(path.name + ".next")
    temporary.write_text(text, encoding="utf-8")
    temporary.replace(path)


def atomic_link(path, target):
    """성공 버전과 복구 버전의 바로가기를 원자적으로 갱신한다."""
    temporary = path.with_name(path.name + ".next")
    temporary.unlink(missing_ok=True)
    temporary.symlink_to(target)
    temporary.replace(path)


def container_id(service):
    """기존 운영 Compose 프로젝트에 속한 컨테이너 하나만 찾는다."""
    ids = run(["docker", "ps", "-aq", "--filter", f"label=com.docker.compose.project={PROJECT}",
               "--filter", f"label=com.docker.compose.service={service}"], phase="기존 컨테이너 확인").splitlines()
    if len(ids) != 1:
        raise RuntimeError(f"기존 {service} 컨테이너가 정확히 하나여야 합니다. 최초 설치는 별도입니다.")
    return ids[0]


def inspect(container, template):
    """필요한 상태 필드만 읽는다. 전체 inspect에는 비밀번호가 있어 출력하지 않는다."""
    return run(["docker", "inspect", "--format", template, container], phase="컨테이너 상태 확인")


def compose(root, release, *arguments, timeout=60):
    """공유 비밀 파일과 해당 버전의 이미지 설정으로 같은 Compose 프로젝트를 사용한다."""
    environment = dict(os.environ, COMPOSE_DISABLE_ENV_FILE="true")
    # 호스트 환경변수 때문에 파일에 명시한 값이 덮어써지지 않게 한다.
    for key in ("SPRING_IMAGE", "MYSQL_PASSWORD", "JWT_SECRET_BASE64", "SPRING_HTTP_PORT"):
        environment.pop(key, None)
    return run(["docker", "compose", "--project-name", PROJECT, "--project-directory", str(root),
                "--env-file", str(root / ".env.prod"), "--env-file", str(release / "image.env"),
                "-f", str(release / "compose.prod.yaml"), *arguments],
               phase="Spring Compose 실행", env=environment, timeout=timeout)


def deploy_locked(request, bundle, root, state):
    """잠금 안에서 오래된 실행을 거절하고, 검증·다운로드 후 Spring만 교체한다."""
    order = (int(request["run_number"]), int(request["run_attempt"]))
    order_file = state / "last-started.json"
    if order_file.exists() and order <= tuple(json.loads(order_file.read_text())):
        raise RuntimeError("이미 시작한 실행보다 오래되었거나 중복된 배포입니다.")

    if not (root / ".env.prod").is_file():
        raise RuntimeError("EC2의 기존 .env.prod 파일이 필요합니다.")
    mysql_id = container_id("mysql")
    if inspect(mysql_id, "{{.State.Health.Status}}") != "healthy":
        raise RuntimeError("기존 MySQL이 healthy가 아니므로 배포하지 않습니다.")
    volume = inspect(mysql_id, '{{range .Mounts}}{{if eq .Destination "/var/lib/mysql"}}{{.Name}}{{end}}{{end}}')
    if volume != f"{PROJECT}_mysql-data":
        raise RuntimeError("기존 MySQL 데이터 볼륨의 이름이 예상과 다릅니다.")

    releases = state / "releases"
    releases.mkdir(exist_ok=True)
    current = state / "current"
    if not current.exists():
        # 첫 자동 배포 전에 수동 배포의 이미지·Compose도 복구용으로 남긴다.
        spring_id = container_id("spring")
        if inspect(spring_id, "{{.State.Health.Status}}") != "healthy":
            raise RuntimeError("첫 자동 배포는 기존 Spring이 healthy일 때 시작합니다.")
        baseline = Path(tempfile.mkdtemp(prefix="manual-", dir=releases))
        old_image = inspect(spring_id, "{{.Config.Image}}")
        shutil.copyfile(root / "compose.prod.yaml", baseline / "compose.prod.yaml")
        (baseline / "image.env").write_text(f"SPRING_IMAGE={old_image}\n")
        atomic_link(current, baseline)

    atomic_link(state / "rollback", current.resolve())
    # 실패한 최신 실행 이후에도 지연 도착한 예전 SSM 명령은 배포하지 않는다.
    atomic_text(order_file, json.dumps(order))
    release = releases / f"{request['run_id']}-{request['run_attempt']}"
    release.mkdir()
    shutil.copyfile(bundle / "compose.prod.yaml", release / "compose.prod.yaml")
    (release / "image.env").write_text(f"SPRING_IMAGE={request['image']}\n")
    (release / "deployment.json").write_text(json.dumps(request, indent=2) + "\n")
    compose(root, release, "config", "--quiet")

    # Docker 로그인 정보는 이번 실행의 임시 폴더에만 보관한다.
    with tempfile.TemporaryDirectory(prefix="ecr-login-", dir=state) as docker_config:
        environment = dict(os.environ, DOCKER_CONFIG=docker_config, AWS_PAGER="")
        password = run(["aws", "ecr", "get-login-password", "--region", request["region"]],
                       phase="EC2 역할로 ECR 인증", env=environment)
        registry = request["image"].split("/", 1)[0]
        run(["docker", "login", "--username", "AWS", "--password-stdin", registry],
            phase="ECR 로그인", stdin=password + "\n", env=environment)
        run(["docker", "pull", request["image"]], phase="이미지 다운로드", env=environment, timeout=300)

    print("이미지 다운로드 완료. Spring 컨테이너를 교체합니다.", flush=True)
    # --no-deps와 서비스 이름으로 기존 MySQL 컨테이너·볼륨을 유지한다.
    compose(root, release, "up", "-d", "--no-deps", "--pull", "never",
            "--wait", "--wait-timeout", "300", "spring", timeout=360)
    spring_id = container_id("spring")
    if inspect(spring_id, "{{.Config.Image}}") != request["image"]:
        raise RuntimeError("실행 이미지가 요청한 digest와 다릅니다.")
    if inspect(spring_id, "{{.State.Health.Status}}") != "healthy":
        raise RuntimeError("Spring readiness 검사에 실패했습니다.")
    if container_id("mysql") != mysql_id:
        raise RuntimeError("배포 중 MySQL 컨테이너가 변경되었습니다. 확인이 필요합니다.")
    atomic_link(current, release)
    print(f"배포 성공: commit={request['commit']}", flush=True)


def deploy(request, bundle, root=DEPLOY_ROOT):
    """SSM이 겹치거나 이전 작업이 남아 있어도 EC2에서는 한 번에 하나만 실행한다."""
    validate_request(request)
    os.umask(0o077)
    state = root / ".deploy"
    state.mkdir(mode=0o700, exist_ok=True)
    with (state / "deploy.lock").open("a") as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as error:
            raise RuntimeError("다른 배포가 실행 중입니다. 종료 상태 확인 후 다시 실행하세요.") from error
        deploy_locked(request, bundle, root, state)


def main():
    """SSM이 전달한 비밀값 없는 배포 요청을 읽고 실패를 종료 코드로 전달한다."""
    bundle = Path(__file__).resolve().parent
    try:
        request = json.loads((bundle / "request.json").read_text())
        deploy(request, bundle)
    except (OSError, ValueError, RuntimeError) as error:
        print(f"배포 실패: {error}\n현재 상태와 .deploy/rollback을 확인하세요. DB는 자동 복구하지 않습니다.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
