package com.example.magnavi_springserver.positioning.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import com.example.magnavi_springserver.positioning.domain.ModelEvent;
import com.example.magnavi_springserver.positioning.domain.SensorSample;

/** 명시적으로 지정한 Python 저장소의 실제 모델과 통신하며 업무 DB는 사용하지 않는다. */
@EnabledIfEnvironmentVariable(named = "MAGNAVI_PYTHON_ROOT", matches = ".+")
class PythonModelIntegrationTests {
    @TempDir Path temporary;
    private final JsonMapper json = JsonMapper.builder().build();
    private static final Duration WAIT = Duration.ofSeconds(5);

    /** 첫 번째·15·16번째를 포함한 기존 144개 결과가 Java 경계까지 동일하게 전달된다. */
    @Test
    void receivesAllReferencePredictionsFromActualPythonModel() throws Exception {
        Path root = Path.of(System.getenv("MAGNAVI_PYTHON_ROOT")).toAbsolutePath();
        var config = (ObjectNode) json.readTree(root.resolve("config/positioning.synthetic.json").toFile());
        config.put("port", 0);
        Path configFile = temporary.resolve("config.json");
        Files.writeString(configFile, json.writeValueAsString(config));
        Path readyFile = temporary.resolve("ready.json");
        var builder = new ProcessBuilder(root.resolve("build/streaming-venv/bin/python").toString(),
                "-m", "positioning", "--config", configFile.toString(), "--ready-file", readyFile.toString())
                .directory(root.toFile()).redirectErrorStream(true)
                .redirectOutput(temporary.resolve("python.log").toFile());
        builder.environment().put("PYTHONPATH", root + ":" + root.resolve("build/generated"));
        Process process = builder.start();
        try {
            int port = awaitPort(process, readyFile);
            var properties = new ModelGrpcProperties(true, "127.0.0.1", port, true, "synthetic-v1",
                    4, 32, 32, 65536, WAIT, WAIT, Duration.ofSeconds(30));
            var channel = Grpc.newChannelBuilderForAddress("127.0.0.1", port, InsecureChannelCredentials.create())
                    .disableRetry().build();
            try (var client = new GrpcModelStreamClient(channel, properties)) {
                JsonNode inputs = json.readTree(root.resolve("tests/baseline/synthetic_inputs.json").toFile()).get("cases");
                JsonNode references = json.readTree(root.resolve("tests/baseline/reference.json").toFile()).get("results").get("cases");
                int checked = 0;
                for (int caseIndex = 0; caseIndex < inputs.size(); caseIndex++) {
                    try (var stream = client.open("magnavi-local-synthetic", "baseline-20261015")) {
                        assertThat(stream.next(WAIT)).contains(new ModelEvent.Ready(16));
                        JsonNode samples = inputs.get(caseIndex).get("samples");
                        for (int i = 0; i < samples.size(); i++) {
                            JsonNode values = samples.get(i);
                            stream.send(new SensorSample(i + 1, i * 100_000_000L, vector(values, 0), vector(values, 3)));
                            var result = (ModelEvent.Prediction) stream.next(WAIT).orElseThrow();
                            assertPrediction(result, references.get(caseIndex).get("samples").get(i), i + 1);
                            checked++;
                        }
                    }
                }
                assertThat(checked).isEqualTo(144);
            }
        } finally {
            process.destroy();
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    /** 단순 프로세스 실행이 아닌 모델·gRPC 준비 완료 표시를 제한 시간 동안 기다린다. */
    private int awaitPort(Process process, Path readyFile) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline && process.isAlive()) {
            if (Files.exists(readyFile) && Files.size(readyFile) > 0) {
                try {
                    JsonNode metadata = json.readTree(readyFile.toFile());
                    if (metadata.get("ready").booleanValue()) return metadata.get("port").intValue();
                } catch (tools.jackson.core.JacksonException incompleteWrite) {
                    // 파일 쓰기 도중 읽은 경우 다음 반복에서 완성된 메타데이터를 확인한다.
                }
            }
            Thread.sleep(25);
        }
        throw new AssertionError("Python 모델 준비 실패: 테스트 임시 폴더의 python.log 확인");
    }

    /** 합성 입력의 축 순서를 바꾸거나 단위를 변환하지 않는다. */
    private SensorSample.Vector vector(JsonNode values, int offset) {
        return new SensorSample.Vector(values.get(offset).doubleValue(), values.get(offset + 1).doubleValue(),
                values.get(offset + 2).doubleValue());
    }

    /** 정수 코드의 문자열 전달과 후보·점수 보존을 기존 회귀 오차 기준으로 검사한다. */
    private void assertPrediction(ModelEvent.Prediction result, JsonNode reference, int sequence) {
        assertThat(result.sequence()).isEqualTo(sequence);
        assertThat(result.locationCode()).isEqualTo(Integer.toString(reference.get("prediction").intValue()));
        JsonNode candidates = reference.get("candidates");
        assertThat(result.candidates()).hasSize(candidates.size());
        for (int i = 0; i < candidates.size(); i++) {
            double score = candidates.get(i).get(1).doubleValue();
            assertThat(result.candidates().get(i).locationCode()).isEqualTo(Integer.toString(candidates.get(i).get(0).intValue()));
            assertThat(result.candidates().get(i).score()).isCloseTo(score, within(Math.max(1e-12, Math.abs(score) * 1e-9)));
        }
        assertThat(result.score()).isEqualTo(result.candidates().getFirst().score());
    }
}
