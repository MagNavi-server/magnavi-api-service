package com.example.magnavi_springserver.member.infrastructure.persistence;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.example.magnavi_springserver.member.domain.LocalCredential;

/** 회원별 일반 로그인 정보를 저장하고 동일한 공백 정리 규칙으로 조회한다. */
public interface LocalCredentialJpaRepository extends JpaRepository<LocalCredential, Long> {

    /** 저장 시와 같은 입력 정리를 거쳐 DB 고유 제약과 같은 비교 규칙을 사용한다. */
    default Optional<LocalCredential> findByLoginId(String loginId) {
        return findByNormalizedLoginId(LocalCredential.normalizeLoginId(loginId));
    }

    /** 정리된 로그인 ID의 비교는 실제 MySQL 컬럼의 collation을 따른다. */
    @Query("select credential from LocalCredential credential where credential.loginId = :loginId")
    Optional<LocalCredential> findByNormalizedLoginId(@Param("loginId") String loginId);
}
