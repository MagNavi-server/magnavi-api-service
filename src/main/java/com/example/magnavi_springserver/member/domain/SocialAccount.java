package com.example.magnavi_springserver.member.domain;

import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.example.magnavi_springserver.shared.persistence.GeneratedIdEntity;
import com.example.magnavi_springserver.shared.validation.DomainValues;

/** 검증된 제공자 사용자 ID를 서비스 회원에게 연결하며 이메일·제공자 토큰은 식별에 쓰지 않는다. */
@Entity
@Table(name = "social_accounts")
public class SocialAccount extends GeneratedIdEntity {

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "provider", nullable = false, length = 20)
    private SocialProvider provider;

    @Column(name = "provider_user_id", nullable = false, length = 255)
    private String providerUserId;

    /** JPA가 기존 연결을 복원할 때 사용한다. */
    protected SocialAccount() {
    }

    /** 제공자 검증을 마친 식별자만 받는다. 이 생성자 자체는 외부 인증을 수행하지 않는다. */
    public SocialAccount(Long memberId, SocialProvider provider, String providerUserId) {
        this.memberId = DomainValues.positiveId(memberId, "회원 번호");
        this.provider = Objects.requireNonNull(provider, "소셜 제공자는 필수입니다.");
        this.providerUserId = DomainValues.requiredText(providerUserId, 255, "소셜 사용자 ID");
    }

    /** 연결된 서비스 회원의 내부 번호를 반환한다. */
    public Long getMemberId() {
        return memberId;
    }

    /** 사용자 ID의 식별 범위를 결정하는 제공자를 반환한다. */
    public SocialProvider getProvider() {
        return provider;
    }

    /** 제공자가 발급한 ID를 대소문자 변경 없이 반환한다. */
    public String getProviderUserId() {
        return providerUserId;
    }
}
