package com.example.magnavi_springserver.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.example.magnavi_springserver.support.MySqlTestConfiguration;

/** 객체 검사를 우회한 SQL에도 실제 MySQL의 고유·참조·필수·유형 제약이 적용되는지 확인한다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(MySqlTestConfiguration.class)
@Transactional
class DatabaseConstraintTests {

    @Autowired private JdbcTemplate jdbc;
    private long owner;
    private long other;
    private long building;
    private long floor;
    private long location;

    /** 각 테스트 트랜잭션 안에서만 사용하는 회원 두 명과 실내 장소를 만든다. */
    @BeforeEach
    void createReferences() {
        owner = insertMember("owner@example.test");
        other = insertMember("other@example.test");
        jdbc.update("INSERT INTO buildings(name,address,created_at,updated_at) VALUES ('시험 건물','합성 주소',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
        building = lastId();
        jdbc.update("INSERT INTO floors(building_id,floor_number,name,created_at,updated_at) VALUES (?,1,'1층',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", building);
        floor = lastId();
        jdbc.update("INSERT INTO indoor_locations(floor_id,name,is_active,created_at,updated_at) VALUES (?,'시험 장소',1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", floor);
        location = lastId();
    }

    /** 기본 필수 값과 문자열 열거값을 Java 코드 없이도 보호한다. */
    @Test
    void rejectsNullAndUnknownMemberValues() {
        rejected("UPDATE members SET display_name = NULL WHERE id = ?", owner);
        rejected("UPDATE members SET role = 'UNKNOWN' WHERE id = ?", owner);
        rejected("UPDATE members SET status = 'UNKNOWN' WHERE id = ?", owner);
    }

    /** 모든 관계의 존재하지 않는 참조와 참조 중인 부모의 물리 삭제를 거절한다. */
    @Test
    void enforcesForeignKeysAndRestrictsReferencedDeletion() {
        rejected("INSERT INTO local_credentials VALUES (-1,'missing','synthetic',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
        rejected("INSERT INTO social_accounts(member_id,provider,provider_user_id,created_at,updated_at) VALUES (-1,'GOOGLE','id',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
        rejected("UPDATE floors SET building_id = -1 WHERE id = ?", floor);
        rejected("UPDATE indoor_locations SET floor_id = -1 WHERE id = ?", location);
        rejected("INSERT INTO model_location_mappings(model_key,model_version,location_code,indoor_location_id,created_at,updated_at) VALUES ('m','v','c',-1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
        assertThatThrownBy(() -> insertFavorite(-1, "INDOOR_PLACE", "indoor:" + location, location, null, null, null))
                .isInstanceOf(DataAccessException.class).satisfies(DatabaseConstraintTests::assertIntegrityFailure);
        assertThatThrownBy(() -> insertFavorite(owner, "INDOOR_PLACE", "indoor:-1", -1L, null, null, null))
                .isInstanceOf(DataAccessException.class).satisfies(DatabaseConstraintTests::assertIntegrityFailure);
        insertFavorite(owner, "INDOOR_PLACE", "indoor:" + location, location, null, null, null);
        rejected("DELETE FROM members WHERE id = ?", owner);
        rejected("DELETE FROM indoor_locations WHERE id = ?", location);
        rejected("DELETE FROM floors WHERE id = ?", floor);
        rejected("DELETE FROM buildings WHERE id = ?", building);
    }

    /** 일반 로그인은 회원당 하나이며 해시·ID를 빈 값으로 바꿀 수 없다. */
    @Test
    void enforcesCredentialCardinalityAndRequiredValues() {
        jdbc.update("INSERT INTO local_credentials VALUES (?,'login','synthetic-hash',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", owner);
        rejected("INSERT INTO local_credentials VALUES (?,'another','synthetic-hash',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", owner);
        rejected("UPDATE local_credentials SET login_id = '' WHERE member_id = ?", owner);
        rejected("UPDATE local_credentials SET login_id = ' padded ' WHERE member_id = ?", owner);
        rejected("UPDATE local_credentials SET password_hash = NULL WHERE member_id = ?", owner);
        rejected("UPDATE local_credentials SET password_hash = '' WHERE member_id = ?", owner);
    }

    /** 대소문자가 다르면 가입 가능하지만 완전히 같은 로그인 ID는 DB에서도 중복을 막는다. */
    @Test
    void enforcesCaseSensitiveLoginIdUniqueness() {
        String sql = "INSERT INTO local_credentials VALUES (?,?,'synthetic-hash',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))";
        jdbc.update(sql, owner, "Alice");
        jdbc.update(sql, other, "alice");
        long third = insertMember("third@example.test");
        rejected(sql, third, "Alice");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM local_credentials WHERE login_id = 'ALICE'", Long.class)).isZero();
    }

    /** 소셜 계정의 타 회원 중복 연결과 회원당 제공자 중복을 함께 차단한다. */
    @Test
    void enforcesBothSocialIdentityConstraints() {
        insertSocial(owner, "GOOGLE", "CaseId");
        assertThatThrownBy(() -> insertSocial(other, "GOOGLE", "CaseId")).isInstanceOf(DataAccessException.class).satisfies(DatabaseConstraintTests::assertIntegrityFailure);
        assertThatThrownBy(() -> insertSocial(owner, "GOOGLE", "DifferentId")).isInstanceOf(DataAccessException.class).satisfies(DatabaseConstraintTests::assertIntegrityFailure);
        assertThatThrownBy(() -> insertSocial(other, "UNKNOWN", "id")).isInstanceOf(DataAccessException.class).satisfies(DatabaseConstraintTests::assertIntegrityFailure);
        insertSocial(other, "GOOGLE", "caseid");
        insertSocial(owner, "KAKAO", "CaseId");
    }

    /** 이름이 같은 건물은 허용하되 한 건물의 같은 층 번호는 중복되지 않는다. */
    @Test
    void scopesFloorUniquenessToBuilding() {
        rejected("INSERT INTO floors(building_id,floor_number,name,created_at,updated_at) VALUES (?,1,'다른 이름',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", building);
        jdbc.update("INSERT INTO buildings(name,address,created_at,updated_at) VALUES ('시험 건물','다른 합성 주소',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
        jdbc.update("INSERT INTO floors(building_id,floor_number,name,created_at,updated_at) VALUES (?,1,'1층',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", lastId());
    }

    /** 모델 코드는 버전·적용 구역 안에서 고유하며 대소문자를 보존한다. */
    @Test
    void scopesModelCodeUniquenessToKeyAndVersion() {
        String sql = "INSERT INTO model_location_mappings(model_key,model_version,location_code,indoor_location_id,created_at,updated_at) VALUES (?,?,?, ?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))";
        jdbc.update(sql, "Area", "v1", "Code", location);
        rejected(sql, "Area", "v1", "Code", location);
        jdbc.update(sql, "Area", "v2", "Code", location);
        jdbc.update(sql, "Other", "v1", "Code", location);
        jdbc.update(sql, "Area", "v1", "code", location);
    }

    /** 동일 장소라도 서로 다른 회원은 저장할 수 있고 키 조작으로 같은 회원의 중복을 우회할 수 없다. */
    @Test
    void scopesIndoorFavoritesToOwner() {
        insertFavorite(owner, "INDOOR_PLACE", "indoor:" + location, location, null, null, null);
        insertFavorite(other, "INDOOR_PLACE", "indoor:" + location, location, null, null, null);
        assertThatThrownBy(() -> insertFavorite(owner, "INDOOR_PLACE", "indoor:" + location, location, null, null, null))
                .isInstanceOf(DataAccessException.class).satisfies(DatabaseConstraintTests::assertIntegrityFailure);
        assertThatThrownBy(() -> insertFavorite(owner, "INDOOR_PLACE", "forged-key", location, null, null, null))
                .isInstanceOf(DataAccessException.class).satisfies(DatabaseConstraintTests::assertIntegrityFailure);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM favorites WHERE indoor_location_id = ?", Long.class, location)).isEqualTo(2);
    }

    /** 교통 중복은 원본 식별 조합으로도 차단하고 다른 지역·유형·회원은 구분한다. */
    @Test
    void scopesTransportIdentityAndRejectsKeyBypass() {
        insertFavorite(owner, "BUS", "key-1", null, "provider", "region-1", "route");
        assertThatThrownBy(() -> insertFavorite(owner, "BUS", "forged-key", null, "provider", "region-1", "route"))
                .isInstanceOf(DataAccessException.class).satisfies(DatabaseConstraintTests::assertIntegrityFailure);
        insertFavorite(owner, "BUS", "key-2", null, "provider", "region-2", "route");
        insertFavorite(owner, "BUS_STOP", "key-3", null, "provider", "region-1", "route");
        insertFavorite(other, "BUS", "key-1", null, "provider", "region-1", "route");
        insertFavorite(owner, "BUS", "key-4", null, "provider", "region-1", "Route");
    }

    /** CHECK가 NULL로 인해 UNKNOWN이 되어 잘못된 조합을 통과시키는 경우도 검사한다. */
    @Test
    void rejectsMissingMixedAndUnsupportedFavoriteTargets() {
        Object[][] invalid = {
            {"INDOOR_PLACE", null, null, null, null},
            {"INDOOR_PLACE", location, "provider", "scope", "id"},
            {"BUS", location, "provider", "scope", "id"},
            {"BUS", null, null, "scope", "id"},
            {"BUS", null, "provider", null, "id"},
            {"BUS_STOP", null, "provider", "scope", null},
            {"BUS_STOP", null, "provider", "", "id"},
            {"EXTERNAL_PLACE", null, "provider", "scope", "id"},
            {null, null, "provider", "scope", "id"}
        };
        for (Object[] values : invalid) {
            assertThatThrownBy(() -> insertFavorite(owner, (String) values[0], "invalid", (Long) values[1],
                    (String) values[2], (String) values[3], (String) values[4]))
                    .isInstanceOf(DataAccessException.class).satisfies(DatabaseConstraintTests::assertIntegrityFailure);
        }
    }

    /** 이관 및 외부 스냅샷 정책을 확정하지 않은 컬럼·테이블을 만들지 않았는지 확인한다. */
    @Test
    void containsOnlyTheEightApprovedBusinessTables() {
        assertThat(jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name <> 'flyway_schema_history'", String.class))
                .containsExactlyInAnyOrder("members", "local_credentials", "social_accounts", "buildings", "floors", "indoor_locations", "model_location_mappings", "favorites");
        assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'favorites'", String.class))
                .doesNotContain("outdoor_place_id", "legacy_id", "display_name", "raw_json");
    }

    /** 필수 연락처 정책 어느 쪽에도 사용할 수 있는 합성 회원을 저장한다. */
    private long insertMember(String email) {
        jdbc.update("INSERT INTO members(display_name,email,phone_number,role,status,created_at,updated_at) VALUES ('시험 회원',?,'01000000000','USER','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", email);
        return lastId();
    }

    /** 같은 테스트 트랜잭션의 연결에서 MySQL이 방금 생성한 번호를 읽는다. */
    private long lastId() {
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    /** 검증을 우회한 SQL로 소셜 연결의 DB 제약을 직접 확인한다. */
    private void insertSocial(long memberId, String provider, String providerId) {
        jdbc.update("INSERT INTO social_accounts(member_id,provider,provider_user_id,created_at,updated_at) VALUES (?,?,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", memberId, provider, providerId);
    }

    /** 모든 선택 컬럼을 명시해 유형별 NULL 조합도 직접 DB에 전달한다. */
    private void insertFavorite(long memberId, String type, String key, Long placeId, String provider, String scope, String externalId) {
        jdbc.update("INSERT INTO favorites(member_id,target_type,target_key,indoor_location_id,provider,provider_scope,external_id,created_at,updated_at) VALUES (?,?,?,?,?,?,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
                memberId, type, key, placeId, provider, scope, externalId);
    }

    /** CHECK 오류가 일반 SQL 예외로 번역되어도 구문 오류 등 다른 실패를 성공으로 오인하지 않는다. */
    private static void assertIntegrityFailure(Throwable exception) {
        Throwable cause = ((DataAccessException) exception).getMostSpecificCause();
        assertThat(cause).isInstanceOf(SQLException.class);
        // NOT NULL, UNIQUE, 참조 삭제, FK, CHECK 위반만 허용한다.
        assertThat(((SQLException) cause).getErrorCode()).isIn(1048, 1062, 1451, 1452, 3819);
    }

    /** MySQL 무결성 오류가 실제로 발생해야 성공으로 처리한다. */
    private void rejected(String sql, Object... args) {
        assertThatThrownBy(() -> jdbc.update(sql, args)).isInstanceOf(DataAccessException.class).satisfies(DatabaseConstraintTests::assertIntegrityFailure);
    }
}
