package com.example.magnavi_springserver.member.infrastructure;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

/** 로그인에 성공한 회원에게 서명된 액세스 토큰을 발급한다. */
@Component
public class JwtTokenProvider {

    private final JwtEncoder encoder;
    private final JwtProperties properties;
    private final Clock clock;

    /** 서명 도구, 토큰 정책, 현재 시각을 읽는 도구를 받는다. */
    public JwtTokenProvider(JwtEncoder encoder, JwtProperties properties, Clock clock) {
        this.encoder = encoder;
        this.properties = properties;
        this.clock = clock;
    }

    /** 비밀번호·연락처 없이 내부 회원 번호와 토큰 검증에 필요한 정보만 넣는다. */
    public String issueAccessToken(Long memberId) {
        Instant now = clock.instant();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .audience(List.of(properties.audience()))
                .subject(memberId.toString())
                .issuedAt(now)
                .expiresAt(now.plus(properties.accessTokenTtl()))
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
