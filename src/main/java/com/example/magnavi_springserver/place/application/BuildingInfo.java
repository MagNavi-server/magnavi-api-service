package com.example.magnavi_springserver.place.application;

/** 건물 조회에 필요한 값만 전달한다. JPA 엔티티를 응답으로 직접 내보내지 않는다. */
public record BuildingInfo(Long id, String name, String address) {
}
