package com.example.magnavi_springserver.place.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import com.example.magnavi_springserver.shared.persistence.GeneratedIdEntity;
import com.example.magnavi_springserver.shared.validation.DomainValues;

/** 실내 장소의 기준 정보이며 센서 측정이나 사용자 위치 이력을 저장하지 않는다. */
@Entity
@Table(name = "indoor_locations")
public class IndoorLocation extends GeneratedIdEntity {

    @Column(name = "floor_id", nullable = false)
    private Long floorId;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "description", nullable = true, columnDefinition = "TEXT")
    private String description;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    /** JPA가 저장된 행을 객체로 복원할 때 사용한다. */
    protected IndoorLocation() {
    }

    /** 존재하는 층을 가리키는 활성 장소를 만든다. 설명은 TEXT의 바이트 한도 안에서 제한한다. */
    public IndoorLocation(Long floorId, String name, String description) {
        this.floorId = DomainValues.positiveId(floorId, "층 번호");
        this.name = DomainValues.requiredText(name, 255, "장소 이름");
        this.description = DomainValues.optionalText(description, 10000, "장소 설명");
        this.active = true;
    }

    /** 장소가 속한 층의 내부 번호를 반환한다. */
    public Long getFloorId() {
        return floorId;
    }

    /** 중복을 허용하는 장소 표시 이름을 반환한다. */
    public String getName() {
        return name;
    }

    /** 등록 시 제공한 선택 설명을 반환한다. */
    public String getDescription() {
        return description;
    }

    /** 신규 사용 가능 여부를 반환하며 참조 중인 행 자체는 유지한다. */
    public boolean isActive() {
        return active;
    }

    /** 장소 원본과 기존 참조를 지우지 않고 사용 가능 표시만 끈다. */
    public void deactivate() {
        active = false;
    }
}
