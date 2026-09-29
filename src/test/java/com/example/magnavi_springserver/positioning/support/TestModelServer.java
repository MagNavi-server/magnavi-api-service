package com.example.magnavi_springserver.positioning.support;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;

import io.grpc.Server;
import io.grpc.Status;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;

import com.example.magnavi_springserver.positioning.grpc.v1.Candidate;
import com.example.magnavi_springserver.positioning.grpc.v1.PositioningServiceGrpc;
import com.example.magnavi_springserver.positioning.grpc.v1.Prediction;
import com.example.magnavi_springserver.positioning.grpc.v1.StartMeasurement;
import com.example.magnavi_springserver.positioning.grpc.v1.StreamEvent;
import com.example.magnavi_springserver.positioning.grpc.v1.StreamReady;
import com.example.magnavi_springserver.positioning.grpc.v1.StreamRequest;
import com.example.magnavi_springserver.positioning.grpc.v1.WarmingUp;

/** 루프백 TCP에서 protobuf를 실제로 주고받는 합성 모델이다. AI 계산·운영 데이터는 사용하지 않는다. */
public final class TestModelServer implements AutoCloseable {
    /** 실제 장애를 재현할 서버 동작이다. 지연은 응답을 보류하는 방법으로 재현한다. */
    public enum Mode { NORMAL, NO_READY, NO_RESULTS, ERROR, COMPLETE }

    private final Server server;
    private final Mode mode;
    private final Status status;
    private final UnaryOperator<StreamEvent> changeResponse;
    private final Map<String, List<StreamRequest>> received = new ConcurrentHashMap<>();
    private final Map<String, CountDownLatch> cancellations = new ConcurrentHashMap<>();
    private final Map<String, StreamObserver<StreamEvent>> responses = new ConcurrentHashMap<>();

    /** 정상 응답을 제공하는 독립 테스트 서버를 만든다. */
    public TestModelServer() throws IOException { this(Mode.NORMAL, Status.UNKNOWN, UnaryOperator.identity()); }

    /** 오류 코드·잘못된 메시지·응답 보류를 테스트마다 선택한다. */
    public TestModelServer(Mode mode, Status status, UnaryOperator<StreamEvent> changeResponse) throws IOException {
        this.mode = mode;
        this.status = status;
        this.changeResponse = changeResponse;
        server = NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
                .addService(new FakeService()).build().start();
    }

    /** 운영 포트와 충돌하지 않도록 OS가 배정한 포트를 제공한다. */
    public int port() { return server.getPort(); }

    /** 전송된 합성 입력을 복사해 optional·순서·연결 분리를 확인한다. */
    public List<StreamRequest> requests(String sessionId) {
        return List.copyOf(received.getOrDefault(sessionId, List.of()));
    }

    /** 클라이언트의 취소가 서버까지 도착했는지 제한된 시간만 기다린다. */
    public boolean awaitCancellation(String sessionId) throws InterruptedException {
        var latch = cancellations.computeIfAbsent(sessionId, key -> new CountDownLatch(1));
        return latch.await(3, TimeUnit.SECONDS);
    }

    /** 종료된 서버 작업이 뒤늦게 결과를 만들어도 새 스트림을 오염시키지 않는지 확인한다. */
    public void sendLate(String sessionId) {
        var response = responses.get(sessionId);
        if (response != null) response.onNext(StreamEvent.newBuilder().setSessionId(sessionId)
                .setWarmingUp(WarmingUp.newBuilder().setSequence(1).setBufferedCount(1).setRequiredCount(16)).build());
    }

    /** 각 테스트가 연 포트와 서버 작업자를 정리한다. */
    @Override
    public void close() throws InterruptedException {
        server.shutdownNow();
        if (!server.awaitTermination(3, TimeUnit.SECONDS)) throw new IllegalStateException("Test server did not stop");
    }

    /** 생성된 서버 기반 클래스에 테스트 응답만 연결한다. */
    private final class FakeService extends PositioningServiceGrpc.PositioningServiceImplBase {
        /** 연결마다 별도 요청 처리기를 만들어 전역 센서 상태를 공유하지 않는다. */
        @Override
        public StreamObserver<StreamRequest> predictStream(StreamObserver<StreamEvent> response) {
            return new InputHandler((ServerCallStreamObserver<StreamEvent>) response);
        }
    }

    /** 이 호출의 시작 메시지와 응답 observer만 보관한다. */
    private final class InputHandler implements StreamObserver<StreamRequest> {
        private final ServerCallStreamObserver<StreamEvent> response;
        private StartMeasurement start;

        /** 서버에서 실제 취소 이벤트를 관찰한다. */
        private InputHandler(ServerCallStreamObserver<StreamEvent> response) {
            this.response = response;
            response.setOnCancelHandler(this::cancelled);
        }

        /** 처음에는 ready를, 이후에는 15회 워밍업과 16번째부터 예측을 돌려준다. */
        @Override
        public synchronized void onNext(StreamRequest request) {
            if (request.hasStart()) {
                start = request.getStart();
                cancellations.computeIfAbsent(start.getSessionId(), key -> new CountDownLatch(1));
                received.put(start.getSessionId(), new CopyOnWriteArrayList<>());
                responses.put(start.getSessionId(), response);
            }
            if (start == null) {
                response.onError(Status.INVALID_ARGUMENT.asRuntimeException());
                return;
            }
            received.get(start.getSessionId()).add(request);
            if (mode == Mode.ERROR) {
                response.onError(status.withDescription("private remote detail").asRuntimeException());
                return;
            }
            if (mode == Mode.COMPLETE) { response.onCompleted(); return; }
            if (request.hasStart()) {
                if (mode != Mode.NO_READY) emit(StreamEvent.newBuilder().setSessionId(start.getSessionId())
                        .setReady(StreamReady.newBuilder().setContractVersion(1).setModelKey(start.getModelKey())
                                .setModelVersion(start.getModelVersion()).setSensorProfileId(start.getSensorProfileId())
                                .setRequiredCount(16)).build());
            } else if (mode != Mode.NO_RESULTS && mode != Mode.NO_READY) {
                long sequence = request.getSample().getSequence();
                var event = StreamEvent.newBuilder().setSessionId(start.getSessionId());
                if (sequence < 16) {
                    event.setWarmingUp(WarmingUp.newBuilder().setSequence(sequence)
                            .setBufferedCount((int) sequence).setRequiredCount(16));
                } else {
                    event.setPrediction(Prediction.newBuilder().setSequence(sequence)
                            .setModelKey(start.getModelKey()).setModelVersion(start.getModelVersion())
                            .setLocationCode("007").setScore(0.8)
                            .addCandidates(Candidate.newBuilder().setLocationCode("007").setScore(0.8))
                            .addCandidates(Candidate.newBuilder().setLocationCode("008").setScore(0.2)));
                }
                emit(event.build());
            }
        }

        /** 의도적으로 잘못된 응답을 만들 때도 실제 protobuf 전송 경로를 사용한다. */
        private void emit(StreamEvent event) { response.onNext(changeResponse.apply(event)); }

        /** 서버 측 취소 관찰을 한 번만 완료한다. */
        private synchronized void cancelled() {
            if (start != null) cancellations.get(start.getSessionId()).countDown();
        }

        /** 취소로 닫힌 요청 스트림에 추가 응답을 쓰지 않는다. */
        @Override
        public void onError(Throwable ignored) { cancelled(); }

        /** 테스트에서 요청이 정상 종료되면 응답도 종료한다. */
        @Override
        public void onCompleted() { response.onCompleted(); }
    }
}
