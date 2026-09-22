package com.example.magnavi_springserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.example.magnavi_springserver.support.MySqlTestConfiguration;

/** 외부 API 없이 실제 MySQL을 이용해 기동·상태 확인·기본 접근 정책을 검증한다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(MySqlTestConfiguration.class)
class MagNaviSpringServerApplicationTests {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Flyway flyway;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApplicationContext context;

    /** 운영 DB 대신 임시 DB에 연결했고 Flyway의 세 버전과 JPA 구조 검증이 완료됐는지 확인한다. */
    @Test
    void connectsToIsolatedMySqlWithFlywayReady() {
        assertThat(jdbcTemplate.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("magnavi_test");
        assertThat(flyway.info().pending()).isEmpty();
        assertThat(flyway.info().applied()).hasSize(3);
        assertThat(flyway.getConfiguration().isCleanDisabled()).isTrue();
        assertThat(context.getEnvironment().getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(context.getEnvironment().getProperty("spring.jpa.open-in-view")).isEqualTo("false");
    }

    /** 공개 상태 응답은 DB 상세 없이 최소 상태만 보여 준다. */
    @Test
    void exposesMinimalLivenessAndReadiness() throws Exception {
        for (String probe : new String[] {"liveness", "readiness"}) {
            mockMvc.perform(get("/actuator/health/" + probe))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("UP"))
                    .andExpect(jsonPath("$.components").doesNotExist())
                    .andExpect(jsonPath("$.details").doesNotExist())
                    .andExpect(header().string("X-Request-ID", not(emptyOrNullString())));
        }
    }

    /** 미인증 요청은 로그인 화면이나 세션 생성 대신 공통 401 응답을 받는다. */
    @Test
    void rejectsAnonymousRequestsWithoutCreatingSession() throws Exception {
        var result = mockMvc.perform(get("/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(header().doesNotExist("Location"))
                .andReturn();
        assertThat(result.getRequest().getSession(false)).isNull();
        // 가입은 공개했지만 JSON 형식과 입력 검사는 통과해야 한다.
        mockMvc.perform(post("/users/signup").contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    /** 아직 허용하지 않은 업무 경로는 테스트 인증 주체에게도 열리지 않는다. */
    @Test
    void rejectsAuthenticatedRequestsUntilAnEndpointIsExplicitlyAllowed() throws Exception {
        mockMvc.perform(get("/favorites").with(user("test-member")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    /** 관리 정보·기본 로그인·오류 경로를 직접 호출해 접근 정책을 우회할 수 없다. */
    @Test
    void keepsManagementAndDirectErrorEndpointsClosed() throws Exception {
        for (String path : new String[] {"/actuator/env", "/actuator/health", "/login", "/error"}) {
            mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
        mockMvc.perform(post("/actuator/health/readiness")).andExpect(status().isUnauthorized());
        assertThat(context.getBeansOfType(UserDetailsService.class)).isEmpty();
    }
}
