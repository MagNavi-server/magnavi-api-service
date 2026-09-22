package com.example.magnavi_springserver.shared.security;

import java.time.Clock;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/** 서명이 맞더라도 회원 번호와 필수 시각이 올바르지 않은 토큰은 거절한다. */
public class MemberTokenValidator implements OAuth2TokenValidator<Jwt> {

    private final Clock clock;

    /** 발급 시각이 미래인지 검사할 기준 시계를 받는다. */
    public MemberTokenValidator(Clock clock) {
        this.clock = clock;
    }

    /** exp·iat가 반드시 있고, sub는 양수인 내부 회원 번호여야 한다. */
    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        String subject = token.getSubject();
        boolean validId = subject != null && subject.matches("[1-9][0-9]{0,18}");
        if (validId) {
            try {
                Long.parseLong(subject);
            } catch (NumberFormatException exception) {
                validId = false;
            }
        }
        if (!validId || token.getIssuedAt() == null || token.getExpiresAt() == null
                || token.getIssuedAt().isAfter(clock.instant())
                || !token.getExpiresAt().isAfter(token.getIssuedAt())
                || !token.getExpiresAt().isAfter(clock.instant())) {
            return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "유효하지 않은 토큰입니다.", null));
        }
        return OAuth2TokenValidatorResult.success();
    }
}
