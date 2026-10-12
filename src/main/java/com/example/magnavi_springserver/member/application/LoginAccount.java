package com.example.magnavi_springserver.member.application;

/** 로그인 검증에만 사용하는 내부 자료다. API 응답으로 반환하지 않는다. */
public record LoginAccount(Long memberId, String passwordHash, boolean active) {

    /** 실수로 이 객체를 로그에 출력해도 비밀번호 해시가 노출되지 않게 한다. */
    @Override
    public String toString() {
        return "LoginAccount[credentials hidden]";
    }
}
