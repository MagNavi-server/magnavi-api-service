package com.example.magnavi_springserver.favorite.presentation;

import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import tools.jackson.databind.JsonNode;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.magnavi_springserver.favorite.application.FavoriteException;
import com.example.magnavi_springserver.favorite.application.FavoriteInfo;
import com.example.magnavi_springserver.favorite.application.FavoriteService;
import com.example.magnavi_springserver.shared.security.AuthenticatedMember;

/** 로그인한 본인의 실내 즐겨찾기 API다. 소유 회원과 즐겨찾기 번호는 서버가 결정한다. */
@RestController
public class FavoriteController {

    private final FavoriteService service;

    /** 업무 처리 순서를 담당하는 서비스를 연결한다. */
    public FavoriteController(FavoriteService service) {
        this.service = service;
    }

    /** 실내 장소 번호만 받아 서버에서 새 즐겨찾기 번호를 만들고 201로 반환한다. */
    @PostMapping(value = {"/favorites", "/favorites/"}, consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<FavoriteInfo> create(
            @AuthenticationPrincipal(errorOnInvalidType = true) AuthenticatedMember member,
            @RequestBody CreateFavoriteRequest request) {
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(service.create(member, request.type(), request.locationId()));
    }

    /** 개인 목록은 캐시하지 않으며 지원하지 않는 필터나 중복 쿼리 파라미터는 거절한다. */
    @GetMapping({"/favorites", "/favorites/"})
    public ResponseEntity<List<FavoriteInfo>> list(
            @AuthenticationPrincipal(errorOnInvalidType = true) AuthenticatedMember member,
            @RequestParam(defaultValue = "0") int skip,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam MultiValueMap<String, String> parameters) {
        for (var entry : parameters.entrySet()) {
            if (!Set.of("skip", "limit").contains(entry.getKey()) || entry.getValue().size() != 1) {
                throw new FavoriteException(FavoriteException.Reason.INVALID_INPUT, "parameters");
            }
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.getMyFavorites(member, skip, limit));
    }

    /** 즐겨찾기 자체 번호를 삭제하며 성공하면 본문 없는 204를 반환한다. */
    @DeleteMapping({"/favorites/{favoriteId}", "/favorites/{favoriteId}/"})
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal(errorOnInvalidType = true) AuthenticatedMember member,
            @PathVariable Long favoriteId) {
        service.delete(member, favoriteId);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    /** 이번 단계에서는 INDOOR_PLACE만 허용한다. 기존 앱의 임의 ID·표시 정보 저장과는 별도 계약이다. */
    public record CreateFavoriteRequest(String type, JsonNode indoorLocationId) {

        /** 1.5가 1로 잘려 다른 장소를 저장하지 않도록 JSON 원래 값이 정수인지 먼저 확인한다. */
        public Long locationId() {
            if (indoorLocationId == null || !indoorLocationId.isIntegralNumber() || !indoorLocationId.canConvertToLong()) {
                throw new FavoriteException(FavoriteException.Reason.INVALID_INPUT, "indoorLocationId");
            }
            return indoorLocationId.asLong();
        }

        /** 이전 앱의 id·name이나 임의 memberId가 조용히 무시되지 않도록 계약 밖 필드를 거절한다. */
        @JsonAnySetter
        public void rejectUnknownField(String name, Object value) {
            throw new FavoriteException(FavoriteException.Reason.INVALID_INPUT, "body");
        }
    }
}
