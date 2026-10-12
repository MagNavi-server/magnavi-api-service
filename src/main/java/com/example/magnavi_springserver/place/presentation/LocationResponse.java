package com.example.magnavi_springserver.place.presentation;

import com.fasterxml.jackson.annotation.JsonProperty;

import com.example.magnavi_springserver.place.application.IndoorLocationInfo;

/** 기존 장소 필드와 문자열 ID를 유지하고 건물·층을 탐색할 정보를 덧붙인다. */
public record LocationResponse(String id, @JsonProperty("location_name") String locationName,
                               String description, int floor, String address,
                               Long buildingId, String buildingName, Long floorId, String floorName) {

    /** floor는 실제 층 번호이고 floorId는 DB 식별자다. 장소 id는 모델 코드가 아니다. */
    public static LocationResponse from(IndoorLocationInfo location) {
        return new LocationResponse(location.id().toString(), location.name(), location.description(),
                location.floorNumber(), location.address(), location.buildingId(), location.buildingName(),
                location.floorId(), location.floorName());
    }
}
