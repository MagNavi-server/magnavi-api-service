package com.example.magnavi_springserver.positioning.infrastructure;

import java.time.Duration;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 모델 연결의 주소·센서 규격·자원 한도다. 기본값은 개발 시작값이며 실측 성능 보장이 아니다. */
@ConfigurationProperties("magnavi.model-grpc")
public record ModelGrpcProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("") String host,
        @DefaultValue("50051") int port,
        @DefaultValue("false") boolean plaintext,
        @DefaultValue("") String sensorProfileId,
        @DefaultValue("32") int maxStreams,
        @DefaultValue("32") int maxPendingSamples,
        @DefaultValue("32") int maxBufferedEvents,
        @DefaultValue("65536") int maxMessageBytes,
        @DefaultValue("3s") Duration startTimeout,
        @DefaultValue("5s") Duration responseTimeout,
        @DefaultValue("30s") Duration idleTimeout) {

    /** 잘못된 한도와 미확정 센서 규격으로 연결을 활성화하지 못하게 한다. */
    public ModelGrpcProperties {
        if (port < 1 || port > 65535 || maxStreams < 1 || maxStreams > 256
                || maxPendingSamples < 1 || maxPendingSamples > 1024
                || maxBufferedEvents < 1 || maxBufferedEvents > 1024
                || maxMessageBytes < 1024 || maxMessageBytes > 1048576) {
            throw new IllegalArgumentException("Invalid model gRPC limits");
        }
        validateDuration(startTimeout);
        validateDuration(responseTimeout);
        validateDuration(idleTimeout);
        if (enabled && (host == null || host.isBlank() || host.chars().anyMatch(Character::isWhitespace)
                || sensorProfileId == null || sensorProfileId.isBlank() || sensorProfileId.length() > 64
                || "UNCONFIRMED".equals(sensorProfileId))) {
            throw new IllegalArgumentException("Model host and confirmed sensor profile are required");
        }
        // 평문은 명시적으로 설정한 로컬 테스트에만 허용한다. 원격 연결은 TLS를 사용한다.
        if (enabled && plaintext && !Set.of("localhost", "127.0.0.1", "::1").contains(host)) {
            throw new IllegalArgumentException("Plaintext is only allowed on loopback");
        }
    }

    /** 지나치게 긴 시간이나 0을 한도로 사용해 자원 정리가 멈추지 않게 한다. */
    private static void validateDuration(Duration value) {
        if (value == null || value.compareTo(Duration.ofMillis(100)) < 0
                || value.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("Invalid model gRPC timeout");
        }
    }

    /** 서버 주소와 센서 규격을 자동 로그에서 제외한다. */
    @Override
    public String toString() { return "ModelGrpcProperties[enabled=" + enabled + "]"; }
}
