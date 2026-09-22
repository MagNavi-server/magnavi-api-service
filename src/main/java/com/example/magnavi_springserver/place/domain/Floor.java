package com.example.magnavi_springserver.place.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import com.example.magnavi_springserver.shared.persistence.GeneratedIdEntity;
import com.example.magnavi_springserver.shared.validation.DomainValues;

/** 건물 안의 층을 표현하며 같은 건물의 층 번호 중복은 DB가 막는다. */
@Entity
@Table(name = "floors")
public class Floor extends GeneratedIdEntity {

    @Column(name = "building_id", nullable = false)
    private Long buildingId;

    @Column(name = "floor_number", nullable = false)
    private int floorNumber;

    @Column(name = "name", nullable = false, length = 50)
    private String name;

    /** JPA가 저장된 행을 객체로 복원할 때 사용한다. */
    protected Floor() {
    }

    /** 건물 참조와 표시 이름을 검사하며 지하층을 위해 음수 층 번호도 허용한다. */
    public Floor(Long buildingId, int floorNumber, String name) {
        this.buildingId = DomainValues.positiveId(buildingId, "건물 번호");
        this.floorNumber = floorNumber;
        this.name = DomainValues.requiredText(name, 50, "층 이름");
    }

    /** 층이 속한 건물의 내부 번호를 반환한다. */
    public Long getBuildingId() {
        return buildingId;
    }

    /** 지하층을 포함하는 숫자 층 번호를 반환한다. */
    public int getFloorNumber() {
        return floorNumber;
    }

    /** 지하 1층 등 표시용 층 이름을 반환한다. */
    public String getName() {
        return name;
    }
}
