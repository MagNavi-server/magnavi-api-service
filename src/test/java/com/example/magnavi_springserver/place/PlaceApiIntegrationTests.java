package com.example.magnavi_springserver.place;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import com.example.magnavi_springserver.member.infrastructure.JwtTokenProvider;
import com.example.magnavi_springserver.place.domain.Building;
import com.example.magnavi_springserver.place.domain.Floor;
import com.example.magnavi_springserver.place.domain.IndoorLocation;
import com.example.magnavi_springserver.place.infrastructure.persistence.BuildingJpaRepository;
import com.example.magnavi_springserver.place.infrastructure.persistence.FloorJpaRepository;
import com.example.magnavi_springserver.place.infrastructure.persistence.IndoorLocationJpaRepository;
import com.example.magnavi_springserver.support.MySqlTestConfiguration;

/** 임시 MySQL에서 계층 조회·페이지 범위·오류·읽기 전용 접근과 SQL 개수를 검증한다. */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(MySqlTestConfiguration.class)
@Transactional
class PlaceApiIntegrationTests {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private BuildingJpaRepository buildings;
    @Autowired private FloorJpaRepository floors;
    @Autowired private IndoorLocationJpaRepository locations;
    @Autowired private EntityManager entityManager;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private JwtTokenProvider tokens;

    private Building firstBuilding;
    private Building otherBuilding;
    private Building emptyBuilding;
    private Floor secondFloor;
    private Floor basementFloor;
    private Floor emptyFloor;
    private Floor otherFloor;
    private IndoorLocation firstLocation;
    private IndoorLocation secondLocation;
    private IndoorLocation thirdLocation;
    private IndoorLocation hiddenLocation;
    private IndoorLocation basementLocation;
    private IndoorLocation otherLocation;

    /** 이름·층 번호가 겹치는 두 건물과 빈 건물을 만들며 테스트가 끝나면 전부 롤백한다. */
    @BeforeEach
    void prepareSyntheticPlaces() {
        firstBuilding = buildings.save(new Building("합성 건물", "테스트 주소 A"));
        otherBuilding = buildings.save(new Building("합성 건물", "테스트 주소 B"));
        emptyBuilding = buildings.save(new Building("빈 건물", "테스트 주소 C"));
        // 일부러 2층을 먼저 저장한다. 응답은 저장 순서가 아닌 층 번호순이어야 한다.
        secondFloor = floors.save(new Floor(firstBuilding.getId(), 2, "2층"));
        basementFloor = floors.save(new Floor(firstBuilding.getId(), -1, "지하 1층"));
        emptyFloor = floors.save(new Floor(firstBuilding.getId(), 3, "3층"));
        otherFloor = floors.save(new Floor(otherBuilding.getId(), 2, "2층"));
        firstLocation = locations.save(new IndoorLocation(secondFloor.getId(), "205호 앞", "합성 설명"));
        hiddenLocation = locations.save(new IndoorLocation(secondFloor.getId(), "숨김 위치", null));
        hiddenLocation.deactivate();
        secondLocation = locations.save(new IndoorLocation(secondFloor.getId(), "계단 앞", null));
        thirdLocation = locations.save(new IndoorLocation(secondFloor.getId(), "복도", null));
        basementLocation = locations.save(new IndoorLocation(basementFloor.getId(), "로비", null));
        otherLocation = locations.save(new IndoorLocation(otherFloor.getId(), "205호 앞", null));
        entityManager.flush();
        entityManager.clear();
    }

    /** 같은 이름의 건물도 서로 다른 ID로 반환하며 이름순이 아닌 ID순으로 탐색한다. */
    @Test
    void listsBuildingsInStableIdOrder() throws Exception {
        mvc.perform(get("/buildings"))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].id").value(firstBuilding.getId()))
                .andExpect(jsonPath("$[1].id").value(otherBuilding.getId()))
                .andExpect(jsonPath("$[2].id").value(emptyBuilding.getId()));
        mvc.perform(get("/buildings").param("skip", "1").param("limit", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(otherBuilding.getId()));
    }

    /** 건물 상세에는 요청한 건물의 이름·주소만 포함한다. */
    @Test
    void readsBuildingDetails() throws Exception {
        mvc.perform(get("/buildings/{id}", firstBuilding.getId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("합성 건물"))
                .andExpect(jsonPath("$.address").value("테스트 주소 A"))
                .andExpect(jsonPath("$.createdAt").doesNotExist());
    }

    /** 다른 건물의 같은 층 번호를 섞지 않고 지하층부터 순서대로 반환한다. */
    @Test
    void listsFloorsWithinSelectedBuildingInFloorNumberOrder() throws Exception {
        mvc.perform(get("/buildings/{id}/floors", firstBuilding.getId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].id").value(basementFloor.getId()))
                .andExpect(jsonPath("$[0].floorNumber").value(-1))
                .andExpect(jsonPath("$[1].id").value(secondFloor.getId()))
                .andExpect(jsonPath("$[2].id").value(emptyFloor.getId()));
        mvc.perform(get("/buildings/{id}/floors", otherBuilding.getId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(otherFloor.getId()));
        mvc.perform(get("/buildings/{id}/floors", firstBuilding.getId()).param("skip", "1").param("limit", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(secondFloor.getId()));
    }

    /** 층의 DB ID와 실제 층 번호를 구분하고 건물 정보도 반환한다. */
    @Test
    void readsFloorDetailsWithBuildingInformation() throws Exception {
        mvc.perform(get("/floors/{id}", basementFloor.getId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(basementFloor.getId()))
                .andExpect(jsonPath("$.floorNumber").value(-1)).andExpect(jsonPath("$.name").value("지하 1층"))
                .andExpect(jsonPath("$.buildingId").value(firstBuilding.getId()))
                .andExpect(jsonPath("$.buildingName").value("합성 건물"));
    }

    /** 층별 목록에는 그 층의 활성 장소만 있으며 다른 층·건물·비활성 장소는 제외한다. */
    @Test
    void listsOnlyActiveLocationsInSelectedFloor() throws Exception {
        mvc.perform(get("/floors/{id}/locations", secondFloor.getId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].id").value(firstLocation.getId().toString()))
                .andExpect(jsonPath("$[1].id").value(secondLocation.getId().toString()))
                .andExpect(jsonPath("$[2].id").value(thirdLocation.getId().toString()));
        mvc.perform(get("/floors/{id}/locations", otherFloor.getId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(otherLocation.getId().toString()));
    }

    /** 목록을 메모리에서 자르지 않고 DB에서 활성 필터 후 정확한 skip·limit을 적용한다. */
    @Test
    void appliesExactOffsetAfterFilteringInactiveLocations() throws Exception {
        for (String path : List.of("/locations", "/floors/" + secondFloor.getId() + "/locations")) {
            mvc.perform(get(path).param("skip", "1").param("limit", "2"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(2)))
                    .andExpect(jsonPath("$[0].id").value(secondLocation.getId().toString()))
                    .andExpect(jsonPath("$[1].id").value(thirdLocation.getId().toString()));
        }
        mvc.perform(get("/locations")).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(5)));
    }

    /** 기존 장소 응답의 필드·문자열 ID를 유지하고 이름·주소·층을 올바르게 조합한다. */
    @Test
    void readsLocationDetailsUsingExistingResponseFields() throws Exception {
        var result = mvc.perform(get("/locations/{id}", firstLocation.getId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(firstLocation.getId().toString()))
                .andExpect(jsonPath("$.location_name").value("205호 앞"))
                .andExpect(jsonPath("$.description").value("합성 설명"))
                .andExpect(jsonPath("$.floor").value(2)).andExpect(jsonPath("$.address").value("테스트 주소 A"))
                .andExpect(jsonPath("$.buildingId").value(firstBuilding.getId()))
                .andExpect(jsonPath("$.floorId").value(secondFloor.getId()))
                .andExpect(jsonPath("$.floorName").value("2층"))
                .andExpect(jsonPath("$.active").doesNotExist()).andReturn();
        assertThat(json.readTree(result.getResponse().getContentAsString()).get("id").isString()).isTrue();
        mvc.perform(get("/locations/{id}", basementLocation.getId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.floor").value(-1))
                .andExpect(jsonPath("$.description").value(nullValue()));
    }

    /** 없는 부모는 404, 존재하지만 자식이 없는 부모는 200과 빈 배열로 구분한다. */
    @Test
    void distinguishesMissingParentFromEmptyChildList() throws Exception {
        mvc.perform(get("/buildings/{id}/floors", Long.MAX_VALUE)).andExpect(status().isNotFound());
        mvc.perform(get("/floors/{id}/locations", Long.MAX_VALUE)).andExpect(status().isNotFound());
        mvc.perform(get("/buildings/{id}/floors", emptyBuilding.getId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(get("/floors/{id}/locations", emptyFloor.getId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
    }

    /** 유효한 범위지만 마지막 항목을 지난 페이지는 오류 대신 빈 배열을 반환한다. */
    @Test
    void returnsEmptyListAfterLastResult() throws Exception {
        for (String path : listPaths()) {
            mvc.perform(get(path).param("skip", "10000").param("limit", "100"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
        }
    }

    /** 삭제·비활성 장소를 일반 조회로 노출하지 않고 DB 행은 그대로 유지한다. */
    @Test
    void rejectsMissingAndInactiveDetailsWithSafe404() throws Exception {
        for (String path : List.of("/buildings/" + Long.MAX_VALUE, "/floors/" + Long.MAX_VALUE,
                "/locations/" + Long.MAX_VALUE, "/locations/" + hiddenLocation.getId())) {
            var result = mvc.perform(get(path)).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND")).andReturn();
            var body = json.readTree(result.getResponse().getContentAsString());
            assertThat(body.get("traceId").asString()).isEqualTo(result.getResponse().getHeader("X-Request-ID"));
        }
        assertThat(locations.findById(hiddenLocation.getId())).isPresent();
    }

    /** 음수·0 ID는 DB 조회 전에 거절하며 존재하지 않는 양수 ID의 404와 구분한다. */
    @Test
    void rejectsNonPositiveIds() throws Exception {
        for (String id : List.of("0", "-1")) {
            for (String path : List.of("/buildings/" + id, "/buildings/" + id + "/floors",
                    "/floors/" + id, "/floors/" + id + "/locations", "/locations/" + id)) {
                mvc.perform(get(path)).andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                        .andExpect(jsonPath("$.errors[0].field").exists());
            }
        }
    }

    /** 모든 목록에서 잘못된 페이지 범위를 동일하게 거절한다. */
    @Test
    void boundsEveryListRequest() throws Exception {
        for (String path : listPaths()) {
            for (String skip : List.of("-1", "10001")) {
                mvc.perform(get(path).param("skip", skip)).andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.errors[0].field").value("skip"));
            }
            for (String limit : List.of("0", "-1", "101")) {
                mvc.perform(get(path).param("limit", limit)).andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.errors[0].field").value("limit"));
            }
        }
    }

    /** 숫자 변환 실패도 500이 아닌 공통 400이며 입력 원문을 그대로 응답하지 않는다. */
    @Test
    void rejectsMalformedAndOverflowingNumbers() throws Exception {
        String invalid = "synthetic-invalid-value";
        for (String id : List.of(invalid, "9223372036854775808")) {
            var result = mvc.perform(get("/locations/{id}", id)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST")).andReturn();
            assertThat(result.getResponse().getContentAsString()).doesNotContain(invalid);
        }
        mvc.perform(get("/buildings").param("skip", "2147483648")).andExpect(status().isBadRequest());
        mvc.perform(get("/locations").param("limit", invalid)).andExpect(status().isBadRequest());
    }

    /** URL 끝의 슬래시 유무에 따라 앱에 불필요한 리다이렉트나 다른 응답을 주지 않는다. */
    @Test
    void supportsBothTrailingSlashFormsWithoutSession() throws Exception {
        for (String path : allPaths()) {
            var first = mvc.perform(get(path)).andExpect(status().isOk()).andReturn();
            var withSlash = mvc.perform(get(path + "/")).andExpect(status().isOk())
                    .andExpect(header().doesNotExist("Location")).andReturn();
            assertThat(withSlash.getResponse().getContentAsString()).isEqualTo(first.getResponse().getContentAsString());
            assertThat(withSlash.getRequest().getSession(false)).isNull();
        }
    }

    /** GET만 공개하며 데이터 변경이나 미구현 API가 함께 열리지 않는지 확인한다. */
    @Test
    void keepsWritesAndUnimplementedRoutesClosed() throws Exception {
        String token = tokens.issueAccessToken(1L);
        for (String path : allPaths()) {
            for (var method : List.of(post(path), put(path), patch(path), delete(path))) {
                mvc.perform(method.header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content("{}"))
                        .andExpect(status().isForbidden());
            }
        }
        for (String path : List.of("/places/search", "/locations/1/mappings", "/buildings/1/admin", "/actuator/env")) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            mvc.perform(get(path).header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
        }
    }

    /** 공개 조회는 토큰 없이도 되지만 잘못된 Bearer 헤더를 보내면 기존 인증 정책대로 거절한다. */
    @Test
    void acceptsValidTokenAndRejectsMalformedBearerHeader() throws Exception {
        mvc.perform(get("/buildings").header("Authorization", "Bearer " + tokens.issueAccessToken(1L)))
                .andExpect(status().isOk());
        mvc.perform(get("/buildings").header("Authorization", "Bearer malformed-token"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    /** 기본 요청도 최대 100개만 가져오고 장소 수가 늘어도 추가 조회가 반복되지 않는다. */
    @Test
    void capsResponseSizeAndAvoidsPerLocationQueries() throws Exception {
        for (int i = 0; i < 105; i++) {
            locations.save(new IndoorLocation(secondFloor.getId(), "합성 장소 " + i, null));
        }
        entityManager.flush();
        entityManager.clear();
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        mvc.perform(get("/locations")).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(100)));
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);

        statistics.clear();
        mvc.perform(get("/floors/{id}/locations", secondFloor.getId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(100)));
        // 부모 층 확인 1회 + 장소·층·건물 JOIN 1회로 끝나야 한다.
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
    }

    /** 조회 API를 모두 사용해도 기준 데이터의 행 수·내용이 바뀌지 않는다. */
    @Test
    void doesNotCreateOrModifyReferenceDataDuringReads() throws Exception {
        long buildingCount = buildings.count();
        long floorCount = floors.count();
        long locationCount = locations.count();
        for (String path : allPaths()) {
            mvc.perform(get(path)).andExpect(status().isOk());
        }
        assertThat(buildings.count()).isEqualTo(buildingCount);
        assertThat(floors.count()).isEqualTo(floorCount);
        assertThat(locations.count()).isEqualTo(locationCount);
        assertThat(locations.findById(firstLocation.getId()).orElseThrow().getName()).isEqualTo("205호 앞");
        assertThat(locations.findById(hiddenLocation.getId()).orElseThrow().isActive()).isFalse();
    }

    /** 공통 페이지 정책을 확인할 네 종류의 목록 경로를 제공한다. */
    private List<String> listPaths() {
        return List.of("/buildings", "/buildings/" + firstBuilding.getId() + "/floors",
                "/floors/" + secondFloor.getId() + "/locations", "/locations");
    }

    /** 이번에 구현한 일곱 가지 GET 경로를 준비한다. */
    private List<String> allPaths() {
        return List.of("/buildings", "/buildings/" + firstBuilding.getId(),
                "/buildings/" + firstBuilding.getId() + "/floors", "/floors/" + secondFloor.getId(),
                "/floors/" + secondFloor.getId() + "/locations", "/locations", "/locations/" + firstLocation.getId());
    }
}
