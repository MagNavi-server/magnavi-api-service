package com.example.magnavi_springserver.favorite.infrastructure.persistence;

import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;

import com.example.magnavi_springserver.favorite.domain.Favorite;

/** 즐겨찾기를 저장하며 소유 회원 조건을 포함한 조회를 제공한다. 모듈 내부 저장소다. */
public interface FavoriteJpaRepository extends JpaRepository<Favorite, Long> {

    /** 즐겨찾기 번호가 맞아도 다른 회원 소유라면 조회하지 않는다. */
    Optional<Favorite> findByIdAndMemberId(Long id, Long memberId);

    /** 소유 회원 범위에서 생성 시각·PK 내림차순으로 안정적인 페이지를 조회한다. */
    Slice<Favorite> findByMemberIdOrderByCreatedAtDescIdDesc(Long memberId, Pageable pageable);
}
