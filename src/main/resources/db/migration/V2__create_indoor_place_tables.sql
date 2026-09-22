-- 실내 장소의 기준 정보만 저장한다. 센서·예측 이력과 기존 DB 데이터 이관은 포함하지 않는다.
CREATE TABLE buildings (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(255) NOT NULL,
    address VARCHAR(255) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE floors (
    id BIGINT NOT NULL AUTO_INCREMENT,
    building_id BIGINT NOT NULL,
    floor_number INT NOT NULL,
    name VARCHAR(50) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_floors_building_number UNIQUE (building_id, floor_number),
    CONSTRAINT fk_floors_building FOREIGN KEY (building_id) REFERENCES buildings (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE indoor_locations (
    id BIGINT NOT NULL AUTO_INCREMENT,
    floor_id BIGINT NOT NULL,
    name VARCHAR(255) NOT NULL,
    description TEXT NULL,
    is_active BIT(1) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_indoor_locations_floor FOREIGN KEY (floor_id) REFERENCES floors (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE model_location_mappings (
    id BIGINT NOT NULL AUTO_INCREMENT,
    model_key VARCHAR(128) NOT NULL,
    model_version VARCHAR(64) NOT NULL,
    location_code VARCHAR(50) NOT NULL,
    indoor_location_id BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_model_location_code UNIQUE (model_key, model_version, location_code),
    CONSTRAINT fk_model_location_place FOREIGN KEY (indoor_location_id) REFERENCES indoor_locations (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
