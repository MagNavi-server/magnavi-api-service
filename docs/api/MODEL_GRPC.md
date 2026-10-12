# 모델 gRPC 계약과 Spring 클라이언트 — 6단계

구현일: 2026-09-29 · 범위: Spring 내부 호출과 합성 모델 서버 검증

6단계는 Spring이 모델 서비스와 센서·결과를 주고받는 기능이다. 앱 WebSocket·인증·사용자별 연결 관리는 7단계, 실제 Python 모델 연동·성능 측정은 8단계다. Python 저장소와 기존 HTTP `/predict`는 이번에 수정하지 않았다. 5단계 교통 신규 등록·조건부 외부 즐겨찾기는 사용자 결정에 따라 보류했다.

## 1. 실제 구현과 호출 흐름

```text
PositioningSessionService.open(modelKey, modelVersion)
  → 새 UUID를 가진 ModelStream 생성
  → 재사용 채널에서 PredictStream 호출
  → start 전송 → ready 확인
  → send(SensorSample)로 연속 전송
  → next(Duration)로 ready / warming_up / prediction 읽기
  → prediction만 place의 ModelLocationQueryService로 최상위 장소 조회
  → PositioningResult 반환
  → close() 시 gRPC 취소·대기열 정리
```

현재는 내부 Java 기능이며 공개 REST·WebSocket 경로를 추가하지 않았다. 테스트용 모델은 Java JUnit 안에서 루프백 TCP 서버로 실행한다. 고정된 합성 응답만 반환하며 실제 Python 추론을 검증한 것은 아니다.

## 2. 규격의 단일 원본과 생성

- 원본: [positioning.proto](../../src/main/proto/magnavi/positioning/v1/positioning.proto).
- 서비스: `magnavi.positioning.v1.PositioningService/PredictStream`.
- 계약 버전: `1`. 패키지의 `v1`은 통신 규격이며 학습 모델 버전과 별개다.
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
| contract_version | 통신 규격 버전 1 |
| model_key, model_version | 이번 연결에서 사용할 모델 묶음과 버전 |
| sensor_profile_id | 양쪽이 합의한 단위·축·방향 표현·샘플 주기 규격 ID |

`ready`에는 요청한 모델·버전·프로필·계약 버전이 그대로 있어야 하고 `required_count=16`이어야 한다. 이 값이 다르면 `PROTOCOL_ERROR`로 취소한다.

**실제 앱·학습 데이터의 단위와 축은 아직 확인되지 않았다.** `synthetic-v1`은 테스트에서만 쓰는 가상의 규격이며 실제 센서 의미를 보증하지 않는다. 서버 설정은 기본 비활성이고 프로필도 비어 있다. 임의의 프로필 이름을 적는 것만으로 앱 규격 확인이 끝나지는 않는다. Python은 지원하지 않는 프로필을 `FAILED_PRECONDITION`으로 거절해야 한다. 실제 단위·주파수·모델 파일 조합을 확인하고 같은 프로필 문서를 공유한 뒤 활성화한다.

## 4. 샘플과 응답 규칙

| 항목 | v1 규칙 |
|---|---|
| sequence | 1부터 연속 증가하는 양의 int64. 누락·중복·역순은 연결 종료 |
| elapsed_nanos | 같은 기기의 단조 시계로 측정한 첫 샘플 이후 경과 시간. 첫 샘플 0, 이후 증가. 서버 시각과 차이를 계산하지 않음 |
| magnetic, orientation | 각각 x·y·z 3개 유한 실수. Java에서는 벡터 전체 누락·NaN·무한대를 거절 |
| protobuf 필수 값 | optional로 수치 누락과 정상 값 0을 구분. Python 구현도 has/presence 확인 필요 |
| 준비 중 | 1~15번째 입력마다 같은 sequence, buffered_count=sequence, required_count=16 반환 |
| 예측 | 16번째부터 입력마다 같은 sequence의 prediction 하나 반환 |
| 결과 순서 | 센서 하나당 준비 중 또는 예측 하나. 보낸 순서대로 반환하고 임의 생략·윈도우 리셋 금지 |
| 위치 코드 | 문자열 그대로 사용. `007`과 `7`, 대소문자·앞뒤 공백을 정규화하지 않음 |
| 후보 | 점수 내림차순 1~3개, 중복 코드 없음. 첫 후보와 최상위 코드·점수 일치 |
| 점수 | 유한한 0~1 값. 실제 위치 정확도가 보장된 확률로 표현하지 않음 |

입력 원문과 예측 이력은 DB에 저장하지 않는다. 메모리 큐는 전달에 필요한 동안만 사용하고 종료 시 비운다. Java record의 기본 문자열 표현도 센서·상세 예측 값을 숨기도록 구현했다. 모델 보정·웨이브렛·스케일링·16개 윈도우는 Python 책임이다.

## 5. 결과의 장소 매핑

`PositioningConnection.next(wait)`는 gRPC 수신 콜백 밖의 호출자 스레드에서 동작한다. 읽기 소비자는 연결마다 하나로 제한한다. 이후 WebSocket 구현에서는 이 메서드를 gRPC 콜백이나 전체 사용자가 공유하는 수신 스레드에서 호출하지 않고 제한된 실행 구조에 연결해야 한다.

- `ready`·`warming_up`: `mappingStatus=NOT_REQUESTED`, `location=null`.
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

Java 21에서 `./gradlew clean build --console=plain`을 실행해 기존 170개와 신규 gRPC 48개, 총 218개 테스트 및 실행 JAR 빌드가 통과했다. 실패·오류·건너뜀은 0이다.

검증한 항목:

- 실제 루프백 gRPC 전송, 0의 optional presence, ready·15회 워밍업·16번째 이후 예측.
- 순번·시간·모델·버전·세션·점수·후보 오류와 원격 오류 비노출.
- 두 동시 스트림의 독립 결과, 연결/입력/결과 한도, 준비·응답·유휴 시간 초과.
- 취소의 서버 전달, 대기 읽기 해제, 늦은 결과 격리, 종료 후 새 호출 차단.
- Spring 설정·기본 비활성·프로필 필수·원격 평문 차단.
- 격리 MySQL과 실제 gRPC를 연결한 버전별 장소 매핑·누락·비활성 처리, DB 조회 중 취소·DB 실패.

미검증·후속:

- 실제 앱·학습 데이터의 센서 단위·축·주파수·상대 시간 생성, 실제 모델 프로필.
- Python 생성 코드·모델 서버 구현 및 동일 입력의 추론 회귀.
- 앱 WebSocket 인증·사용자별 연결 한도·재접속·결과 표시.
- 실제 모델 부하·성능, 운영 서비스 인증·TLS/mTLS·배포.
- 교통 신규 등록·외부 즐겨찾기·기존 데이터 이관.

참고: [gRPC 기본 개념](https://grpc.io/docs/what-is-grpc/core-concepts/), [protobuf Gradle 플러그인](https://github.com/google/protobuf-gradle-plugin). API의 실제 콜백 제약은 현재 사용 중인 gRPC Java 소스와 컴파일·통합 테스트로 확인했다.
