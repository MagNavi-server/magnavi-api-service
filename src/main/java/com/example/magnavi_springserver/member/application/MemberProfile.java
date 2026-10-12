package com.example.magnavi_springserver.member.application;

/** DB 객체 전체 대신 응답에 필요한 회원 정보만 전달한다. 소셜 전용 회원의 loginId는 null이다. */
public record MemberProfile(Long id, String loginId, String displayName, String email, String phoneNumber) {
}
