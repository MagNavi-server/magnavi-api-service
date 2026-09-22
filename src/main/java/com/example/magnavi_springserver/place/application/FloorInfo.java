package com.example.magnavi_springserver.place.application;

/** 층의 내부 ID와 실제 층 번호를 구분하고 소속 건물 정보도 함께 전달한다. */
public record FloorInfo(Long id, Long buildingId, String buildingName, int floorNumber, String name) {
}
