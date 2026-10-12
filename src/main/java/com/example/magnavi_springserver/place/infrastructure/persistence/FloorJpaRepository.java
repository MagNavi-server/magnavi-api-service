package com.example.magnavi_springserver.place.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import com.example.magnavi_springserver.place.domain.Floor;

/** 건물 내부의 층을 저장·조회한다. 다른 모듈의 직접 호출용 공개 API가 아니다. */
public interface FloorJpaRepository extends JpaRepository<Floor, Long> {

    /** 건물 안에서 고유한 층 번호로 조회한다. */
    Optional<Floor> findByBuildingIdAndFloorNumber(Long buildingId, int floorNumber);
}
