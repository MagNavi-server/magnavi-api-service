package com.example.magnavi_springserver.shared.security;

import java.security.Principal;

/** JWT 검증을 통과한 내부 회원 번호다. 비밀번호나 토큰 원문은 보관하지 않는다. */
public record AuthenticatedMember(Long memberId) implements Principal {

    /** 인증 주체를 잘못 생성하지 않도록 양수 회원 번호만 허용한다. */
    public AuthenticatedMember {
        if (memberId == null || memberId <= 0) {
            throw new IllegalArgumentException("회원 번호는 양수여야 합니다.");
        }
    }

    /** Spring Security가 인증 주체를 식별할 때 회원 번호를 사용한다. */
    @Override
    public String getName() {
        return memberId.toString();
    }
}
