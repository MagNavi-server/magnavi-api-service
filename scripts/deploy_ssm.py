#!/usr/bin/env python3
"""GitHub Actions에서 비밀값 없는 배포 파일을 SSM으로 보내고 실제 완료까지 기다린다."""

import base64
import io
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tarfile
import tempfile
import time

from deploy_ec2 import validate_request


def make_payload(repository, request, instance_id):
    """허용한 세 파일만 묶는다. 로컬 .env·키·Git 디렉터리는 읽거나 전송하지 않는다."""
    validate_request(request)
    if not re.fullmatch(r"i-(?:[a-f0-9]{8}|[a-f0-9]{17})", instance_id):
        raise ValueError("EC2 인스턴스 ID 형식이 올바르지 않습니다.")
    files = {
        "compose.prod.yaml": (repository / "compose.prod.yaml").read_bytes(),
        "deploy_ec2.py": (repository / "scripts/deploy_ec2.py").read_bytes(),
        "request.json": json.dumps(request).encode(),
    }
    archive = io.BytesIO()
    with tarfile.open(fileobj=archive, mode="w:gz") as tar:
        for name, data in files.items():
            entry = tarfile.TarInfo(name)
            entry.size, entry.mode = len(data), 0o600
            tar.addfile(entry, io.BytesIO(data))
    encoded = base64.b64encode(archive.getvalue()).decode()
    # 한 명령 안에서 생성·실행·정리를 수행한다. base64에는 쉘 제어 문자가 없다.
    command = "\n".join([
        "set -eu", "umask 077", 'work_dir=$(mktemp -d /tmp/magnavi-deploy.XXXXXXXX)',
        'trap \'rm -rf -- "$work_dir"\' EXIT',
        f"printf '%s' '{encoded}' | base64 --decode > \"$work_dir/bundle.tar.gz\"",
        'tar -xzf "$work_dir/bundle.tar.gz" -C "$work_dir"',
        'python3 "$work_dir/deploy_ec2.py"',
    ])
    payload = {
        "DocumentName": "AWS-RunShellScript",
        "InstanceIds": [instance_id],
        "Comment": f"MagNavi release {request['commit']}",
        "TimeoutSeconds": 120,
        "Parameters": {"commands": [command], "executionTimeout": ["900"]},
    }
    # Run Command를 큰 파일 전송에 사용하지 않도록 자체 크기 제한을 둔다.
    if len(json.dumps(payload).encode()) > 24000:
        raise ValueError("배포 파일 묶음이 자체 24KB 제한을 넘습니다. 전송 방식을 재검토하세요.")
    return payload


def aws_json(region, arguments):
    """AWS CLI JSON 응답만 반환하고 인증 실패를 배포 성공으로 숨기지 않는다."""
    result = subprocess.run(["aws", "--region", region, "--no-cli-pager", *arguments,
                             "--output", "json"], capture_output=True, text=True,
                            timeout=45, check=False)
    if result.returncode:
        if "InvocationDoesNotExist" in result.stderr:
            return None  # SSM 전송 직후 결과 조회에 잠깐 반영 지연이 생길 수 있다.
        raise RuntimeError("AWS CLI 요청 실패: 실행 역할의 권한·리전·대상과 AWS 상태를 확인하세요.")
    return json.loads(result.stdout)


def wait_for_command(region, command_id, instance_id, timeout=1140):
    """명령 접수와 실행 성공을 구분하고 실패·취소·시간 초과를 모두 실패 처리한다."""
    deadline = time.monotonic() + timeout
    last_status = None
    while time.monotonic() < deadline:
        result = aws_json(region, ["ssm", "get-command-invocation", "--command-id", command_id,
                                   "--instance-id", instance_id])
        status = result["Status"] if result else "Pending"
        if status != last_status:
            print(f"SSM 실행 상태: {status}", flush=True)
            last_status = status
        if status == "Success":
            if result.get("ResponseCode") != 0:
                raise RuntimeError("SSM 성공 상태지만 실행 종료 코드가 0이 아닙니다.")
            return
        if status not in ("Pending", "InProgress", "Delayed"):
            # 원격 출력에는 앞으로 민감 정보가 생길 수 있어 Actions 로그로 복사하지 않는다.
            raise RuntimeError(f"EC2 배포 실패: SSM 상태 {status}. SSM 명령 상세에서 확인하세요.")
        time.sleep(10)
    raise RuntimeError("SSM 완료 확인 시간 초과. EC2 작업이 남을 수 있으므로 명령 상태부터 확인하세요.")


def main():
    """워크플로 환경변수를 검사하고 SSM 명령 ID·커밋·이미지를 실행 요약에 기록한다."""
    try:
        request = {
            "region": os.environ["AWS_REGION"], "image": os.environ["DEPLOY_IMAGE"],
            "commit": os.environ["GITHUB_SHA"], "run_id": os.environ["GITHUB_RUN_ID"],
            "run_number": os.environ["GITHUB_RUN_NUMBER"], "run_attempt": os.environ["GITHUB_RUN_ATTEMPT"],
        }
        instance_id = os.environ["EC2_INSTANCE_ID"]
        repository = Path(__file__).resolve().parents[1]
        payload = make_payload(repository, request, instance_id)
        with tempfile.TemporaryDirectory(prefix="magnavi-ssm-") as directory:
            payload_path = Path(directory) / "command.json"
            payload_path.write_text(json.dumps(payload))
            result = aws_json(request["region"], ["ssm", "send-command", "--cli-input-json", f"file://{payload_path}"])
        command_id = result["Command"]["CommandId"]
        print(f"SSM 명령 접수: {command_id}", flush=True)
        summary = os.environ.get("GITHUB_STEP_SUMMARY")
        if summary:
            with open(summary, "a") as output:
                output.write(f"- 커밋: `{request['commit']}`\n- 이미지: `{request['image']}`\n- SSM 명령: `{command_id}`\n")
        wait_for_command(request["region"], command_id, instance_id)
        print("EC2 Spring 이미지 교체와 readiness 확인이 완료되었습니다.")
        if summary:
            with open(summary, "a") as output:
                output.write("- 결과: EC2 Spring 교체·readiness 성공\n")
    except (KeyError, OSError, ValueError, RuntimeError, subprocess.TimeoutExpired) as error:
        print(f"배포 실패: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
