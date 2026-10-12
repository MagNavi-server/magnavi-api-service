package com.example.magnavi_springserver.place.application;

/** 장소 조회의 입력 오류와 대상 없음만 표현한다. 입력 원문이나 SQL을 보관하지 않는다. */
public class PlaceException extends RuntimeException {

    /** 응답 계층에서 HTTP 상태로 바꿀 업무상의 실패 종류다. */
    public enum Reason {
        INVALID_INPUT, NOT_FOUND
    }

    private final Reason reason;
    private final String field;

    /** 대상이 없을 때는 상세 입력 없이 실패 종류만 전달한다. */
    public PlaceException(Reason reason) {
        this(reason, "");
    }

    /** 입력 오류는 사용자가 고칠 필드 이름만 덧붙인다. */
    public PlaceException(Reason reason, String field) {
        super(reason.name());
        this.reason = reason;
        this.field = field;
    }

    /** 공통 오류 응답으로 변환할 실패 종류를 반환한다. */
    public Reason getReason() {
        return reason;
    }

    /** 잘못된 값 자체 대신 필드 이름만 반환한다. */
    public String getField() {
        return field;
    }
}
