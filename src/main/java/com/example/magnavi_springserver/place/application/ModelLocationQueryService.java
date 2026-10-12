package com.example.magnavi_springserver.place.application;

import org.springframework.stereotype.Service;

import com.example.magnavi_springserver.place.infrastructure.ModelLocationPersistenceAdapter;

/** 모델 키·버전·출력 코드를 실제 장소로 바꾼다. 다른 모듈은 이 서비스를 호출한다. */
@Service
public class ModelLocationQueryService {

    private final ModelLocationPersistenceAdapter persistence;

    /** 매핑과 장소를 읽는 작업을 같은 place 모듈의 DB 어댑터에 맡긴다. */
    public ModelLocationQueryService(ModelLocationPersistenceAdapter persistence) {
        this.persistence = persistence;
    }

    /** 세 식별자가 모두 일치하고 연결된 장소가 활성 상태일 때 건물·층·장소 정보를 반환한다. */
    public IndoorLocationInfo findLocation(String modelKey, String modelVersion, String locationCode) {
        validateIdentifier(modelKey, 128, "modelKey");
        validateIdentifier(modelVersion, 64, "modelVersion");
        validateIdentifier(locationCode, 50, "locationCode");

        // "007"과 "7", "v1"과 "V1"은 서로 다른 키다. 숫자로 바꾸거나 공백을 제거하지 않는다.
        return persistence.findLocation(modelKey, modelVersion, locationCode);
    }

    /** DB 컬럼과 같은 문자 수 제한을 적용하고, 잘못된 값 자체는 오류에 담지 않는다. */
    private void validateIdentifier(String value, int maxLength, String field) {
        if (value == null || value.isBlank() || value.codePointCount(0, value.length()) > maxLength) {
            throw new PlaceException(PlaceException.Reason.INVALID_INPUT, field);
        }
    }
}
