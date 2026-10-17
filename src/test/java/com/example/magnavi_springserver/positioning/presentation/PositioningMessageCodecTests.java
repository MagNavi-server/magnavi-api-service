package com.example.magnavi_springserver.positioning.presentation;

import static org.assertj.core.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import com.example.magnavi_springserver.positioning.application.PositioningResult;
import com.example.magnavi_springserver.positioning.domain.ModelEvent;
import com.example.magnavi_springserver.positioning.domain.PositioningException;

/** JSON 경계에서 누락·자동 변환·원문 노출이 생기지 않는지 검사한다. */
class PositioningMessageCodecTests {
    private final PositioningMessageCodec codec = new PositioningMessageCodec();
    private final JsonMapper json = new JsonMapper();
    private static final String SAMPLE = """
            {"type":"sensor","sequence":1,"elapsedNanos":0,
             "magnetic":{"x":0,"y":-1.5,"z":2},"orientation":{"x":0,"y":0,"z":0}}
            """;

    /** 값 0을 누락으로 처리하지 않고 축과 부호를 그대로 보존한다. */
    @Test void preservesZeroAndAxes() {
        var command = (PositioningMessageCodec.Sensor) codec.decode(SAMPLE);
        assertThat(command.sample().elapsedNanos()).isZero();
        assertThat(command.sample().magnetic().x()).isZero();
        assertThat(command.sample().magnetic().y()).isEqualTo(-1.5);
        assertThat(command.toString()).doesNotContain("-1.5");
    }

    /** 기존 gRPC 식별자를 임의로 정규화하지 않는다. */
    @Test void preservesIdentifiers() {
        var start = (PositioningMessageCodec.Start) codec.decode("""
                {"type":"start","contractVersion":1,"modelKey":" 모델 01 ","modelVersion":"v1","sensorProfileId":"synthetic-v1"}
                """);
        assertThat(start.modelKey()).isEqualTo(" 모델 01 ");
        assertThat(codec.decode("{\"type\":\"stop\"}")).isInstanceOf(PositioningMessageCodec.Stop.class);
    }

    /** 여러 입력 오류를 하나의 안전한 코드로 거절하며 입력값을 예외 원인에도 담지 않는다. */
    @ParameterizedTest
    @ValueSource(strings = {"null", "[]", "{}", "{bad", "{\"type\":\"unknown\"}",
            "{\"type\":\"stop\",\"extra\":1}", "{\"type\":\"stop\",\"type\":\"stop\"}",
            "{\"type\":\"stop\"} {}", "{\"type\":1}",
            "{\"type\":\"start\",\"contractVersion\":2,\"modelKey\":\"a\",\"modelVersion\":\"v1\",\"sensorProfileId\":\"x\"}"})
    void rejectsInvalidMessages(String value) {
        assertThatThrownBy(() -> codec.decode(value)).isInstanceOf(PositioningException.class)
                .hasMessage("INVALID_MESSAGE").hasNoCause();
    }

    /** 숫자 문자열·소수 순번·범위 초과·누락·무한대 변환을 거절한다. */
    @Test void rejectsInvalidSensorNumbers() {
        for (String bad : List.of(
                SAMPLE.replace("\"sequence\":1", "\"sequence\":\"1\""),
                SAMPLE.replace("\"sequence\":1", "\"sequence\":1.1"),
                SAMPLE.replace("\"sequence\":1", "\"sequence\":9223372036854775808"),
                SAMPLE.replace("\"sequence\":1", "\"sequence\":0"),
                SAMPLE.replace("\"elapsedNanos\":0", "\"elapsedNanos\":-1"),
                SAMPLE.replace("\"x\":0", "\"x\":null"),
                SAMPLE.replace("\"x\":0", "\"x\":1e309"),
                SAMPLE.replace("\"y\":-1.5,", ""))) {
            assertThatThrownBy(() -> codec.decode(bad)).isInstanceOf(PositioningException.class).hasNoCause();
        }
    }

    /** 미등록 장소를 null과 별도 상태로 알리고 문자열 위치 코드의 앞자리 0을 보존한다. */
    @Test void preservesPredictionAndMissingMapping() {
        var event = new ModelEvent.Prediction(16, "a", "v1", "007", 0.8,
                List.of(new ModelEvent.Candidate("007", 0.8)));
        var result = new PositioningResult(event, PositioningResult.MappingStatus.NOT_FOUND, null);
        var value = json.readTree(codec.result("session-a", new PositioningMessageCodec.Start("a", "v1", "test"), result));
        assertThat(value.get("type").asString()).isEqualTo("prediction");
        assertThat(value.get("locationCode").asString()).isEqualTo("007");
        assertThat(value.get("mappingStatus").asString()).isEqualTo("NOT_FOUND");
        assertThat(value.get("location").isNull()).isTrue();
    }
}
