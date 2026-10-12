# 일반 회원 API — 3-1단계

구현일: 2026-09-21 · 상태: Spring 로컬 구현·격리 테스트 완료

[실행 방법](../../README.md) · [회원 모듈 설명](../modules/MEMBER.md)

## 1. 구현한 API

| API | 요청 형식 | 인증 | 정상 응답 |
|---|---|---|---|
| `POST /users/signup` | JSON | 불필요 | 201, 회원 정보 |
| `POST /users/login` | `application/x-www-form-urlencoded` | 불필요 | 200, 액세스 토큰 |
| `GET /users/me` | 본문 없음 | Bearer JWT | 200, 본인 정보 |
| `PUT /users/me/username` | JSON | Bearer JWT | 200, 변경한 본인 정보 |

가입 성공 시 토큰을 자동 발급하지 않는다. 로그인 API를 별도로 호출한다. 공개 API라도 잘못된 Bearer 헤더를 보내면 토큰 검증에서 401이 발생하므로 가입·로그인에는 이전 토큰을 붙이지 않는다.

## 2. 회원가입

```http
POST /users/signup
Content-Type: application/json
```

```json
{
  "userId": "ExampleUser",
  "userName": "예시 회원",
  "email": "member@example.test",
  "phone_number": "01000000000",
  "password": "example-password-only"
}
```

위 비밀번호·연락처는 요청 형식 설명용 합성값이다.

- `userId`: 앞뒤 공백을 제거한 뒤 1~50자. 대소문자를 구분하며 `ExampleUser`와 `exampleuser`는 다른 ID다.
- `userName`: 공백만 있는 이름은 거절하며 최대 50자다.
- `email`: 선택값, 최대 254자와 이메일 형식을 검사한다. 중복 주소를 허용한다.
- `phone_number`: 선택값, 최대 20자다. 국가별 전화번호 형식 검사는 아직 추가하지 않았다.
- 선택 연락처를 생략하거나 null·빈 문자열·공백만 보내면 null로 저장한다. 실제 입력한 연락처는 앞뒤 공백을 정리한다.
- `password`: 우선 최소 **8자**, UTF-8 기준 최대 **72바이트**로 구현했다. 문자 종류를 강제하지 않으며 공백만 있는 값은 거절한다. 한글 한 글자는 보통 3바이트다. 비밀번호의 앞뒤 공백·대소문자는 그대로 보존한다.

비밀번호는 BCrypt `$2b$`, 비용 계수 12로 해시만 저장한다. 회원 행과 일반 로그인 행을 하나의 짧은 트랜잭션으로 저장하며, 동시에 중복 가입이 들어와도 한 요청만 성공한다. 실패 요청이 만든 회원 행은 되돌린다.

응답 필드는 `id`, `userId`, `userName`, `email`, `phone_number`다. `id`는 새 DB의 내부 회원 번호이고 `userId`는 일반 로그인 ID다. 비밀번호·해시·권한·회원 상태는 반환하지 않는다.

## 3. 로그인

```http
POST /users/login
Content-Type: application/x-www-form-urlencoded

username=ExampleUser&password=example-password-only
```

폼 인코딩을 사용한다. 비밀번호에 공백·`+`·`&` 등이 있으면 클라이언트의 폼 인코딩 도구로 변환한다. URL 쿼리의 비밀번호는 사용하지 않으며, 같은 username 또는 password 필드가 여러 번 있으면 400으로 거절한다.

정상 응답:

```json
{
  "access_token": "발급된 JWT 문자열",
  "token_type": "bearer"
}
```

토큰 응답에는 `Cache-Control: no-store`, `Pragma: no-cache`를 적용한다. 비밀번호 오류와 존재하지 않는 ID는 모두 `401 INVALID_CREDENTIALS`로 응답한다. 없는 ID도 비교용 해시로 계산하여 응답 시간 차이를 줄이지만 일정한 응답 시간을 보장하는 것은 아니다.

로그인에는 가입 최소 길이를 다시 적용하지 않는다. 이후 기존 데이터를 이관할 때 짧은 기존 비밀번호를 검사할 수 있게 하기 위함이다. UTF-8 최대 72바이트·빈 값 검사는 로그인에도 적용한다.

## 4. 본인 정보 조회·이름 변경

```http
GET /users/me
Authorization: Bearer 발급된-JWT
```

```http
PUT /users/me/username
Authorization: Bearer 발급된-JWT
Content-Type: application/json

{"userName":"새 표시 이름"}
```

조회와 변경 모두 검증된 토큰의 내부 회원 번호를 사용한다. 본문에 다른 회원의 `id`, `userId`, `role` 등을 보내도 변경 대상으로 사용하지 않는다. 이름 변경은 `userName`만 반영한다.

토큰이 유효해도 회원이 DB에 없거나 ACTIVE 상태가 아니면 401로 거절한다. 일반 로그인 수단이 없는 회원의 프로필에서는 `userId`가 null이다. 소셜 로그인 API 자체는 아직 구현하지 않았다.

## 5. JWT 설정과 수명

- 알고리즘: HS256. 현재 Spring이 발급과 검증을 함께 담당한다.
- 키: `JWT_SECRET_BASE64` 필수. `openssl rand -base64 32`로 만든 환경별 난수 키를 서버에만 주입한다. 기본 키나 자동 생성 키로 대체하지 않는다.
- 발급자 `iss`: `JWT_ISSUER`, 기본값 `magnavi-api-service`.
- 대상 서비스 `aud`: `JWT_AUDIENCE`, 기본값 `magnavi-api`.
- 회원 식별자 `sub`: 양수 Long 범위의 내부 회원 번호.
- 발급 시각 `iat`, 만료 시각 `exp`: 필수. 현재 액세스 토큰 유효 시간은 30분이다.
- 서명·발급자·대상·만료·미래 발급 시각·선택 `nbf`를 검증한다. 허용 시간 오차는 0초이므로 서버 시계를 정확하게 유지한다.
- JWT 내용은 암호화된 비밀 정보가 아니다. 비밀번호·이메일·전화번호는 넣지 않는다.
- 토큰은 Authorization 헤더로만 받는다. URL의 `access_token`이나 세션 쿠키는 사용하지 않는다.

키를 바꾸면 기존 토큰은 더 이상 검증되지 않는다. 재발급·로그아웃·개별 토큰 즉시 폐기 API는 이번 범위에 없다. 만료되면 다시 로그인한다. 현재 접근 정책은 이 문서의 회원 API, 상태 확인, 4-1단계 [실내 장소 조회 API](PLACE_API.md)를 허용한다.

검증 방식 참고: [Spring Security JWT](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html), [비밀번호 저장](https://docs.spring.io/spring-security/reference/features/authentication/password-storage.html).

## 6. 오류와 기존 앱 전환

| 상황 | 상태 / 코드 |
|---|---|
| 필수 입력·길이·이메일 형식 오류 | 400 / INVALID_REQUEST |
| 중복 로그인 ID | 409 / DUPLICATE_LOGIN_ID |
| 로그인 정보 불일치 | 401 / INVALID_CREDENTIALS |
| JWT 누락·위조·만료·회원 없음 | 401 / UNAUTHENTICATED |
| 인증 후 허용하지 않은 경로 접근 | 403 / ACCESS_DENIED |
| 지원하지 않는 Content-Type | 415 / UNSUPPORTED_MEDIA_TYPE |
| 예상하지 못한 서버 오류 | 500 / INTERNAL_ERROR |

오류 본문은 `code`, `message`, `traceId`, `errors`를 사용한다. 입력 오류의 `errors`에는 필드명과 안전한 안내만 넣는다. `traceId`는 응답의 `X-Request-ID`와 같다. 예외 원문·SQL·입력 비밀번호는 반환하지 않는다.

기존 FastAPI와의 차이는 다음과 같다.

- 경로, JSON 가입 필드, 폼 로그인, 성공 응답 필드 이름, 가입 201은 유지했다.
- FastAPI의 중복 400은 409로, 입력 422는 공통 400으로 통일했다. 기존 `detail` 오류 파서는 새 공통 응답 형식으로 전환해야 한다.
- 이메일·전화번호의 null을 허용한다. 앱에서도 선택값을 처리해야 한다.
- 기존 FastAPI JWT는 새 키·발급자·대상·회원 번호 체계와 호환되지 않는다. Spring에서 다시 로그인해야 한다.
- 기존 데이터는 아직 이관하지 않았다. 새 DB의 ID가 기존 DB의 ID와 같다고 가정하지 않는다.

## 7. 검증과 후속 범위

Java 21 `./gradlew test`와 `./gradlew build`에서 전체 65개 테스트를 통과했다. 기존 42개에 회원 API 16개·JWT 설정 4개·BCrypt 3개를 추가했다. 임시 MySQL과 테스트마다 생성한 키를 사용하며 운영 DB·키는 읽지 않는다.

검증에는 실제 로그인 토큰으로 프로필 조회, 대소문자, 선택·중복 연락처, 동시 가입·전체 롤백, 잘못된 비밀번호, 위조·만료·잘못된 claim, 타인 정보 변경 방지, 비밀번호·해시·토큰의 기본 로그 비노출을 포함한다. `legacy-bcrypt.properties`는 기존 Python과 같은 passlib 1.7.4·bcrypt 4.3.0으로 만든 합성 자료다.

카카오·구글 인증, 계정 연결, 재발급·로그아웃, 기존 데이터 이관, 실제 모바일 앱 연동은 후속 작업이다. 운영 전 HTTPS·허용 앱/CORS·가입 및 로그인 호출 제한 정책과 실제 환경의 비밀번호 해싱 비용도 확인해야 한다. 이번 검증은 운영 배포나 성능 검증을 포함하지 않는다.
