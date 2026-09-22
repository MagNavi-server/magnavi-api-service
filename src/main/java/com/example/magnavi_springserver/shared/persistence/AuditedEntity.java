package com.example.magnavi_springserver.shared.persistence;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;

/** 모든 업무 행의 생성·수정 시각을 UTC와 MySQL의 마이크로초 정밀도로 맞춘다. */
@MappedSuperclass
public abstract class AuditedEntity {

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** 하위 저장 객체와 JPA가 공통 시각 필드를 초기화할 수 있게 한다. */
    protected AuditedEntity() {
    }

    /** 최초 저장 직전에 동일한 시각으로 생성·수정 정보를 기록한다. */
    @PrePersist
    protected void initializeTimestamps() {
        createdAt = utcNow();
        updatedAt = createdAt;
    }

    /** 변경 감지로 UPDATE가 실행될 때 수정 시각만 갱신한다. */
    @PreUpdate
    protected void updateTimestamp() {
        updatedAt = utcNow();
    }

    /** 서버 기본 시간대와 무관하게 DB 컬럼 정밀도에 맞춘 UTC 시각을 얻는다. */
    private static Instant utcNow() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    /** 최초 저장 시각을 UTC 기준으로 반환한다. */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /** 마지막 JPA 변경 시각을 UTC 기준으로 반환한다. */
    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
