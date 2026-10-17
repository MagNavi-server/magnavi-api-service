package com.example.magnavi_springserver.positioning.domain;

import static org.assertj.core.api.Assertions.*;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import com.example.magnavi_springserver.positioning.infrastructure.InMemorySessionRegistry;

/** 실제 시간을 기다리지 않고 상태·만료·입력 한도와 동시 연결 제한을 검증한다. */
class PositioningSessionTests {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    /** 시작 전 센서, 중복 시작, 종료 후 준비 콜백을 허용하지 않는다. */
    @Test void enforcesConnectionState() {
        var session = new PositioningSession(1, NOW.plusSeconds(60), NOW, 5);
        assertThatThrownBy(session::requireStreaming).hasMessage("NOT_READY");
        session.start();
        assertThatThrownBy(session::requireStreaming).hasMessage("NOT_READY");
        assertThatThrownBy(session::start).hasMessage("INVALID_MESSAGE");
        assertThat(session.ready()).isTrue();
        session.requireStreaming();
        assertThat(session.close()).isTrue();
        assertThat(session.close()).isFalse();
        assertThat(session.ready()).isFalse();
    }

    /** 만료 시각에 추가 메시지를 거절하고 입력이 없어도 종료 대상으로 판별한다. */
    @Test void expiresEvenWithoutInput() {
        var session = new PositioningSession(1, NOW.plusSeconds(5), NOW, 5);
        assertThatThrownBy(() -> session.acceptMessage(NOW.plusSeconds(5))).hasMessage("TOKEN_EXPIRED");
        assertThat(session.timeout(NOW.plusSeconds(5), Duration.ofSeconds(10), Duration.ofSeconds(30)))
                .isEqualTo(PositioningException.Reason.TOKEN_EXPIRED);
    }

    /** start 미전송과 측정 중 무응답을 서로 다른 오류로 구분한다. */
    @Test void distinguishesStartAndIdleTimeout() {
        var session = new PositioningSession(1, NOW.plusSeconds(60), NOW, 5);
        assertThat(session.timeout(NOW.plusSeconds(10), Duration.ofSeconds(10), Duration.ofSeconds(30)))
                .isEqualTo(PositioningException.Reason.START_TIMEOUT);
        session.start(); session.ready();
        assertThat(session.timeout(NOW.plusSeconds(30), Duration.ofSeconds(10), Duration.ofSeconds(30)))
                .isEqualTo(PositioningException.Reason.IDLE_TIMEOUT);
    }

    /** 전송 제한이 초마다 갱신되고 다른 세션과 공유되지 않는다. */
    @Test void boundsInputRatePerConnection() {
        var session = new PositioningSession(1, NOW.plusSeconds(60), NOW, 2);
        session.acceptMessage(NOW); session.acceptMessage(NOW);
        assertThatThrownBy(() -> session.acceptMessage(NOW)).hasMessage("RATE_LIMITED");
        session.acceptMessage(NOW.plusSeconds(1));
        new PositioningSession(1, NOW.plusSeconds(60), NOW, 2).acceptMessage(NOW);
    }

    /** 동시 예약에서도 사용자별 한도가 지켜지고 이중 반환으로 슬롯이 늘어나지 않는다. */
    @Test void reservesMemberSlotsAtomically() throws Exception {
        var registry = new InMemorySessionRegistry(4, 2);
        var accepted = new AtomicInteger();
        var go = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(8)) {
            var tasks = java.util.stream.IntStream.range(0, 8).mapToObj(index -> workers.submit(() -> {
                try { go.await(); registry.reserve("connection-" + index, 1); accepted.incrementAndGet(); }
                catch (PositioningException expected) { }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            })).toList();
            go.countDown();
            for (var task : tasks) task.get(3, TimeUnit.SECONDS);
        }
        assertThat(accepted).hasValue(2);
        assertThat(registry.size()).isEqualTo(2);
        registry.reserve("other-member", 2);
        registry.reserve("third-member", 3);
        assertThatThrownBy(() -> registry.reserve("full", 4)).hasMessage("OVERLOADED");
        registry.release("other-member"); registry.release("other-member");
        assertThat(registry.size()).isEqualTo(3);
        registry.reserve("reconnected", 2);
        assertThat(registry.size()).isEqualTo(4);
    }
}
