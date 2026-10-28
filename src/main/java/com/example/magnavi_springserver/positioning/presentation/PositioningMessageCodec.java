package com.example.magnavi_springserver.positioning.presentation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.example.magnavi_springserver.positioning.application.ModelStreamException;
import com.example.magnavi_springserver.positioning.application.PositioningResult;
import com.example.magnavi_springserver.positioning.domain.ModelEvent;
import com.example.magnavi_springserver.positioning.domain.PositioningException;
import com.example.magnavi_springserver.positioning.domain.PositioningException.Reason;
import com.example.magnavi_springserver.positioning.domain.SensorSample;

/** 첫 샘플부터 예측하는 WebSocket v2 JSON을 변환하며 입력값을 오류에 되돌리지 않는다. */
public final class PositioningMessageCodec {
    private final JsonMapper json = JsonMapper.builder(JsonFactory.builder()
                    .streamReadConstraints(StreamReadConstraints.builder()
                            .maxNestingDepth(8).maxStringLength(4096).maxNumberLength(64).build()).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    /** 한 연결에서는 start 한 번, sensor 여러 번, stop 순서로 요청한다. */
    public sealed interface Command { }

    /** 실제 센서 규격과 모델 버전은 앱과 서버가 같은 값을 지정해야 한다. */
    public record Start(String modelKey, String modelVersion, String sensorProfileId) implements Command { }

    /** 모델용 16개 묶음이 아니라 한 시점의 센서 하나다. */
    public record Sensor(SensorSample sample) implements Command {
        /** 센서 원문을 기본 로그 표현에서 제외한다. */
        @Override public String toString() { return "Sensor[REDACTED]"; }
    }

    /** 정상적으로 측정을 끝내고 연결을 닫는 요청이다. */
    public record Stop() implements Command { }

    /** 누락·잘못된 타입·중복 키·알 수 없는 필드를 명시적으로 거절한다. */
    public Command decode(String payload) {
        try {
            JsonNode root = json.readTree(payload);
            String type = text(root, "type", 16);
            return switch (type) {
                case "start" -> {
                    fields(root, "type", "contractVersion", "modelKey", "modelVersion", "sensorProfileId");
                    if (integer(root, "contractVersion") != 2) throw invalid();
                    yield new Start(identifier(root, "modelKey", 128), identifier(root, "modelVersion", 64),
                            identifier(root, "sensorProfileId", 64));
                }
                case "sensor" -> {
                    fields(root, "type", "sequence", "elapsedNanos", "magnetic", "orientation");
                    yield new Sensor(new SensorSample(integer(root, "sequence"), integer(root, "elapsedNanos"),
                            vector(root.get("magnetic")), vector(root.get("orientation"))));
                }
                case "stop" -> {
                    fields(root, "type");
                    yield new Stop();
                }
                default -> throw invalid();
            };
        } catch (RuntimeException ignored) {
            // Jackson 오류에는 원문이 담길 수 있어 원인 예외를 보존하지 않는다.
            throw invalid();
        }
    }

    /** 준비·워밍업·예측을 구분하고, 장소 미등록 상태를 예측 실패와 혼동하지 않는다. */
    public String result(String sessionId, Start start, PositioningResult result) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("sessionId", sessionId);
        if (result.event() instanceof ModelEvent.Ready ready) {
            output.put("type", "ready");
            output.put("contractVersion", 2);
            output.put("modelKey", start.modelKey());
            output.put("modelVersion", start.modelVersion());
            output.put("sensorProfileId", start.sensorProfileId());
            output.put("requiredCount", ready.requiredCount());
        } else if (result.event() instanceof ModelEvent.WarmingUp warming) {
            output.put("type", "warming_up");
            output.put("sequence", warming.sequence());
            output.put("bufferedCount", warming.bufferedCount());
            output.put("requiredCount", warming.requiredCount());
        } else if (result.event() instanceof ModelEvent.Prediction prediction) {
            output.put("type", "prediction");
            output.put("sequence", prediction.sequence());
            output.put("modelKey", prediction.modelKey());
            output.put("modelVersion", prediction.modelVersion());
            output.put("locationCode", prediction.locationCode());
            output.put("score", prediction.score());
            output.put("candidates", prediction.candidates());
            output.put("mappingStatus", result.mappingStatus().name());
            output.put("location", result.location());
        } else throw new PositioningException(Reason.INTERNAL);
        return json.writeValueAsString(output);
    }

    /** 원격 오류 설명·센서·토큰 대신 고정된 코드와 안내만 공개한다. */
    public String error(Reason reason) {
        return json.writeValueAsString(Map.of("type", "error", "code", reason.name(),
                "message", switch (reason) {
                    case UNAUTHORIZED, TOKEN_EXPIRED -> "로그인 상태를 확인하고 다시 연결해 주세요.";
                    case INVALID_MESSAGE, NOT_READY -> "메시지 형식과 측정 시작 순서를 확인해 주세요.";
                    case RATE_LIMITED, OVERLOADED -> "처리 한도를 초과했습니다. 잠시 후 새로 연결해 주세요.";
                    case START_TIMEOUT, IDLE_TIMEOUT -> "입력이 없어 측정 연결을 종료했습니다.";
                    default -> "모델 연결을 사용할 수 없습니다. 잠시 후 새로 연결해 주세요.";
                }));
    }

    /** 정상 종료 응답에는 입력이나 위치 정보를 담지 않는다. */
    public String stopped() { return "{\"type\":\"stopped\"}"; }

    /** 내부 gRPC 오류를 앱 계약의 안정된 코드로 변환한다. */
    public Reason modelFailure(ModelStreamException error) {
        return switch (error.getReason()) {
            case NOT_CONFIGURED -> Reason.NOT_CONFIGURED;
            case NOT_READY -> Reason.NOT_READY;
            case INVALID_INPUT -> Reason.INVALID_MESSAGE;
            case PROTOCOL_ERROR -> Reason.MODEL_PROTOCOL_ERROR;
            case UNAVAILABLE, CANCELLED, CLOSED -> Reason.MODEL_UNAVAILABLE;
            case OVERLOADED -> Reason.OVERLOADED;
            case TIMED_OUT -> Reason.MODEL_TIMEOUT;
            case INTERNAL -> Reason.INTERNAL;
        };
    }

    /** 명시한 필드만 허용해 오타와 새로운 규격을 조용히 무시하지 않는다. */
    private void fields(JsonNode object, String... names) {
        if (object == null || !object.isObject() || object.size() != names.length) throw invalid();
        Set<String> allowed = Set.of(names);
        for (String name : object.propertyNames()) if (!allowed.contains(name)) throw invalid();
    }

    /** 문자열로 전달한 숫자와 null을 자동 변환하지 않는다. */
    private String text(JsonNode object, String name, int max) {
        if (object == null || !object.isObject()) throw invalid();
        JsonNode value = object.get(name);
        if (value == null || !value.isString()) throw invalid();
        String result = value.asString();
        if (result.isBlank() || result.codePointCount(0, result.length()) > max) throw invalid();
        return result;
    }

    /** 기존 gRPC처럼 식별자의 대소문자·공백·앞자리 0을 변환하지 않는다. */
    private String identifier(JsonNode object, String name, int max) {
        return text(object, name, max);
    }

    /** 정수 필드에서 누락·소수·64비트 범위 초과를 거절한다. */
    private long integer(JsonNode object, String name) {
        JsonNode value = object.get(name);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) throw invalid();
        return value.longValue();
    }

    /** 축이 빠진 경우와 정상 0을 구분한다. */
    private SensorSample.Vector vector(JsonNode object) {
        fields(object, "x", "y", "z");
        return new SensorSample.Vector(number(object, "x"), number(object, "y"), number(object, "z"));
    }

    /** 센서값은 유한한 JSON 숫자여야 한다. */
    private double number(JsonNode object, String name) {
        JsonNode value = object.get(name);
        if (value == null || !value.isNumber()) throw invalid();
        double result = value.doubleValue();
        if (!Double.isFinite(result)) throw invalid();
        return result;
    }

    /** 입력 원문 없이 같은 안전한 실패를 만든다. */
    private PositioningException invalid() { return new PositioningException(Reason.INVALID_MESSAGE); }
}
