package com.example.magnavi_springserver.place;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.example.magnavi_springserver.member.domain.Member;
import com.example.magnavi_springserver.member.infrastructure.JwtTokenProvider;
import com.example.magnavi_springserver.member.infrastructure.persistence.MemberJpaRepository;
import com.example.magnavi_springserver.place.application.PlaceSearchException;
import com.example.magnavi_springserver.place.application.PlaceSearchResult;
import com.example.magnavi_springserver.place.infrastructure.NaverLocalSearchClient;
import com.example.magnavi_springserver.support.MySqlTestConfiguration;

/** 실제 JWT·보안 필터·서비스·임시 MySQL을 연결하고 외부 통신만 대체한다. */
@SpringBootTest(properties = "magnavi.naver-search.requests-per-member-per-minute=3")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(MySqlTestConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class PlaceSearchApiIntegrationTests {

    @Autowired private MockMvc mvc;
    @Autowired private MemberJpaRepository members;
    @Autowired private JwtTokenProvider tokens;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private NaverLocalSearchClient client;
    private String token;
    private long memberId;

    /** 합성 회원과 실제 서명 토큰을 준비한다. 사용자 DB나 운영 키는 사용하지 않는다. */
    @BeforeEach
    void createMember() {
        memberId = members.saveAndFlush(new Member("검색 테스트", null, null)).getId();
        token = tokens.issueAccessToken(memberId);
        when(client.search(anyString(), anyInt(), anyString())).thenReturn(new PlaceSearchResult("NAVER", List.of()));
    }

    /** 전체 요청 흐름에서 응답·캐시 금지·DB 무변경·트랜잭션 없음·로그 비노출을 검증한다. */
    @Test
    void searchesWithoutDatabaseWritesOrSensitiveLogs(CapturedOutput output) throws Exception {
        var before = rowCounts();
        when(client.search("private-query", 5, "random")).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new PlaceSearchResult("NAVER", List.of(new PlaceSearchResult.Item("합성 장소", "카페",
                    "합성 지번", "합성 도로명", "https://example.test", new BigDecimal("37"), new BigDecimal("127"))));
        });
        for (String path : List.of("/places/search", "/places/search/")) {
            mvc.perform(get(path).header("Authorization", "Bearer " + token).param("query", "  private-query  "))
                    .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.source").value("NAVER"))
                    .andExpect(jsonPath("$.items[0].name").value("합성 장소"))
                    .andExpect(jsonPath("$.items[0].latitude").value(37))
                    .andExpect(jsonPath("$.items[0].longitude").value(127))
                    .andExpect(jsonPath("$.items[0].id").doesNotExist());
        }
        assertThat(rowCounts()).isEqualTo(before);
        assertThat(output.getAll()).doesNotContain("private-query", token, "합성 도로명");
    }

    /** 무인증·위조·없는 회원과 토큰 발급 후 삭제된 회원은 외부 검색을 실행하지 못한다. */
    @Test
    void requiresActiveAuthenticatedMember() throws Exception {
        mvc.perform(get("/places/search").param("query", "카페")).andExpect(status().isUnauthorized());
        for (String invalid : List.of("invalid-token", tokens.issueAccessToken(Long.MAX_VALUE))) {
            mvc.perform(get("/places/search").header("Authorization", "Bearer " + invalid).param("query", "카페"))
                    .andExpect(status().isUnauthorized());
        }
        members.deleteById(memberId);
        mvc.perform(get("/places/search/").header("Authorization", "Bearer " + token).param("query", "카페"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(client);
    }

    /** 검색 입력이 잘못되면 공통 400을 반환하고 원문은 오류에 넣지 않는다. */
    @Test
    void rejectsInvalidMissingDuplicateAndUnsupportedParameters() throws Exception {
        for (var parameters : List.of(Map.of("query", ""), Map.of("query", "a".repeat(101)),
                Map.of("query", "카페", "display", "6"), Map.of("query", "카페", "display", "abc"),
                Map.of("query", "카페", "sort", "unknown"), Map.of("query", "카페", "start", "2"))) {
            var request = get("/places/search").header("Authorization", "Bearer " + token);
            parameters.forEach(request::param);
            mvc.perform(request).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        mvc.perform(get("/places/search").header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/places/search").header("Authorization", "Bearer " + token).param("query", "first", "second"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(client);
    }

    /** 정상적인 결과 없음은 200과 빈 배열로 응답한다. */
    @Test
    void returnsEmptyArray() throws Exception {
        mvc.perform(get("/places/search").header("Authorization", "Bearer " + token).param("query", "없는 장소"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
    }

    /** 같은 회원이 한도를 넘기면 HTTP 429가 되며 네이버를 추가 호출하지 않는다. */
    @Test
    void stopsProviderCallsAtMemberLimit() throws Exception {
        for (int i = 0; i < 3; i++) {
            mvc.perform(get("/places/search").header("Authorization", "Bearer " + token).param("query", "카페"))
                    .andExpect(status().isOk());
        }
        mvc.perform(get("/places/search").header("Authorization", "Bearer " + token).param("query", "카페"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("PLACE_SEARCH_RATE_LIMITED"));
        verify(client, times(3)).search("카페", 5, "random");
    }

    /** 외부 장애·설정 누락·각 한도를 안전한 상태와 추적 ID로 구분한다. */
    @ParameterizedTest
    @EnumSource(PlaceSearchException.Reason.class)
    void preservesSearchFailureMeaning(PlaceSearchException.Reason reason) throws Exception {
        when(client.search(anyString(), anyInt(), anyString())).thenThrow(new PlaceSearchException(reason));
        int expectedStatus = switch (reason) {
            case RATE_LIMITED -> 429;
            case TIMEOUT -> 504;
            case PROVIDER_ERROR -> 502;
            default -> 503;
        };
        var result = mvc.perform(get("/places/search").header("Authorization", "Bearer " + token).param("query", "private-query"))
                .andExpect(status().is(expectedStatus))
                .andExpect(jsonPath("$.code").value("PLACE_SEARCH_" + reason.name()))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(header().exists("X-Request-ID")).andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("private-query", token);
    }

    /** 검색 GET만 열고 쓰기 요청은 계속 차단한다. 실내 장소 공개 조회도 유지한다. */
    @Test
    void doesNotOpenOtherMethodsOrProtectPublicPlaces() throws Exception {
        mvc.perform(post("/places/search").header("Authorization", "Bearer " + token).param("query", "카페"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/buildings")).andExpect(status().isOk());
        verifyNoInteractions(client);
    }

    /** 검색이 기존 업무 테이블 어디에도 결과를 추가하지 않는지 비교할 기준이다. */
    private Map<String, Long> rowCounts() {
        var counts = new LinkedHashMap<String, Long>();
        for (String table : List.of("members", "local_credentials", "social_accounts", "buildings", "floors",
                "indoor_locations", "model_location_mappings", "favorites")) {
            counts.put(table, jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class));
        }
        return counts;
    }
}
