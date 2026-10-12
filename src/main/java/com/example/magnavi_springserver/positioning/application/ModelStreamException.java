package com.example.magnavi_springserver.positioning.application;

/** 외부 서버의 오류 원문 없이 실패 종류만 전달한다. HTTP 상태로 변환하는 API는 아직 없다. */
public class ModelStreamException extends RuntimeException {
    /** 호출자가 구분할 수 있는 통신·규격·자원 실패다. */
    public enum Reason {
        NOT_CONFIGURED, NOT_READY, INVALID_INPUT, PROTOCOL_ERROR, UNAVAILABLE,
        OVERLOADED, TIMED_OUT, CANCELLED, CLOSED, INTERNAL
    }

    private final Reason reason;

    /** 센서·주소·원격 오류 상세를 예외에 보관하지 않는다. */
    public ModelStreamException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    /** 이후 WebSocket 계층에서 안전한 오류 메시지로 변환할 실패 종류를 반환한다. */
    public Reason getReason() { return reason; }
}
