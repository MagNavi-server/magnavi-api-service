package com.example.magnavi_springserver.favorite.infrastructure;

import java.util.List;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.example.magnavi_springserver.favorite.application.FavoriteException;
import com.example.magnavi_springserver.favorite.domain.Favorite;
import com.example.magnavi_springserver.favorite.domain.FavoriteTargetType;
import com.example.magnavi_springserver.favorite.infrastructure.persistence.FavoriteJpaRepository;
import com.example.magnavi_springserver.favorite.infrastructure.persistence.FavoriteReadRepository;

/** 즐겨찾기 DB 작업만 짧은 트랜잭션으로 처리한다. 다른 모듈의 엔티티를 직접 읽지 않는다. */
@Repository
public class FavoritePersistenceAdapter {

    private final FavoriteJpaRepository favorites;
    private final FavoriteReadRepository queries;

    /** 같은 모듈 안의 저장·조회 도구를 연결한다. */
    public FavoritePersistenceAdapter(FavoriteJpaRepository favorites, FavoriteReadRepository queries) {
        this.favorites = favorites;
        this.queries = queries;
    }

    /** 동일 회원·장소의 기존 등록 여부를 확인한다. 다른 회원의 등록은 영향을 주지 않는다. */
    @Transactional(readOnly = true)
    public boolean indoorExists(Long memberId, Long locationId) {
        return favorites.existsByMemberIdAndIndoorLocationId(memberId, locationId);
    }

    /** 실제 INSERT까지 실행해 동시 등록의 제약 위반을 이 메서드에서 변환한다. 실패하면 롤백한다. */
    @Transactional
    public Favorite createIndoor(Long memberId, Long locationId) {
        try {
            return favorites.saveAndFlush(Favorite.indoor(memberId, locationId));
        } catch (DataIntegrityViolationException exception) {
            String constraint = constraintName(exception);
            if (constraint.equals("uk_favorites_member_target") || constraint.equals("uk_favorites_member_indoor")) {
                throw new FavoriteException(FavoriteException.Reason.DUPLICATE);
            }
            // 장소·회원 확인 직후 삭제된 경우도 FK가 잘못된 참조 저장을 막는다.
            if (constraint.equals("fk_favorites_indoor")) {
                throw new FavoriteException(FavoriteException.Reason.NOT_FOUND);
            }
            if (constraint.equals("fk_favorites_member")) {
                throw new FavoriteException(FavoriteException.Reason.UNAUTHENTICATED);
            }
            throw exception;
        }
    }

    /** 엔티티 안에는 ID·값만 있으므로 트랜잭션 종료 후에도 응답을 안전하게 만들 수 있다. */
    @Transactional(readOnly = true)
    public List<Favorite> findMyIndoorFavorites(Long memberId, int skip, int limit) {
        return queries.findIndoorByMemberId(memberId, skip, limit);
    }

    /** 이미 삭제된 항목과 타인 소유 항목은 같은 404로 처리한다. 장소 원본에는 접근하지 않는다. */
    @Transactional
    public void deleteMyIndoorFavorite(Long memberId, Long favoriteId) {
        if (favorites.deleteOwned(favoriteId, memberId, FavoriteTargetType.INDOOR_PLACE) == 0) {
            throw new FavoriteException(FavoriteException.Reason.NOT_FOUND);
        }
    }

    /** SQL 메시지를 해석하거나 공개하지 않고 스키마에 정의된 제약 이름만 확인한다. */
    private String constraintName(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation && violation.getConstraintName() != null) {
                String name = violation.getConstraintName();
                return name.substring(name.lastIndexOf('.') + 1);
            }
        }
        return "";
    }
}
