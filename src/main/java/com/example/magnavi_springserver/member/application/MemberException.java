package com.example.magnavi_springserver.member.application;

/** 회원 처리에서 예상할 수 있는 실패를 구분한다. 입력값이나 DB 오류는 담지 않는다. */
public class MemberException extends RuntimeException {

    /** HTTP 상태 코드는 응답 계층에서 정하고, 여기서는 실패 이유만 표현한다. */
    public enum Reason {
        INVALID_INPUT, DUPLICATE_LOGIN_ID, INVALID_CREDENTIALS, UNAUTHENTICATED
    }

    private final Reason reason;
    private final String field;

    /** 비밀번호나 로그인 ID 원문 대신 미리 정한 실패 종류를 보관한다. */
    public MemberException(Reason reason) {
        this(reason, "");
    }

    /** 입력 오류일 때는 앱이 수정할 필드의 이름만 추가한다. */
    public MemberException(Reason reason, String field) {
        super(reason.name());
        this.reason = reason;
        this.field = field;
    }

    /** 응답에 사용할 실패 종류를 반환한다. */
    public Reason getReason() {
        return reason;
    }

    /** 잘못된 입력의 필드 이름을 반환하며 입력값은 포함하지 않는다. */
    public String getField() {
        return field;
    }
}
