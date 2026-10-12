package com.example.magnavi_springserver.support;

import java.security.SecureRandom;
import java.util.Base64;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.mysql.MySQLContainer;

/** Spring 테스트 컨텍스트와 같은 수명을 가진 임시 MySQL을 준비한다. */
@TestConfiguration(proxyBeanMethods = false)
public class MySqlTestConfiguration {

    /** 테스트 실행마다 임시 서명 키를 만들며 로컬 .env나 운영 키는 읽지 않는다. */
    @Bean
    public DynamicPropertyRegistrar jwtTestProperties() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        String encodedKey = Base64.getEncoder().encodeToString(key);
        return registry -> registry.add("magnavi.jwt.secret-base64", () -> encodedKey);
    }

    /** 개발용과 같은 MySQL 버전을 쓰되 별도 컨테이너·DB·임의 호스트 포트로 격리한다. */
    @Bean
    @ServiceConnection
    public MySQLContainer mysqlContainer() {
        return new MySQLContainer("mysql:8.4.8")
                .withDatabaseName("magnavi_test")
                .withUsername("magnavi_test")
                .withPassword("isolated-test-only");
    }
}
