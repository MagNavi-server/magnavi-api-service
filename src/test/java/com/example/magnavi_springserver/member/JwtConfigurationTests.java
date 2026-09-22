package com.example.magnavi_springserver.member;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.SecureRandom;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;

import com.example.magnavi_springserver.config.JwtConfig;

/** DB 연결 없이도 잘못된 JWT 설정으로 서버가 시작되지 않는지 확인한다. */
class JwtConfigurationTests {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(JwtConfig.class)
            .withPropertyValues("magnavi.jwt.issuer=magnavi-test", "magnavi.jwt.audience=magnavi-test-api",
                    "magnavi.jwt.access-token-ttl=30m");

    /** 키가 없을 때 알려진 키나 자동 생성 키로 대체하지 않고 기동을 거절한다. */
    @Test
    void rejectsMissingSigningKey() {
        context.run(application -> assertThat(application).hasFailed());
    }

    /** Base64 형식 오류나 32바이트 미만의 키로 시작할 수 없다. */
    @Test
    void rejectsMalformedAndShortSigningKeys() {
        for (String value : new String[] {"invalid-base64!", "c2hvcnQ="}) {
            context.withPropertyValues("magnavi.jwt.secret-base64=" + value)
                    .run(application -> {
                        assertThat(application).hasFailed();
                        assertThat(application.getStartupFailure()).hasRootCauseInstanceOf(IllegalStateException.class);
                    });
        }
    }

    /** 유효 시간이 0이거나 필수 대상이 비어 있는 정책은 사용할 수 없다. */
    @Test
    void rejectsInvalidTokenPolicy() {
        for (String invalid : new String[] {"magnavi.jwt.access-token-ttl=0s", "magnavi.jwt.audience="}) {
            context.withPropertyValues("magnavi.jwt.secret-base64=" + randomKey(), invalid)
                    .run(application -> assertThat(application).hasFailed());
        }
    }

    /** 충분한 난수 키를 주입하면 외부 인증 서버 없이 발급·검증 도구가 준비된다. */
    @Test
    void acceptsExplicitRandomSigningKey() {
        context.withPropertyValues("magnavi.jwt.secret-base64=" + randomKey()).run(application -> {
            assertThat(application).hasNotFailed().hasSingleBean(JwtEncoder.class).hasSingleBean(JwtDecoder.class);
        });
    }

    /** 테스트마다 새 키를 만들며 저장소나 테스트 출력에 실제 키를 남기지 않는다. */
    private String randomKey() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }
}
