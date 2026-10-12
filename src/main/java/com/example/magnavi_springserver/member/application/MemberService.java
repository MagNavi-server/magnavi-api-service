package com.example.magnavi_springserver.member.application;

import org.springframework.stereotype.Service;

import com.example.magnavi_springserver.member.infrastructure.MemberPersistenceAdapter;
import com.example.magnavi_springserver.shared.security.AuthenticatedMember;

/** 로그인한 본인의 정보 조회와 이름 변경을 처리한다. */
@Service
public class MemberService {

    private final MemberPersistenceAdapter persistence;

    /** 회원 저장·조회 작업을 담당할 어댑터를 받는다. */
    public MemberService(MemberPersistenceAdapter persistence) {
        this.persistence = persistence;
    }

    /** 요청 본문이 아니라 검증된 인증 주체의 회원 번호로 조회한다. */
    public MemberProfile getMyProfile(AuthenticatedMember currentMember) {
        return persistence.findActiveProfile(currentMember.memberId());
    }

    /** 이름의 입력 조건을 확인한 뒤 인증된 본인의 이름만 변경한다. */
    public MemberProfile changeMyDisplayName(AuthenticatedMember currentMember, String displayName) {
        return persistence.changeDisplayName(currentMember.memberId(), MemberInput.displayName(displayName));
    }
}
