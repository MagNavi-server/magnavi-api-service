package com.example.magnavi_springserver.positioning.infrastructure;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 앱 연결의 자원 한도다. 기본 비활성이며 초기 한도는 운영 성능 측정값이 아니다. */
@ConfigurationProperties("magnavi.positioning-websocket")
public record PositioningWebSocketProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("32") int maxConnections,
        @DefaultValue("2") int maxConnectionsPerMember,
        @DefaultValue("4096") int maxMessageBytes,
        @DefaultValue("100") int maxMessagesPerSecond,
        @DefaultValue("65536") int sendBufferBytes,
        @DefaultValue("3s") Duration sendTimeout,
        @DefaultValue("10s") Duration startTimeout,
        @DefaultValue("30s") Duration idleTimeout,
        @DefaultValue List<String> allowedOrigins) {

    /** 무제한 연결·대기와 무분별한 브라우저 출처 허용을 설정 단계에서 막는다. */
    public PositioningWebSocketProperties {
        if (maxConnections < 1 || maxConnections > 256 || maxConnectionsPerMember < 1
                || maxConnectionsPerMember > maxConnections || maxMessageBytes < 256 || maxMessageBytes > 65536
                || maxMessagesPerSecond < 1 || maxMessagesPerSecond > 1000
                || sendBufferBytes < 1024 || sendBufferBytes > 1048576) {
            throw new IllegalArgumentException("Invalid WebSocket limits");
        }
        validateTimeout(sendTimeout);
        validateTimeout(startTimeout);
        validateTimeout(idleTimeout);
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
        if (allowedOrigins.stream().anyMatch(origin -> origin.contains("*")
                || !(origin.startsWith("https://") || origin.startsWith("http://localhost:")))) {
            throw new IllegalArgumentException("Explicit HTTPS or localhost origins are required");
        }
    }

    /** 시간 제한이 너무 작거나 커서 자원 정리가 사실상 멈추지 않도록 한다. */
    private static void validateTimeout(Duration timeout) {
        if (timeout == null || timeout.toMillis() < 100 || timeout.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("Invalid WebSocket timeout");
        }
    }
}
