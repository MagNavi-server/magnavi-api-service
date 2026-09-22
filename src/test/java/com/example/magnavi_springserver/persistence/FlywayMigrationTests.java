package com.example.magnavi_springserver.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.mysql.MySQLContainer;

/** 기존 운영 DB 대신 별도 일회용 MySQL에서 버전별 확장과 재실행 시 데이터 보존을 검증한다. */
class FlywayMigrationTests {

    /** V1 회원 데이터가 V2·V3 적용과 최신 버전 재실행 이후에도 유지된다. */
    @Test
    void upgradesFromV1AndDoesNotReapplyCompletedMigrations() {
        try (MySQLContainer mysql = new MySQLContainer("mysql:8.4.8")
                .withDatabaseName("migration_test").withUsername("migration_test").withPassword("isolated-test-only")) {
            mysql.start();
            Flyway initial = configuration(mysql).target("1").load();
            assertThat(initial.migrate().migrationsExecuted).isEqualTo(1);
            JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword()));
            jdbc.update("INSERT INTO members(display_name,email,phone_number,role,status,created_at,updated_at) VALUES ('보존할 합성 회원','preserved@example.test','01000000000','USER','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
            Long memberId = jdbc.queryForObject("SELECT id FROM members", Long.class);
            Flyway latest = configuration(mysql).load();
            assertThat(latest.migrate().migrationsExecuted).isEqualTo(2);
            assertThat(latest.info().applied()).hasSize(3);
            assertThat(latest.validateWithResult().validationSuccessful).isTrue();
            assertThat(latest.migrate().migrationsExecuted).isZero();
            assertThat(latest.info().pending()).isEmpty();
            assertThat(jdbc.queryForObject("SELECT display_name FROM members WHERE id = ?", String.class, memberId)).isEqualTo("보존할 합성 회원");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM members", Long.class)).isEqualTo(1);
        }
    }

    /** 운영과 같은 기본 SQL 경로를 쓰고 clean·자동 baseline을 비활성화한다. */
    private org.flywaydb.core.api.configuration.FluentConfiguration configuration(MySQLContainer mysql) {
        return Flyway.configure().dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                .locations("classpath:db/migration").cleanDisabled(true).baselineOnMigrate(false);
    }
}
