package com.example.magnavi_springserver.place.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import com.example.magnavi_springserver.shared.persistence.GeneratedIdEntity;
import com.example.magnavi_springserver.shared.validation.DomainValues;

/** 실내 장소가 속한 건물의 기준 정보를 보관한다. */
@Entity
@Table(name = "buildings")
public class Building extends GeneratedIdEntity {

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "address", nullable = false, length = 255)
    private String address;

    /** JPA가 저장된 행을 객체로 복원할 때 사용한다. */
    protected Building() {
    }

    /** 실제 등록 자료의 건물 이름과 주소를 검사해 새 건물을 만든다. */
    public Building(String name, String address) {
        this.name = DomainValues.requiredText(name, 255, "건물 이름");
        this.address = DomainValues.requiredText(address, 255, "건물 주소");
    }

    /** 화면에 표시할 건물 이름을 반환한다. */
    public String getName() {
        return name;
    }

    /** 건물에 한 번만 저장한 주소를 반환한다. */
    public String getAddress() {
        return address;
    }
}
