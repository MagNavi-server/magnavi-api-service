package com.example.magnavi_springserver.place.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.magnavi_springserver.place.domain.Building;

/** 건물 기준 정보를 저장·조회한다. 다른 모듈의 직접 호출용 공개 API가 아니다. */
public interface BuildingJpaRepository extends JpaRepository<Building, Long> {
}
