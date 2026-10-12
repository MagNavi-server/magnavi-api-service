"""AWS·운영 Docker를 호출하지 않고 배포 실패, 순서, 데이터 보존을 검증한다."""

import base64
import fcntl
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tarfile
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import deploy_ec2
import deploy_ssm

IMAGE = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/magnavi-api@sha256:" + "a" * 64
OLD_IMAGE = IMAGE.replace("a" * 64, "b" * 64)


def request(number=2, attempt=1):
    """실제 계정·비밀값을 사용하지 않는 배포 요청을 만든다."""
    return {"region": "ap-northeast-2", "image": IMAGE, "commit": "c" * 40,
            "run_number": str(number), "run_attempt": str(attempt), "run_id": str(1000 + number)}


class FakeDocker:
    """Docker 상태 변화를 흉내 내어 실패 지점 뒤의 변경 여부를 확인한다."""

    def __init__(self, failure=None):
        """기존 정상 Spring·MySQL과 선택한 실패 지점을 준비한다."""
        self.failure = failure
        self.calls = []
        self.replaced = False

    def __call__(self, command, **options):
        """AWS·Docker 대신 결정된 응답을 반환하고 실제 서비스 변경은 하지 않는다."""
        self.calls.append(command)
        if command[:2] == ["docker", "ps"]:
            service = command[-1].split("=")[-1]
            return "changed-mysql" if self.replaced and service == "mysql" and self.failure == "mysql-changed" else service
        if command[:2] == ["docker", "inspect"]:
            template, service = command[-2:]
            if "Mounts" in template:
                return "other-volume" if self.failure == "volume" else "magnavi-api-prod_mysql-data"
            if "Config.Image" in template:
                return IMAGE if self.replaced and self.failure != "wrong-image" else OLD_IMAGE
            if service == "mysql" and self.failure == "mysql-unhealthy":
                return "unhealthy"
            return "unhealthy" if self.replaced and self.failure == "readiness" else "healthy"
        if command[:2] == ["docker", "compose"]:
            if "config" in command and self.failure == "config":
                raise RuntimeError("설정 검증 실패")
            if "up" in command:
                self.replaced = True
                if self.failure == "up":
                    raise RuntimeError("기동 실패")
        if command[:2] == ["docker", "pull"] and self.failure == "pull":
            raise RuntimeError("다운로드 실패")
        if command[0] == "aws":
            return "temporary-test-token"
        return ""


class Ec2DeploymentTests(unittest.TestCase):
    """배포 잠금·검증·복구 기록과 MySQL 보존을 로컬 임시 폴더에서 검증한다."""

    def setUp(self):
        """운영 파일과 무관한 가짜 EC2 폴더·Compose·비밀 파일을 준비한다."""
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.bundle = self.root / "bundle"
        self.bundle.mkdir()
        (self.root / ".env.prod").write_text("MYSQL_PASSWORD=test-only-never-read\n")
        (self.root / "compose.prod.yaml").write_text("old-compose")
        (self.bundle / "compose.prod.yaml").write_text("new-compose")
        previous_umask = os.umask(0o077)
        self.addCleanup(os.umask, previous_umask)

    def deploy(self, fake, data=None):
        """비밀 파일을 직접 읽으면 실패시키고 가짜 Docker로 배포 함수를 실행한다."""
        original_read = Path.read_text

        def read_non_secret(path, *args, **kwargs):
            """상태 기록은 허용하되 비밀 파일 직접 읽기는 금지한다."""
            if path.name == ".env.prod":
                raise AssertionError("배포 코드가 비밀 파일을 직접 읽었습니다.")
            return original_read(path, *args, **kwargs)

        with patch.object(deploy_ec2, "run", fake), patch.object(Path, "read_text", read_non_secret):
            deploy_ec2.deploy(data or request(), self.bundle, self.root)

    def test_success_replaces_only_spring_and_records_rollback(self):
        """성공 시 새 digest를 기록하고 이전 수동 배포와 비밀번호 파일을 보존한다."""
        fake = FakeDocker()
        self.deploy(fake)
        state = self.root / ".deploy"
        self.assertEqual((state / "current/image.env").read_text(), f"SPRING_IMAGE={IMAGE}\n")
        self.assertEqual((state / "rollback/image.env").read_text(), f"SPRING_IMAGE={OLD_IMAGE}\n")
        self.assertEqual((state / "rollback/compose.prod.yaml").read_text(), "old-compose")
        self.assertEqual((self.root / ".env.prod").read_text(), "MYSQL_PASSWORD=test-only-never-read\n")
        up = [command for command in fake.calls if "up" in command]
        self.assertEqual(len(up), 1)
        self.assertEqual(up[0][-1], "spring")
        self.assertIn("--no-deps", up[0])
        self.assertIn("--wait", up[0])
        self.assertFalse(any("down" in command or "prune" in command for command in fake.calls))
        self.assertFalse(list(state.glob("ecr-login-*")))

    def test_preflight_and_pull_failures_never_replace_spring(self):
        """DB·볼륨·설정·다운로드 실패는 컨테이너 교체 전에 멈춘다."""
        for attempt, failure in enumerate(("mysql-unhealthy", "volume", "config", "pull"), 1):
            with self.subTest(failure=failure):
                fake = FakeDocker(failure)
                with self.assertRaises(RuntimeError):
                    self.deploy(fake, request(attempt=attempt))
                self.assertFalse(fake.replaced)

    def test_failed_health_keeps_last_successful_record(self):
        """Spring 기동 실패 후 성공 기록을 바꾸지 않고 복구할 버전을 남긴다."""
        fake = FakeDocker("up")
        with self.assertRaises(RuntimeError):
            self.deploy(fake)
        self.assertTrue(fake.replaced)
        self.assertEqual((self.root / ".deploy/current/image.env").read_text(), f"SPRING_IMAGE={OLD_IMAGE}\n")
        self.assertEqual((self.root / ".deploy/rollback/image.env").read_text(), f"SPRING_IMAGE={OLD_IMAGE}\n")

    def test_mismatched_image_or_state_is_not_success(self):
        """Compose가 성공해도 이미지·readiness·DB 보존이 다르면 실패 처리한다."""
        for index, failure in enumerate(("wrong-image", "readiness", "mysql-changed"), 1):
            with self.subTest(failure=failure), self.assertRaises(RuntimeError):
                self.deploy(FakeDocker(failure), request(attempt=index))
        self.assertEqual((self.root / ".deploy/current/image.env").read_text(), f"SPRING_IMAGE={OLD_IMAGE}\n")

    def test_old_and_duplicate_runs_are_rejected_before_docker(self):
        """늦게 온 과거 명령과 중복 명령이 최신 배포를 덮어쓰지 못하게 한다."""
        self.deploy(FakeDocker())
        for data in (request(number=1, attempt=99), request()):
            fake = FakeDocker()
            with self.assertRaises(RuntimeError):
                self.deploy(fake, data)
            self.assertEqual(fake.calls, [])

    def test_higher_attempt_can_retry_failed_deployment(self):
        """같은 실행의 새 시도는 실패 원인을 해결한 후 재배포할 수 있다."""
        with self.assertRaises(RuntimeError):
            self.deploy(FakeDocker("pull"))
        self.deploy(FakeDocker(), request(attempt=2))
        self.assertTrue((self.root / ".deploy/current").resolve().name.endswith("-2"))

    def test_lock_rejects_overlapping_ssm_command(self):
        """GitHub 실행 취소 뒤 남아 있는 EC2 작업과 새 배포가 겹치지 않는다."""
        state = self.root / ".deploy"
        state.mkdir()
        with (state / "deploy.lock").open("a") as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            fake = FakeDocker()
            with self.assertRaises(RuntimeError):
                self.deploy(fake)
            self.assertEqual(fake.calls, [])

    def test_missing_environment_does_not_create_database(self):
        """최초 설치를 자동 배포가 대신하지 않으며 DB를 새로 만들지 않는다."""
        (self.root / ".env.prod").unlink()
        fake = FakeDocker()
        with self.assertRaises(RuntimeError):
            self.deploy(fake)
        self.assertFalse(fake.calls)


class SsmDeploymentTests(unittest.TestCase):
    """SSM 접수 지연·실패·시간 초과와 전송 파일 범위를 검증한다."""

    def test_payload_contains_only_explicit_non_secret_files(self):
        """실제 저장소의 비밀 파일을 탐색하지 않고 지정 파일 세 개만 전송한다."""
        repository = Path(__file__).resolve().parents[2]
        payload = deploy_ssm.make_payload(repository, request(), "i-0123456789abcdef0")
        command = payload["Parameters"]["commands"][0]
        encoded = command.split("printf '%s' '", 1)[1].split("'", 1)[0]
        with tarfile.open(fileobj=io.BytesIO(base64.b64decode(encoded)), mode="r:gz") as archive:
            self.assertEqual(archive.getnames(), ["compose.prod.yaml", "deploy_ec2.py", "request.json"])
            self.assertEqual(json.load(archive.extractfile("request.json")), request())
        self.assertEqual(payload["InstanceIds"], ["i-0123456789abcdef0"])
        self.assertEqual(payload["Parameters"]["executionTimeout"], ["900"])

    def test_invalid_request_is_rejected(self):
        """임의 쉘 입력·태그 주소·다른 리전·잘못된 배포 순서를 거절한다."""
        for key, value in (("image", "image:latest"), ("image", IMAGE + ";id"),
                           ("region", "us-east-1"), ("commit", "main"), ("run_number", "0")):
            with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                deploy_ec2.validate_request(dict(request(), **{key: value}))

    def test_eventual_consistency_waits_until_actual_success(self):
        """명령 접수 직후 결과가 없어도 기다리고 실제 종료 코드 0을 확인한다."""
        results = [None, {"Status": "Pending"}, {"Status": "InProgress"}, {"Status": "Success", "ResponseCode": 0}]
        with patch.object(deploy_ssm, "aws_json", side_effect=results) as aws, patch.object(deploy_ssm.time, "sleep"):
            deploy_ssm.wait_for_command("ap-northeast-2", "command", "instance")
        self.assertEqual(aws.call_count, 4)

    def test_terminal_failures_are_not_reported_as_success(self):
        """실패·취소·시간 초과·비정상 종료는 모두 워크플로 실패로 전달한다."""
        for status in ("Failed", "Cancelled", "TimedOut", "Cancelling", "Success"):
            with self.subTest(status=status), patch.object(deploy_ssm, "aws_json", return_value={"Status": status, "ResponseCode": 1}):
                with self.assertRaises(RuntimeError):
                    deploy_ssm.wait_for_command("region", "command", "instance")

    def test_polling_has_a_deadline(self):
        """응답이 계속 대기 상태여도 무한히 기다리지 않는다."""
        with patch.object(deploy_ssm.time, "monotonic", side_effect=[0, 1, 4]), patch.object(deploy_ssm.time, "sleep"), \
                patch.object(deploy_ssm, "aws_json", return_value={"Status": "Pending"}):
            with self.assertRaisesRegex(RuntimeError, "시간 초과"):
                deploy_ssm.wait_for_command("region", "command", "instance", timeout=3)

    def test_aws_access_denied_does_not_become_pending(self):
        """존재 반영 지연과 실제 권한 오류를 구분해 잘못된 권한을 빨리 알린다."""
        result = subprocess.CompletedProcess([], 1, "", "AccessDeniedException")
        with patch.object(deploy_ssm.subprocess, "run", return_value=result), self.assertRaises(RuntimeError):
            deploy_ssm.aws_json("region", ["ssm", "get-command-invocation"])

    def test_cli_error_never_exposes_captured_output(self):
        """Docker 오류 원문에 비밀값이 섞여도 상위 로그로 전달하지 않는다."""
        result = subprocess.CompletedProcess([], 1, "test-secret", "test-secret")
        with patch.object(deploy_ec2.subprocess, "run", return_value=result):
            with self.assertRaises(RuntimeError) as error:
                deploy_ec2.run(["docker", "compose"], phase="설정 검사")
        self.assertNotIn("test-secret", str(error.exception))


if __name__ == "__main__":
    unittest.main()
