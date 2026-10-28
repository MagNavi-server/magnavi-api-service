# 앱 WebSocket과 모델 gRPC 연결 — 7단계

구현일: 2026-10-15 · 계약 v2 변경: 2026-10-28 · 상태: Spring 로컬 구현·테스트, 운영 기본 비활성

앱이 센서 한 개씩 보내면 Spring이 그 연결의 모델 스트림으로 전달하고 결과를 돌려준다. 위치 계산과 최근 16개 센서 관리는 Python의 역할이며 Spring에서 중복 계산하지 않는다. Java gRPC 클라이언트와 실제 Python 모델의 144개 결과 비교는 검증했다. 실제 모바일 앱부터 WebSocket·Python까지의 종단 전환과 운영 WSS 연결은 후속이다.

## 1. 연결과 인증

- 경로: `/ws/positioning`. STOMP·SockJS 없이 JSON 텍스트 메시지를 주고받는다.
- 처음 HTTP 연결할 때 `Authorization: Bearer <MagNavi 액세스 토큰>` 헤더를 보낸다. 기존 로그인 API가 발급한 토큰을 사용한다.
- 서명·발급자·대상·만료 검증은 기존 JWT 설정을 공유한다. 연결 뒤에도 만료를 확인하며, 만료 시 해당 연결과 gRPC 호출을 종료한다.
- URL 쿼리로 토큰을 받지 않는다. 어떤 쿼리라도 거절한다. 새 연결에는 다시 유효한 토큰이 필요하며 연결 안에서 토큰을 갱신하지 않는다.
- 네이티브 앱은 인증 헤더를 지원하는 WebSocket 클라이언트가 필요하다. 브라우저의 기본 WebSocket API용 연결 티켓·쿠키 인증은 이번 범위에 없다.
- 브라우저 `Origin`이 있으면 기본적으로 같은 출처만 허용한다. 필요한 HTTPS 출처는 `allowed-origins`에 정확히 등록한다. Origin 검사는 JWT 인증을 대체하지 않는다.

한 WebSocket은 한 측정이다. 같은 회원이 재접속해도 새 측정 ID와 새 gRPC 스트림을 만들며, 이전 결과·입력을 이어 붙이지 않는다. 연결만 열고 `start`를 보내지 않으면 모델을 호출하지 않는다.

## 2. 메시지 순서

아래 값은 합성 테스트 예시다. `synthetic-v1`을 실제 센서 규격이 검증되었다는 뜻으로 사용하지 않는다.

### ① 앱 → 시작 요청

```json
{"type":"start","contractVersion":2,"modelKey":"model-a","modelVersion":"v1","sensorProfileId":"synthetic-v1"}
```

서버에 설정한 센서 프로필과 같아야 한다. 모델 키·버전·프로필은 모델 서비스에서도 확인한다. 한 연결에서 `start`는 한 번만 가능하며 모델을 바꾸려면 새 연결을 연다.

### ② 서버 → 준비 완료

```json
{"type":"ready","sessionId":"측정마다 생성한 UUID","contractVersion":2,"modelKey":"model-a","modelVersion":"v1","sensorProfileId":"synthetic-v1","requiredCount":16}
```

**이 응답을 받은 뒤 센서를 보낸다.** WebSocket 연결 성공만으로 모델 준비가 끝난 것은 아니다.

### ③ 앱 → 센서 한 개

```json
{"type":"sensor","sequence":1,"elapsedNanos":0,"magnetic":{"x":12.5,"y":0,"z":-3.2},"orientation":{"x":0,"y":0,"z":0}}
```

| 필드 | 규칙 |
|---|---|
| `sequence` | 첫 샘플 1, 다음부터 정확히 1씩 증가. 중복·누락·역순 거절 |
| `elapsedNanos` | 첫 샘플 0, 이후 엄격히 증가하는 나노초 단위 경과 시간. 날짜·Unix 시각이 아니며 앱의 단조 증가 시계 기준 |
| `magnetic`, `orientation` | 각각 x/y/z 모두 필수. 유한한 JSON 숫자만 허용하며 0은 유효 |
| 문자열 식별자 | 대소문자·공백·앞자리 0을 변경하지 않음. 모델 키 최대 128자, 버전·프로필 최대 64자 |

필수값 누락·추가 필드·중복 키·숫자 문자열·정수 필드의 소수·64비트 정수 범위 초과를 거절한다. 단위와 축의 물리적 의미·주파수는 별도의 센서 프로필로 확정해야 한다. 서버가 임의로 단위·축을 바꾸지 않는다.

### ④ 서버 → 첫 샘플부터 예측

계약 v2는 처음 15개에도 예측한다. `requiredCount=16`은 Python 필터의 윈도우 크기다. 기존 v1 앱은 start의 버전과 초기 응답 처리를 함께 갱신해야 하며 서버는 v1을 거절한다.

```json
{"type":"prediction","sessionId":"측정 ID","sequence":1,"modelKey":"model-a","modelVersion":"v1","locationCode":"007","score":0.8,"candidates":[{"locationCode":"007","score":0.8}],"mappingStatus":"NOT_FOUND","location":null}
```

`locationCode`는 문자열이며 `007`을 숫자 7로 바꾸지 않는다. `score`는 모델 점수로 실제 정확도와 같은 의미가 아니다.

예측의 최상위 코드가 장소 DB에 연결되어 있으면 `mappingStatus=FOUND`와 장소 정보를 보낸다. 매핑이 없거나 장소가 비활성이면 `NOT_FOUND`, `location=null`이다. [장소 매핑 안내](MODEL_LOCATION_MAPPING.md)의 장소 응답을 그대로 사용한다. 후보 전체를 각각 DB 조회하거나 원본 센서·예측 이력을 저장하지 않는다.

### ⑤ 앱 → 정상 종료

```json
{"type":"stop"}
```

가능하면 서버가 `{"type":"stopped"}`를 보내고 코드 1000으로 닫는다. 앱이 직접 연결을 닫아도 모델 호출이 취소된다. 자동 재연결·이전 센서 재전송은 서버에서 하지 않는다.

## 3. 오류와 종료

```json
{"type":"error","code":"NOT_READY","message":"메시지 형식과 측정 시작 순서를 확인해 주세요."}
```

| 상황 | 오류 코드 | 종료 코드 |
|---|---|---|
| 형식·순번·프로필 오류, 준비 전 센서 | `INVALID_MESSAGE`, `NOT_READY` | 1008 |
| 토큰 만료, 시작 지연, 유휴 | `TOKEN_EXPIRED`, `START_TIMEOUT`, `IDLE_TIMEOUT` | 1008 |
| 연결·메시지·대기열 한도 | `OVERLOADED`, `RATE_LIMITED` | 1013 |
| 모델 기능 비활성 | `NOT_CONFIGURED` | 1013 |
| 모델 장애·잘못된 응답·시간 초과 | `MODEL_UNAVAILABLE`, `MODEL_PROTOCOL_ERROR`, `MODEL_TIMEOUT` | 1011 |
| 내부 처리 실패 | `INTERNAL` | 1011 |

인증 실패는 업그레이드 전 HTTP 401, 허용하지 않은 Origin은 403, 인증 후 쿼리가 있으면 400이다. 연결 한도를 넘기면 JSON 없이 1013으로 닫을 수 있다. 컨테이너가 큰 메시지를 먼저 차단하면 1009로 닫는다. 바이너리는 지원하지 않는다.

이미 끊어진 연결이나 느린 수신자에게 오류 JSON 전달은 보장하지 않는다. 앱은 JSON뿐 아니라 연결 종료 이벤트도 처리해야 한다. 토큰·센서 원문·DB/모델 예외 상세를 오류에 넣지 않는다.

## 4. 설정과 자원 제한

새 설정 접두사는 `magnavi.positioning-websocket`이다. 기본값은 초기 제한값이며 부하 시험으로 보장한 처리량이 아니다.

| 속성 | 기본값 | 의미 |
|---|---|---|
| `enabled` | `false` | 명시적으로 켜기 전에는 경로·작업자 미등록 |
| `max-connections` | 32 | 시작 전 대기 연결도 포함한 인스턴스 전체 한도 |
| `max-connections-per-member` | 2 | 한 회원의 동시 연결 수, 현재 인스턴스 기준 |
| `max-message-bytes` | 4096 | 입력 JSON UTF-8 바이트 한도 |
| `max-messages-per-second` | 100 | 연결별 1초 고정 구간의 입력 메시지 수. start/stop도 포함 |
| `send-buffer-bytes` | 65536 | 송신 버퍼와 개별 결과의 UTF-8 바이트 한도 |
| `send-timeout` | 3s | 느린 앱 송신 제한 |
| `start-timeout` | 10s | 연결 후 start를 기다리는 시간 |
| `idle-timeout` | 30s | 마지막 앱 JSON 이후 허용 대기. ping/pong으로 연장하지 않음 |
| `allowed-origins` | 빈 목록 | 기본 같은 출처 검사. 네이티브 앱의 Origin 없는 요청도 JWT 필요 |

gRPC 쪽 활성화·센서 프로필·TLS·대기열·모델 응답 시간 제한은 [기존 모델 설정](MODEL_GRPC.md)을 함께 사용한다. 둘 중 하나만 켜서는 측정이 동작하지 않는다.

```yaml
# 설정 형태 예시다. 실제 모델·센서 규격 확인 전에는 false를 유지한다.
magnavi:
  positioning-websocket:
    enabled: false
    max-connections: 32
    max-connections-per-member: 2
```

이 단계에서는 실제 `.env`·`.env.prod`·Compose·Nginx 설정을 변경하지 않았다. 추후 운영 활성화할 때 Spring에 위 속성과 모델 속성이 실제로 주입되도록 배포 설정도 연결해야 한다. 외부 연결에는 HTTPS/WSS와 프록시의 WebSocket 업그레이드 설정이 필요하다.

결과 처리 작업자는 최대 연결 수만큼만 만든다. gRPC 콜백에서 DB 조회·앱 송신을 하지 않고, 연결별 작업자에서 순서대로 처리한다. 공용 감시자는 약 100ms마다 만료를 확인하고 모델을 취소한다. DB 조회가 늦어도 그 결과를 새 연결에 넘기지 않으며, 작업자가 실제 끝날 때까지 슬롯을 유지해 무제한 작업자 생성을 막는다.

한 연결의 동기 쓰기는 현재 내장 Tomcat의 송신 시간 제한도 적용한다. [Tomcat 설정](https://tomcat.apache.org/tomcat-11.0-doc/web-socket-howto.html), [Spring 송신 래퍼](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/web/socket/handler/ConcurrentWebSocketSessionDecorator.html)를 참고한다. 기존 gRPC 입력·결과 큐 제한도 그대로 적용한다.

분산 서버 전체의 회원별 한도, 접속 시도 자체의 속도 제한, 연결/큐 운영 지표는 아직 없다. HTTP readiness는 기존처럼 앱·DB 상태이며 모델 준비를 뜻하지 않는다. 운영 기본 로그에 센서·예측 내용을 추가하지 않았고 WebSocket 원문 TRACE 로깅도 활성화하지 않는다.

## 5. 확인한 모바일 앱과 남은 작업

읽기 전용으로 확인한 기준은 `midas-capston-design/front`의 `eca0611ab25f8a4d514aa9563def7945b0a0223e`다. 앱 코드는 수정하지 않았다.

- [기존 예측 호출](https://github.com/midas-capston-design/front/blob/eca0611ab25f8a4d514aa9563def7945b0a0223e/lib/function/prediction_service.dart)은 HTTP `/predict`에 `Mag_X/Y/Z`, `Ori_X/Y/Z`를 보낸다. 실내 지도 화면은 약 2초마다 호출하며 새 WebSocket과 자동 호환되지 않는다.
- [센서 처리](https://github.com/midas-capston-design/front/blob/eca0611ab25f8a4d514aa9563def7945b0a0223e/lib/function/sensor_controller.dart)는 자기장 축을 전달하고 방향에 heading, 음수 pitch, roll을 사용한다. 단위·축 방향·보정·전송 주기를 모델 학습 조건과 대조해야 한다. 2초 HTTP 주기는 센서 수집 주파수가 검증됐다는 의미가 아니다.
- 앱 변경 시 인증 헤더 연결 → start → ready 대기 → 순번/경과 시간을 붙인 개별 sensor 전송 → 첫 샘플부터 예측 수신 → 종료·재접속 처리가 필요하다. 기존 결과의 숫자 코드·후보 문자열 배열과 새 JSON의 차이도 반영해야 한다.
- Python gRPC 계약 v2·세션별 윈도우는 [Python PR #6](https://github.com/MagNavi-server/magnavi-positioning-service/pull/6)으로 반영했다. 실제 앱·모델 종단 검증, WSS 및 운영 부하 검증은 후속 단계다. 이 문서의 테스트 성공을 모델 정확도 검증으로 해석하지 않는다.

## 6. 검증 방법

Docker를 켜고 Spring 저장소에서 실행한다. 테스트는 격리 MySQL과 루프백 합성 gRPC 서버를 사용하며 실제 환경 파일·운영 DB에 접근하지 않는다.

```bash
# 실제 WebSocket 인증·연결·중계를 확인한다.
./gradlew test --tests '*PositioningWebSocketIntegrationTests'

# 기존 REST·DB·JWT 회귀 검증과 실행 JAR 생성까지 확인한다.
./gradlew clean build --console=plain
```

실제 WebSocket 테스트는 JWT 실패·만료, 쿼리 토큰/출처 차단, 센서 전달·즉시 예측·장소 매핑, 연결 격리·재접속, 정상 종료·취소, 잘못된 순번·프로필·바이너리·과도한 크기·유휴를 확인한다. 별도 제어 가능한 대역으로 연결 생성 중 종료·느린 쓰기·늦은 DB 결과·슬롯 반납·송신 바이트 한도를 검증한다. 최신 전체 테스트 결과는 [README](../../README.md)에 기록한다.
