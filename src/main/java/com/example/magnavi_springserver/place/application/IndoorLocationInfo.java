package com.example.magnavi_springserver.place.application;

/** 장소·층·건물을 한 번에 읽은 결과다. 모델 출력 코드나 사용자 위치 이력은 포함하지 않는다. */
public record IndoorLocationInfo(Long id, Long buildingId, String buildingName, String address,
                                 Long floorId, int floorNumber, String floorName, String name, String description) {
}
