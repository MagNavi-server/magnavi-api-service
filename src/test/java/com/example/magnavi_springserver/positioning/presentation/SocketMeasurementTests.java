package com.example.magnavi_springserver.positioning.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import com.example.magnavi_springserver.positioning.application.ModelStreamException;
import com.example.magnavi_springserver.positioning.application.PositioningConnection;
import com.example.magnavi_springserver.positioning.application.PositioningResult;
import com.example.magnavi_springserver.positioning.application.PositioningSessionService;
import com.example.magnavi_springserver.positioning.domain.ModelEvent;
import com.example.magnavi_springserver.positioning.domain.PositioningException.Reason;
import com.example.magnavi_springserver.positioning.domain.PositioningSession;
import com.example.magnavi_springserver.positioning.infrastructure.ModelGrpcProperties;
import com.example.magnavi_springserver.positioning.infrastructure.PositioningWebSocketProperties;

/** 실제 네트워크에서 재현하기 어려운 종료 경합·느린 소비자를 제어 가능한 대역으로 검증한다. */
class SocketMeasurementTests {
    private static final String START = """
            {"type":"start","contractVersion":1,"modelKey":"a","modelVersion":"v1","sensorProfileId":"synthetic-v1"}
            """;
    private static final String SENSOR = """
            {"type":"sensor","sequence":1,"elapsedNanos":0,"magnetic":{"x":0,"y":0,"z":0},"orientation":{"x":0,"y":0,"z":0}}
            """;

    /** 준비 전 센서는 모델에 보내지 않고 같은 연결에 명확한 오류를 돌려준다. */
    @Test void refusesSensorsBeforeReady() throws Exception {
        try (var test = new Fixture(100, 65536)) {
            test.measurement.receive(START);
            test.measurement.receive(SENSOR);
            test.runAndAwait();
            assertThat(test.sent).singleElement().asString().contains("NOT_READY");
            verify(test.connection, never()).send(any());
            verify(test.connection).close();
        }
    }

    /** 중복 시작은 기존 모델을 취소하며 두 번째 스트림을 만들지 않는다. */
    @Test void refusesDuplicateStart() throws Exception {
        try (var test = new Fixture(100, 65536)) {
            test.measurement.receive(START);
            test.measurement.receive(START);
            test.runAndAwait();
            verify(test.service, times(1)).open("a", "v1");
            verify(test.connection).close();
            assertThat(test.sent).singleElement().asString().contains("INVALID_MESSAGE");
        }
    }

    /** 서버 내부 주소나 예외 원문 대신 안정된 모델 오류 코드만 노출한다. */
    @Test void hidesModelFailureDetails() throws Exception {
        try (var test = new Fixture(100, 65536)) {
            when(test.service.open("a", "v1")).thenThrow(new ModelStreamException(ModelStreamException.Reason.UNAVAILABLE));
            test.measurement.receive(START);
            test.runAndAwait();
            assertThat(test.sent).singleElement().asString().contains("MODEL_UNAVAILABLE");
        }
    }

    /** 모델 비활성 상태에는 실제 원격 연결을 시도하지 않는다. */
    @Test void refusesUnconfiguredModel() throws Exception {
        try (var test = new Fixture(100, 65536)) {
            when(test.model.enabled()).thenReturn(false);
            test.measurement.receive(START);
            test.runAndAwait();
            verifyNoInteractions(test.service);
            assertThat(test.sent).singleElement().asString().contains("NOT_CONFIGURED");
        }
    }

    /** 입력 한도를 넘긴 측정만 종료하고 센서를 전달하지 않는다. */
    @Test void cancelsExcessInput() throws Exception {
        try (var test = new Fixture(1, 65536)) {
            test.measurement.receive(START);
            test.measurement.receive(SENSOR);
            test.runAndAwait();
            assertThat(test.sent).singleElement().asString().contains("RATE_LIMITED");
            verify(test.connection).close();
        }
    }

    /** 연결 생성 도중 종료되면 뒤늦게 반환된 모델 핸들도 반드시 취소한다. */
    @Test void cancelsConnectionOpenedAfterSocketClosed() throws Exception {
        try (var test = new Fixture(100, 65536)) {
            var entered = new CountDownLatch(1);
            var resume = new CountDownLatch(1);
            when(test.service.open("a", "v1")).thenAnswer(ignored -> {
                entered.countDown(); awaitDespiteInterrupt(resume); return test.connection;
            });
            Thread opening = Thread.startVirtualThread(() -> test.measurement.receive(START));
            try {
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                test.measurement.finish(null, false, true);
                test.runAndAwait();
            } finally { resume.countDown(); opening.join(2000); }
            assertThat(opening.isAlive()).isFalse();
            verify(test.connection).close();
            assertThat(test.sent).isEmpty();
        }
    }

    /** 중단 신호를 즉시 처리하지 못하는 DB 조회가 있어도 모델은 취소하고 늦은 결과는 버린다. */
    @Test void dropsLateResultAndRetainsSlotUntilWorkerEnds() throws Exception {
        try (var test = new Fixture(100, 65536)) {
            var entered = new CountDownLatch(1);
            var resume = new CountDownLatch(1);
            when(test.connection.next(any())).thenAnswer(ignored -> {
                entered.countDown(); awaitDespiteInterrupt(resume); return Optional.of(prediction());
            });
            test.measurement.receive(START);
            test.startWorker();
            try {
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                test.measurement.finish(Reason.TOKEN_EXPIRED, false, false);
                verify(test.connection).close();
                assertThat(test.releaseCount).hasValue(0);
            } finally { resume.countDown(); }
            test.awaitWorker();
            assertThat(test.sent).singleElement().asString().contains("TOKEN_EXPIRED").doesNotContain("prediction");
            assertThat(test.releaseCount).hasValue(1);
        }
    }

    /** 느린 앱 쓰기를 기다리느라 공용 감시 스레드가 멈추지 않고 모델 취소부터 수행한다. */
    @Test void watchdogCancelsBlockedWriterWithoutBlockingOtherConnections() throws Exception {
        try (var slow = new Fixture(100, 65536); var other = new Fixture(100, 65536)) {
            var entered = new CountDownLatch(1);
            var resume = new CountDownLatch(1);
            doAnswer(ignored -> { entered.countDown(); awaitDespiteInterrupt(resume); return null; })
                    .when(slow.socket).sendMessage(any());
            slow.results.add(new PositioningResult(new ModelEvent.Ready(16), PositioningResult.MappingStatus.NOT_REQUESTED, null));
            slow.measurement.receive(START);
            slow.startWorker();
            try {
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                Thread.sleep(150); // 실제 송신 경과 시간은 decorator의 단조 증가 시계가 아닌 벽시계로 측정된다.
                assertTimeoutPreemptively(Duration.ofMillis(500), slow.measurement::checkTimeout);
                verify(slow.connection).close();
                assertThat(slow.releaseCount).hasValue(0);
                other.measurement.receive("{\"type\":\"stop\"}");
                other.runAndAwait();
                assertThat(other.sent).containsExactly("{\"type\":\"stopped\"}");
            } finally { resume.countDown(); }
            slow.awaitWorker();
        }
    }

    /** 한글 결과의 바이트 수가 제한을 넘으면 문자 수가 작아도 전송하지 않는다. */
    @Test void boundsOutgoingUtf8Bytes() throws Exception {
        try (var test = new Fixture(100, 1024)) {
            test.results.add(prediction());
            test.measurement.receive(START);
            test.runAndAwait();
            assertThat(test.sent).singleElement().asString().contains("OVERLOADED").doesNotContain("prediction");
            verify(test.connection).close();
        }
    }

    /** 센서 원문 대신 합성 후보로 UTF-8 송신 한도를 넘기는 결과를 만든다. */
    private static PositioningResult prediction() {
        var event = new ModelEvent.Prediction(16, "a", "v1", "가".repeat(300), 0.8,
                List.of(new ModelEvent.Candidate("나".repeat(300), 0.8)));
        return new PositioningResult(event, PositioningResult.MappingStatus.NOT_FOUND, null);
    }

    /** 종료 신호에 늦게 반응하는 외부 IO를 모사하되 테스트 자체는 3초를 넘기지 않는다. */
    private static void awaitDespiteInterrupt(CountDownLatch resume) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (resume.getCount() != 0 && System.nanoTime() < deadline) {
            try { resume.await(20, TimeUnit.MILLISECONDS); }
            catch (InterruptedException ignored) { }
        }
        assertThat(resume.getCount()).isZero();
    }

    /** 비밀값·실제 모델·DB 없이 연결 작업자의 수명만 제어한다. */
    private static final class Fixture implements AutoCloseable {
        final WebSocketSession socket = mock(WebSocketSession.class);
        final PositioningSessionService service = mock(PositioningSessionService.class);
        final PositioningConnection connection = mock(PositioningConnection.class);
        final ModelGrpcProperties model = mock(ModelGrpcProperties.class);
        final List<String> sent = new CopyOnWriteArrayList<>();
        final LinkedBlockingQueue<PositioningResult> results = new LinkedBlockingQueue<>(4);
        final AtomicInteger releaseCount = new AtomicInteger();
        final SocketMeasurement measurement;
        private Thread worker;

        /** 열린 소켓·고정 시각·제한된 결과 큐를 준비한다. */
        Fixture(int rate, int buffer) throws Exception {
            Instant now = Instant.parse("2026-10-15T00:00:00Z");
            var limits = new PositioningWebSocketProperties(true, 2, 2, 4096, rate, buffer,
                    Duration.ofMillis(100), Duration.ofSeconds(10), Duration.ofSeconds(30), List.of());
            when(socket.isOpen()).thenReturn(true);
            doAnswer(call -> { sent.add(((TextMessage) call.getArgument(0)).getPayload()); return null; })
                    .when(socket).sendMessage(any());
            when(model.enabled()).thenReturn(true);
            when(model.sensorProfileId()).thenReturn("synthetic-v1");
            when(service.open("a", "v1")).thenReturn(connection);
            when(connection.sessionId()).thenReturn("synthetic-session");
            when(connection.next(any())).thenAnswer(ignored -> Optional.ofNullable(results.poll(100, TimeUnit.MILLISECONDS)));
            measurement = new SocketMeasurement(socket, new PositioningSession(1, now.plusSeconds(60), now, rate),
                    service, limits, model, new PositioningMessageCodec(), Clock.fixed(now, ZoneOffset.UTC), releaseCount::incrementAndGet);
        }

        /** 작업자가 필요할 때만 시작해 종료와 실행의 경합을 제어한다. */
        void startWorker() { worker = Thread.startVirtualThread(measurement); }

        /** 실패 경로의 오류 응답과 자원 반납까지 기다린다. */
        void runAndAwait() throws Exception { startWorker(); awaitWorker(); }

        /** 실행되지 않는 종료와 무한 대기를 테스트 실패로 드러낸다. */
        void awaitWorker() throws Exception {
            worker.join(2500);
            assertThat(worker.isAlive()).isFalse();
            assertThat(releaseCount).hasValue(1);
        }

        /** 테스트 실패 때도 남은 작업자가 다음 테스트에 영향을 주지 않게 정리한다. */
        @Override public void close() throws Exception {
            measurement.finish(null, false, true);
            if (worker != null) worker.join(3500);
        }
    }
}
