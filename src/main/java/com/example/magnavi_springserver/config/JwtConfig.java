package com.example.magnavi_springserver.config;

import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import com.example.magnavi_springserver.member.infrastructure.JwtProperties;
import com.example.magnavi_springserver.shared.security.MemberTokenValidator;

/** 하나의 Spring 서버에서 사용할 HS256 서명·검증 도구를 연결한다. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(JwtProperties.class)
public class JwtConfig {

    /** 서버 시간대와 무관하게 UTC 시각을 사용한다. */
    @Bean
    public Clock jwtClock() {
        return Clock.systemUTC();
    }

    /** 외부에서 주입한 Base64 키만 사용한다. 누락·잘못된 키를 기본 키로 대체하지 않는다. */
    @Bean
    public SecretKey jwtSigningKey(@Value("${magnavi.jwt.secret-base64:}") String encodedKey) {
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(encodedKey);
        } catch (IllegalArgumentException exception) {
            // 원래 예외나 키 문자열을 포함하면 기동 실패 로그에 비밀값이 남을 수 있다.
            throw new IllegalStateException("JWT_SECRET_BASE64는 올바른 Base64 형식이어야 합니다.");
        }
        if (keyBytes.length < 32) {
            throw new IllegalStateException("JWT_SECRET_BASE64에 최소 32바이트 난수 키를 설정해 주세요.");
        }
        return new SecretKeySpec(keyBytes, "HmacSHA256");
    }

    /** 발급 시 사용할 서명 알고리즘을 HS256으로 고정한다. */
    @Bean
    public JwtEncoder jwtEncoder(SecretKey jwtSigningKey) {
        return NimbusJwtEncoder.withSecretKey(jwtSigningKey).algorithm(MacAlgorithm.HS256).build();
    }

    /** 서명·만료·발급자·대상 서비스·회원 번호를 모두 확인한다. */
    @Bean
    public JwtDecoder jwtDecoder(SecretKey jwtSigningKey, JwtProperties properties, Clock jwtClock) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtSigningKey)
                .macAlgorithm(MacAlgorithm.HS256).build();
        JwtTimestampValidator timestamps = new JwtTimestampValidator(Duration.ZERO);
        timestamps.setClock(jwtClock);
        JwtClaimValidator<List<String>> audience = new JwtClaimValidator<>("aud",
                values -> values != null && values.contains(properties.audience()));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(timestamps,
                new JwtIssuerValidator(properties.issuer()), audience, new MemberTokenValidator(jwtClock)));
        return decoder;
    }
}
