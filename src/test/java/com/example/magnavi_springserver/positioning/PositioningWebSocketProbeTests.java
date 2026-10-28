package com.example.magnavi_springserver.positioning;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.net.http.WebSocket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** 네트워크·DB 없이 종료 순서를 제어하여 테스트 클라이언트의 정리 경합을 재현한다. */
@Timeout(10)
class PositioningWebSocketProbeTests {
    private final WebSocket socket = mock(WebSocket.class);
    private final PositioningWebSocketIntegrationTests.Probe probe =
            new PositioningWebSocketIntegrationTests.Probe(socket);

    /** 클라이언트가 먼저 종료하면 서버의 종료 응답까지 확인한다. */
    @Test void closesOpenConnection() throws Exception {
        when(socket.sendClose(WebSocket.NORMAL_CLOSURE, "test finished")).thenAnswer(call -> {
            probe.onClose(socket, WebSocket.NORMAL_CLOSURE, "closed");
            return CompletableFuture.completedFuture(socket);
        });

        probe.close();

        verify(socket).sendClose(WebSocket.NORMAL_CLOSURE, "test finished");
        verify(socket, never()).abort();
    }

    /** 서버가 이미 종료했다면 반복 정리에서도 종료 프레임을 추가로 보내지 않는다. */
    @Test void acceptsAlreadyClosedConnection() throws Exception {
        when(socket.isOutputClosed()).thenReturn(true);
        probe.onClose(socket, WebSocket.NORMAL_CLOSURE, "closed");

        probe.close();
        probe.close();

        verify(socket, never()).sendClose(anyInt(), anyString());
        verify(socket, never()).abort();
    }

    /** 상태 확인 뒤 서버 종료가 끼어들어도 자동 종료 응답과 중복 전송하지 않는다. */
    @Test void coordinatesServerCloseWithPendingClientClose() throws Exception {
        var sendEntered = new CountDownLatch(1);
        var callbackStarted = new CountDownLatch(1);
        var callbackFinished = new CountDownLatch(1);
        var outputClosed = new AtomicBoolean();
        when(socket.isOutputClosed()).thenAnswer(call -> outputClosed.get());
        when(socket.sendClose(WebSocket.NORMAL_CLOSURE, "test finished")).thenAnswer(call -> {
            sendEntered.countDown();
            assertThat(callbackStarted.await(3, TimeUnit.SECONDS)).isTrue();
            // JDK는 onClose가 반환된 뒤 자동 종료 응답을 보낸다. 그 순서가 끼어들 기회를 준다.
            callbackFinished.await(100, TimeUnit.MILLISECONDS);
            if (!outputClosed.compareAndSet(false, true)) {
                return CompletableFuture.failedFuture(new IOException("Output closed"));
            }
            return CompletableFuture.completedFuture(socket);
        });

        try (var executor = Executors.newFixedThreadPool(2)) {
            var cleanup = executor.submit(() -> { probe.close(); return null; });
            assertThat(sendEntered.await(3, TimeUnit.SECONDS)).isTrue();
            var serverClose = executor.submit(() -> {
                callbackStarted.countDown();
                probe.onClose(socket, WebSocket.NORMAL_CLOSURE, "server closed");
                outputClosed.set(true);
                callbackFinished.countDown();
            });

            cleanup.get(4, TimeUnit.SECONDS);
            serverClose.get(4, TimeUnit.SECONDS);
        }

        verify(socket, never()).abort();
    }

    /** 이미 전달된 통신 오류를 종료 완료로 오인하여 숨기지 않는다. */
    @Test void propagatesPreviouslyReportedConnectionError() {
        var failure = new IOException("connection reset");
        probe.onError(socket, failure);

        assertThatThrownBy(probe::close).hasCause(failure);

        verify(socket, never()).sendClose(anyInt(), anyString());
        verify(socket).abort();
    }

    /** 종료 프레임 전송 자체의 실패는 그대로 보고하고 자원을 반납한다. */
    @Test void propagatesCloseSendFailure() {
        var failure = new IOException("write failed");
        when(socket.sendClose(WebSocket.NORMAL_CLOSURE, "test finished"))
                .thenReturn(CompletableFuture.failedFuture(failure));

        assertThatThrownBy(probe::close).hasCause(failure);

        verify(socket).abort();
    }

    /** 전송이 끝나지 않아도 제한 시간 뒤 실패하고 연결을 강제 정리한다. */
    @Test void abortsWhenCloseSendTimesOut() {
        when(socket.sendClose(WebSocket.NORMAL_CLOSURE, "test finished"))
                .thenReturn(new CompletableFuture<>());

        assertThatThrownBy(probe::close).isInstanceOf(TimeoutException.class);

        verify(socket).abort();
    }

    /** 전송 성공만으로 정상 종료를 단정하지 않고 서버 응답의 시간 초과도 보고한다. */
    @Test void abortsWhenCloseCallbackTimesOut() {
        when(socket.sendClose(WebSocket.NORMAL_CLOSURE, "test finished"))
                .thenReturn(CompletableFuture.completedFuture(socket));

        assertThatThrownBy(probe::close).isInstanceOf(TimeoutException.class);

        verify(socket).abort();
    }
}
