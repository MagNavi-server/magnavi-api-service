package com.example.magnavi_springserver.favorite.application;

/** 즐겨찾기에서 예상 가능한 실패만 표현하며 입력값이나 SQL 원문은 담지 않는다. */
public class FavoriteException extends RuntimeException {

    /** 응답 계층에서 입력·인증·대상 없음·중복을 구분하는 기준이다. */
    public enum Reason {
        INVALID_INPUT, UNAUTHENTICATED, NOT_FOUND, DUPLICATE
    }

    private final Reason reason;
    private final String field;

    /** 입력 필드가 필요 없는 업무 실패를 만든다. */
    public FavoriteException(Reason reason) {
        this(reason, "");
    }

    /** 잘못된 값 대신 사용자가 고칠 필드 이름만 전달한다. */
    public FavoriteException(Reason reason, String field) {
        super(reason.name());
        this.reason = reason;
        this.field = field;
    }

    /** 공통 HTTP 응답으로 바꿀 실패 종류를 반환한다. */
    public Reason getReason() {
        return reason;
    }

    /** 입력 오류에만 사용할 필드 이름을 반환한다. */
    public String getField() {
        return field;
    }
}
