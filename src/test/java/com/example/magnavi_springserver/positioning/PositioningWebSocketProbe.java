package com.example.magnavi_springserver.positioning;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 네트워크 프레임을 모아 테스트에서 순서와 종료를 관찰한다. */
final class PositioningWebSocketProbe implements WebSocket.Listener, AutoCloseable {
    private static final JsonMapper JSON = new JsonMapper();
    WebSocket socket;
    final ArrayBlockingQueue<String> messages = new ArrayBlockingQueue<>(64);
    final CompletableFuture<Integer> closed = new CompletableFuture<>();
    private final StringBuilder partial = new StringBuilder();
    private final Object closeLock = new Object();
    /** 실제 연결은 비동기 핸드셰이크가 끝난 뒤 주입한다. */
    PositioningWebSocketProbe() { }
    /** 종료 순서를 제어하는 단위 테스트에서는 소켓 대역을 주입한다. */
    PositioningWebSocketProbe(WebSocket socket) { this.socket = socket; }
    /** 테스트가 소유한 HTTP 클라이언트로 인증된 WebSocket 연결을 만든다. */
    static PositioningWebSocketProbe connect(HttpClient http, URI uri, String token) throws Exception {
        var probe = new PositioningWebSocketProbe();
        probe.socket = http.newWebSocketBuilder().header("Authorization", "Bearer " + token)
                .buildAsync(uri, probe).get(4, TimeUnit.SECONDS);
        return probe;
    }
    /** 연결 후 첫 수신을 요청한다. */
    @Override public void onOpen(WebSocket socket) { socket.request(1); }
    /** 프레임 분할 여부와 무관하게 JSON 메시지 하나씩 보관한다. */
    @Override public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
        partial.append(data);
        if (last) { messages.add(partial.toString()); partial.setLength(0); }
        socket.request(1);
        return null;
    }
    /** 종료를 기록한 뒤 JDK가 자동 응답하므로 직접 종료 요청과 같은 잠금으로 순서를 보장한다. */
    @Override public CompletionStage<?> onClose(WebSocket socket, int status, String reason) {
        synchronized (closeLock) {
            closed.complete(status);
        }
        return null;
    }
    /** 네트워크 실패를 성공한 종료로 숨기지 않는다. */
    @Override public void onError(WebSocket socket, Throwable error) { closed.completeExceptionally(error); }
    /** 메시지가 실제 전송될 때까지 짧게 기다린다. */
    void send(String value) throws Exception { socket.sendText(value, true).get(3, TimeUnit.SECONDS); }
    /** 응답이 없는 경우 테스트를 무한히 기다리지 않는다. */
    JsonNode next() throws Exception {
        String value = messages.poll(4, TimeUnit.SECONDS);
        assertThat(value).as("WebSocket response").isNotNull();
        return JSON.readTree(value);
    }
    /** 중복 종료 요청을 막고, 통신 실패·시간 초과는 보고하면서 연결을 반납한다. */
    @Override public void close() throws Exception {
        boolean gracefullyClosed = false;
        try {
            CompletableFuture<WebSocket> closeSent;
            synchronized (closeLock) {
                // 확인 직후 onClose의 자동 응답이 먼저 송신부를 닫는 경합을 막는다.
                closeSent = closed.isDone() || socket.isOutputClosed()
                        ? CompletableFuture.completedFuture(socket)
                        : socket.sendClose(WebSocket.NORMAL_CLOSURE, "test finished");
            }
            // 콜백이 잠금을 얻을 수 있어야 하므로 실제 완료 대기는 잠금 밖에서 한다.
            closeSent.get(3, TimeUnit.SECONDS);
            closed.get(3, TimeUnit.SECONDS);
            gracefullyClosed = true;
        } finally {
            // 정상 종료를 확인하지 못한 연결은 예외를 숨기지 않고 강제 정리한다.
            if (!gracefullyClosed) socket.abort();
        }
    }
}
