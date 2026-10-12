package com.example.magnavi_springserver.positioning.infrastructure;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.ClientResponseObserver;

import com.example.magnavi_springserver.positioning.application.ModelStream;
import com.example.magnavi_springserver.positioning.application.ModelStreamClient;
import com.example.magnavi_springserver.positioning.application.ModelStreamException;
import com.example.magnavi_springserver.positioning.application.ModelStreamException.Reason;
import com.example.magnavi_springserver.positioning.domain.ModelEvent;
import com.example.magnavi_springserver.positioning.domain.SensorSample;
import com.example.magnavi_springserver.positioning.grpc.v1.PositioningServiceGrpc;
import com.example.magnavi_springserver.positioning.grpc.v1.StreamEvent;
import com.example.magnavi_springserver.positioning.grpc.v1.StreamRequest;

/** 재사용 채널 위에 측정별 스트림을 만든다. DB 조회·모델 계산·앱 인증은 담당하지 않는다. */
public final class GrpcModelStreamClient implements ModelStreamClient, AutoCloseable {
    private final ManagedChannel channel;
    private final PositioningServiceGrpc.PositioningServiceStub stub;
    private final ModelGrpcProperties properties;
    private final Map<String, SessionStream> streams = new ConcurrentHashMap<>();
    private final ScheduledExecutorService watchdog;
    private boolean closed;

    /** 전달받은 채널의 소유권을 받아 클라이언트 종료 시 함께 닫는다. */
    public GrpcModelStreamClient(ManagedChannel channel, ModelGrpcProperties properties) {
        this.channel = channel;
        this.properties = properties;
        this.stub = PositioningServiceGrpc.newStub(channel)
                .withMaxInboundMessageSize(properties.maxMessageBytes())
                .withMaxOutboundMessageSize(properties.maxMessageBytes());
        watchdog = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "model-stream-watchdog");
            thread.setDaemon(true);
            return thread;
        });
        watchdog.scheduleWithFixedDelay(this::checkTimeouts, 50, 50, TimeUnit.MILLISECONDS);
    }

    /** 활성 연결 한도를 검사하고 재연결에도 재사용하지 않는 새로운 세션 ID를 만든다. */
    @Override
    public synchronized ModelStream open(String modelKey, String modelVersion) {
        if (closed) throw new ModelStreamException(Reason.CLOSED);
        if (!properties.enabled()) throw new ModelStreamException(Reason.NOT_CONFIGURED);
        if (!ModelProtocol.validIdentifier(modelKey, 128) || !ModelProtocol.validIdentifier(modelVersion, 64)) {
            throw new ModelStreamException(Reason.INVALID_INPUT);
        }
        if (streams.size() >= properties.maxStreams()) throw new ModelStreamException(Reason.OVERLOADED);
        var stream = new SessionStream(UUID.randomUUID().toString(), modelKey, modelVersion);
        streams.put(stream.sessionId(), stream);
        stream.start();
        return stream;
    }

    /** 읽는 호출자가 없어도 준비·응답·유휴 한도를 넘긴 스트림을 정리한다. */
    private void checkTimeouts() {
        long now = System.nanoTime();
        streams.values().forEach(stream -> stream.checkTimeout(now));
    }

    /** 모든 호출을 취소한 뒤 감시 작업과 재사용 채널을 제한된 시간 안에 정리한다. */
    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        streams.values().forEach(SessionStream::close);
        watchdog.shutdownNow();
        channel.shutdownNow();
        try {
            channel.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    /** 원격 오류 설명·원인을 버리고 앱 계층에서 구분할 수 있는 종류만 남긴다. */
    private static Reason failureReason(Throwable error) {
        return switch (Status.fromThrowable(error).getCode()) {
            case INVALID_ARGUMENT, FAILED_PRECONDITION, OUT_OF_RANGE -> Reason.INVALID_INPUT;
            case UNAVAILABLE -> Reason.UNAVAILABLE;
            case RESOURCE_EXHAUSTED -> Reason.OVERLOADED;
            case DEADLINE_EXCEEDED -> Reason.TIMED_OUT;
            case CANCELLED -> Reason.CANCELLED;
            default -> Reason.INTERNAL;
        };
    }

    /** 응답 순서와 최대 대기 시간을 확인할 최소 정보다. 센서 원문은 보관하지 않는다. */
    private record PendingSample(long sequence, long queuedAt) { }

    /** 느린 소비자가 결과를 무한히 보관하지 않도록 수신 시각을 함께 관리한다. */
    private record BufferedEvent(ModelEvent event, long receivedAt) { }

    /** 한 스트림의 상태만 잠근다. 모든 송신·종료를 직렬화하고 읽기는 wait로 잠금을 놓는다. */
    private final class SessionStream implements ModelStream, ClientResponseObserver<StreamRequest, StreamEvent> {
        private final String id;
        private final String modelKey;
        private final String modelVersion;
        private final ArrayDeque<StreamRequest> outbound = new ArrayDeque<>();
        private final ArrayDeque<PendingSample> pending = new ArrayDeque<>();
        private final ArrayDeque<BufferedEvent> inbound = new ArrayDeque<>();
        private final long startedAt = System.nanoTime();
        private long lastActivity = startedAt;
        private long lastAccepted;
        private long lastSent;
        private long lastElapsed = -1;
        private ClientCallStreamObserver<StreamRequest> request;
        private boolean callStarted;
        private boolean ready;
        private boolean draining;
        private Reason terminal;

        /** 전역 사용자 버퍼 대신 이 연결만의 대기열과 모델 식별자를 만든다. */
        private SessionStream(String id, String modelKey, String modelVersion) {
            this.id = id;
            this.modelKey = modelKey;
            this.modelVersion = modelVersion;
        }

        /** gRPC 시작 콜백에서는 설정만 수행하고, 호출이 시작된 뒤 첫 메시지를 보낸다. */
        private synchronized void start() {
            outbound.add(ModelProtocol.start(id, modelKey, modelVersion, properties.sensorProfileId()));
            try {
                stub.predictStream(this);
                callStarted = true;
                flush();
            } catch (RuntimeException error) {
                finish(failureReason(error), true);
            }
        }

        /** 자동 수신을 끄고 버퍼에 들어갈 개수만 요청한다. 소비할 때 한 자리씩 보충한다. */
        @Override
        public synchronized void beforeStart(ClientCallStreamObserver<StreamRequest> requestStream) {
            request = requestStream;
            request.disableAutoRequestWithInitial(properties.maxBufferedEvents());
            request.setOnReadyHandler(this::flush);
        }

        /** 채널의 쓰기 가능 상태를 확인하며 순서대로 보낸다. 동시에 onNext를 호출하지 않는다. */
        private synchronized void flush() {
            if (!callStarted || terminal != null || draining) return;
            draining = true;
            try {
                while (terminal == null && !outbound.isEmpty() && request.isReady()) {
                    StreamRequest next = outbound.removeFirst();
                    if (next.hasSample()) lastSent = next.getSample().getSequence();
                    request.onNext(next);
                }
            } catch (RuntimeException error) {
                finish(failureReason(error), true);
            } finally {
                draining = false;
            }
        }

        /** 연결 번호만 공개하고 회원 식별자와 혼용하지 않는다. */
        @Override
        public String sessionId() { return id; }

        /** 첫 샘플은 1·0ns이며 이후 순번은 연속, 경과 시간은 증가해야 한다. */
        @Override
        public synchronized void send(SensorSample sample) {
            checkOpen();
            if (!ready) throw new ModelStreamException(Reason.NOT_READY);
            if (sample == null || lastAccepted == Long.MAX_VALUE || sample.sequence() != lastAccepted + 1
                    || (lastAccepted == 0 && sample.elapsedNanos() != 0)
                    || (lastAccepted > 0 && sample.elapsedNanos() <= lastElapsed)) {
                finish(Reason.INVALID_INPUT, true);
                throw new ModelStreamException(Reason.INVALID_INPUT);
            }
            if (pending.size() >= properties.maxPendingSamples()) {
                finish(Reason.OVERLOADED, true);
                throw new ModelStreamException(Reason.OVERLOADED);
            }
            lastAccepted = sample.sequence();
            lastElapsed = sample.elapsedNanos();
            lastActivity = System.nanoTime();
            pending.addLast(new PendingSample(sample.sequence(), lastActivity));
            outbound.addLast(ModelProtocol.sample(sample));
            flush();
            checkOpen();
        }

        /** 수신 콜백은 검증과 메모리 큐 추가만 한다. DB 조회나 앱 전송을 실행하지 않는다. */
        @Override
        public synchronized void onNext(StreamEvent event) {
            if (terminal != null) return;
            try {
                if (!event.getSessionId().equals(id)) throw new ModelStreamException(Reason.PROTOCOL_ERROR);
                ModelEvent decoded;
                if (!ready) {
                    decoded = ModelProtocol.ready(event, modelKey, modelVersion, properties.sensorProfileId());
                    ready = true;
                } else {
                    PendingSample expected = pending.peekFirst();
                    if (expected == null || expected.sequence() > lastSent) {
                        throw new ModelStreamException(Reason.PROTOCOL_ERROR);
                    }
                    decoded = ModelProtocol.result(event, expected.sequence(), modelKey, modelVersion);
                    pending.removeFirst();
                }
                if (inbound.size() >= properties.maxBufferedEvents()) {
                    throw new ModelStreamException(Reason.OVERLOADED);
                }
                lastActivity = System.nanoTime();
                inbound.addLast(new BufferedEvent(decoded, lastActivity));
                notifyAll();
            } catch (ModelStreamException error) {
                finish(error.getReason(), true);
            } catch (RuntimeException ignored) {
                finish(Reason.PROTOCOL_ERROR, true);
            }
        }

        /** 외부 오류를 안전한 내부 종류로 바꾸고 모든 대기자를 깨운다. */
        @Override
        public synchronized void onError(Throwable error) { finish(failureReason(error), false); }

        /** v1 스트림은 클라이언트 취소까지 유지한다. 원격 조기 종료는 연결 실패로 다룬다. */
        @Override
        public synchronized void onCompleted() { finish(Reason.UNAVAILABLE, false); }

        /** 큐에서 한 결과를 꺼낸 뒤 수신 한도를 한 개 보충한다. DB 조회는 호출자 책임이다. */
        @Override
        public synchronized Optional<ModelEvent> next(Duration wait) {
            if (wait == null || wait.isNegative() || wait.compareTo(Duration.ofMinutes(1)) > 0) {
                throw new ModelStreamException(Reason.INVALID_INPUT);
            }
            long deadline = System.nanoTime() + wait.toNanos();
            while (true) {
                checkOpen();
                if (!inbound.isEmpty()) {
                    ModelEvent event = inbound.removeFirst().event();
                    request.request(1);
                    return Optional.of(event);
                }
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) return Optional.empty();
                try {
                    TimeUnit.NANOSECONDS.timedWait(this, remaining);
                } catch (InterruptedException ignored) {
                    finish(Reason.CANCELLED, true);
                    Thread.currentThread().interrupt();
                    throw new ModelStreamException(Reason.CANCELLED);
                }
            }
        }

        /** 종료 원인을 일관되게 반환해 이전 연결의 결과가 다시 사용되지 않게 한다. */
        @Override
        public synchronized void checkOpen() {
            if (terminal != null) throw new ModelStreamException(terminal);
        }

        /** 스트림 전체에 짧은 deadline을 걸지 않고 준비·미응답·소비 지연을 각각 확인한다. */
        private synchronized void checkTimeout(long now) {
            if (terminal != null) return;
            // 준비 중에는 start-timeout만 적용한다. 더 짧은 idle-timeout이 준비 시간을 덮지 않는다.
            if (!ready) {
                if (now - startedAt >= properties.startTimeout().toNanos()) finish(Reason.TIMED_OUT, true);
                return;
            }
            if (!inbound.isEmpty()
                    && now - inbound.peekFirst().receivedAt() >= properties.responseTimeout().toNanos()) {
                finish(Reason.OVERLOADED, true);
            } else if (!pending.isEmpty()
                    && now - pending.peekFirst().queuedAt() >= properties.responseTimeout().toNanos()) {
                finish(Reason.TIMED_OUT, true);
            } else if (now - lastActivity >= properties.idleTimeout().toNanos()) {
                finish(Reason.TIMED_OUT, true);
            }
        }

        /** 정상적인 사용자 종료도 gRPC 취소로 전파하고 센서를 재전송하지 않는다. */
        @Override
        public synchronized void close() { finish(Reason.CANCELLED, true); }

        /** 어떤 경로로 종료해도 한 번만 자원을 반환한다. 취소 후 observer 메서드는 호출하지 않는다. */
        private void finish(Reason reason, boolean cancel) {
            if (terminal != null) return;
            terminal = reason;
            outbound.clear();
            pending.clear();
            inbound.clear();
            streams.remove(id, this);
            notifyAll();
            if (cancel && request != null) request.cancel(reason.name(), null);
        }
    }
}
