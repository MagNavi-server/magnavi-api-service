package com.example.magnavi_springserver.favorite.application;

import java.time.Instant;

import com.example.magnavi_springserver.favorite.domain.Favorite;
import com.example.magnavi_springserver.favorite.domain.FavoriteTargetType;
import com.example.magnavi_springserver.place.application.IndoorLocationInfo;

/** 즐겨찾기 번호와 장소 번호를 구분한다. 사용 불가 장소는 location 없이 삭제에 필요한 번호만 유지한다. */
public record FavoriteInfo(Long id, FavoriteTargetType type, Long indoorLocationId, Instant createdAt,
                           boolean available, IndoorLocationInfo location) {

    /** 장소 이름 등을 복사해 저장하지 않고 현재 공개된 장소 정보와 합쳐 반환한다. */
    public static FavoriteInfo from(Favorite favorite, IndoorLocationInfo location) {
        return new FavoriteInfo(favorite.getId(), favorite.getTargetType(), favorite.getIndoorLocationId(),
                favorite.getCreatedAt(), location != null, location);
    }
}
