package com.example.magnavi_springserver.member.infrastructure;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import com.example.magnavi_springserver.member.application.MemberException;

/** BCrypt로 비밀번호의 검증용 해시를 만든다. 원래 비밀번호로 되돌리는 기능은 없다. */
@Component
public class PasswordHashAdapter {

    private static final int MAX_PASSWORD_BYTES = 72;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(BCryptPasswordEncoder.BCryptVersion.$2B, 12);
    private final String dummyHash;

    /** 없는 아이디의 로그인도 해시 계산을 거치도록 비교용 해시를 한 번 준비한다. */
    public PasswordHashAdapter() {
        dummyHash = encoder.encode(UUID.randomUUID().toString());
    }

    /** 가입 비밀번호의 길이를 확인하고 매번 새로운 salt가 포함된 해시를 만든다. */
    public String hashForSignUp(String password) {
        validateLoginPassword(password);
        if (password.codePointCount(0, password.length()) < 8) {
            throw new MemberException(MemberException.Reason.INVALID_INPUT, "password");
        }
        return encoder.encode(password);
    }

    /** 로그인에는 가입 최소 길이를 적용하지 않아 이후 이관할 기존 비밀번호도 검사할 수 있다. */
    public void validateLoginPassword(String password) {
        // BCrypt의 제한은 글자 수가 아닌 UTF-8 바이트 수다. 한글 한 글자는 보통 3바이트다.
        if (password == null || password.isBlank()
                || password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            throw new MemberException(MemberException.Reason.INVALID_INPUT, "password");
        }
    }

    /** 저장한 해시와 비교한다. 회원이 없을 때도 계산해 응답 시간 차이를 줄인다. */
    public boolean matches(String password, String passwordHash) {
        validateLoginPassword(password);
        boolean matched = encoder.matches(password, passwordHash == null ? dummyHash : passwordHash);
        return passwordHash != null && matched;
    }
}
