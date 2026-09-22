package com.example.magnavi_springserver.place.infrastructure;

import java.util.List;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.example.magnavi_springserver.place.application.BuildingInfo;
import com.example.magnavi_springserver.place.application.FloorInfo;
import com.example.magnavi_springserver.place.application.IndoorLocationInfo;
import com.example.magnavi_springserver.place.application.PlaceException;
import com.example.magnavi_springserver.place.infrastructure.persistence.PlaceReadRepository;

/** 장소 조회를 짧은 읽기 전용 트랜잭션으로 처리한다. 조회 결과에는 필요한 값만 담는다. */
@Repository
@Transactional(readOnly = true)
public class PlacePersistenceAdapter {

    private final PlaceReadRepository queries;

    /** 필요한 건물·층·장소를 함께 읽는 JPA 조회 도구를 받는다. */
    public PlacePersistenceAdapter(PlaceReadRepository queries) {
        this.queries = queries;
    }

    /** 등록된 건물이 없으면 정상적인 빈 목록을 반환한다. */
    public List<BuildingInfo> findBuildings(int skip, int limit) {
        return queries.findBuildings(skip, limit);
    }

    /** 상세 조회 대상이 없으면 공통 404로 변환할 업무 오류를 전달한다. */
    public BuildingInfo findBuilding(Long buildingId) {
        return queries.findBuilding(buildingId)
                .orElseThrow(() -> new PlaceException(PlaceException.Reason.NOT_FOUND));
    }

    /** 없는 건물과 층이 아직 등록되지 않은 건물을 구분한다. */
    public List<FloorInfo> findFloors(Long buildingId, int skip, int limit) {
        findBuilding(buildingId);
        return queries.findFloors(buildingId, skip, limit);
    }

    /** 층과 그 층이 속한 건물 정보를 함께 조회한다. */
    public FloorInfo findFloor(Long floorId) {
        return queries.findFloor(floorId)
                .orElseThrow(() -> new PlaceException(PlaceException.Reason.NOT_FOUND));
    }

    /** 없는 층은 오류로, 장소가 없는 정상 층은 빈 목록으로 응답한다. */
    public List<IndoorLocationInfo> findLocationsByFloor(Long floorId, int skip, int limit) {
        findFloor(floorId);
        return queries.findLocationsByFloor(floorId, skip, limit);
    }

    /** 전체 장소를 읽지 않고 요청한 범위의 활성 장소만 가져온다. */
    public List<IndoorLocationInfo> findLocations(int skip, int limit) {
        return queries.findLocations(skip, limit);
    }

    /** 비활성 장소도 일반 조회에서는 없는 장소처럼 처리한다. 기존 DB 행은 보존한다. */
    public IndoorLocationInfo findLocation(Long locationId) {
        return queries.findLocation(locationId)
                .orElseThrow(() -> new PlaceException(PlaceException.Reason.NOT_FOUND));
    }
}
