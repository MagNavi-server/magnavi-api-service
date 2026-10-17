package com.example.magnavi_springserver.positioning.domain;

import java.time.Duration;
import java.time.Instant;
import com.example.magnavi_springserver.positioning.domain.PositioningException.Reason;

/** 한 연결의 상태·만료·전송 빈도만 관리한다. 센서 원문이나 모델 윈도우는 저장하지 않는다. */
public final class PositioningSession {
    /** 준비 전 센서 전송과 종료 후 재사용을 구분한다. */
    public enum State { WAITING_START, STARTING, STREAMING, CLOSED }
    private final long memberId;
    private final Instant expiresAt;
    private final Instant openedAt;
    private final int messagesPerSecond;
    private State state = State.WAITING_START;
    private Instant lastMessageAt;
    private Instant rateWindow;
    private int messageCount;

    /** 같은 회원이 재접속해도 새 객체를 만들어 이전 상태를 공유하지 않는다. */
    public PositioningSession(long memberId, Instant expiresAt, Instant now, int messagesPerSecond) {
        if (memberId < 1 || expiresAt == null || !expiresAt.isAfter(now) || messagesPerSecond < 1) {
            throw new PositioningException(Reason.UNAUTHORIZED);
        }
        this.memberId = memberId;
        this.expiresAt = expiresAt;
        this.openedAt = now;
        this.lastMessageAt = now;
        this.rateWindow = now;
        this.messagesPerSecond = messagesPerSecond;
    }

    /** 모든 앱 메시지에 만료·전송량 검사를 적용한다. */
    public synchronized void acceptMessage(Instant now) {
        if (state == State.CLOSED) throw new PositioningException(Reason.INVALID_MESSAGE);
        if (!expiresAt.isAfter(now)) throw new PositioningException(Reason.TOKEN_EXPIRED);
        if (!now.isBefore(rateWindow.plusSeconds(1))) {
            rateWindow = now;
            messageCount = 0;
        }
        if (++messageCount > messagesPerSecond) throw new PositioningException(Reason.RATE_LIMITED);
        lastMessageAt = now;
    }

    /** 한 연결에서 start를 두 번 실행하거나 모델을 중간 교체하지 않는다. */
    public synchronized void start() {
        if (state != State.WAITING_START) throw new PositioningException(Reason.INVALID_MESSAGE);
        state = State.STARTING;
    }

    /** 모델 준비 응답을 앱에 보낼 때 측정 가능 상태로 전환한다. */
    public synchronized boolean ready() {
        if (state == State.CLOSED) return false;
        if (state != State.STARTING) throw new PositioningException(Reason.MODEL_PROTOCOL_ERROR);
        state = State.STREAMING;
        return true;
    }

    /** 준비 응답 전 센서를 원격 모델로 전달하지 않는다. */
    public synchronized void requireStreaming() {
        if (state != State.STREAMING) throw new PositioningException(Reason.NOT_READY);
    }

    /** 입력이 없어도 만료·시작 지연·유휴 상태를 판별한다. */
    public synchronized Reason timeout(Instant now, Duration startTimeout, Duration idleTimeout) {
        if (state == State.CLOSED) return null;
        if (!expiresAt.isAfter(now)) return Reason.TOKEN_EXPIRED;
        if (state == State.WAITING_START && !openedAt.plus(startTimeout).isAfter(now)) return Reason.START_TIMEOUT;
        if (!lastMessageAt.plus(idleTimeout).isAfter(now)) return Reason.IDLE_TIMEOUT;
        return null;
    }

    /** 중복 종료에서도 자원 정리는 한 번만 시작한다. */
    public synchronized boolean close() {
        if (state == State.CLOSED) return false;
        state = State.CLOSED;
        return true;
    }

    /** 늦게 도착한 결과를 보낼지 확인한다. */
    public synchronized boolean isClosed() { return state == State.CLOSED; }

    /** 사용자별 연결 수를 셀 때만 회원 식별자를 제공한다. */
    public long memberId() { return memberId; }
}
