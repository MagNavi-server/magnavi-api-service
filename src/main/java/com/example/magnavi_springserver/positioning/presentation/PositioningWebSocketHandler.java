package com.example.magnavi_springserver.positioning.presentation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.adapter.standard.StandardWebSocketSession;

import com.example.magnavi_springserver.positioning.application.PositioningSessionService;
import com.example.magnavi_springserver.positioning.domain.PositioningException;
import com.example.magnavi_springserver.positioning.domain.PositioningException.Reason;
import com.example.magnavi_springserver.positioning.domain.PositioningSession;
import com.example.magnavi_springserver.positioning.infrastructure.InMemorySessionRegistry;
import com.example.magnavi_springserver.positioning.infrastructure.ModelGrpcProperties;
import com.example.magnavi_springserver.positioning.infrastructure.PositioningWebSocketProperties;

/** 인증된 앱 연결을 기존 gRPC 기능에 연결하고 종료 시 메모리·작업자를 반납한다. */
public final class PositioningWebSocketHandler extends TextWebSocketHandler implements AutoCloseable {
    private final PositioningSessionService service;
    private final PositioningWebSocketProperties limits;
    private final ModelGrpcProperties model;
    private final Clock clock;
    private final PositioningMessageCodec codec = new PositioningMessageCodec();
    private final InMemorySessionRegistry registry;
    private final Map<String, SocketMeasurement> measurements = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor readers;
    private final ScheduledExecutorService watchdog;
    private boolean closed;

    /** 연결 수와 같은 최대 작업자 수를 사용하고 실행 대기열을 만들지 않는다. */
    public PositioningWebSocketHandler(PositioningSessionService service, PositioningWebSocketProperties limits,
                                       ModelGrpcProperties model, Clock clock) {
        this.service = service;
        this.limits = limits;
        this.model = model;
        this.clock = clock;
        registry = new InMemorySessionRegistry(limits.maxConnections(), limits.maxConnectionsPerMember());
        readers = new ThreadPoolExecutor(0, limits.maxConnections(), 30, TimeUnit.SECONDS,
                new SynchronousQueue<>(), Thread.ofPlatform().daemon().name("positioning-reader-", 0).factory());
        watchdog = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon().name("positioning-watchdog").factory());
        watchdog.scheduleWithFixedDelay(this::checkTimeouts, 100, 100, TimeUnit.MILLISECONDS);
    }

    /** 인증 정보·연결 한도를 확인한 뒤에만 연결 전용 결과 처리 작업자를 할당한다. */
    @Override
    public synchronized void afterConnectionEstablished(WebSocketSession socket) throws IOException {
        if (closed) { socket.close(CloseStatus.GOING_AWAY); return; }
        Object member = socket.getAttributes().get(PositioningHandshakeInterceptor.MEMBER);
        Object expiry = socket.getAttributes().get(PositioningHandshakeInterceptor.EXPIRY);
        if (!(member instanceof Long memberId) || !(expiry instanceof Instant expiresAt)) {
            socket.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        try {
            var state = new PositioningSession(memberId, expiresAt, clock.instant(), limits.maxMessagesPerSecond());
            registry.reserve(socket.getId(), memberId);
            socket.setTextMessageSizeLimit(limits.maxMessageBytes());
            socket.setBinaryMessageSizeLimit(limits.maxMessageBytes());
            // 현재 내장 Tomcat의 단일 동기 송신도 제한 시간 안에 끝나도록 한다.
            if (socket instanceof StandardWebSocketSession standard) {
                standard.getNativeSession().getUserProperties().put(
                        "org.apache.tomcat.websocket.BLOCKING_SEND_TIMEOUT", limits.sendTimeout().toMillis());
            }
            var measurement = new SocketMeasurement(socket, state, service, limits, model, codec, clock,
                    () -> release(socket.getId()));
            measurements.put(socket.getId(), measurement);
            try { readers.execute(measurement); }
            catch (RejectedExecutionException ignored) { measurement.rejectBeforeRun(); }
        } catch (PositioningException error) {
            socket.close(new CloseStatus(error.reason() == Reason.OVERLOADED ? 1013 : 1008, error.reason().name()));
        } catch (RuntimeException ignored) {
            release(socket.getId());
            socket.close(CloseStatus.SERVER_ERROR);
        }
    }

    /** 크기 제한 후 타입·필수값·순서 검사는 해당 측정 연결에 맡긴다. */
    @Override
    protected void handleTextMessage(WebSocketSession socket, TextMessage message) {
        SocketMeasurement measurement = measurements.get(socket.getId());
        if (measurement == null) return;
        if (!message.isLast() || message.getPayload().getBytes(StandardCharsets.UTF_8).length > limits.maxMessageBytes()) {
            measurement.finish(Reason.INVALID_MESSAGE, false, false);
            return;
        }
        measurement.receive(message.getPayload());
    }

    /** v1은 JSON 텍스트만 지원하므로 바이너리 요청을 다른 규격으로 추측하지 않는다. */
    @Override
    protected void handleBinaryMessage(WebSocketSession socket, BinaryMessage message) {
        SocketMeasurement measurement = measurements.get(socket.getId());
        if (measurement != null) measurement.finish(Reason.INVALID_MESSAGE, false, false);
    }

    /** 끊어진 앱으로 오류를 다시 쓰지 않고 모델 호출부터 취소한다. */
    @Override
    public void handleTransportError(WebSocketSession socket, Throwable exception) {
        SocketMeasurement measurement = measurements.get(socket.getId());
        if (measurement != null) measurement.finish(Reason.INTERNAL, false, true);
    }

    /** 동일 회원의 새 연결에 영향을 주지 않고 이 소켓의 측정만 닫는다. */
    @Override
    public void afterConnectionClosed(WebSocketSession socket, CloseStatus status) {
        SocketMeasurement measurement = measurements.get(socket.getId());
        if (measurement != null) measurement.finish(null, false, true);
    }

    /** 하나의 연결 오류가 감시 작업 전체를 중단시키지 않도록 각각 처리한다. */
    private void checkTimeouts() {
        measurements.values().forEach(measurement -> {
            try { measurement.checkTimeout(); }
            catch (RuntimeException ignored) { measurement.finish(Reason.INTERNAL, false, true); }
        });
    }

    /** 소켓 작업자가 실제 종료된 뒤 슬롯을 반환하여 느린 종료가 무제한 작업자를 만들지 못하게 한다. */
    private void release(String connectionId) {
        measurements.remove(connectionId);
        registry.release(connectionId);
    }

    /** 원문·사용자별 상세 상태 없이 현재 연결 수만 확인한다. */
    public int activeConnections() { return registry.size(); }

    /** 서버 종료 시 모든 스트림을 취소하고 실행 자원을 제한된 시간 동안 정리한다. */
    @Override
    public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
            measurements.values().forEach(measurement -> measurement.finish(null, false, true));
        }
        watchdog.shutdownNow();
        readers.shutdown();
        try {
            if (!readers.awaitTermination(limits.sendTimeout().toMillis() + 1000, TimeUnit.MILLISECONDS)) {
                readers.shutdownNow();
            }
        } catch (InterruptedException ignored) {
            readers.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
