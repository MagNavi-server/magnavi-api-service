package com.example.magnavi_springserver.place.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import com.example.magnavi_springserver.place.domain.ModelLocationMapping;

/** 모델 버전별 출력 코드를 장소에 연결한다. 다른 모듈의 직접 호출용 공개 API가 아니다. */
public interface ModelLocationMappingJpaRepository extends JpaRepository<ModelLocationMapping, Long> {

    /** 모델 구역·버전·코드를 모두 조건으로 사용해 다른 버전의 매핑 혼용을 막는다. */
    Optional<ModelLocationMapping> findByModelKeyAndModelVersionAndLocationCode(
            String modelKey, String modelVersion, String locationCode);
}
