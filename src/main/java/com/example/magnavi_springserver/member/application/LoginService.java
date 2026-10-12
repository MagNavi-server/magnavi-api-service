package com.example.magnavi_springserver.member.application;

import org.springframework.stereotype.Service;

import com.example.magnavi_springserver.member.infrastructure.JwtTokenProvider;
import com.example.magnavi_springserver.member.infrastructure.MemberPersistenceAdapter;
import com.example.magnavi_springserver.member.infrastructure.PasswordHashAdapter;

/** 일반 아이디·비밀번호를 확인한 뒤 MagNavi 액세스 토큰을 발급한다. */
@Service
public class LoginService {

    private final MemberPersistenceAdapter persistence;
    private final PasswordHashAdapter passwords;
    private final JwtTokenProvider tokens;

    /** DB 조회, 해시 비교, 토큰 발급 도구를 받아 로그인 순서만 관리한다. */
    public LoginService(MemberPersistenceAdapter persistence, PasswordHashAdapter passwords, JwtTokenProvider tokens) {
        this.persistence = persistence;
        this.passwords = passwords;
        this.tokens = tokens;
    }

    /** 아이디가 없는 경우와 비밀번호가 틀린 경우를 같은 실패로 안내한다. */
    public String login(String loginId, String password) {
        String normalizedId;
        try {
            normalizedId = MemberInput.loginId(loginId);
        } catch (MemberException exception) {
            throw new MemberException(MemberException.Reason.INVALID_INPUT, "username");
        }
        passwords.validateLoginPassword(password);
        LoginAccount account = persistence.findLoginAccount(normalizedId).orElse(null);
        boolean passwordMatches = passwords.matches(password, account == null ? null : account.passwordHash());
        if (account == null || !passwordMatches || !account.active()) {
            throw new MemberException(MemberException.Reason.INVALID_CREDENTIALS);
        }
        return tokens.issueAccessToken(account.memberId());
    }
}
