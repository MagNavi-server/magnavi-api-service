package com.example.magnavi_springserver.place.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import com.example.magnavi_springserver.place.application.PlaceSearchException;
import com.example.magnavi_springserver.place.infrastructure.NaverSearchProperties.CoordinateFormat;

/** 실제 네이버와 키 없이 임시 HTTP 서버로 요청·응답·취소를 검증한다. */
class NaverLocalSearchClientTests {

    private HttpServer server;
    private ExecutorService serverThreads;
    private HttpClient http;
    private final AtomicReference<String> queryString = new AtomicReference<>();
    private final AtomicReference<String> credentials = new AtomicReference<>();
    private final AtomicInteger calls = new AtomicInteger();
    private volatile int responseStatus = 200;
    private volatile String responseBody = "{\"items\":[]}";
    private volatile long delayMs;
    private volatile boolean delayAfterHeaders;

    /** 매 테스트마다 임의 포트와 가짜 인증 정보만 사용하는 서버를 시작한다. */
    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverThreads = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(serverThreads);
        server.createContext("/search", exchange -> {
            calls.incrementAndGet();
            queryString.set(exchange.getRequestURI().getRawQuery());
            credentials.set(exchange.getRequestHeaders().getFirst("X-NCP-APIGW-API-KEY-ID") + ":"
                    + exchange.getRequestHeaders().getFirst("X-NCP-APIGW-API-KEY"));
            try (exchange) {
                byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.getResponseHeaders().set("Location", "/redirect-target");
                if (delayAfterHeaders) {
                    exchange.sendResponseHeaders(responseStatus, body.length);
                    exchange.getResponseBody().write(body, 0, 1);
                    exchange.getResponseBody().flush();
                    Thread.sleep(delayMs);
                    exchange.getResponseBody().write(body, 1, body.length - 1);
                } else {
                    Thread.sleep(delayMs);
                    exchange.sendResponseHeaders(responseStatus, body.length);
                    exchange.getResponseBody().write(body);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } catch (IOException exception) {
                // 시간 초과 테스트에서 클라이언트가 연결을 취소하면 쓰기가 실패하는 것이 정상이다.
            }
        });
        server.createContext("/redirect-target", exchange -> {
            calls.incrementAndGet();
            exchange.close();
        });
        server.start();
        http = new com.example.magnavi_springserver.config.NaverSearchConfig()
                .naverSearchHttpClient(properties(CoordinateFormat.DEGREES, 2000));
    }

    /** 테스트의 연결·스레드를 종료해 다음 테스트나 JVM 종료를 방해하지 않는다. */
    @AfterEach
    void stopServer() {
        server.stop(0);
        serverThreads.shutdownNow();
        http.close();
    }

    /** 한글·예약 문자가 검색 파라미터를 바꾸지 않고 인증 정보는 헤더로만 전달되는지 확인한다. */
    @Test
    void encodesQueryAndConvertsOrderedResults() {
        responseBody = item("127.1234567", "37.1234567");
        var result = client(CoordinateFormat.DEGREES, 2000).search("한글 &+?#%", 5, "random");
        assertThat(queryString.get()).contains("query=%ED%95%9C%EA%B8%80%20%26%2B%3F%23%25",
                "display=5", "start=1", "sort=random", "format=json").doesNotContain("test-secret");
        assertThat(credentials.get()).isEqualTo("test-id:test-secret");
        assertThat(result.source()).isEqualTo("NAVER");
        assertThat(result.items().getFirst().name()).isEqualTo("테스트 & 카페");
        assertThat(result.items().getFirst().latitude()).isEqualByComparingTo("37.1234567");
        assertThat(result.items().getFirst().longitude()).isEqualByComparingTo("127.1234567");
        assertThat(result.items().getFirst().roadAddress()).isEqualTo("합성 도로명");
    }

    /** 확인된 E7 설정에서만 10의 7제곱으로 나누고 축을 뒤집지 않는다. */
    @Test
    void convertsExplicitE7Coordinates() {
        responseBody = item("1271234567", "371234567");
        var place = client(CoordinateFormat.E7, 2000).search("카페", 1, "comment").items().getFirst();
        assertThat(place.longitude()).isEqualByComparingTo("127.1234567");
        assertThat(place.latitude()).isEqualByComparingTo("37.1234567");
    }

    /** 결과 없음은 오류가 아니라 정상적인 빈 목록이다. */
    @Test
    void acceptsEmptyResults() {
        assertThat(client(CoordinateFormat.DEGREES, 2000).search("없음", 5, "random").items()).isEmpty();
    }

    /** 잘못된 JSON·항목·이름·좌표를 정상 결과처럼 숨기거나 임의로 보정하지 않는다. */
    @ParameterizedTest
    @ValueSource(strings = {"", "{}", "null", "{\"items\":null}", "{\"items\":{}}",
            "{\"items\":[null]}", "{\"items\":[{}]}", "<html>secret-provider-error</html>"})
    void rejectsMalformedResponses(String body) {
        responseBody = body;
        assertFailure(PlaceSearchException.Reason.PROVIDER_ERROR, 2000);
    }

    /** 범위 밖·무한대·누락·다른 단위의 좌표는 지도에 잘못 표시하지 않고 오류로 처리한다. */
    @ParameterizedTest
    @ValueSource(strings = {"181", "NaN", "Infinity", "", "1271234567", "1e20"})
    void rejectsInvalidCoordinates(String longitude) {
        responseBody = item(longitude, "37");
        assertFailure(PlaceSearchException.Reason.PROVIDER_ERROR, 2000);
    }

    /** 제공자가 요청보다 많은 항목을 보내면 임의로 자르거나 순서를 변경하지 않는다. */
    @Test
    void rejectsMoreItemsThanRequestedAndUnsafeLinks() {
        responseBody = item("127", "37").replace("]}", ",{}]}");
        assertFailure(PlaceSearchException.Reason.PROVIDER_ERROR, 2000);
        responseBody = item("127", "37").replace("https://example.test", "javascript:alert(1)");
        assertFailure(PlaceSearchException.Reason.PROVIDER_ERROR, 2000);
    }

    /** 제공자 인증 오류·서버 오류·리다이렉트는 앱 인증 오류로 전달하지 않으며 재시도하지 않는다. */
    @ParameterizedTest
    @ValueSource(ints = {301, 302, 400, 401, 403, 500, 503})
    void rejectsProviderErrorsWithoutFollowingRedirects(int status) {
        responseStatus = status;
        responseBody = "secret-provider-error";
        assertFailure(PlaceSearchException.Reason.PROVIDER_ERROR, 2000);
        assertThat(calls.get()).isEqualTo(1);
    }

    /** 네이버의 사용량 초과는 우리 회원의 분당 한도와 다른 실패 종류다. */
    @Test
    void distinguishesProviderQuota() {
        responseStatus = 429;
        assertFailure(PlaceSearchException.Reason.PROVIDER_LIMITED, 2000);
    }

    /** 헤더를 기다리는 동안 응답이 없으면 지정한 시간 안에 종료한다. */
    @Test
    void limitsWaitingForHeaders() {
        delayMs = 1000;
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(2),
                () -> assertFailure(PlaceSearchException.Reason.TIMEOUT, 200));
        assertThat(calls.get()).isEqualTo(1);
    }

    /** 헤더 이후 본문이 멈추는 경우도 전체 대기 시간 제한을 적용한다. */
    @Test
    void limitsWaitingForBody() {
        delayAfterHeaders = true;
        delayMs = 1000;
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(2),
                () -> assertFailure(PlaceSearchException.Reason.TIMEOUT, 200));
    }

    /** 과도한 본문은 전부 메모리에 올리지 않고 중단한다. */
    @Test
    void rejectsOversizedBody() {
        responseBody = " ".repeat(65537);
        assertFailure(PlaceSearchException.Reason.PROVIDER_ERROR, 2000);
    }

    /** 좌표 단위가 미확인인 경우 실제 네이버 호출을 하지 않는다. */
    @Test
    void requiresExplicitCoordinateFormat() {
        assertThatThrownBy(() -> client(CoordinateFormat.UNCONFIRMED, 2000).search("카페", 5, "random"))
                .isInstanceOfSatisfying(PlaceSearchException.class,
                        error -> assertThat(error.getReason()).isEqualTo(PlaceSearchException.Reason.NOT_CONFIGURED));
        assertThat(calls.get()).isZero();
        assertThat(properties(CoordinateFormat.DEGREES, 2000).toString()).doesNotContain("test-secret", "test-id");
    }

    /** 비활성·키 누락·잘못 복사된 헤더 값은 네트워크 호출 전에 일정한 503 원인으로 구분한다. */
    @Test
    void rejectsIncompleteConfigurationBeforeNetworkAccess() {
        for (var settings : java.util.List.of(
                new NaverSearchProperties(false, "test-id", "test-secret", CoordinateFormat.DEGREES, 1000, 2000, 30, 300, 4),
                new NaverSearchProperties(true, "", "test-secret", CoordinateFormat.DEGREES, 1000, 2000, 30, 300, 4),
                new NaverSearchProperties(true, "test-id", "", CoordinateFormat.DEGREES, 1000, 2000, 30, 300, 4),
                new NaverSearchProperties(true, "test-id", "bad\nsecret", CoordinateFormat.DEGREES, 1000, 2000, 30, 300, 4))) {
            var unconfigured = new NaverLocalSearchClient(http, JsonMapper.builder().build(), settings);
            assertThatThrownBy(() -> unconfigured.search("카페", 5, "random"))
                    .isInstanceOfSatisfying(PlaceSearchException.class,
                            error -> assertThat(error.getReason()).isEqualTo(PlaceSearchException.Reason.NOT_CONFIGURED));
        }
        assertThat(calls.get()).isZero();
    }

    /** 실제 운영 주소 대신 현재 테스트 서버를 연결한다. */
    private NaverLocalSearchClient client(CoordinateFormat format, int timeout) {
        return new NaverLocalSearchClient(http, JsonMapper.builder().build(), properties(format, timeout),
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/search"));
    }

    /** 가짜 키와 짧은 테스트 제한을 만들며 사용자 환경변수를 읽지 않는다. */
    private NaverSearchProperties properties(CoordinateFormat format, int timeout) {
        return new NaverSearchProperties(true, "test-id", "test-secret", format, 1000, timeout, 30, 300, 4);
    }

    /** 원본 검색어·오류·키가 예외나 연결된 원인으로 노출되지 않는지도 확인한다. */
    private void assertFailure(PlaceSearchException.Reason reason, int timeout) {
        assertThatThrownBy(() -> client(CoordinateFormat.DEGREES, timeout).search("private-query", 1, "random"))
                .isInstanceOfSatisfying(PlaceSearchException.class, error -> {
                    assertThat(error.getReason()).isEqualTo(reason);
                    assertThat(error).hasNoCause();
                    assertThat(error.getMessage()).doesNotContain("private-query", "test-secret", "secret-provider-error");
                });
    }

    /** 실제 업체나 위치를 사용하지 않는 합성 응답을 만든다. */
    private String item(String longitude, String latitude) {
        return """
                {"items":[{"title":"<b>테스트</b> &amp; 카페","category":"카페",
                "address":"합성 지번","roadAddress":"합성 도로명","link":"https://example.test",
                "mapx":"%s","mapy":"%s"}]}
                """.formatted(longitude, latitude).strip();
    }
}
