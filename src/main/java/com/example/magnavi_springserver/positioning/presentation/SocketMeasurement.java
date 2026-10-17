package com.example.magnavi_springserver.positioning.presentation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;

import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import com.example.magnavi_springserver.positioning.application.ModelStreamException;
import com.example.magnavi_springserver.positioning.application.PositioningConnection;
import com.example.magnavi_springserver.positioning.application.PositioningSessionService;
import com.example.magnavi_springserver.positioning.domain.ModelEvent;
import com.example.magnavi_springserver.positioning.domain.PositioningException;
import com.example.magnavi_springserver.positioning.domain.PositioningException.Reason;
import com.example.magnavi_springserver.positioning.domain.PositioningSession;
import com.example.magnavi_springserver.positioning.infrastructure.ModelGrpcProperties;
import com.example.magnavi_springserver.positioning.infrastructure.PositioningWebSocketProperties;

/** 한 앱 연결과 모델 호출을 연결한다. 결과 읽기·DB 조회·앱 쓰기는 이 연결의 작업자만 수행한다. */
final class SocketMeasurement implements Runnable {
    private final WebSocketSession rawSocket;
    private final ConcurrentWebSocketSessionDecorator socket;
    private final PositioningSession state;
    private final PositioningSessionService service;
    private final PositioningWebSocketProperties limits;
    private final ModelGrpcProperties model;
    private final PositioningMessageCodec codec;
    private final Clock clock;
    private final Runnable released;
    private final Object resources = new Object();
    private volatile PositioningConnection connection;
    private volatile PositioningMessageCodec.Start start;
    private volatile Thread reader;
    private volatile Reason terminalReason;
    private volatile boolean normalStop;
    private volatile boolean transportBroken;

    /** 모델 호출 전의 대기 연결도 한도와 만료 검사 대상에 포함한다. */
    SocketMeasurement(WebSocketSession socket, PositioningSession state, PositioningSessionService service,
                      PositioningWebSocketProperties limits, ModelGrpcProperties model,
                      PositioningMessageCodec codec, Clock clock, Runnable released) {
        this.rawSocket = socket;
        this.socket = new ConcurrentWebSocketSessionDecorator(socket, (int) limits.sendTimeout().toMillis(),
                limits.sendBufferBytes());
        this.state = state;
        this.service = service;
        this.limits = limits;
        this.model = model;
        this.codec = codec;
        this.clock = clock;
        this.released = released;
    }

    /** 메시지 순서와 규격을 검사한 뒤 해당 측정의 모델 스트림으로만 전달한다. */
    void receive(String payload) {
        if (state.isClosed()) return;
        try {
            state.acceptMessage(clock.instant());
            switch (codec.decode(payload)) {
                case PositioningMessageCodec.Start request -> openModel(request);
                case PositioningMessageCodec.Sensor sensor -> {
                    state.requireStreaming();
                    connection.send(sensor.sample());
                }
                case PositioningMessageCodec.Stop ignored -> finish(null, true, false);
            }
        } catch (PositioningException error) {
            finish(error.reason(), false, false);
        } catch (ModelStreamException error) {
            finish(codec.modelFailure(error), false, false);
        } catch (RuntimeException ignored) {
            finish(Reason.INTERNAL, false, false);
        }
    }

    /** 종료와 모델 연결 생성이 겹쳐도 뒤늦게 생긴 호출을 즉시 취소한다. */
    private void openModel(PositioningMessageCodec.Start request) {
        state.start();
        if (!model.enabled()) throw new PositioningException(Reason.NOT_CONFIGURED);
        if (!request.sensorProfileId().equals(model.sensorProfileId())) {
            throw new PositioningException(Reason.INVALID_MESSAGE);
        }
        PositioningConnection opened = service.open(request.modelKey(), request.modelVersion());
        synchronized (resources) {
            if (state.isClosed()) {
                opened.close();
                return;
            }
            start = request;
            connection = opened;
            resources.notifyAll();
        }
    }

    /** gRPC 콜백 밖에서만 장소 조회와 네트워크 송신을 실행한다. 작업자 수는 전체 연결 한도로 제한한다. */
    @Override
    public void run() {
        reader = Thread.currentThread();
        try {
            while (!state.isClosed()) {
                checkTimeout();
                if (state.isClosed()) break;
                PositioningConnection current = connection;
                if (current == null) {
                    synchronized (resources) {
                        if (connection == null && !state.isClosed()) resources.wait(100);
                    }
                    continue;
                }
                var result = current.next(Duration.ofMillis(100));
                checkTimeout();
                if (result.isPresent() && !state.isClosed()) {
                    if (result.get().event() instanceof ModelEvent.Ready && !state.ready()) break;
                    if (!state.isClosed()) send(codec.result(current.sessionId(), start, result.get()));
                }
            }
        } catch (InterruptedException ignored) {
            if (!state.isClosed()) finish(Reason.INTERNAL, false, true);
        } catch (ModelStreamException error) {
            finish(codec.modelFailure(error), false, false);
        } catch (PositioningException error) {
            finish(error.reason(), false, false);
        } catch (IOException | RuntimeException ignored) {
            finish(Reason.INTERNAL, false, true);
        } finally {
            finish(null, false, false);
            // 종료 알림 쓰기를 시도하기 전에 자기 자신에게 전달된 취소 신호를 지운다.
            Thread.interrupted();
            closeTransport();
            released.run();
        }
    }

    /** 입력·DB 조회·앱 송신이 지연돼도 감시 스레드가 모델 취소를 먼저 수행한다. */
    void checkTimeout() {
        Reason reason = state.timeout(clock.instant(), limits.startTimeout(), limits.idleTimeout());
        if (reason != null) finish(reason, false, false);
        if (socket.getTimeSinceSendStarted() > limits.sendTimeout().toMillis()) {
            finish(Reason.OVERLOADED, false, true);
        }
    }

    /** 감시 스레드에서는 네트워크 쓰기를 하지 않는다. 실제 종료는 연결 전용 작업자가 맡는다. */
    void finish(Reason reason, boolean stopped, boolean broken) {
        synchronized (resources) {
            if (!state.close()) return;
            terminalReason = reason;
            normalStop = stopped;
            transportBroken = broken;
            if (connection != null) connection.close();
            resources.notifyAll();
            if (reader != null && reader != Thread.currentThread()) reader.interrupt();
        }
    }

    /** 실행 슬롯을 얻지 못한 경우 모델 호출 없이 현재 요청 스레드에서 소켓만 정리한다. */
    void rejectBeforeRun() {
        finish(Reason.OVERLOADED, false, false);
        closeTransport();
        released.run();
    }

    /** 송신이 느려도 gRPC 콜백이나 다른 연결의 결과 처리 스레드를 붙잡지 않는다. */
    private void send(String text) throws IOException {
        if (text.getBytes(StandardCharsets.UTF_8).length > limits.sendBufferBytes()) {
            throw new PositioningException(Reason.OVERLOADED);
        }
        socket.sendMessage(new TextMessage(text));
    }

    /** 종료 알림은 가능한 경우에만 전송하며 실패하더라도 슬롯과 모델 자원은 반환한다. */
    private void closeTransport() {
        try {
            if (rawSocket.isOpen() && !transportBroken) {
                if (terminalReason != null) send(codec.error(terminalReason));
                else if (normalStop) send(codec.stopped());
            }
        } catch (IOException | RuntimeException ignored) {
            // 전송할 수 없는 연결의 오류 원문을 운영 로그에 남기지 않는다.
        } finally {
            try { rawSocket.close(closeStatus()); }
            catch (IOException | RuntimeException ignored) { }
        }
    }

    /** 앱 오류·서버 오류·일시적 과부하를 WebSocket 종료 코드로 구분한다. */
    private CloseStatus closeStatus() {
        if (terminalReason == null) return CloseStatus.NORMAL;
        return switch (terminalReason) {
            case OVERLOADED, RATE_LIMITED, NOT_CONFIGURED -> new CloseStatus(1013, terminalReason.name());
            case MODEL_UNAVAILABLE, MODEL_PROTOCOL_ERROR, MODEL_TIMEOUT, INTERNAL ->
                    new CloseStatus(1011, terminalReason.name());
            default -> new CloseStatus(1008, terminalReason.name());
        };
    }
}
