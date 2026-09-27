package com.example.magnavi_springserver.favorite.infrastructure.persistence;

import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.example.magnavi_springserver.favorite.domain.Favorite;
import com.example.magnavi_springserver.favorite.domain.FavoriteTargetType;

/** 즐겨찾기를 저장하며 소유 회원 조건을 포함한 조회를 제공한다. 모듈 내부 저장소다. */
public interface FavoriteJpaRepository extends JpaRepository<Favorite, Long> {

    /** 빠른 중복 안내용 조회다. 동시에 들어오는 요청은 DB UNIQUE가 마지막으로 검사한다. */
    boolean existsByMemberIdAndIndoorLocationId(Long memberId, Long indoorLocationId);

    /** 소유권 확인과 삭제를 SQL 한 번에 처리해 다른 회원·다른 유형의 행은 건드리지 않는다. */
    @Modifying
    @Query("delete from Favorite f where f.id = :id and f.memberId = :memberId and f.targetType = :type")
    int deleteOwned(@Param("id") Long id, @Param("memberId") Long memberId,
                    @Param("type") FavoriteTargetType type);

    /** 즐겨찾기 번호가 맞아도 다른 회원 소유라면 조회하지 않는다. */
    Optional<Favorite> findByIdAndMemberId(Long id, Long memberId);

    /** 소유 회원 범위에서 생성 시각·PK 내림차순으로 안정적인 페이지를 조회한다. */
    Slice<Favorite> findByMemberIdOrderByCreatedAtDescIdDesc(Long memberId, Pageable pageable);
}
