package com.example.magnavi_springserver.place.infrastructure;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.example.magnavi_springserver.place.application.IndoorLocationInfo;
import com.example.magnavi_springserver.place.application.PlaceException;
import com.example.magnavi_springserver.place.infrastructure.persistence.ModelLocationMappingJpaRepository;
import com.example.magnavi_springserver.place.infrastructure.persistence.PlaceReadRepository;

/** 모델 매핑과 활성 장소 조회를 하나의 짧은 읽기 전용 트랜잭션으로 묶는다. */
@Repository
@Transactional(readOnly = true)
public class ModelLocationPersistenceAdapter {

    private final ModelLocationMappingJpaRepository mappings;
    private final PlaceReadRepository places;

    /** 이미 구현된 매핑 저장소와 건물·층을 함께 읽는 장소 조회 도구를 재사용한다. */
    public ModelLocationPersistenceAdapter(ModelLocationMappingJpaRepository mappings, PlaceReadRepository places) {
        this.mappings = mappings;
        this.places = places;
    }

    /** 매핑 누락과 비활성 장소는 대상 없음으로 처리하며 다른 모델·버전의 장소로 대체하지 않는다. */
    public IndoorLocationInfo findLocation(String modelKey, String modelVersion, String locationCode) {
        var mapping = mappings.findByModelKeyAndModelVersionAndLocationCode(modelKey, modelVersion, locationCode)
                .orElseThrow(() -> new PlaceException(PlaceException.Reason.NOT_FOUND));

        // 모델 코드를 장소 ID로 해석하지 않고, 매핑 행에 등록된 장소 ID만 사용한다.
        // 기존 JOIN 조회를 재사용하므로 건물·층 정보를 각각 추가 조회하지 않는다.
        return places.findLocation(mapping.getIndoorLocationId())
                .orElseThrow(() -> new PlaceException(PlaceException.Reason.NOT_FOUND));
    }
}
