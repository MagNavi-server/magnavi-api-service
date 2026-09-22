package com.example.magnavi_springserver.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.mysql.MySQLContainer;

/** Spring 테스트 컨텍스트와 같은 수명을 가진 임시 MySQL을 준비한다. */
@TestConfiguration(proxyBeanMethods = false)
public class MySqlTestConfiguration {

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
