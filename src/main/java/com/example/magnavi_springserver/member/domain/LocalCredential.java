package com.example.magnavi_springserver.member.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.example.magnavi_springserver.shared.persistence.AuditedEntity;
import com.example.magnavi_springserver.shared.validation.DomainValues;

/** 회원 번호를 PK로 사용해 한 회원의 일반 로그인 정보를 최대 하나만 저장한다. */
@Entity
@Table(name = "local_credentials")
public class LocalCredential extends AuditedEntity {

    @Id
    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(name = "login_id", nullable = false, length = 50)
    private String loginId;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    /** JPA가 일반 로그인 정보를 복원할 때 사용한다. */
    protected LocalCredential() {
    }

    /** 가입 트랜잭션에서 기존 회원과 해시 처리된 비밀번호를 연결한다. 원문을 전달하면 안 된다. */
    public LocalCredential(Long memberId, String loginId, String passwordHash) {
        this.memberId = DomainValues.positiveId(memberId, "회원 번호");
        this.loginId = normalizeLoginId(loginId);
        this.passwordHash = DomainValues.requiredText(passwordHash, 255, "비밀번호 해시");
    }

    /** 저장과 조회 모두 앞뒤 공백을 제거한다. 문자 대소문자의 동등성은 DB collation으로 판단한다. */
    public static String normalizeLoginId(String loginId) {
        if (loginId == null) {
            throw new IllegalArgumentException("로그인 ID는 필수입니다.");
        }
        return DomainValues.requiredText(loginId.strip(), 50, "로그인 ID");
    }

    /** 자격 정보가 연결된 회원 번호를 반환한다. */
    public Long getMemberId() {
        return memberId;
    }

    /** 공백을 정리해 저장한 로그인 ID를 반환한다. */
    public String getLoginId() {
        return loginId;
    }

    /** 비밀번호 검증에만 사용하는 해시다. 응답 DTO나 로그에 포함하지 않는다. */
    public String getPasswordHash() {
        return passwordHash;
    }
}
