package com.example.magnavi_springserver.member.infrastructure;

import java.util.Optional;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.example.magnavi_springserver.member.application.LoginAccount;
import com.example.magnavi_springserver.member.application.MemberException;
import com.example.magnavi_springserver.member.application.MemberProfile;
import com.example.magnavi_springserver.member.domain.LocalCredential;
import com.example.magnavi_springserver.member.domain.Member;
import com.example.magnavi_springserver.member.domain.MemberStatus;
import com.example.magnavi_springserver.member.infrastructure.persistence.LocalCredentialJpaRepository;
import com.example.magnavi_springserver.member.infrastructure.persistence.MemberJpaRepository;

/** 짧은 DB 작업만 맡는다. 비밀번호 해싱과 토큰 발급은 이 트랜잭션 밖에서 처리한다. */
@Repository
public class MemberPersistenceAdapter {

    private final MemberJpaRepository members;
    private final LocalCredentialJpaRepository credentials;

    /** 같은 회원 모듈에 속한 두 JPA 저장소를 연결한다. */
    public MemberPersistenceAdapter(MemberJpaRepository members, LocalCredentialJpaRepository credentials) {
        this.members = members;
        this.credentials = credentials;
    }

    /** 빠른 중복 안내를 위한 사전 확인이며 최종 중복 방지는 DB 고유 제약이 담당한다. */
    @Transactional(readOnly = true)
    public boolean loginIdExists(String loginId) {
        return credentials.findByLoginId(loginId).isPresent();
    }

    /** 두 저장을 한 트랜잭션으로 묶어 로그인 정보 저장 실패 시 회원 생성도 되돌린다. */
    @Transactional
    public MemberProfile createLocalMember(String loginId, String displayName, String email,
                                           String phoneNumber, String passwordHash) {
        Member member = members.save(new Member(displayName, email, phoneNumber));
        try {
            // flush는 SQL을 지금 실행한다. 이 메서드 안에서 동시 가입의 중복도 확인할 수 있다.
            credentials.saveAndFlush(new LocalCredential(member.getId(), loginId, passwordHash));
        } catch (DataIntegrityViolationException exception) {
            if (isDuplicateLoginId(exception)) {
                throw new MemberException(MemberException.Reason.DUPLICATE_LOGIN_ID);
            }
            // FK 오류나 다른 DB 장애를 아이디 중복으로 잘못 안내하지 않는다.
            throw exception;
        }
        return profile(member, loginId);
    }

    /** 트랜잭션이 끝난 뒤에도 비밀번호를 검사할 수 있도록 필요한 값만 복사한다. */
    @Transactional(readOnly = true)
    public Optional<LoginAccount> findLoginAccount(String loginId) {
        return credentials.findByLoginId(loginId).flatMap(credential ->
                members.findById(credential.getMemberId()).map(member -> new LoginAccount(
                        member.getId(), credential.getPasswordHash(), member.getStatus() == MemberStatus.ACTIVE)));
    }

    /** 토큰이 유효해도 현재 회원이 존재하고 이용 가능한 상태인지 다시 확인한다. */
    @Transactional(readOnly = true)
    public MemberProfile findActiveProfile(Long memberId) {
        Member member = activeMember(memberId);
        String loginId = credentials.findById(memberId).map(LocalCredential::getLoginId).orElse(null);
        return profile(member, loginId);
    }

    /** 인증된 회원의 이름만 바꾼다. JPA가 트랜잭션 종료 때 변경 내용을 저장한다. */
    @Transactional
    public MemberProfile changeDisplayName(Long memberId, String displayName) {
        Member member = activeMember(memberId);
        member.changeDisplayName(displayName);
        String loginId = credentials.findById(memberId).map(LocalCredential::getLoginId).orElse(null);
        return profile(member, loginId);
    }

    /** 삭제되었거나 이용할 수 없는 회원의 토큰으로 업무를 계속하지 못하게 한다. */
    private Member activeMember(Long memberId) {
        return members.findById(memberId)
                .filter(member -> member.getStatus() == MemberStatus.ACTIVE)
                .orElseThrow(() -> new MemberException(MemberException.Reason.UNAUTHENTICATED));
    }

    /** 회원 엔티티에서 외부 전달이 가능한 프로필만 골라 담는다. */
    private MemberProfile profile(Member member, String loginId) {
        return new MemberProfile(member.getId(), loginId, member.getDisplayName(), member.getEmail(), member.getPhoneNumber());
    }

    /** SQL 원문이나 입력값 대신 이미 정의한 고유 제약의 이름으로 충돌을 식별한다. */
    private boolean isDuplicateLoginId(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                String name = violation.getConstraintName();
                return name != null && (name.equals("uk_local_credentials_login")
                        || name.endsWith(".uk_local_credentials_login"));
            }
        }
        return false;
    }
}
