package com.example.magnavi_springserver.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import com.example.magnavi_springserver.member.application.MemberException;
import com.example.magnavi_springserver.member.infrastructure.PasswordHashAdapter;

/** 비밀번호를 안전하게 저장하고 기존 Python 해시를 검증할 수 있는지 확인한다. */
class PasswordHashAdapterTests {

    private final PasswordHashAdapter passwords = new PasswordHashAdapter();

    /** 기존 서버와 같은 Python 라이브러리로 만든 한글·공백 포함 해시를 검증한다. */
    @Test
    void verifiesBcryptHashGeneratedByLegacyPythonLibraries() throws Exception {
        Properties fixture = new Properties();
        try (var reader = new InputStreamReader(new ClassPathResource("legacy-bcrypt.properties").getInputStream(),
                StandardCharsets.UTF_8)) {
            fixture.load(reader);
        }
        String password = fixture.getProperty("password");
        String hash = fixture.getProperty("hash");
        assertThat(passwords.matches(password, hash)).isTrue();
        assertThat(passwords.matches(password.strip(), hash)).isFalse();
        assertThat(passwords.matches("another-password", hash)).isFalse();
    }

    /** 같은 비밀번호도 salt가 달라 다른 해시로 저장되지만 모두 정상 비교된다. */
    @Test
    void producesDifferentHashesForTheSamePassword() {
        String first = passwords.hashForSignUp("synthetic-password");
        String second = passwords.hashForSignUp("synthetic-password");
        assertThat(first).isNotEqualTo(second);
        assertThat(passwords.matches("synthetic-password", first)).isTrue();
        assertThat(passwords.matches("synthetic-password", second)).isTrue();
        assertThat(passwords.matches("synthetic-password", null)).isFalse();
    }

    /** 문자 종류를 강제하지 않고 최소 8자·UTF-8 최대 72바이트를 확인한다. */
    @Test
    void enforcesSignupPasswordLengthWithoutTruncatingInput() {
        assertThat(passwords.matches("12345678", passwords.hashForSignUp("12345678"))).isTrue();
        for (String invalid : new String[] {"1234567", "        ", "a".repeat(73), "한".repeat(25)}) {
            assertThatThrownBy(() -> passwords.hashForSignUp(invalid)).isInstanceOf(MemberException.class);
        }
    }
}
