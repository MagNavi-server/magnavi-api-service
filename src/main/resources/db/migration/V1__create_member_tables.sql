-- 신규 DB용 회원 구조다. 기존 FastAPI users 테이블을 수정하거나 데이터를 옮기지 않는다.
CREATE TABLE members (
    id BIGINT NOT NULL AUTO_INCREMENT,
    display_name VARCHAR(50) NOT NULL,
    email VARCHAR(254) NULL,
    phone_number VARCHAR(20) NULL,
    role VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT ck_members_role CHECK (role IN ('USER', 'ADMIN')),
    CONSTRAINT ck_members_status CHECK (status = 'ACTIVE')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE local_credentials (
    member_id BIGINT NOT NULL,
    -- Alice와 alice를 서로 다른 ID로 취급한다. 앞뒤 공백은 저장·조회 전에 제거한다.
    login_id VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (member_id),
    CONSTRAINT uk_local_credentials_login UNIQUE (login_id),
    CONSTRAINT fk_local_credentials_member FOREIGN KEY (member_id) REFERENCES members (id),
    CONSTRAINT ck_local_credentials_login CHECK (CHAR_LENGTH(TRIM(login_id)) > 0 AND login_id = TRIM(login_id)),
    CONSTRAINT ck_local_credentials_password CHECK (CHAR_LENGTH(TRIM(password_hash)) > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE social_accounts (
    id BIGINT NOT NULL AUTO_INCREMENT,
    member_id BIGINT NOT NULL,
    provider VARCHAR(20) NOT NULL,
    provider_user_id VARCHAR(255) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_social_accounts_identity UNIQUE (provider, provider_user_id),
    CONSTRAINT uk_social_accounts_member_provider UNIQUE (member_id, provider),
    CONSTRAINT fk_social_accounts_member FOREIGN KEY (member_id) REFERENCES members (id),
    CONSTRAINT ck_social_accounts_provider CHECK (provider IN ('KAKAO', 'GOOGLE')),
    CONSTRAINT ck_social_accounts_user CHECK (CHAR_LENGTH(TRIM(provider_user_id)) > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
