package com.example.magnavi_springserver.positioning;

import static org.assertj.core.api.Assertions.*;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.example.magnavi_springserver.member.infrastructure.JwtProperties;
import com.example.magnavi_springserver.member.infrastructure.JwtTokenProvider;
import com.example.magnavi_springserver.place.domain.Building;
import com.example.magnavi_springserver.place.domain.Floor;
import com.example.magnavi_springserver.place.domain.IndoorLocation;
import com.example.magnavi_springserver.place.domain.ModelLocationMapping;
import com.example.magnavi_springserver.place.infrastructure.persistence.BuildingJpaRepository;
import com.example.magnavi_springserver.place.infrastructure.persistence.FloorJpaRepository;
import com.example.magnavi_springserver.place.infrastructure.persistence.IndoorLocationJpaRepository;
import com.example.magnavi_springserver.place.infrastructure.persistence.ModelLocationMappingJpaRepository;
import com.example.magnavi_springserver.positioning.presentation.PositioningWebSocketHandler;
import com.example.magnavi_springserver.positioning.support.TestModelServer;
import com.example.magnavi_springserver.support.MySqlTestConfiguration;

/** 실제 HTTP 업그레이드·서명 JWT·gRPC·격리 DB를 연결한다. 실제 앱·Python 추론은 사용하지 않는다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"magnavi.positioning-websocket.enabled=true", "magnavi.positioning-websocket.max-connections=4",
                "magnavi.positioning-websocket.max-connections-per-member=2"})
@ActiveProfiles("test")
@Import({MySqlTestConfiguration.class, PositioningWebSocketIntegrationTests.TimeConfiguration.class})
class PositioningWebSocketIntegrationTests {
    private static final TestModelServer MODEL = startModel();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private static final JsonMapper JSON = new JsonMapper();
    @LocalServerPort private int port;
    @Autowired private JwtTokenProvider tokens;
    @Autowired private JwtEncoder encoder;
    @Autowired private JwtProperties jwt;
    @Autowired private MutableClock clock;
    @Autowired private PositioningWebSocketHandler handler;
    @Autowired private BuildingJpaRepository buildings;
    @Autowired private FloorJpaRepository floors;
    @Autowired private IndoorLocationJpaRepository locations;
    @Autowired private ModelLocationMappingJpaRepository mappings;

    /** 테스트 전용 루프백 모델과 가상의 규격만 사용한다. */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("magnavi.model-grpc.enabled", () -> true);
        properties.add("magnavi.model-grpc.host", () -> "127.0.0.1");
        properties.add("magnavi.model-grpc.port", MODEL::port);
        properties.add("magnavi.model-grpc.plaintext", () -> true);
        properties.add("magnavi.model-grpc.sensor-profile-id", () -> "synthetic-v1");
    }

    /** 이전 테스트에서 이동한 시각을 되돌린다. */
    @BeforeEach void resetTime() { clock.reset(); }

    /** 각 테스트의 정상·오류 경로 모두 연결 슬롯을 반납해야 한다. */
    @AfterEach void allConnectionsReleased() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (handler.activeConnections() != 0 && System.nanoTime() < deadline) Thread.sleep(10);
        assertThat(handler.activeConnections()).isZero();
    }

    /** 앱 JSON이 실제 gRPC로 전달되고 준비·첫 샘플부터 장소 매핑 결과가 같은 연결로 돌아온다. */
    @Test void relaysSensorsAndMappedPrediction() throws Exception {
        var building = buildings.save(new Building("합성 WS 건물", "합성 주소"));
        var floor = floors.save(new Floor(building.getId(), 1, "1층"));
        var location = locations.save(new IndoorLocation(floor.getId(), "합성 장소", null));
        mappings.save(new ModelLocationMapping("ws-map", "v1", "007", location.getId()));
        try (var socket = connect(tokens.issueAccessToken(1L))) {
            socket.send(start("ws-map", "v1"));
            JsonNode ready = socket.next();
            String sessionId = ready.get("sessionId").asString();
            assertThat(ready.get("type").asString()).isEqualTo("ready");
            assertThat(ready.get("requiredCount").asInt()).isEqualTo(16);
            for (int sequence = 1; sequence <= 16; sequence++) {
                socket.send(sensor(sequence, sequence - 1, 12.5));
                JsonNode result = socket.next();
                assertThat(result.get("sessionId").asString()).isEqualTo(sessionId);
                assertThat(result.get("sequence").asLong()).isEqualTo(sequence);
                {
                    assertThat(result.get("type").asString()).isEqualTo("prediction");
                    assertThat(result.get("locationCode").asString()).isEqualTo("007");
                    assertThat(result.get("mappingStatus").asString()).isEqualTo("FOUND");
                    assertThat(result.get("location").get("id").asLong()).isEqualTo(location.getId());
                }
            }
            var wireSample = MODEL.requests(sessionId).get(1).getSample();
            assertThat(wireSample.getMagnetic().getX()).isEqualTo(12.5);
            assertThat(wireSample.hasElapsedNanos()).isTrue();
            assertThat(wireSample.getElapsedNanos()).isZero();
            socket.send("{\"type\":\"stop\"}");
            assertThat(socket.next().get("type").asString()).isEqualTo("stopped");
            assertThat(socket.closed.get(3, TimeUnit.SECONDS)).isEqualTo(1000);
            assertThat(MODEL.awaitCancellation(sessionId)).isTrue();
        }
    }

    /** 두 사용자와 같은 사용자의 재접속이 서로 다른 측정·입력·결과를 가진다. */
    @Test void isolatesMembersAndReconnection() throws Exception {
        String firstSession;
        try (var first = connect(tokens.issueAccessToken(1L)); var second = connect(tokens.issueAccessToken(2L))) {
            first.send(start("model-a", "v1")); second.send(start("model-a", "v2"));
            firstSession = first.next().get("sessionId").asString();
            String secondSession = second.next().get("sessionId").asString();
            assertThat(firstSession).isNotEqualTo(secondSession);
            first.send(sensor(1, 0, 1.5)); second.send(sensor(1, 0, -9.5));
            assertThat(first.next().get("sessionId").asString()).isEqualTo(firstSession);
            assertThat(second.next().get("sessionId").asString()).isEqualTo(secondSession);
            assertThat(MODEL.requests(firstSession).get(1).getSample().getMagnetic().getX()).isEqualTo(1.5);
            assertThat(MODEL.requests(secondSession).get(1).getSample().getMagnetic().getX()).isEqualTo(-9.5);
        }
        assertThat(MODEL.awaitCancellation(firstSession)).isTrue();
        try (var reconnected = connect(tokens.issueAccessToken(1L))) {
            reconnected.send(start("model-a", "v1"));
            String newSession = reconnected.next().get("sessionId").asString();
            assertThat(newSession).isNotEqualTo(firstSession);
            MODEL.sendLate(firstSession);
            reconnected.send(sensor(1, 0, 0));
            assertThat(reconnected.next().get("sessionId").asString()).isEqualTo(newSession);
            assertThat(reconnected.messages.poll(150, TimeUnit.MILLISECONDS)).isNull();
        }
    }

    /** 인증 없는 요청·변조·만료·다른 발급자 토큰은 HTTP 단계에서 거절한다. */
    @Test void rejectsMissingInvalidAndExpiredTokens() throws Exception {
        rejectHandshake(null, "", null, 401);
        rejectHandshake("invalid-token", "", null, 401);
        rejectHandshake(signed(jwt.issuer(), clock.instant().minusSeconds(1)), "", null, 401);
        rejectHandshake(signed("wrong-issuer", clock.instant().plusSeconds(60)), "", null, 401);
    }

    /** URL에 토큰을 넣거나 승인하지 않은 브라우저 출처로 연결할 수 없다. */
    @Test void rejectsQueryTokensAndForeignOrigins() throws Exception {
        String token = tokens.issueAccessToken(1L);
        rejectHandshake(null, "?access_token=synthetic", null, 401);
        rejectHandshake(token, "?anything=1", null, 400);
        rejectHandshake(token, "", "https://untrusted.example", 403);
    }

    /** 열린 연결도 토큰 만료 시 모델 호출을 취소한다. */
    @Test void expiresAnEstablishedConnection() throws Exception {
        try (var socket = connect(tokens.issueAccessToken(1L))) {
            socket.send(start("a", "v1"));
            String id = socket.next().get("sessionId").asString();
            clock.advance(Duration.ofHours(1));
            assertThat(socket.next().get("code").asString()).isEqualTo("TOKEN_EXPIRED");
            assertThat(socket.closed.get(3, TimeUnit.SECONDS)).isEqualTo(1008);
            assertThat(MODEL.awaitCancellation(id)).isTrue();
        }
    }

    /** start를 보내지 않는 연결도 시간 제한 안에 정리된다. */
    @Test void timesOutBeforeStart() throws Exception {
        try (var socket = connect(tokens.issueAccessToken(1L))) {
            clock.advance(Duration.ofSeconds(11));
            assertThat(socket.next().get("code").asString()).isEqualTo("START_TIMEOUT");
            assertThat(socket.closed.get(3, TimeUnit.SECONDS)).isEqualTo(1008);
        }
    }

    /** 동일 회원의 세 번째 연결은 거절하고 기존 두 연결은 유지한다. */
    @Test void rejectsExcessMemberConnections() throws Exception {
        String token = tokens.issueAccessToken(1L);
        try (var one = connect(token); var two = connect(token); var three = connect(token)) {
            assertThat(three.closed.get(3, TimeUnit.SECONDS)).isEqualTo(1013);
            one.send(start("a", "v1")); two.send(start("a", "v1"));
            assertThat(one.next().get("type").asString()).isEqualTo("ready");
            assertThat(two.next().get("type").asString()).isEqualTo("ready");
        }
    }

    /** 누락된 순번이나 잘못된 JSON을 받고 센서 원문을 오류 응답에 돌려주지 않는다. */
    @Test void rejectsMalformedAndOutOfOrderMessages() throws Exception {
        try (var socket = connect(tokens.issueAccessToken(1L))) {
            socket.send("{\"type\":\"private-invalid-marker\"}");
            String error = socket.next().toString();
            assertThat(error).contains("INVALID_MESSAGE").doesNotContain("private-invalid-marker");
        }
        try (var socket = connect(tokens.issueAccessToken(1L))) {
            socket.send(start("a", "v1"));
            String id = socket.next().get("sessionId").asString();
            socket.send(sensor(2, 0, 0));
            assertThat(socket.next().get("code").asString()).isEqualTo("INVALID_MESSAGE");
            assertThat(MODEL.awaitCancellation(id)).isTrue();
        }
    }

    /** 설정과 다른 센서 프로필을 임의로 호환되는 것으로 처리하지 않는다. */
    @Test void rejectsUnconfirmedProfile() throws Exception {
        try (var socket = connect(tokens.issueAccessToken(1L))) {
            socket.send(start("a", "v1").replace("synthetic-v1", "unknown-profile"));
            assertThat(socket.next().get("code").asString()).isEqualTo("INVALID_MESSAGE");
        }
    }

    /** 바이너리 프레임을 다른 센서 규격으로 추정하지 않고 모델 호출도 정리한다. */
    @Test void rejectsBinaryMessages() throws Exception {
        try (var socket = connect(tokens.issueAccessToken(1L))) {
            socket.send(start("a", "v1"));
            String id = socket.next().get("sessionId").asString();
            socket.socket.sendBinary(java.nio.ByteBuffer.wrap(new byte[] {1, 2, 3}), true).get(3, TimeUnit.SECONDS);
            assertThat(socket.next().get("code").asString()).isEqualTo("INVALID_MESSAGE");
            assertThat(MODEL.awaitCancellation(id)).isTrue();
        }
    }

    /** 과도한 프레임은 JSON 파싱 전 컨테이너에서 제한하고 연결을 반납한다. */
    @Test void rejectsOversizedMessages() throws Exception {
        try (var socket = connect(tokens.issueAccessToken(1L))) {
            socket.send("x".repeat(5000));
            assertThat(socket.closed.get(3, TimeUnit.SECONDS)).isEqualTo(1009);
        }
    }

    /** 모델 준비 후 센서를 보내지 않는 앱도 유휴 시간 이후 취소한다. */
    @Test void cancelsIdleMeasurement() throws Exception {
        try (var socket = connect(tokens.issueAccessToken(1L))) {
            socket.send(start("a", "v1"));
            String id = socket.next().get("sessionId").asString();
            clock.advance(Duration.ofSeconds(31));
            assertThat(socket.next().get("code").asString()).isEqualTo("IDLE_TIMEOUT");
            assertThat(MODEL.awaitCancellation(id)).isTrue();
        }
    }

    /** HTTP JWT 검증 실패를 업그레이드 응답 코드까지 확인한다. */
    private void rejectHandshake(String token, String query, String origin, int status) throws Exception {
        var builder = HTTP.newWebSocketBuilder();
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (origin != null) builder.header("Origin", origin);
        try {
            WebSocket socket = builder.buildAsync(uri(query), new Probe()).get(4, TimeUnit.SECONDS);
            socket.abort();
            fail("Handshake should fail");
        } catch (ExecutionException error) {
            assertThat(error.getCause()).isInstanceOf(WebSocketHandshakeException.class);
            assertThat(((WebSocketHandshakeException) error.getCause()).getResponse().statusCode()).isEqualTo(status);
        }
    }

    /** 운영 키 대신 테스트 컨테이너 설정의 임시 키로 실패 조건 토큰을 발급한다. */
    private String signed(String issuer, Instant expires) {
        var claims = JwtClaimsSet.builder().issuer(issuer).audience(List.of(jwt.audience())).subject("1")
                .issuedAt(clock.instant().minusSeconds(10)).expiresAt(expires).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    /** 테스트가 띄운 임의 포트로만 연결한다. */
    private URI uri(String query) { return URI.create("ws://127.0.0.1:" + port + "/ws/positioning" + query); }

    /** 네이티브 앱과 같은 Authorization 헤더로 실제 WebSocket 연결을 만든다. */
    private Probe connect(String token) throws Exception {
        var probe = new Probe();
        probe.socket = HTTP.newWebSocketBuilder().header("Authorization", "Bearer " + token)
                .buildAsync(uri(""), probe).get(4, TimeUnit.SECONDS);
        return probe;
    }

    /** 센서 의미를 보증하지 않는 테스트용 모델·프로필을 지정한다. */
    private String start(String model, String version) {
        return JSON.writeValueAsString(java.util.Map.of("type", "start", "contractVersion", 2,
                "modelKey", model, "modelVersion", version, "sensorProfileId", "synthetic-v1"));
    }

    /** 각 연결의 순번과 축 보존 여부를 구분할 합성 센서를 만든다. */
    private String sensor(long sequence, long elapsed, double x) {
        return JSON.writeValueAsString(java.util.Map.of("type", "sensor", "sequence", sequence, "elapsedNanos", elapsed,
                "magnetic", java.util.Map.of("x", x, "y", 0, "z", 0),
                "orientation", java.util.Map.of("x", 0, "y", 0, "z", 0)));
    }

    /** 클래스 초기화 시 테스트용 TCP 모델을 시작한다. */
    private static TestModelServer startModel() {
        try { return new TestModelServer(); }
        catch (IOException error) { throw new ExceptionInInitializerError(error); }
    }

    /** 테스트가 소유한 연결·서버를 반납한다. */
    @AfterAll static void stopModel() throws Exception { HTTP.close(); MODEL.close(); }

    /** 서버·JWT 모두 같은 제어 가능한 시계를 사용한다. */
    @TestConfiguration(proxyBeanMethods = false)
    static class TimeConfiguration {
        /** 운영 시계 대신 테스트마다 초기화하는 UTC 시계를 제공한다. */
        @Bean @Primary MutableClock measurementClock() { return new MutableClock(); }
    }

    /** 벽시계 대기 없이 만료·유휴 경계 조건을 재현한다. */
    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>();
        /** 최초 시각을 설정한다. */
        MutableClock() { reset(); }
        /** 다른 테스트에 시간 이동이 남지 않게 한다. */
        void reset() { now.set(Instant.now()); }
        /** 모든 연결에서 보이는 시각을 함께 이동한다. */
        void advance(Duration duration) { now.updateAndGet(value -> value.plus(duration)); }
        /** 서버 시간대와 무관하게 UTC를 사용한다. */
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        /** 이 테스트의 시간대 변경은 필요하지 않다. */
        @Override public Clock withZone(ZoneId zone) { return this; }
        /** 현재 테스트 시각을 제공한다. */
        @Override public Instant instant() { return now.get(); }
    }

    /** 네트워크 프레임을 모아 테스트에서 순서와 종료를 관찰한다. */
    static final class Probe implements WebSocket.Listener, AutoCloseable {
        private WebSocket socket;
        private final ArrayBlockingQueue<String> messages = new ArrayBlockingQueue<>(64);
        private final CompletableFuture<Integer> closed = new CompletableFuture<>();
        private final StringBuilder partial = new StringBuilder();
        private final Object closeLock = new Object();
        /** 실제 연결은 비동기 핸드셰이크가 끝난 뒤 주입한다. */
        Probe() { }
        /** 종료 순서를 제어하는 단위 테스트에서는 소켓 대역을 주입한다. */
        Probe(WebSocket socket) { this.socket = socket; }
        /** 연결 후 첫 수신을 요청한다. */
        @Override public void onOpen(WebSocket socket) { socket.request(1); }
        /** 프레임 분할 여부와 무관하게 JSON 메시지 하나씩 보관한다. */
        @Override public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) { messages.add(partial.toString()); partial.setLength(0); }
            socket.request(1);
            return null;
        }
        /** 종료를 기록한 뒤 JDK가 자동 응답하므로 직접 종료 요청과 같은 잠금으로 순서를 보장한다. */
        @Override public CompletionStage<?> onClose(WebSocket socket, int status, String reason) {
            synchronized (closeLock) {
                closed.complete(status);
            }
            return null;
        }
        /** 네트워크 실패를 성공한 종료로 숨기지 않는다. */
        @Override public void onError(WebSocket socket, Throwable error) { closed.completeExceptionally(error); }
        /** 메시지가 실제 전송될 때까지 짧게 기다린다. */
        void send(String value) throws Exception { socket.sendText(value, true).get(3, TimeUnit.SECONDS); }
        /** 응답이 없는 경우 테스트를 무한히 기다리지 않는다. */
        JsonNode next() throws Exception {
            String value = messages.poll(4, TimeUnit.SECONDS);
            assertThat(value).as("WebSocket response").isNotNull();
            return JSON.readTree(value);
        }
        /** 중복 종료 요청을 막고, 통신 실패·시간 초과는 보고하면서 연결을 반납한다. */
        @Override public void close() throws Exception {
            boolean gracefullyClosed = false;
            try {
                CompletableFuture<WebSocket> closeSent;
                synchronized (closeLock) {
                    // 확인 직후 onClose의 자동 응답이 먼저 송신부를 닫는 경합을 막는다.
                    closeSent = closed.isDone() || socket.isOutputClosed()
                            ? CompletableFuture.completedFuture(socket)
                            : socket.sendClose(WebSocket.NORMAL_CLOSURE, "test finished");
                }
                // 콜백이 잠금을 얻을 수 있어야 하므로 실제 완료 대기는 잠금 밖에서 한다.
                closeSent.get(3, TimeUnit.SECONDS);
                closed.get(3, TimeUnit.SECONDS);
                gracefullyClosed = true;
            } finally {
                // 정상 종료를 확인하지 못한 연결은 예외를 숨기지 않고 강제 정리한다.
                if (!gracefullyClosed) socket.abort();
            }
        }
    }
}
