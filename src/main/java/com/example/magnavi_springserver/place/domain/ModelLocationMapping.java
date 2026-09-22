package com.example.magnavi_springserver.place.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import com.example.magnavi_springserver.shared.persistence.GeneratedIdEntity;
import com.example.magnavi_springserver.shared.validation.DomainValues;

/** 모델의 구역·버전·출력 코드와 실내 장소를 연결하는 기준 사전이다. */
@Entity
@Table(name = "model_location_mappings")
public class ModelLocationMapping extends GeneratedIdEntity {

    @Column(name = "model_key", nullable = false, length = 128)
    private String modelKey;

    @Column(name = "model_version", nullable = false, length = 64)
    private String modelVersion;

    @Column(name = "location_code", nullable = false, length = 50)
    private String locationCode;

    @Column(name = "indoor_location_id", nullable = false)
    private Long indoorLocationId;

    /** JPA가 저장된 행을 객체로 복원할 때 사용한다. */
    protected ModelLocationMapping() {
    }

    /** 등록 단계에서 구역 일치를 확인한 매핑을 만들며 식별자의 대소문자는 보존한다. */
    public ModelLocationMapping(String modelKey, String modelVersion, String locationCode, Long indoorLocationId) {
        this.modelKey = DomainValues.requiredText(modelKey, 128, "모델 키");
        this.modelVersion = DomainValues.requiredText(modelVersion, 64, "모델 버전");
        this.locationCode = DomainValues.requiredText(locationCode, 50, "모델 위치 코드");
        this.indoorLocationId = DomainValues.positiveId(indoorLocationId, "실내 장소 번호");
    }

    /** 모델 종류와 적용 구역을 구분하는 키를 반환한다. */
    public String getModelKey() {
        return modelKey;
    }

    /** 같은 코드의 의미를 구분하는 모델 버전을 반환한다. */
    public String getModelVersion() {
        return modelVersion;
    }

    /** DB 번호와 독립적인 모델 출력 코드를 그대로 반환한다. */
    public String getLocationCode() {
        return locationCode;
    }

    /** 이 코드가 가리키는 실제 실내 장소 번호를 반환한다. */
    public Long getIndoorLocationId() {
        return indoorLocationId;
    }
}
