package com.example.magnavi_springserver.positioning.domain;

/** 앱에 공개할 실패 종류만 보관하며 입력·JWT·원격 오류 원문은 담지 않는다. */
public final class PositioningException extends RuntimeException {
    /** 인증, 메시지, 연결 수명, 모델 실패를 구분하는 고정 코드다. */
    public enum Reason {
        UNAUTHORIZED, TOKEN_EXPIRED, INVALID_MESSAGE, NOT_READY, RATE_LIMITED, OVERLOADED,
        START_TIMEOUT, IDLE_TIMEOUT, MODEL_UNAVAILABLE, MODEL_PROTOCOL_ERROR, MODEL_TIMEOUT,
        NOT_CONFIGURED, INTERNAL
    }
    private final Reason reason;

    /** 안전한 고정 코드만 예외 메시지에 넣는다. */
    public PositioningException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    /** 전송 계층이 오류 메시지와 종료 상태를 선택할 때 사용한다. */
    public Reason reason() { return reason; }
}
