package com.example.magnavi_springserver.positioning.application;

import com.example.magnavi_springserver.place.application.IndoorLocationInfo;
import com.example.magnavi_springserver.positioning.domain.ModelEvent;

/** 모델 이벤트와 최상위 장소 조회 결과다. 매핑 없음과 준비 중을 구분한다. */
public record PositioningResult(ModelEvent event, MappingStatus mappingStatus, IndoorLocationInfo location) {
    /** 준비 중에는 조회하지 않고, 예측 결과에 대해서만 장소의 존재 여부를 표시한다. */
    public enum MappingStatus { NOT_REQUESTED, FOUND, NOT_FOUND }

    /** 상세 위치는 기본 로그 표현에 넣지 않는다. */
    @Override
    public String toString() { return "PositioningResult[mappingStatus=" + mappingStatus + "]"; }
}
