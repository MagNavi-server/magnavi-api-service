package com.example.magnavi_springserver.member.infrastructure;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 토큰 발급자, 사용할 서비스, 유효 시간을 읽는다. 비밀 키는 이 객체에 보관하지 않는다. */
@ConfigurationProperties(prefix = "magnavi.jwt")
public record JwtProperties(String issuer, String audience, Duration accessTokenTtl) {

    /** 잘못된 토큰 정책으로 서버가 시작되지 않도록 필수 설정을 확인한다. */
    public JwtProperties {
        if (issuer == null || issuer.isBlank() || audience == null || audience.isBlank()
                || accessTokenTtl == null || accessTokenTtl.compareTo(Duration.ofSeconds(1)) < 0) {
            throw new IllegalArgumentException("JWT 발급자·대상·양수 유효 시간을 설정해 주세요.");
        }
    }
}
