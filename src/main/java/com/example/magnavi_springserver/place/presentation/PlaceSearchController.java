package com.example.magnavi_springserver.place.presentation;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import java.util.Set;
import org.springframework.util.MultiValueMap;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.example.magnavi_springserver.place.application.PlaceSearchResult;
import com.example.magnavi_springserver.place.application.PlaceSearchService;
import com.example.magnavi_springserver.place.application.PlaceException;
import com.example.magnavi_springserver.shared.security.AuthenticatedMember;

/** 로그인한 사용자의 외부 장소 검색 요청을 받는다. 검색 결과는 캐시·DB에 저장하지 않는다. */
@RestController
public class PlaceSearchController {

    private final PlaceSearchService service;

    /** 요청 검증과 실제 검색을 담당하는 서비스를 연결한다. */
    public PlaceSearchController(PlaceSearchService service) {
        this.service = service;
    }

    /** 한 번에 최대 5개를 제공한다. 제공자가 지원하지 않는 다음 페이지 번호는 받지 않는다. */
    @GetMapping({"/places/search", "/places/search/"})
    public ResponseEntity<PlaceSearchResult> search(
            @AuthenticationPrincipal(errorOnInvalidType = true) AuthenticatedMember member,
            @RequestParam String query,
            @RequestParam(defaultValue = "5") int display,
            @RequestParam(defaultValue = "random") String sort,
            @RequestParam MultiValueMap<String, String> parameters) {
        // start=2 같은 지원하지 않는 옵션이나 중복 입력을 조용히 무시하지 않는다.
        for (var entry : parameters.entrySet()) {
            if (!Set.of("query", "display", "sort").contains(entry.getKey()) || entry.getValue().size() != 1) {
                throw new PlaceException(PlaceException.Reason.INVALID_INPUT, "parameters");
            }
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.search(member, query, display, sort));
    }
}
