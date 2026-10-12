package com.example.magnavi_springserver.shared.persistence;

import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;

/** MySQL이 내부 번호를 생성하는 업무 객체의 공통 식별자 매핑이다. */
@MappedSuperclass
public abstract class GeneratedIdEntity extends AuditedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** JPA 및 하위 객체의 생성을 지원하며 ID는 직접 지정하지 않는다. */
    protected GeneratedIdEntity() {
    }

    /** 저장 전에는 null이고 저장 후에는 DB가 발급한 번호를 반환한다. */
    public Long getId() {
        return id;
    }
}
