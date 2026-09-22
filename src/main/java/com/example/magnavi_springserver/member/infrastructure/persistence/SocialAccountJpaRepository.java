package com.example.magnavi_springserver.member.infrastructure.persistence;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.magnavi_springserver.member.domain.SocialAccount;
import com.example.magnavi_springserver.member.domain.SocialProvider;

/** 소셜 제공자의 검증된 ID로 연결을 찾는 member 내부 저장소다. */
public interface SocialAccountJpaRepository extends JpaRepository<SocialAccount, Long> {

    /** 이메일 대신 제공자와 사용자 ID를 모두 조건으로 사용한다. */
    Optional<SocialAccount> findByProviderAndProviderUserId(SocialProvider provider, String providerUserId);
}
