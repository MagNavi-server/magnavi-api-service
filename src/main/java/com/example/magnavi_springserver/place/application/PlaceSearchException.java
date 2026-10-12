package com.example.magnavi_springserver.place.application;

/** 외부 검색 실패의 종류만 전달한다. 검색어·키·제공자의 오류 본문은 보관하지 않는다. */
public class PlaceSearchException extends RuntimeException {

    /** 앱의 요청 한도와 제공자의 장애를 서로 다르게 안내하기 위한 구분이다. */
    public enum Reason {
        NOT_CONFIGURED, RATE_LIMITED, BUSY, PROVIDER_LIMITED, PROVIDER_ERROR, TIMEOUT
    }

    private final Reason reason;

    /** 원본 예외를 연결하지 않아 외부 요청 URL이나 인증 정보의 노출을 방지한다. */
    public PlaceSearchException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    /** 응답 계층에서 안전한 HTTP 오류로 바꿀 실패 종류를 반환한다. */
    public Reason getReason() {
        return reason;
    }
}
