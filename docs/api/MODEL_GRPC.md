# 모델 gRPC 계약과 Spring 클라이언트 — 6단계

최초 구현: 2026-09-29 · 후속 변경: 2026-10-28 · 현재 계약 버전: 2

6단계는 Spring이 모델 서비스와 센서·결과를 주고받는 기능이다. 앱 WebSocket·인증·사용자별 연결 관리는 7단계, 실제 Python 모델 연동·성능 측정은 8단계다. 2026-10-28에는 Python gRPC 구현과 함께 초기 응답 규칙을 갱신했다. 기존 HTTP `/predict` 응답은 유지한다. 5단계 교통 신규 등록·조건부 외부 즐겨찾기는 사용자 결정에 따라 보류했다.

## 1. 실제 구현과 호출 흐름

```text
PositioningSessionService.open(modelKey, modelVersion)
  → 새 UUID를 가진 ModelStream 생성
  → 재사용 채널에서 PredictStream 호출
  → start 전송 → ready 확인
  → send(SensorSample)로 연속 전송
  → next(Duration)로 ready / prediction 읽기
  → prediction만 place의 ModelLocationQueryService로 최상위 장소 조회
  → PositioningResult 반환
  → close() 시 gRPC 취소·대기열 정리
```

6단계에서는 내부 Java 기능을 구현했다. 2026-10-15의 7단계에서 이 기능을 사용하는 [인증된 앱 WebSocket](POSITIONING_WEBSOCKET.md)을 추가했으며 기본 비활성이다. 테스트용 모델은 Java JUnit 안에서 루프백 TCP 서버로 실행한다. 기존 대역 검증과 별도로 `PythonModelIntegrationTests`가 실제 Python 서버를 띄워 기존 144개 예측 결과를 비교한다. 실행 방법은 아래 추가 절을 따른다.

## 2. 규격의 단일 원본과 생성

- 원본: [positioning.proto](../../src/main/proto/magnavi/positioning/v1/positioning.proto).
- 서비스: `magnavi.positioning.v1.PositioningService/PredictStream`.
- 동작 계약 버전: `2`. 메시지 필드 번호·서비스 경로는 그대로여서 protobuf 패키지 `v1`은 유지한다. 초기 대기 의미가 달라 이전 계약 1은 거절한다. 학습 모델 버전과는 별개다.
- `./gradlew generateProto` 또는 `./gradlew build`가 Java 메시지와 비동기 stub을 생성한다.
- 생성물은 `build/generated/sources/proto/` 아래에만 두며 직접 수정하거나 Git에 올리지 않는다.
- 생성기 버전은 기존 Spring Boot BOM의 `protobuf-java.version`·`grpc-java.version`을 사용한다. 이번 환경에서는 protobuf/protoc 4.35.1, gRPC/Java 생성기 1.83.1이다. Boot·Java·protobuf Gradle 플러그인 버전은 변경하지 않았다.
- Python 연결 시 같은 원본의 고정 커밋 또는 태그를 빌드 입력으로 사용하고 버전·해시를 확인한다. 별도 복사본을 수정하지 않는다. Python 생성·배포 파이프라인은 후속이다.
- 필드 번호는 재사용하지 않는다. 삭제할 때 `reserved`로 남기고 호환되지 않는 변경은 새 계약 버전으로 나눈다.

## 3. 시작과 센서 규격

한 스트림은 하나의 측정이다. 클라이언트가 첫 메시지로 `start`를 정확히 한 번 보내며, 서버가 `ready`를 보낸 뒤 `sample`을 전송한다. 모델 버전은 요청에서 명시하고 서버가 다른 버전으로 자동 대체하지 않는다.

| 시작 필드 | 의미 |
|---|---|
| session_id | Spring이 생성한 UUID. 회원 ID·기기 ID가 아니다 |
| contract_version | 첫 샘플 예측 계약 버전 2 |
| model_key, model_version | 이번 연결에서 사용할 모델 묶음과 버전 |
| sensor_profile_id | 양쪽이 합의한 단위·축·방향 표현·샘플 주기 규격 ID |

`ready`에는 요청한 모델·버전·프로필·계약 버전이 그대로 있어야 하고 `required_count=16`이어야 한다. 이 값이 다르면 `PROTOCOL_ERROR`로 취소한다. 16은 필터 윈도우 크기이며 응답을 기다릴 샘플 수가 아니다.

**실제 앱·학습 데이터의 단위와 축은 아직 확인되지 않았다.** `synthetic-v1`은 테스트에서만 쓰는 가상의 규격이며 실제 센서 의미를 보증하지 않는다. 서버 설정은 기본 비활성이고 프로필도 비어 있다. 임의의 프로필 이름을 적는 것만으로 앱 규격 확인이 끝나지는 않는다. Python은 지원하지 않는 프로필을 `FAILED_PRECONDITION`으로 거절해야 한다. 실제 단위·주파수·모델 파일 조합을 확인하고 같은 프로필 문서를 공유한 뒤 활성화한다.

## 4. 샘플과 응답 규칙

| 항목 | 계약 v2 규칙 |
|---|---|
| sequence | 1부터 연속 증가하는 양의 int64. 누락·중복·역순은 연결 종료 |
| elapsed_nanos | 같은 기기의 단조 시계로 측정한 첫 샘플 이후 경과 시간. 첫 샘플 0, 이후 증가. 서버 시각과 차이를 계산하지 않음 |
| magnetic, orientation | 각각 x·y·z 3개 유한 실수. Java에서는 벡터 전체 누락·NaN·무한대를 거절 |
| protobuf 필수 값 | optional로 수치 누락과 정상 값 0을 구분. Python 구현도 has/presence 확인 필요 |
| 준비 중 | `warming_up`을 받으면 규격 오류. protobuf 메시지는 필드 호환성을 위해 보존 |
| 예측 | 첫 번째부터 입력마다 같은 sequence의 prediction 하나 반환. Python의 기존 pad 계산 유지 |
| 결과 순서 | 센서 하나당 예측 하나. 보낸 순서대로 반환하고 임의 생략·윈도우 리셋 금지 |
| 위치 코드 | 문자열 그대로 사용. `007`과 `7`, 대소문자·앞뒤 공백을 정규화하지 않음 |
| 후보 | 점수 내림차순 1~3개, 중복 코드 없음. 첫 후보와 최상위 코드·점수 일치 |
| 점수 | 유한한 0~1 값. 실제 위치 정확도가 보장된 확률로 표현하지 않음 |

입력 원문과 예측 이력은 DB에 저장하지 않는다. 메모리 큐는 전달에 필요한 동안만 사용하고 종료 시 비운다. Java record의 기본 문자열 표현도 센서·상세 예측 값을 숨기도록 구현했다. 모델 보정·웨이브렛·스케일링·16개 윈도우는 Python 책임이다.

## 5. 결과의 장소 매핑

`PositioningConnection.next(wait)`는 gRPC 수신 콜백 밖의 호출자 스레드에서 동작한다. 읽기 소비자는 연결마다 하나로 제한한다. 이후 WebSocket 구현에서는 이 메서드를 gRPC 콜백이나 전체 사용자가 공유하는 수신 스레드에서 호출하지 않고 제한된 실행 구조에 연결해야 한다.

- `ready`: `mappingStatus=NOT_REQUESTED`, `location=null`. v2에서는 워밍업 응답이 업무 계층으로 전달되지 않는다.
- 매핑 성공: `mappingStatus=FOUND`, 최상위 장소의 건물·층·이름 포함.
- 매핑 누락·비활성 장소: `mappingStatus=NOT_FOUND`, `location=null`. 다른 모델·버전이나 숫자 장소 ID로 대체하지 않는다.
- 후보 목록은 원래 코드·점수로 반환하고 후보별 DB 조회를 추가하지 않는다.
- DB 장애는 `INTERNAL`로 변환하고 스트림을 취소한다. DB 조회 중 취소되면 조회 후 연결 상태를 다시 확인해 늦은 결과를 반환하지 않는다.
- 긴 스트림 전체를 DB 트랜잭션으로 감싸지 않는다. place의 기존 짧은 읽기 전용 조회를 사용한다.

## 6. 오류·종료·한도

| 상황 | 내부 실패 종류 |
|---|---|
| 기능 비활성 | NOT_CONFIGURED |
| ready 전에 센서 전송 | NOT_READY |
| 잘못된 순번·시간·외부 INVALID_ARGUMENT/FAILED_PRECONDITION/OUT_OF_RANGE | INVALID_INPUT |
| 다른 세션·모델·버전, 누락·잘못된 응답 | PROTOCOL_ERROR |
| 외부 UNAVAILABLE·예상하지 못한 원격 정상 종료 | UNAVAILABLE |
| 연결·대기열 한도, 느린 결과 소비, RESOURCE_EXHAUSTED | OVERLOADED |
| 준비·응답·유휴 시간 초과, DEADLINE_EXCEEDED | TIMED_OUT |
| 명시적 취소·읽기 스레드 인터럽트 | CANCELLED |
| 종료한 클라이언트에서 새 호출 | CLOSED |
| 외부 상세를 숨겨야 하는 기타 실패·DB 장애 | INTERNAL |

gRPC 오류 description·원인·trailer는 업무 예외에 전달하지 않는다. `next`가 지정한 대기 시간 내 메시지를 받지 못하면 빈 Optional을 반환하며, 호출의 실제 시간 초과·종료는 별도 예외로 구분한다. 이미 유효한 SensorSample을 잘못된 순서로 보내면 연결을 종료한다. 객체 생성 시 잘못된 벡터·NaN 등을 발견한 경우에는 전송 전 IllegalArgumentException으로 거절한다.

입력 큐는 응답을 아직 받지 못한 샘플 수로 제한한다. `isReady`·`onReady`로 쓰기 가능 상태를 확인하고 같은 observer의 송신·취소를 직렬화한다. 결과 큐는 수동 수신 요청으로 한도를 지키고 `next`가 소비한 수만큼 수신 허용량을 보충한다. 임의 센서 폐기·자동 재전송·측정 중 자동 스트림 재연결은 하지 않는다.

`close()`는 여러 번 호출해도 한 번만 취소한다. 종료 뒤 callback은 무시하고 재연결에는 새 세션 ID를 사용한다. 정상 앱 종료를 어떤 UI 이벤트로 표시할지는 7단계다. 모델 서버가 취소를 받았다고 이미 진행 중인 계산이 자동 중단되지는 않으므로 Python도 결과 폐기·자원 정리를 구현해야 한다.

## 7. 설정

현재 [.env.example](../../.env.example)과 [application.yaml](../../src/main/resources/application.yaml)에 설정을 추가했다. Spring은 `.env`를 자동으로 읽지 않으므로 [README](../../README.md)의 환경변수 주입 방법을 사용한다.

| 설정 | 기본값·의미 |
|---|---|
| MODEL_GRPC_ENABLED | false. 실제 호출을 활성화할 때만 true |
| MODEL_GRPC_HOST | 빈 값. 활성화 시 모델 서버 호스트 명시 |
| MODEL_GRPC_PORT | 50051. 제안 기본 포트이며 실제 Python 포트가 정해졌다는 뜻은 아님 |
| MODEL_GRPC_PLAINTEXT | false. TLS 기본. true는 localhost·127.0.0.1·::1 테스트에서만 허용 |
| MODEL_SENSOR_PROFILE_ID | 빈 값. 합의한 실제 센서 규격 ID 필요 |
| magnavi.model-grpc.max-streams | 인스턴스당 32 |
| max-pending-samples / max-buffered-events | 스트림별 각각 32 |
| max-message-bytes | gRPC 송수신 메시지 최대 65536바이트 |
| start-timeout | ready 대기 3초 |
| response-timeout | 미응답 입력·소비하지 않은 결과 최대 대기 5초 |
| idle-timeout | 활동 없는 준비된 연결 30초 |

위 한도는 개발 시작값이다. 운영 동시 사용자·주파수·허용 지연을 실측한 결과가 아니다. 감시 주기는 50ms이며 스케줄링 지연이 있어 정확히 임계 순간에 종료한다고 보장하지 않는다. TLS는 기본 신뢰 저장소를 사용한다. 운영 서비스 인증·mTLS·인증서 배포 정책은 네트워크 구성과 함께 후속 확정한다.

외부 모델에 연결하거나 ready를 받아야 Spring 자체가 시작되는 구조는 아니다. 기능 비활성·모델 장애는 회원·장소·즐겨찾기 API의 준비 상태에 섞지 않는다.

## 8. 검증과 남은 작업

최초 6단계(2026-09-29)에는 Java 21의 `./gradlew clean build --console=plain`으로 기존 170개와 신규 gRPC 48개, 총 218개 테스트 및 실행 JAR 빌드를 통과했다. 실패·오류·건너뜀은 0이었다. 아래 검증 항목 중 초기 응답 규칙은 2026-10-28 계약 v2에 맞춰 갱신했으며 최신 전체 결과는 9장을 따른다.

검증한 항목:

- 실제 루프백 gRPC 전송, 0의 optional presence, ready·첫 샘플부터 예측 및 1·15·16번째 경계.
- 순번·시간·모델·버전·세션·점수·후보 오류와 원격 오류 비노출.
- 두 동시 스트림의 독립 결과, 연결/입력/결과 한도, 준비·응답·유휴 시간 초과.
- 취소의 서버 전달, 대기 읽기 해제, 늦은 결과 격리, 종료 후 새 호출 차단.
- Spring 설정·기본 비활성·프로필 필수·원격 평문 차단.
- 격리 MySQL과 실제 gRPC를 연결한 버전별 장소 매핑·누락·비활성 처리, DB 조회 중 취소·DB 실패.

미검증·후속:

- 실제 앱·학습 데이터의 센서 단위·축·주파수·상대 시간 생성, 실제 모델 프로필.
- 실제 센서·Linux 환경의 Python 모델 호환성과 수치 경고 해소. 로컬 생성 코드·서버·144개 합성 회귀는 2026-10-28 검증했다.
- 실제 앱의 WebSocket v2 적용·센서 전송·결과 표시(서버 인증·한도·재접속은 기존 구현).
- 실제 모델 부하·성능, 운영 서비스 인증·TLS/mTLS·배포.
- 교통 신규 등록·외부 즐겨찾기·기존 데이터 이관.

참고: [gRPC 기본 개념](https://grpc.io/docs/what-is-grpc/core-concepts/), [protobuf Gradle 플러그인](https://github.com/google/protobuf-gradle-plugin). API의 실제 콜백 제약은 현재 사용 중인 gRPC Java 소스와 컴파일·통합 테스트로 확인했다.

## 9. 계약 v2와 실제 Python 검증(2026-10-28)

초기 15개 대기 강제를 제거하고 첫 샘플 예측을 받는다. 원본 proto·Python 사본·생성 코드와
Spring 입력/응답 검증을 같이 갱신했다. Python은 [PR #6](https://github.com/MagNavi-server/magnavi-positioning-service/pull/6)으로 반영됐고 대응 Spring 변경을 이 PR에 포함한다. 이전 계약 1은 거절한다.
현재 Python 모델의 정수 위치 코드는 전송 경계에서 문자열이 된다. 실제 코드에 앞자리 0이 없는지
Python 시작 단계에서 검사하며 Spring은 받은 문자열을 변환하지 않는다. 운영 DB 매핑 등록은 후속이다.

Java 21에서 아래 명령을 실행해 269개 테스트·실행 JAR 빌드가 통과했다(실패·오류·건너뜀 0).
기존 테스트는 격리 MySQL·Java 대역을 사용하고 추가 1개는 실제 Python 모델의 144개 결과를 비교했다.

```bash
MAGNAVI_PYTHON_ROOT=/Users/ychoik/back_fastapi_my ./gradlew test build --no-daemon
```

위 절대 경로는 이번 검증 환경 예시이므로 각 환경의 Python 저장소 경로로 바꾼다.
Python 쪽 `build/streaming-venv`와 `build/generated`를 먼저 준비해야 한다.
환경 변수 미지정 시 실제 Python 테스트는 건너뛴다. 의도적으로 다시 검증할 때는
`--tests '*PythonModelIntegrationTests' --rerun-tasks`를 사용한다. 운영 DB·앱·배포는 변경하지 않았다.

Python [계약·실행 안내](https://github.com/MagNavi-server/magnavi-positioning-service/blob/c41e0a86d6c2b407f91b1230a191eee487096a15/docs/api/GRPC_CONTRACT.md)와
[트러블슈팅](https://github.com/MagNavi-server/magnavi-positioning-service/blob/c41e0a86d6c2b407f91b1230a191eee487096a15/docs/troubleshooting/grpc-first-prediction.md)에 원인·해결·검증·한계를 기록했다.
Spring 게시 후 Python lock의 원본 커밋 표시는 별도 갱신이 필요하다. 현재 사본의 내용·해시는 동일하다.
부하 성능 개선이나 실제 위치 정확도를 입증한 결과는 아니다.

게시 준비 중 확인(2026-10-28): 첫 전체 실행은 269개 중 기존 WebSocket 바이너리 거절 테스트의 정리 단계에서 1개 실패했다. 오류 응답·모델 취소 확인 이후 `Probe.close()`의 `sendClose()`가 `Output closed`를 반환했다. 동일 코드의 WebSocket 모음 재실행과 전체 `test build --rerun-tasks` 재검증에서 269개 모두 통과하고 JAR 빌드가 성공했다(실패·오류·건너뜀 0). 당시 종료 콜백과 정리 요청 사이 경합을 미해결로 기록했으며, 이후 보완은 아래에 구분한다.

### WebSocket 테스트 종료 경합 보완(2026-10-28)

- 원인: `closed.isDone()` 확인과 `sendClose()` 사이에 서버의 종료 콜백이 실행되면, JDK의 자동 종료 응답이 먼저 송신부를 닫을 수 있다. 사용 중인 Java 21 소스의 처리 순서를 확인하고 소켓 대역으로 같은 `Output closed` 오류를 재현했다. 최초 실패 당시 스레드 실행 순서를 직접 수집한 것은 아니다.
- 해결: `Probe.onClose()`의 종료 기록과 `Probe.close()`의 상태 확인·종료 요청을 같은 잠금으로 보호한다. 완료 대기는 잠금 밖에서 수행하여 종료 콜백을 막지 않는다. 송신부가 이미 닫혔어도 종료 결과를 확인하고, 전송 실패·통신 오류·각 단계 3초 시간 초과는 그대로 전달하면서 `abort()`로 자원을 정리한다.
- 회귀 검증: [종료 처리 테스트](../../src/test/java/com/example/magnavi_springserver/positioning/PositioningWebSocketProbeTests.java) 7개를 추가했다. 기존 정리 코드에서는 경합 재현·오류 전파·실패 시 정리 항목 5개가 실패했고, 수정 후 7개와 기존 실제 WebSocket 통합 12개가 모두 통과했다. 테스트 재시도나 예외 무시는 추가하지 않았다.
- 전체 검증: Java 21에서 위 Python 경로를 지정한 `./gradlew test build --no-daemon --console=plain` 실행 결과 276개 통과(실패·오류·건너뜀 0), 빌드 성공. 실제 Python 테스트 1개 안의 144개 예측 비교도 포함한다. 운영 코드·계약·의존성 변경과 부하 성능 측정은 없다.
- 게시 범위: [이슈 #32](https://github.com/MagNavi-server/magnavi-api-service/issues/32)의 별도 변경으로 테스트 보조 코드·회귀 테스트·이 기록만 포함한다. 선행 [Spring PR #31](https://github.com/MagNavi-server/magnavi-api-service/pull/31)은 머지됐으며, 그 CI 결과와 이번 수정의 로컬·CI 검증 결과를 구분한다.
