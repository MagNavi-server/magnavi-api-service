# 외부 장소 검색 API — 4-3단계

구현일: 2026-09-22. 건물·업체·기관을 네이버에서 검색한다. 건물 내부 층·호실은 [실내 장소 API](PLACE_API.md)의 역할이며, 이 검색은 모델 추론이나 gRPC를 사용하지 않는다.

## 1. 요청과 응답

```http
GET /places/search?query=검색어&display=5&sort=random
Authorization: Bearer <로그인으로 받은 액세스 토큰>
```

끝의 슬래시도 허용한다. 유효한 JWT와 현재 존재하는 회원이 필요하다. 현재 회원 상태는 ACTIVE만 구현되어 있으며 정지·탈퇴 기능을 추가한 것은 아니다.

| 파라미터 | 기본값 | 조건 |
|---|---|---|
| query | 필수 | 앞뒤 공백 제거 후 1~100 유니코드 문자, 제어 문자 금지. 원본은 최대 200 UTF-16 코드 단위 |
| display | 5 | 1~5의 정수 |
| sort | random | random: 정확도순, comment: 리뷰 개수순 |

중복 파라미터와 위 표 이외의 파라미터는 400이다. 네이버 호출의 `start`는 항상 1, `format`은 json이다. 다음 페이지 기능은 제공하지 않는다. 비어 있는 display·sort는 Spring의 기본값 규칙을 따른다.

합성 데이터로 만든 응답 예:

```json
{
  "source": "NAVER",
  "items": [
    {
      "name": "예시 카페",
      "category": "카페,디저트>카페",
      "address": "예시 지번 주소",
      "roadAddress": "예시 도로명 주소",
      "link": "https://example.test",
      "latitude": 37.1234567,
      "longitude": 127.1234567
    }
  ]
}
```

결과 없음은 200과 `{"source":"NAVER","items":[]}`다. 성공 응답에는 `Cache-Control: no-store`를 붙인다. 순서는 제공자 응답을 유지하며, 앱에는 필요한 필드만 전달한다. 이름의 강조용 `<b>` 태그와 HTML 엔티티를 표시용 문자열로 바꾼다. 앱에서는 문자열을 HTML로 실행하지 말고 텍스트로 표시한다.

선택 표시 필드의 누락은 빈 문자열이다. 이름이나 좌표가 없거나 잘못되면 502이며 임의 장소·좌표로 대체하지 않는다. `latitude`는 위도(-90~90), `longitude`는 경도(-180~180), 둘 다 WGS84 도 단위다. `link`는 업체 홈페이지일 수 있으며 HTTP(S)만 허용한다. 제공자의 고유 장소 ID나 자체 DB 장소 ID는 생성하지 않는다.

## 2. 서버 설정

검색은 기본 비활성이다. 네이버 설정이 없어도 회원·실내 조회 API는 계속 동작하고 검색만 503을 반환한다. [환경변수 예시](../../.env.example)를 참고해 다음 값을 서버에 주입한다. `.env`를 자동으로 읽지는 않으므로 [실행 안내](../../README.md)의 방법을 따른다.

| 환경변수 | 의미 |
|---|---|
| NAVER_SEARCH_ENABLED | 실제 검색을 사용하려면 true |
| NAVER_SEARCH_CLIENT_ID | NAVER API HUB 검색용 Client ID |
| NAVER_SEARCH_CLIENT_SECRET | 해당 Client Secret. 앱·Git·로그에 노출 금지 |
| NAVER_SEARCH_COORDINATE_FORMAT | 실제 응답 확인 후 DEGREES 또는 E7. 기본 UNCONFIRMED에서는 호출하지 않음 |

현재 API HUB 공식 문서는 mapx/mapy가 WGS84 경도/위도라는 점은 명시하지만 숫자 예시는 제공하지 않는다. 따라서 오래된 변환 공식을 자동 적용하지 않고 설정으로 구분했다. `DEGREES`는 `127.1234567` 같은 도 단위 수를 그대로 사용하고, `E7`은 `1271234567` 같은 정수를 10^7로 나눈다. 이는 지원 가능한 변환 방식이며 실제 API HUB 응답이 어느 형식인지 검증했다는 뜻은 아니다. 키 연결 후 알려진 장소의 좌표와 비교해 단위를 선택해야 한다. 옛 KATECH 좌표 변환은 지원하지 않는다.

요청 주소는 `https://naverapihub.apigw.ntruss.com/search/v1/local`로 고정한다. 인증 헤더는 `X-NCP-APIGW-API-KEY-ID`, `X-NCP-APIGW-API-KEY`다. 다른 주소로 키가 전달되지 않도록 리다이렉트를 따라가지 않는다.

`magnavi.naver-search` 설정의 기본 한도:

| 설정 | 기본값 | 의미 |
|---|---|---|
| connect-timeout-ms | 1000 | 연결 대기 시간 |
| request-timeout-ms | 3000 | 응답 헤더와 본문을 모두 기다리는 전체 시간 |
| requests-per-member-per-minute | 30 | 회원 한 명의 분당 검색 횟수 |
| requests-per-minute | 300 | 서버 한 대의 분당 총 검색 횟수 |
| max-concurrent-requests | 4 | 동시에 진행할 검색 수. 초과 요청은 대기시키지 않음 |

연결 제한은 100~10,000ms, 전체 제한은 100~30,000ms로 설정할 수 있다. 수신 본문은 64KiB까지 허용한다. 애플리케이션의 추가 재시도는 없다. 분당 횟수는 UTC의 분 경계마다 초기화하고 시작한 요청은 실패해도 집계한다. 회원별 집계 수는 전체 분당 한도로 제한되며 다음 분 요청에서 비운다. 서버 재시작 시 집계는 초기화된다. 분 경계의 순간적인 연속 호출은 가능하다.

이 한도는 한 프로세스의 보호 장치이며 네이버의 계정 전체 일일 할당량을 보장하는 장치는 아니다. 서버를 여러 대로 늘릴 때 공유 제한 저장소를 도입하고, 실제 API HUB 계정의 호출 한도도 별도로 관리해야 한다.

## 3. 처리 흐름

```text
앱의 검색 요청
  → JWT 서명·만료 검사
  → 검색 조건 검사
  → member 공개 서비스로 현재 회원 확인(짧은 읽기 트랜잭션 종료)
  → 검색 설정·호출 횟수·동시 실행 한도 확인
  → 네이버 HTTP 호출(전체 3초, 최대 64KiB)
  → 이름·주소·좌표·링크를 검색 결과 DTO로 변환
  → 앱에 응답하고 실행 자리 반환
```

PlaceSearchController는 요청/응답, PlaceSearchService는 처리 순서, PlaceSearchLimiter는 호출 한도, NaverLocalSearchClient는 네이버 통신/변환을 맡는다. LimitedSearchBodySubscriber는 큰 본문을 한꺼번에 메모리에 올리지 않도록 조금씩 받는다. 모든 클래스·메서드에 역할을 설명하는 주석을 작성했다.

검색 결과를 DB·서버 캐시에 저장하지 않고 기존 Flyway SQL도 변경하지 않는다. 외부 통신 동안 DB 트랜잭션을 유지하지 않는다. 기본 로그에는 검색어·응답 본문·키를 남기지 않는다. 향후 프록시나 접근 로그를 추가할 때도 검색 URL의 쿼리 문자열은 수집하지 않아야 한다.

## 4. 오류

기존 공통 오류 형식인 `code`, `message`, `traceId`, `errors`를 사용한다.

| 상황 | HTTP | code |
|---|---|---|
| 잘못된 입력 | 400 | INVALID_REQUEST |
| JWT 없음·위조·만료·존재하지 않는 회원 | 401 | UNAUTHENTICATED |
| 우리 서버의 회원별/전체 분당 한도 | 429 | PLACE_SEARCH_RATE_LIMITED |
| 비활성·키 누락·좌표 단위 미확인 | 503 | PLACE_SEARCH_NOT_CONFIGURED |
| 동시 검색 초과·처리 스레드 중단 | 503 | PLACE_SEARCH_BUSY |
| 네이버 429 | 503 | PLACE_SEARCH_PROVIDER_LIMITED |
| 네이버의 다른 비정상 상태·통신 실패·잘못된 본문 | 502 | PLACE_SEARCH_PROVIDER_ERROR |
| 네이버 응답 시간 초과 | 504 | PLACE_SEARCH_TIMEOUT |

네이버 인증 실패를 앱 JWT의 401로 전달하지 않는다. 외부 오류 메시지나 검색어도 그대로 반사하지 않는다.

## 5. 검증과 남은 확인

로컬 모의 HTTP 서버로 URL 인코딩·인증 헤더·좌표 변환·빈 결과·잘못된 응답·리다이렉트 차단·제공자 오류·헤더/본문 시간 초과·응답 크기 제한을 검사한다. 임시 MySQL과 실제 JWT로 API 접근·삭제된 회원 차단·DB 행 수 보존·외부 호출 시 트랜잭션 없음·원문 로그 제외를 검증한다. 한도 초기화·회원 격리·실제 동시 요청 제한·오류 후 실행 자리 반환도 확인한다.

실제 키를 사용한 API HUB 호출, 실제 좌표 단위와 앱 지도 표시, 계정에 적용되는 표시·변환 조건은 아직 검증하지 않았다. 외부 즐겨찾기 저장·캐시·자체 실외 장소 CRUD는 이번 범위에 포함하지 않는다. 저장 가능 기간이나 공식 식별자가 확정된 것으로 취급하지 않는다.

참고(2026-09-22 확인): [지역 검색 명세](https://api.ncloud-docs.com/docs/naver-api-hub-search-local), [인증 헤더·오류 명세](https://api.ncloud-docs.com/docs/naver-api-hub-overview), [API HUB 신청 안내](https://guide.ncloud-docs.com/docs/apihub-application). 실제 이용 조건은 [서비스 약관](https://www.ncloud.com/policy/terms/svc?language=ko-KR)과 계정 신청 화면에서 확인한다.
