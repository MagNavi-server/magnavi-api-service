package com.example.magnavi_springserver.positioning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistrar;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

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
import com.example.magnavi_springserver.positioning.support.PythonModelTestServer;
import com.example.magnavi_springserver.support.MySqlTestConfiguration;

/** 앱 대체 클라이언트부터 실제 Spring·Python 모델·격리 MySQL까지 한 흐름으로 검증한다. */
@EnabledIfEnvironmentVariable(named = "MAGNAVI_PYTHON_ROOT", matches = ".+")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "magnavi.positioning-websocket.enabled=true", "magnavi.positioning-websocket.max-connections=4",
        "magnavi.positioning-websocket.max-connections-per-member=2", "magnavi.model-grpc.enabled=true"})
@ActiveProfiles("test")
@Import({MySqlTestConfiguration.class, PythonWebSocketIntegrationTests.PythonConfiguration.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PythonWebSocketIntegrationTests {
    private static final String MODEL_KEY = "magnavi-local-synthetic";
    private static final String MODEL_VERSION = "baseline-20261015";
    private static final String PROFILE = "synthetic-v1";
    private static final int PYTHON_STREAM_LIMIT = 2;
    private final JsonMapper json = new JsonMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final Map<String, Long> locationIds = new HashMap<>();
    private JsonNode inputs;
    private JsonNode references;
    @LocalServerPort private int port;
    @Autowired private JwtTokenProvider tokens;
    @Autowired private PositioningWebSocketHandler handler;
    @Autowired private PythonModelTestServer model;
    @Autowired private BuildingJpaRepository buildings;
    @Autowired private FloorJpaRepository floors;
    @Autowired private IndoorLocationJpaRepository locations;
    @Autowired private ModelLocationMappingJpaRepository mappings;

    /** 기존 기준 파일을 읽고 그 출력 코드에 대응하는 합성 장소만 격리 DB에 등록한다. */
    @BeforeAll
    void prepareReferencesAndLocations() throws Exception {
        Path baseline = model.root().resolve("tests/baseline");
        inputs = json.readTree(baseline.resolve("synthetic_inputs.json").toFile()).get("cases");
        references = json.readTree(baseline.resolve("reference.json").toFile()).get("results").get("cases");
        assertThat(inputs.size()).isEqualTo(references.size());
        var building = buildings.save(new Building("합성 Python 통합 건물", "합성 주소"));
        var floor = floors.save(new Floor(building.getId(), 1, "합성 1층"));
        for (int caseIndex = 0; caseIndex < inputs.size(); caseIndex++) {
            assertThat(inputs.get(caseIndex).get("id")).isEqualTo(references.get(caseIndex).get("id"));
            assertThat(inputs.get(caseIndex).get("samples").size())
                    .isEqualTo(references.get(caseIndex).get("samples").size());
            for (JsonNode sample : references.get(caseIndex).get("samples")) {
                String code = Integer.toString(sample.get("prediction").intValue());
                if (!locationIds.containsKey(code)) {
                    var location = locations.save(new IndoorLocation(floor.getId(), "합성 위치 " + code, null));
                    mappings.save(new ModelLocationMapping(MODEL_KEY, MODEL_VERSION, code, location.getId()));
                    locationIds.put(code, location.getId());
                }
            }
        }
    }

    /** 1·15·16번째를 포함한 144개 예측·후보 점수·장소 매핑이 같은 앱 연결로 돌아온다. */
    @Test
    void receivesAllReferencePredictionsThroughWebSocket() throws Exception {
        int checked = 0;
        for (int caseIndex = 0; caseIndex < inputs.size(); caseIndex++) {
            try (var socket = connect(101L)) {
                String session = start(socket);
                checked += assertCase(socket, session, caseIndex);
                stop(socket);
            }
            awaitReleasedConnections();
        }
        assertThat(checked).isEqualTo(144);
    }

    /** 서로 다른 회원의 센서를 교차 전송해도 독립 실행 기준과 각각 같은 결과를 받는다. */
    @Test
    void isolatesTwoMembersWithInterleavedInputs() throws Exception {
        assertInterleavedConnections(101L, 202L);
    }

    /** 같은 회원의 두 기기 연결도 하나의 센서 윈도우를 공유하지 않는다. */
    @Test
    void isolatesTwoConnectionsOfTheSameMember() throws Exception {
        assertInterleavedConnections(101L, 101L);
    }

    /** 정상 stop과 소켓 종료 뒤 새 순번·빈 윈도우로 재접속하고 연결 슬롯을 재사용한다. */
    @Test
    void reconnectsAfterStopAndTransportCloseWithFreshWindow() throws Exception {
        Set<String> sessions = new HashSet<>();
        // Python 한도보다 많은 연결을 순차 생성하여 취소 누락에 따른 슬롯 누적도 검출한다.
        for (int attempt = 0; attempt < PYTHON_STREAM_LIMIT + 2; attempt++) {
            try (var socket = connect(101L)) {
                String session = start(socket);
                assertThat(sessions.add(session)).as("재접속마다 새 측정 ID").isTrue();
                int caseIndex = attempt % 2 == 0 ? 1 : 2;
                int count = assertCase(socket, session, caseIndex);
                if (attempt % 2 == 0) {
                    stop(socket);
                } else {
                    // 마지막 샘플의 응답을 기다리지 않고 닫아 미처리 입력이 있는 종료도 다룬다.
                    socket.send(sensor(inputs.get(caseIndex).get("samples").get(0), count));
                }
            }
            awaitReleasedConnections();
        }
    }

    /** 두 스트림에 서로 다른 연속 입력을 먼저 보내고 응답 목적지·순번·수치를 검사한다. */
    private void assertInterleavedConnections(long firstMember, long secondMember) throws Exception {
        try (var first = connect(firstMember); var second = connect(secondMember)) {
            String firstSession = start(first);
            String secondSession = start(second);
            assertThat(firstSession).isNotEqualTo(secondSession);
            JsonNode firstSamples = inputs.get(1).get("samples");
            JsonNode secondSamples = inputs.get(2).get("samples");
            assertThat(firstSamples.size()).isEqualTo(secondSamples.size());
            assertThat(references.get(1)).isNotEqualTo(references.get(2));
            for (int i = 0; i < firstSamples.size(); i++) {
                first.send(sensor(firstSamples.get(i), i));
                second.send(sensor(secondSamples.get(i), i));
                assertPrediction(first.next(), firstSession, reference(1, i), i + 1);
                assertPrediction(second.next(), secondSession, reference(2, i), i + 1);
            }
            assertNoExtraResponses(first);
            assertNoExtraResponses(second);
            stop(first);
            stop(second);
        }
    }

    /** 한 입력 묶음을 순서대로 보내며 첫 샘플도 즉시 예측이어야 한다. */
    private int assertCase(PositioningWebSocketProbe socket, String session, int caseIndex) throws Exception {
        JsonNode samples = inputs.get(caseIndex).get("samples");
        for (int i = 0; i < samples.size(); i++) {
            socket.send(sensor(samples.get(i), i));
            assertPrediction(socket.next(), session, reference(caseIndex, i), i + 1);
        }
        assertNoExtraResponses(socket);
        return samples.size();
    }

    /** 실제 인증 필터를 통과하도록 테스트 키로 서명한 회원 토큰을 헤더에 전달한다. */
    private PositioningWebSocketProbe connect(long memberId) throws Exception {
        URI uri = URI.create("ws://127.0.0.1:" + port + "/ws/positioning");
        return PositioningWebSocketProbe.connect(http, uri, tokens.issueAccessToken(memberId));
    }

    /** ready의 계약·모델·프로필을 확인하고 이후 응답을 대조할 측정 ID를 보관한다. */
    private String start(PositioningWebSocketProbe socket) throws Exception {
        socket.send(json.writeValueAsString(Map.of("type", "start", "contractVersion", 2,
                "modelKey", MODEL_KEY, "modelVersion", MODEL_VERSION, "sensorProfileId", PROFILE)));
        JsonNode ready = socket.next();
        assertThat(ready.path("type").asString()).isEqualTo("ready");
        assertThat(ready.path("contractVersion").asInt()).isEqualTo(2);
        assertThat(ready.path("requiredCount").asInt()).isEqualTo(16);
        assertThat(ready.path("modelKey").asString()).isEqualTo(MODEL_KEY);
        assertThat(ready.path("modelVersion").asString()).isEqualTo(MODEL_VERSION);
        assertThat(ready.path("sensorProfileId").asString()).isEqualTo(PROFILE);
        String session = ready.path("sessionId").asString();
        assertThat(session).isNotBlank();
        return session;
    }

    /** 기준 입력의 축·단위를 바꾸지 않고 순번과 단조 증가하는 합성 경과 시간을 붙인다. */
    private String sensor(JsonNode values, int index) {
        return json.writeValueAsString(Map.of("type", "sensor", "sequence", index + 1,
                "elapsedNanos", index * 100_000_000L, "magnetic", vector(values, 0),
                "orientation", vector(values, 3)));
    }

    /** 기준 파일의 자기장·방향 각 세 축을 앱 JSON으로 옮긴다. */
    private Map<String, Double> vector(JsonNode values, int offset) {
        return Map.of("x", values.get(offset).doubleValue(), "y", values.get(offset + 1).doubleValue(),
                "z", values.get(offset + 2).doubleValue());
    }

    /** 재계산한 값을 정답으로 쓰지 않고 고정된 이전 예측 결과를 선택한다. */
    private JsonNode reference(int caseIndex, int sampleIndex) {
        return references.get(caseIndex).get("samples").get(sampleIndex);
    }

    /** 응답 목적지·코드·후보 순서·점수와 실제 DB 조회 결과를 함께 대조한다. */
    private void assertPrediction(JsonNode result, String session, JsonNode reference, int sequence) {
        assertThat(result.path("type").asString()).isEqualTo("prediction");
        assertThat(result.path("sessionId").asString()).isEqualTo(session);
        assertThat(result.path("sequence").asLong()).isEqualTo(sequence);
        assertThat(result.path("modelKey").asString()).isEqualTo(MODEL_KEY);
        assertThat(result.path("modelVersion").asString()).isEqualTo(MODEL_VERSION);
        String code = Integer.toString(reference.get("prediction").intValue());
        assertThat(result.path("locationCode").asString()).isEqualTo(code);
        JsonNode candidates = reference.get("candidates");
        assertThat(result.path("candidates").size()).isEqualTo(candidates.size());
        for (int i = 0; i < candidates.size(); i++) {
            JsonNode actual = result.get("candidates").get(i);
            assertThat(actual.path("locationCode").asString())
                    .isEqualTo(Integer.toString(candidates.get(i).get(0).intValue()));
            double expectedScore = candidates.get(i).get(1).doubleValue();
            assertThat(actual.path("score").isNumber()).isTrue();
            assertThat(actual.path("score").asDouble()).isCloseTo(expectedScore,
                    within(Math.max(1e-12, Math.abs(expectedScore) * 1e-9)));
        }
        assertThat(result.path("score").isNumber()).isTrue();
        assertThat(result.path("score").asDouble()).isEqualTo(result.get("candidates").get(0).get("score").asDouble());
        assertThat(result.path("mappingStatus").asString()).isEqualTo("FOUND");
        assertThat(result.path("location").path("id").asLong()).isEqualTo(locationIds.get(code));
    }

    /** 정상 측정 종료 응답과 WebSocket의 정상 종료 코드를 모두 확인한다. */
    private void stop(PositioningWebSocketProbe socket) throws Exception {
        socket.send("{\"type\":\"stop\"}");
        assertThat(socket.next().path("type").asString()).isEqualTo("stopped");
        assertThat(socket.closed.get(3, TimeUnit.SECONDS)).isEqualTo(1000);
    }

    /** 이미 받은 샘플에 대한 중복 응답이나 다른 연결의 결과가 뒤따르지 않는지 관찰한다. */
    private void assertNoExtraResponses(PositioningWebSocketProbe socket) throws Exception {
        assertThat(socket.messages.poll(150, TimeUnit.MILLISECONDS)).isNull();
    }

    /** 정상·오류 경로 모두 Spring 연결 슬롯이 제한 시간 안에 반환되어야 한다. */
    @AfterEach
    void awaitReleasedConnections() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (handler.activeConnections() != 0 && System.nanoTime() < deadline) Thread.sleep(10);
        assertThat(handler.activeConnections()).isZero();
    }

    /** HTTP·Python을 회수하며 Python 종료 실패도 컨텍스트 경고가 아닌 테스트 실패로 확인한다. */
    @AfterAll
    void closeResources() throws Exception {
        try { http.close(); }
        finally { model.close(); }
    }

    /** Python 프로세스를 테스트 컨텍스트가 소유하고 종료 시 함께 회수한다. */
    @TestConfiguration(proxyBeanMethods = false)
    static class PythonConfiguration {
        /** 기존 Python 환경을 사용하고 각 실행의 설정·준비 표시·로그는 build 아래에 분리한다. */
        @Bean(destroyMethod = "close")
        PythonModelTestServer pythonModelTestServer() throws Exception {
            Path runs = Path.of("build/python-websocket-test");
            Files.createDirectories(runs);
            return PythonModelTestServer.start(Path.of(System.getenv("MAGNAVI_PYTHON_ROOT")),
                    Files.createTempDirectory(runs, "run-"), PYTHON_STREAM_LIMIT);
        }

        /** 실제로 준비된 Python 포트와 합성 프로필만 Spring 연결 설정에 주입한다. */
        @Bean
        DynamicPropertyRegistrar pythonProperties(PythonModelTestServer model) {
            return registry -> {
                registry.add("magnavi.model-grpc.host", () -> "127.0.0.1");
                registry.add("magnavi.model-grpc.port", model::port);
                registry.add("magnavi.model-grpc.plaintext", () -> true);
                registry.add("magnavi.model-grpc.sensor-profile-id", () -> PROFILE);
            };
        }
    }
}
