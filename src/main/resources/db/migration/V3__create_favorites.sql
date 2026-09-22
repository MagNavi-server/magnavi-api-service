-- 외부 장소 스냅샷·선택 실외 장소·기존 앱 ID는 저장 정책 및 이관 범위 확정 후 추가한다.
CREATE TABLE favorites (
    id BIGINT NOT NULL AUTO_INCREMENT,
    member_id BIGINT NOT NULL,
    target_type VARCHAR(32) NOT NULL,
    target_key VARCHAR(255) NOT NULL,
    indoor_location_id BIGINT NULL,
    provider VARCHAR(32) NULL,
    provider_scope VARCHAR(128) NULL,
    external_id VARCHAR(255) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_favorites_member_target UNIQUE (member_id, target_type, target_key),
    -- 직접 SQL로 다른 target_key를 넣어도 같은 원본 대상의 중복은 허용하지 않는다.
    CONSTRAINT uk_favorites_member_indoor UNIQUE (member_id, indoor_location_id),
    CONSTRAINT uk_favorites_member_transport UNIQUE (member_id, target_type, provider, provider_scope, external_id),
    INDEX ix_favorites_member_created (member_id, created_at, id),
    CONSTRAINT fk_favorites_member FOREIGN KEY (member_id) REFERENCES members (id),
    CONSTRAINT fk_favorites_indoor FOREIGN KEY (indoor_location_id) REFERENCES indoor_locations (id),
    -- CHECK의 UNKNOWN 통과를 막기 위해 모든 선택 컬럼의 NULL 조건을 명시한다.
    CONSTRAINT ck_favorites_target CHECK (
        (target_type = 'INDOOR_PLACE'
            AND indoor_location_id IS NOT NULL
            AND provider IS NULL AND provider_scope IS NULL AND external_id IS NULL
            AND target_key = CONCAT('indoor:', indoor_location_id))
        OR
        (target_type IN ('BUS', 'BUS_STOP')
            AND indoor_location_id IS NULL
            AND provider IS NOT NULL AND CHAR_LENGTH(TRIM(provider)) > 0
            AND provider_scope IS NOT NULL AND CHAR_LENGTH(TRIM(provider_scope)) > 0
            AND external_id IS NOT NULL AND CHAR_LENGTH(TRIM(external_id)) > 0
            AND CHAR_LENGTH(TRIM(target_key)) > 0)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
