package com.example.magnavi_springserver.place.infrastructure.persistence;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;

import com.example.magnavi_springserver.place.application.BuildingInfo;
import com.example.magnavi_springserver.place.application.FloorInfo;
import com.example.magnavi_springserver.place.application.IndoorLocationInfo;

/** 읽기 전용 JPA 조회다. 필요한 테이블을 JOIN해 장소 수만큼 추가 SQL이 생기지 않게 한다. */
@Repository
public class PlaceReadRepository {

    // select new는 조회한 컬럼으로 전달 객체를 만든다. 엔티티 전체를 외부에 내보내지 않는다.
    private static final String BUILDING_SELECT = """
            select new com.example.magnavi_springserver.place.application.BuildingInfo(
                building.id, building.name, building.address)
            from Building building
            """;

    private static final String FLOOR_SELECT = """
            select new com.example.magnavi_springserver.place.application.FloorInfo(
                floor.id, building.id, building.name, floor.floorNumber, floor.name)
            from Floor floor
            join Building building on building.id = floor.buildingId
            """;

    private static final String LOCATION_SELECT = """
            select new com.example.magnavi_springserver.place.application.IndoorLocationInfo(
                location.id, building.id, building.name, building.address,
                floor.id, floor.floorNumber, floor.name, location.name, location.description)
            from IndoorLocation location
            join Floor floor on floor.id = location.floorId
            join Building building on building.id = floor.buildingId
            where location.active = true
            """;

    private final EntityManager entityManager;

    /** Spring이 현재 트랜잭션에 맞는 EntityManager를 제공한다. */
    public PlaceReadRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /** 이름이 같은 건물도 안정적으로 탐색할 수 있도록 고유한 ID로 정렬한다. */
    public List<BuildingInfo> findBuildings(int skip, int limit) {
        return entityManager.createQuery(BUILDING_SELECT + " order by building.id", BuildingInfo.class)
                .setFirstResult(skip).setMaxResults(limit).getResultList();
    }

    /** 사용자가 보낸 ID는 쿼리 문자열에 붙이지 않고 매개변수로 전달한다. */
    public Optional<BuildingInfo> findBuilding(Long buildingId) {
        return entityManager.createQuery(BUILDING_SELECT + " where building.id = :buildingId", BuildingInfo.class)
                .setParameter("buildingId", buildingId).getResultList().stream().findFirst();
    }

    /** 같은 건물 안에서 지하층부터 층 번호순으로 조회한다. */
    public List<FloorInfo> findFloors(Long buildingId, int skip, int limit) {
        return entityManager.createQuery(FLOOR_SELECT
                        + " where floor.buildingId = :buildingId order by floor.floorNumber, floor.id", FloorInfo.class)
                .setParameter("buildingId", buildingId)
                .setFirstResult(skip).setMaxResults(limit).getResultList();
    }

    /** 층 상세에 필요한 건물 이름도 한 번의 조회로 가져온다. */
    public Optional<FloorInfo> findFloor(Long floorId) {
        return entityManager.createQuery(FLOOR_SELECT + " where floor.id = :floorId", FloorInfo.class)
                .setParameter("floorId", floorId).getResultList().stream().findFirst();
    }

    /** 페이지 번호로 바꾸지 않고 skip 개수만큼 정확히 건너뛴다. */
    public List<IndoorLocationInfo> findLocations(int skip, int limit) {
        return entityManager.createQuery(LOCATION_SELECT + " order by location.id", IndoorLocationInfo.class)
                .setFirstResult(skip).setMaxResults(limit).getResultList();
    }

    /** 활성 여부와 소속 층을 DB에서 먼저 걸러 낸 뒤 페이지 범위를 적용한다. */
    public List<IndoorLocationInfo> findLocationsByFloor(Long floorId, int skip, int limit) {
        return entityManager.createQuery(LOCATION_SELECT
                        + " and location.floorId = :floorId order by location.id", IndoorLocationInfo.class)
                .setParameter("floorId", floorId)
                .setFirstResult(skip).setMaxResults(limit).getResultList();
    }

    /** 활성 장소의 이름·설명·층·건물을 한 번에 가져온다. */
    public Optional<IndoorLocationInfo> findLocation(Long locationId) {
        return entityManager.createQuery(LOCATION_SELECT + " and location.id = :locationId", IndoorLocationInfo.class)
                .setParameter("locationId", locationId).getResultList().stream().findFirst();
    }
}
