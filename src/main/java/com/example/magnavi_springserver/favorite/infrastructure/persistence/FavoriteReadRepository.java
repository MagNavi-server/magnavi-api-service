package com.example.magnavi_springserver.favorite.infrastructure.persistence;

import java.util.List;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;

import com.example.magnavi_springserver.favorite.domain.Favorite;
import com.example.magnavi_springserver.favorite.domain.FavoriteTargetType;

/** 페이지 번호로 바꾸지 않고 앱의 skip 개수만큼 정확히 건너뛰는 내부 조회 도구다. */
@Repository
public class FavoriteReadRepository {

    private final EntityManager entityManager;

    /** 현재 트랜잭션에서 사용할 JPA 조회 도구를 받는다. */
    public FavoriteReadRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /** 소유자·실내 유형을 먼저 거른 뒤 DB에서 정렬과 조회 개수 제한을 적용한다. */
    public List<Favorite> findIndoorByMemberId(Long memberId, int skip, int limit) {
        return entityManager.createQuery("""
                select favorite from Favorite favorite
                where favorite.memberId = :memberId and favorite.targetType = :type
                order by favorite.createdAt desc, favorite.id desc
                """, Favorite.class)
                .setParameter("memberId", memberId)
                .setParameter("type", FavoriteTargetType.INDOOR_PLACE)
                .setFirstResult(skip).setMaxResults(limit).getResultList();
    }
}
