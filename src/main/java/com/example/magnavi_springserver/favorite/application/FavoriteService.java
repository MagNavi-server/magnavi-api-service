package com.example.magnavi_springserver.favorite.application;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.example.magnavi_springserver.favorite.infrastructure.FavoritePersistenceAdapter;
import com.example.magnavi_springserver.member.application.MemberException;
import com.example.magnavi_springserver.member.application.MemberService;
import com.example.magnavi_springserver.place.application.IndoorLocationInfo;
import com.example.magnavi_springserver.place.application.PlaceException;
import com.example.magnavi_springserver.place.application.PlaceQueryService;
import com.example.magnavi_springserver.shared.security.AuthenticatedMember;

/** 실내 즐겨찾기의 처리 순서를 관리한다. 다른 모듈의 저장소 대신 공개 서비스를 사용한다. */
@Service
public class FavoriteService {

    private final FavoritePersistenceAdapter persistence;
    private final MemberService members;
    private final PlaceQueryService places;

    /** 저장 작업과 회원·장소 확인 기능을 연결한다. */
    public FavoriteService(FavoritePersistenceAdapter persistence, MemberService members, PlaceQueryService places) {
        this.persistence = persistence;
        this.members = members;
        this.places = places;
    }

    /** 회원 번호는 토큰에서, 장소 정보는 DB에서 얻는다. 앱이 임의 소유자·표시 정보를 저장할 수 없다. */
    public FavoriteInfo create(AuthenticatedMember member, String type, Long indoorLocationId) {
        requireActiveMember(member);
        if (!"INDOOR_PLACE".equals(type)) {
            throw new FavoriteException(FavoriteException.Reason.INVALID_INPUT, "type");
        }
        validateId(indoorLocationId, "indoorLocationId");
        IndoorLocationInfo location = requireLocation(indoorLocationId);
        if (persistence.indoorExists(member.memberId(), indoorLocationId)) {
            throw new FavoriteException(FavoriteException.Reason.DUPLICATE);
        }
        // 사전 조회 뒤 동시에 같은 요청이 와도 DB 고유 제약이 최종 중복을 막는다.
        var favorite = persistence.createIndoor(member.memberId(), indoorLocationId);
        return FavoriteInfo.from(favorite, location);
    }

    /** 본인의 실내 항목만 페이지로 읽고 장소는 한 번에 조회한다. 비활성 장소도 삭제할 수 있게 남긴다. */
    public List<FavoriteInfo> getMyFavorites(AuthenticatedMember member, int skip, int limit) {
        requireActiveMember(member);
        if (skip < 0 || skip > 10_000) {
            throw new FavoriteException(FavoriteException.Reason.INVALID_INPUT, "skip");
        }
        if (limit < 1 || limit > 100) {
            throw new FavoriteException(FavoriteException.Reason.INVALID_INPUT, "limit");
        }
        var favorites = persistence.findMyIndoorFavorites(member.memberId(), skip, limit);
        if (favorites.isEmpty()) {
            return List.of();
        }
        var locationIds = favorites.stream().map(favorite -> favorite.getIndoorLocationId()).toList();
        var locations = places.getLocationsByIds(locationIds).stream()
                .collect(Collectors.toMap(IndoorLocationInfo::id, Function.identity()));
        return favorites.stream()
                .map(favorite -> FavoriteInfo.from(favorite, locations.get(favorite.getIndoorLocationId())))
                .toList();
    }

    /** 장소 상태와 무관하게 본인이 소유한 실내 즐겨찾기 한 건만 삭제한다. */
    public void delete(AuthenticatedMember member, Long favoriteId) {
        requireActiveMember(member);
        validateId(favoriteId, "favoriteId");
        persistence.deleteMyIndoorFavorite(member.memberId(), favoriteId);
    }

    /** 토큰이 유효해도 이미 삭제된 회원이면 업무를 진행하지 않는다. */
    private void requireActiveMember(AuthenticatedMember member) {
        if (member == null) {
            throw new FavoriteException(FavoriteException.Reason.UNAUTHENTICATED);
        }
        try {
            members.getMyProfile(member);
        } catch (MemberException exception) {
            if (exception.getReason() == MemberException.Reason.UNAUTHENTICATED) {
                throw new FavoriteException(FavoriteException.Reason.UNAUTHENTICATED);
            }
            throw exception;
        }
    }

    /** 장소 모듈의 대상 없음 응답을 즐겨찾기의 공통 404로 변환한다. */
    private IndoorLocationInfo requireLocation(Long locationId) {
        try {
            return places.getLocation(locationId);
        } catch (PlaceException exception) {
            if (exception.getReason() == PlaceException.Reason.NOT_FOUND) {
                throw new FavoriteException(FavoriteException.Reason.NOT_FOUND);
            }
            throw exception;
        }
    }

    /** 잘못된 번호는 DB에 전달하기 전에 입력 오류로 처리한다. */
    private void validateId(Long id, String field) {
        if (id == null || id <= 0) {
            throw new FavoriteException(FavoriteException.Reason.INVALID_INPUT, field);
        }
    }
}
