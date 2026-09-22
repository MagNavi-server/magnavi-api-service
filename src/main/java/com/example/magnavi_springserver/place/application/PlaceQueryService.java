package com.example.magnavi_springserver.place.application;

import java.util.List;

import org.springframework.stereotype.Service;

import com.example.magnavi_springserver.place.infrastructure.PlacePersistenceAdapter;

/** 조회 범위를 검사하고 건물 → 층 → 장소 탐색을 제공한다. 등록·모델 매핑은 별도 기능이다. */
@Service
public class PlaceQueryService {

    private static final int MAX_SKIP = 10_000;
    private static final int MAX_LIMIT = 100;
    private final PlacePersistenceAdapter persistence;

    /** DB 조회의 세부 구현은 같은 모듈의 저장 어댑터에 맡긴다. */
    public PlaceQueryService(PlacePersistenceAdapter persistence) {
        this.persistence = persistence;
    }

    /** 건물 목록을 내부 ID 순서로 일정한 개수만 조회한다. */
    public List<BuildingInfo> getBuildings(int skip, int limit) {
        validateRange(skip, limit);
        return persistence.findBuildings(skip, limit);
    }

    /** 양수인 내부 건물 ID로 상세 정보를 조회한다. */
    public BuildingInfo getBuilding(Long buildingId) {
        validateId(buildingId, "buildingId");
        return persistence.findBuilding(buildingId);
    }

    /** 지정한 건물에 속하는 층만 조회한다. 다른 건물의 같은 층 번호가 섞이지 않는다. */
    public List<FloorInfo> getFloors(Long buildingId, int skip, int limit) {
        validateId(buildingId, "buildingId");
        validateRange(skip, limit);
        return persistence.findFloors(buildingId, skip, limit);
    }

    /** floorId는 DB 식별자다. 지하 1층을 뜻하는 floorNumber=-1과는 다르다. */
    public FloorInfo getFloor(Long floorId) {
        validateId(floorId, "floorId");
        return persistence.findFloor(floorId);
    }

    /** 지정한 층의 사용 가능한 장소만 조회한다. */
    public List<IndoorLocationInfo> getLocationsByFloor(Long floorId, int skip, int limit) {
        validateId(floorId, "floorId");
        validateRange(skip, limit);
        return persistence.findLocationsByFloor(floorId, skip, limit);
    }

    /** 기존 /locations 목록처럼 전체 활성 장소를 제한된 범위로 조회한다. */
    public List<IndoorLocationInfo> getLocations(int skip, int limit) {
        validateRange(skip, limit);
        return persistence.findLocations(skip, limit);
    }

    /** 모델 코드가 아닌 내부 장소 ID로 활성 장소 상세를 조회한다. */
    public IndoorLocationInfo getLocation(Long locationId) {
        validateId(locationId, "locationId");
        return persistence.findLocation(locationId);
    }

    /** 한 번에 너무 많이 읽거나 지나치게 큰 OFFSET 조회를 실행하지 않게 제한한다. */
    private void validateRange(int skip, int limit) {
        if (skip < 0 || skip > MAX_SKIP) {
            throw new PlaceException(PlaceException.Reason.INVALID_INPUT, "skip");
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new PlaceException(PlaceException.Reason.INVALID_INPUT, "limit");
        }
    }

    /** ID가 올바른 범위인지 DB에 접근하기 전에 검사한다. */
    private void validateId(Long id, String field) {
        if (id == null || id <= 0) {
            throw new PlaceException(PlaceException.Reason.INVALID_INPUT, field);
        }
    }
}
