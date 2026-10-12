package com.example.magnavi_springserver.positioning.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.Status;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.example.magnavi_springserver.positioning.application.ModelStream;
import com.example.magnavi_springserver.positioning.application.ModelStreamException;
import com.example.magnavi_springserver.positioning.application.ModelStreamException.Reason;
import com.example.magnavi_springserver.positioning.domain.ModelEvent;
import com.example.magnavi_springserver.positioning.domain.SensorSample;
import com.example.magnavi_springserver.positioning.grpc.v1.Candidate;
import com.example.magnavi_springserver.positioning.grpc.v1.StreamEvent;
import com.example.magnavi_springserver.positioning.support.TestModelServer;
import com.example.magnavi_springserver.positioning.support.TestModelServer.Mode;

/** 실제 루프백 HTTP/2·protobuf 호출로 계약, 한도, 취소, 동시 연결을 검증한다. */
class GrpcModelStreamClientTests {
    private TestModelServer server;
    private GrpcModelStreamClient client;
    private static final Duration WAIT = Duration.ofSeconds(3);

    /** 실패한 테스트도 열린 연결과 포트를 남기지 않는다. */
    @AfterEach
    void cleanup() throws Exception {
        if (client != null) client.close();
        if (server != null) server.close();
    }

    /** 실제 0의 presence와 15회 준비 중·16번째 예측·원래 코드 보존을 확인한다. */
    @Test
    void streamsZeroValuesWarmupAndPredictions() throws Exception {
        start(Mode.NORMAL, Status.UNKNOWN, UnaryOperator.identity(), 32, 32, Duration.ofSeconds(5));
        try (var stream = openReady()) {
            for (int i = 1; i <= 17; i++) {
                stream.send(sample(i));
                var event = stream.next(WAIT).orElseThrow();
                if (i < 16) assertThat(event).isEqualTo(new ModelEvent.WarmingUp(i, i, 16));
                else {
                    var prediction = (ModelEvent.Prediction) event;
                    assertThat(prediction.locationCode()).isEqualTo("007");
                    assertThat(prediction.sequence()).isEqualTo(i);
                    assertThat(prediction.candidates()).hasSize(2);
                    assertThat(prediction.toString()).doesNotContain("007", "0.8");
                }
            }
            var sent = server.requests(stream.sessionId());
            assertThat(sent).hasSize(18);
            assertThat(sent.getFirst().getStart().getSensorProfileId()).isEqualTo("synthetic-v1");
            var first = sent.get(1).getSample();
            assertThat(first.hasElapsedNanos()).isTrue();
            assertThat(first.getElapsedNanos()).isZero();
            assertThat(first.getMagnetic().hasX()).isTrue();
            assertThat(first.getMagnetic().getX()).isZero();
        }
    }

    /** 서로 다른 모델·연결의 40개 결과가 섞이지 않고 하나의 채널에서 처리된다. */
    @Test
    void isolatesConcurrentStreams() throws Exception {
        start(Mode.NORMAL, Status.UNKNOWN, UnaryOperator.identity(), 32, 32, Duration.ofSeconds(5));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = executor.submit(() -> runMeasurement("model-a"));
            var b = executor.submit(() -> runMeasurement("model-b"));
            String firstId = a.get(5, TimeUnit.SECONDS);
            String secondId = b.get(5, TimeUnit.SECONDS);
            assertThat(firstId).isNotEqualTo(secondId);
            assertThat(server.requests(firstId)).hasSize(41);
            assertThat(server.requests(secondId)).hasSize(41);
        }
    }

    /** 잘못된 순번·시간을 보내면 그 호출만 종료하고 실제 서버까지 취소한다. */
    @ParameterizedTest
    @MethodSource("invalidSamples")
    void rejectsOutOfOrderOrNonIncreasingTime(SensorSample invalid) throws Exception {
        start(Mode.NORMAL, Status.UNKNOWN, UnaryOperator.identity(), 32, 32, Duration.ofSeconds(5));
        var stream = openReady();
        stream.send(sample(1));
        stream.next(WAIT).orElseThrow();
        assertFailure(() -> stream.send(invalid), Reason.INVALID_INPUT);
        assertThat(server.awaitCancellation(stream.sessionId())).isTrue();
        assertThat(server.requests(stream.sessionId())).hasSize(2);
    }

    /** 입력 순번 중복·누락·역순과 시간 역행을 각각 재현한다. */
    static Stream<SensorSample> invalidSamples() {
        return Stream.of(sample(1), sample(3), new SensorSample(2, 0, vector(), vector()));
    }

    /** 준비 완료를 받기 전에는 센서를 전송하지 않는다. */
    @Test
    void waitsForReadyAndTimesOutWithoutIt() throws Exception {
        start(Mode.NO_READY, Status.UNKNOWN, UnaryOperator.identity(), 32, 32, Duration.ofMillis(200));
        var stream = client.open("model-a", "v1");
        assertFailure(() -> stream.send(sample(1)), Reason.NOT_READY);
        assertFailure(() -> stream.next(WAIT), Reason.TIMED_OUT);
    }

    /** 짧게 설정한 유휴 한도가 아직 준비 중인 스트림의 시작 한도를 덮지 않는다. */
    @Test
    void keepsPreparationTimeoutSeparateFromIdleTimeout() throws Exception {
        server = new TestModelServer(Mode.NO_READY, Status.UNKNOWN, UnaryOperator.identity());
        var properties = new ModelGrpcProperties(true, "127.0.0.1", server.port(), true, "synthetic-v1",
                4, 32, 32, 65536, Duration.ofSeconds(2), Duration.ofSeconds(2), Duration.ofMillis(100));
        var channel = Grpc.newChannelBuilderForAddress("127.0.0.1", server.port(), InsecureChannelCredentials.create())
                .disableRetry().build();
        client = new GrpcModelStreamClient(channel, properties);
        var stream = client.open("model-a", "v1");
        assertThat(stream.next(Duration.ofMillis(250))).isEmpty();
        stream.close();
    }

    /** 응답하지 않는 모델의 입력 대기열이 무한히 커지지 않는다. */
    @Test
    void boundsPendingSamplesAndReleasesCapacity() throws Exception {
        start(Mode.NO_RESULTS, Status.UNKNOWN, UnaryOperator.identity(), 2, 32, Duration.ofSeconds(5));
        var stream = openReady();
        stream.send(sample(1));
        stream.send(sample(2));
        assertFailure(() -> stream.send(sample(3)), Reason.OVERLOADED);
        assertThat(server.awaitCancellation(stream.sessionId())).isTrue();
        try (var replacement = openReady()) { assertThat(replacement.sessionId()).isNotEqualTo(stream.sessionId()); }
    }

    /** 보낸 센서의 결과가 오지 않으면 전체 호출 수명과 별개로 시간 초과 처리한다. */
    @Test
    void timesOutUnansweredSample() throws Exception {
        start(Mode.NO_RESULTS, Status.UNKNOWN, UnaryOperator.identity(), 32, 32, Duration.ofMillis(200));
        var stream = openReady();
        stream.send(sample(1));
        assertFailure(() -> stream.next(WAIT), Reason.TIMED_OUT);
        assertThat(server.awaitCancellation(stream.sessionId())).isTrue();
    }

    /** 소비하지 않는 결과는 정해진 수만 수신하고 오래 쌓이면 과부하로 정리한다. */
    @Test
    void boundsUnreadResultsAndClosesSlowConsumer() throws Exception {
        start(Mode.NORMAL, Status.UNKNOWN, UnaryOperator.identity(), 32, 1, Duration.ofMillis(200));
        var stream = openReady();
        stream.send(sample(1));
        stream.send(sample(2));
        assertThat(server.awaitCancellation(stream.sessionId())).isTrue();
        assertFailure(() -> stream.next(Duration.ZERO), Reason.OVERLOADED);
    }

    /** 정상적으로 읽어도 입력을 멈춘 측정 연결은 유휴 한도 뒤 반환된다. */
    @Test
    void expiresIdleConnection() throws Exception {
        start(Mode.NORMAL, Status.UNKNOWN, UnaryOperator.identity(), 32, 32, Duration.ofMillis(200));
        var stream = openReady();
        assertFailure(() -> stream.next(WAIT), Reason.TIMED_OUT);
    }

    /** 연결 수 한도를 넘긴 요청을 거절하고 기존 연결 종료 후 다시 허용한다. */
    @Test
    void limitsActiveConnections() throws Exception {
        start(Mode.NORMAL, Status.UNKNOWN, UnaryOperator.identity(), 32, 32, Duration.ofSeconds(5));
        List<ModelStream> streams = new ArrayList<>();
        for (int i = 0; i < 4; i++) streams.add(openReady());
        assertFailure(() -> client.open("model-a", "v1"), Reason.OVERLOADED);
        streams.getFirst().close();
        try (var replacement = openReady()) { assertThat(replacement.sessionId()).isNotEqualTo(streams.getFirst().sessionId()); }
        streams.forEach(ModelStream::close);
    }

    /** 취소는 대기 중인 읽기를 깨우고 서버에 전달되며 새 연결에 늦은 결과가 들어오지 않는다. */
    @Test
    void cancelsBlockedReadAndDiscardsLateResult() throws Exception {
        start(Mode.NO_RESULTS, Status.UNKNOWN, UnaryOperator.identity(), 32, 32, Duration.ofSeconds(5));
        var old = openReady();
        old.send(sample(1));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var read = executor.submit(() -> catchThrowableOfType(ModelStreamException.class, () -> old.next(WAIT)));
            old.close();
            old.close();
            assertThat(read.get(2, TimeUnit.SECONDS).getReason()).isEqualTo(Reason.CANCELLED);
            assertThat(server.awaitCancellation(old.sessionId())).isTrue();
            var replacement = openReady();
            server.sendLate(old.sessionId());
            assertThat(replacement.next(Duration.ofMillis(50))).isEmpty();
            replacement.close();
        }
    }

    /** 모델 서버가 오류 설명을 보내더라도 내부 예외에는 종류만 남긴다. */
    @ParameterizedTest
    @MethodSource("remoteErrors")
    void convertsRemoteErrorsWithoutLeakingDetails(Status status, Reason expected) throws Exception {
        start(Mode.ERROR, status, UnaryOperator.identity(), 32, 32, Duration.ofSeconds(5));
        var stream = client.open("model-a", "v1");
        assertFailure(() -> stream.next(WAIT), expected);
    }

    /** 외부 실패별로 앱에 구분해 전달할 종류를 명시한다. */
    static Stream<Arguments> remoteErrors() {
        return Stream.of(Arguments.of(Status.UNAVAILABLE, Reason.UNAVAILABLE),
                Arguments.of(Status.INVALID_ARGUMENT, Reason.INVALID_INPUT),
                Arguments.of(Status.RESOURCE_EXHAUSTED, Reason.OVERLOADED),
                Arguments.of(Status.DEADLINE_EXCEEDED, Reason.TIMED_OUT),
                Arguments.of(Status.CANCELLED, Reason.CANCELLED),
                Arguments.of(Status.INTERNAL, Reason.INTERNAL));
    }

    /** 모델의 조기 정상 종료도 측정이 계속 가능하다고 오인하지 않는다. */
    @Test
    void rejectsUnexpectedServerCompletion() throws Exception {
        start(Mode.COMPLETE, Status.UNKNOWN, UnaryOperator.identity(), 32, 32, Duration.ofSeconds(5));
        assertFailure(() -> client.open("model-a", "v1").next(WAIT), Reason.UNAVAILABLE);
    }

    /** 다른 연결·모델·규격으로 준비되었다는 응답을 거절한다. */
    @ParameterizedTest
    @MethodSource("badReadyResponses")
    void rejectsMismatchedReady(UnaryOperator<StreamEvent> mutation) throws Exception {
        start(Mode.NORMAL, Status.UNKNOWN, mutation, 32, 32, Duration.ofSeconds(5));
        assertFailure(() -> client.open("model-a", "v1").next(WAIT), Reason.PROTOCOL_ERROR);
    }

    /** 핸드셰이크의 각 식별자와 필수 payload 누락을 독립적으로 검사한다. */
    static Stream<UnaryOperator<StreamEvent>> badReadyResponses() {
        return Stream.of(e -> e.toBuilder().setSessionId("another-session").build(),
                e -> e.toBuilder().setReady(e.getReady().toBuilder().setContractVersion(2)).build(),
                e -> e.toBuilder().setReady(e.getReady().toBuilder().setModelKey("another-model")).build(),
                e -> e.toBuilder().setReady(e.getReady().toBuilder().setModelVersion("V1")).build(),
                e -> e.toBuilder().setReady(e.getReady().toBuilder().setSensorProfileId("unknown")).build(),
                e -> e.toBuilder().setReady(e.getReady().toBuilder().setRequiredCount(32)).build(),
                e -> e.toBuilder().clearPayload().build());
    }

    /** 잘못된 위치·버전·점수·순번을 장소 매핑까지 전달하지 않는다. */
    @ParameterizedTest
    @MethodSource("badPredictions")
    void rejectsMalformedPrediction(UnaryOperator<StreamEvent> mutation) throws Exception {
        start(Mode.NORMAL, Status.UNKNOWN, e -> e.hasPrediction() ? mutation.apply(e) : e,
                32, 32, Duration.ofSeconds(5));
        var stream = openReady();
        warmup(stream);
        stream.send(sample(16));
        assertFailure(() -> stream.next(WAIT), Reason.PROTOCOL_ERROR);
    }

    /** 프로토콜 오류를 실제 네트워크 응답으로 재현한다. */
    static Stream<UnaryOperator<StreamEvent>> badPredictions() {
        return Stream.of(e -> e.toBuilder().setPrediction(e.getPrediction().toBuilder().clearSequence()).build(),
                e -> e.toBuilder().setPrediction(e.getPrediction().toBuilder().setSequence(17)).build(),
                e -> e.toBuilder().setPrediction(e.getPrediction().toBuilder().setModelVersion("v2")).build(),
                e -> e.toBuilder().setPrediction(e.getPrediction().toBuilder().setScore(Double.NaN)).build(),
                e -> e.toBuilder().setPrediction(e.getPrediction().toBuilder().clearScore()).build(),
                e -> e.toBuilder().setPrediction(e.getPrediction().toBuilder().setLocationCode(" ")).build(),
                e -> e.toBuilder().setPrediction(e.getPrediction().toBuilder().clearCandidates()).build(),
                e -> e.toBuilder().setPrediction(e.getPrediction().toBuilder()
                        .setCandidates(1, Candidate.newBuilder().setLocationCode("007").setScore(0.1))).build(),
                e -> e.toBuilder().setPrediction(e.getPrediction().toBuilder()
                        .setCandidates(0, Candidate.newBuilder().setLocationCode("other").setScore(0.8))).build(),
                e -> e.toBuilder().setPrediction(e.getPrediction().toBuilder()
                        .setCandidates(1, Candidate.newBuilder().setLocationCode("008").setScore(0.9))).build());
    }

    /** NaN·무한대·빠진 벡터를 네트워크 호출 전에 거절한다. */
    @Test
    void rejectsInvalidSensorValues() {
        assertThatThrownBy(() -> new SensorSample.Vector(Double.NaN, 0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SensorSample.Vector(0, Double.POSITIVE_INFINITY, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SensorSample(1, 0, null, vector())).isInstanceOf(IllegalArgumentException.class);
        assertThat(sample(1).toString()).isEqualTo("SensorSample[REDACTED]");
    }

    /** 명시적 클라이언트 종료는 기존 호출과 새 호출을 모두 막는다. */
    @Test
    void shutsDownAllCallsAndRejectsNewOnes() throws Exception {
        start(Mode.NORMAL, Status.UNKNOWN, UnaryOperator.identity(), 32, 32, Duration.ofSeconds(5));
        var stream = openReady();
        client.close();
        assertFailure(() -> stream.send(sample(1)), Reason.CANCELLED);
        assertFailure(() -> client.open("model-a", "v1"), Reason.CLOSED);
        assertThat(server.awaitCancellation(stream.sessionId())).isTrue();
    }

    /** 타이머 값을 테스트에서만 짧게 바꾸며 모든 호출은 독립 루프백 포트를 사용한다. */
    private void start(Mode mode, Status status, UnaryOperator<StreamEvent> mutation,
                       int pending, int buffered, Duration timeout) throws Exception {
        server = new TestModelServer(mode, status, mutation);
        var properties = new ModelGrpcProperties(true, "127.0.0.1", server.port(), true, "synthetic-v1",
                4, pending, buffered, 65536, timeout, timeout, timeout);
        var channel = Grpc.newChannelBuilderForAddress("127.0.0.1", server.port(), InsecureChannelCredentials.create())
                .disableRetry().build();
        client = new GrpcModelStreamClient(channel, properties);
    }

    /** 모델의 준비 완료를 실제로 읽은 뒤 테스트 입력을 보낸다. */
    private ModelStream openReady() {
        var stream = client.open("model-a", "v1");
        assertThat(stream.next(WAIT)).contains(new ModelEvent.Ready(16));
        return stream;
    }

    /** 각 스트림이 독립적으로 워밍업하고 자신의 모델 결과를 받는지 확인한다. */
    private String runMeasurement(String model) {
        try (var stream = client.open(model, "v1")) {
            assertThat(stream.next(WAIT)).contains(new ModelEvent.Ready(16));
            for (int i = 1; i <= 40; i++) {
                stream.send(sample(i));
                var event = stream.next(WAIT).orElseThrow();
                if (event instanceof ModelEvent.Prediction prediction) assertThat(prediction.modelKey()).isEqualTo(model);
            }
            return stream.sessionId();
        }
    }

    /** 합성 샘플 15개를 처리해 예측 응답의 검사 위치까지 진행한다. */
    private void warmup(ModelStream stream) {
        for (int i = 1; i < 16; i++) {
            stream.send(sample(i));
            assertThat(stream.next(WAIT)).contains(new ModelEvent.WarmingUp(i, i, 16));
        }
    }

    /** 의미 없는 합성 값만 사용하며 상대 시각은 0부터 증가한다. */
    private static SensorSample sample(long sequence) { return new SensorSample(sequence, (sequence - 1) * 100, vector(), vector()); }

    /** 0이 정상 센서값으로 전달되는지 확인하기 위한 벡터다. */
    private static SensorSample.Vector vector() { return new SensorSample.Vector(0, 0, 0); }

    /** 사용자에게 전달할 예외에 원격 오류 원문이나 내부 원인을 남기지 않는다. */
    private static void assertFailure(Runnable action, Reason expected) {
        var error = catchThrowableOfType(ModelStreamException.class, action::run);
        assertThat(error).isNotNull();
        assertThat(error.getReason()).isEqualTo(expected);
        assertThat(error.getMessage()).isEqualTo(expected.name());
        assertThat(error.getCause()).isNull();
    }
}
