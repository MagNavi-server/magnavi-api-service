package com.example.magnavi_springserver.place.application;

import java.math.BigDecimal;
import java.util.List;

/** DB에 저장하지 않는 검색 결과다. 제공자 내부 응답이나 가짜 장소 ID는 노출하지 않는다. */
public record PlaceSearchResult(String source, List<Item> items) {

    /** 결과 순서를 유지하며 반환 후 목록이 바뀌지 않도록 복사한다. */
    public PlaceSearchResult {
        items = List.copyOf(items);
    }

    /** 좌표는 WGS84의 도 단위다. 링크는 업체 홈페이지일 수 있어 고유 ID로 쓰지 않는다. */
    public record Item(String name, String category, String address, String roadAddress,
                       String link, BigDecimal latitude, BigDecimal longitude) {
    }
}
