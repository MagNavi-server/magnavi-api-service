package com.example.magnavi_springserver.place.presentation;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.magnavi_springserver.place.application.BuildingInfo;
import com.example.magnavi_springserver.place.application.FloorInfo;
import com.example.magnavi_springserver.place.application.PlaceQueryService;

/** 공개된 실내 기준 정보의 조회 입구다. 데이터 등록·수정·삭제는 제공하지 않는다. */
@RestController
public class PlaceController {

    private final PlaceQueryService placeQueryService;

    /** HTTP 요청을 처리할 서비스에 연결한다. */
    public PlaceController(PlaceQueryService placeQueryService) {
        this.placeQueryService = placeQueryService;
    }

    /** 건물 목록을 반환한다. skip은 건너뛸 개수, limit은 받을 최대 개수다. */
    @GetMapping({"/buildings", "/buildings/"})
    public List<BuildingInfo> getBuildings(@RequestParam(defaultValue = "0") int skip,
                                           @RequestParam(defaultValue = "100") int limit) {
        return placeQueryService.getBuildings(skip, limit);
    }

    /** 건물 이름과 주소 등 기준 정보를 조회한다. */
    @GetMapping({"/buildings/{buildingId}", "/buildings/{buildingId}/"})
    public BuildingInfo getBuilding(@PathVariable Long buildingId) {
        return placeQueryService.getBuilding(buildingId);
    }

    /** 앱이 선택한 건물 안의 층 목록을 조회한다. */
    @GetMapping({"/buildings/{buildingId}/floors", "/buildings/{buildingId}/floors/"})
    public List<FloorInfo> getFloors(@PathVariable Long buildingId,
                                    @RequestParam(defaultValue = "0") int skip,
                                    @RequestParam(defaultValue = "100") int limit) {
        return placeQueryService.getFloors(buildingId, skip, limit);
    }

    /** 층의 내부 ID로 실제 층 번호와 소속 건물 정보를 조회한다. */
    @GetMapping({"/floors/{floorId}", "/floors/{floorId}/"})
    public FloorInfo getFloor(@PathVariable Long floorId) {
        return placeQueryService.getFloor(floorId);
    }

    /** 선택한 층에서 현재 사용할 수 있는 장소만 반환한다. */
    @GetMapping({"/floors/{floorId}/locations", "/floors/{floorId}/locations/"})
    public List<LocationResponse> getLocationsByFloor(@PathVariable Long floorId,
                                                      @RequestParam(defaultValue = "0") int skip,
                                                      @RequestParam(defaultValue = "100") int limit) {
        return placeQueryService.getLocationsByFloor(floorId, skip, limit).stream()
                .map(LocationResponse::from).toList();
    }

    /** 기존 /locations/ 경로와 배열 응답을 유지하며 전체 활성 장소를 페이지 범위로 조회한다. */
    @GetMapping({"/locations", "/locations/"})
    public List<LocationResponse> getLocations(@RequestParam(defaultValue = "0") int skip,
                                               @RequestParam(defaultValue = "100") int limit) {
        return placeQueryService.getLocations(skip, limit).stream().map(LocationResponse::from).toList();
    }

    /** 등록된 내부 장소 ID에 해당하는 상세 정보만 반환한다. */
    @GetMapping({"/locations/{locationId}", "/locations/{locationId}/"})
    public LocationResponse getLocation(@PathVariable Long locationId) {
        return LocationResponse.from(placeQueryService.getLocation(locationId));
    }
}
