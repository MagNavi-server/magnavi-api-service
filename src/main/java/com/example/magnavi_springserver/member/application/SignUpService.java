package com.example.magnavi_springserver.member.application;

import org.springframework.stereotype.Service;

import com.example.magnavi_springserver.member.infrastructure.MemberPersistenceAdapter;
import com.example.magnavi_springserver.member.infrastructure.PasswordHashAdapter;

/** 입력 확인 → 중복 확인 → 해싱 → 회원 저장 순서로 일반 가입을 진행한다. */
@Service
public class SignUpService {

    private final MemberPersistenceAdapter persistence;
    private final PasswordHashAdapter passwords;

    /** 저장과 비밀번호 처리의 세부 기술은 각각의 어댑터에 맡긴다. */
    public SignUpService(MemberPersistenceAdapter persistence, PasswordHashAdapter passwords) {
        this.persistence = persistence;
        this.passwords = passwords;
    }

    /** 오래 걸리는 해싱 중에는 DB 트랜잭션을 열어 두지 않는다. */
    public MemberProfile signUp(String loginId, String displayName, String email, String phoneNumber, String password) {
        String normalizedId = MemberInput.loginId(loginId);
        String checkedName = MemberInput.displayName(displayName);
        String checkedEmail = MemberInput.optionalContact(email, 254, "email");
        String checkedPhone = MemberInput.optionalContact(phoneNumber, 20, "phone_number");
        if (persistence.loginIdExists(normalizedId)) {
            throw new MemberException(MemberException.Reason.DUPLICATE_LOGIN_ID);
        }
        String passwordHash = passwords.hashForSignUp(password);
        return persistence.createLocalMember(normalizedId, checkedName, checkedEmail, checkedPhone, passwordHash);
    }
}
