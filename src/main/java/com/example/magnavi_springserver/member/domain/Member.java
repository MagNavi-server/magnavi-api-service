package com.example.magnavi_springserver.member.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.example.magnavi_springserver.shared.persistence.GeneratedIdEntity;
import com.example.magnavi_springserver.shared.validation.DomainValues;

/** 로그인 수단과 독립적인 서비스 회원 프로필을 보관하며 비밀번호는 포함하지 않는다. */
@Entity
@Table(name = "members")
public class Member extends GeneratedIdEntity {

    @Column(name = "display_name", nullable = false, length = 50)
    private String displayName;

    @Column(name = "email", length = 254)
    private String email;

    @Column(name = "phone_number", length = 20)
    private String phoneNumber;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "role", nullable = false, length = 20)
    private MemberRole role;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 20)
    private MemberStatus status;

    /** JPA가 기존 회원을 복원할 때 사용한다. */
    protected Member() {
    }

    /** 새 회원의 기본 권한·상태와 연락 정보를 초기화한다. 로그인 연결은 별도로 저장한다. */
    public Member(String displayName, String email, String phoneNumber) {
        this.displayName = DomainValues.requiredText(displayName, 50, "표시 이름");
        this.email = DomainValues.optionalText(email, 254, "이메일");
        this.phoneNumber = DomainValues.optionalText(phoneNumber, 20, "전화번호");
        this.role = MemberRole.USER;
        this.status = MemberStatus.ACTIVE;
    }

    /** 새 표시 이름을 검사해 적용한다. 권한과 로그인 식별자는 변경하지 않는다. */
    public void changeDisplayName(String displayName) {
        this.displayName = DomainValues.requiredText(displayName, 50, "표시 이름");
    }

    /** 앱 표시용 이름을 반환한다. */
    public String getDisplayName() {
        return displayName;
    }

    /** 소셜 계정 연결 기준으로 사용하지 않는 연락용 이메일을 반환한다. */
    public String getEmail() {
        return email;
    }

    /** 연락용 전화번호를 반환한다. */
    public String getPhoneNumber() {
        return phoneNumber;
    }

    /** 인증 구현에서 사용할 서비스 권한을 반환한다. */
    public MemberRole getRole() {
        return role;
    }

    /** 회원 상태를 반환하며 토큰 효력 변경은 별도의 인증 정책이다. */
    public MemberStatus getStatus() {
        return status;
    }
}
