package com.example.magnavi_springserver.favorite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import com.example.magnavi_springserver.favorite.application.FavoriteException;
import com.example.magnavi_springserver.favorite.domain.Favorite;
import com.example.magnavi_springserver.favorite.infrastructure.FavoritePersistenceAdapter;
import com.example.magnavi_springserver.favorite.infrastructure.persistence.FavoriteJpaRepository;
import com.example.magnavi_springserver.member.domain.Member;
import com.example.magnavi_springserver.member.infrastructure.JwtTokenProvider;
import com.example.magnavi_springserver.member.infrastructure.persistence.MemberJpaRepository;
import com.example.magnavi_springserver.place.application.PlaceException;
import com.example.magnavi_springserver.place.application.PlaceQueryService;
import com.example.magnavi_springserver.place.domain.Building;
import com.example.magnavi_springserver.place.domain.Floor;
import com.example.magnavi_springserver.place.domain.IndoorLocation;
import com.example.magnavi_springserver.place.infrastructure.persistence.BuildingJpaRepository;
import com.example.magnavi_springserver.place.infrastructure.persistence.FloorJpaRepository;
import com.example.magnavi_springserver.place.infrastructure.persistence.IndoorLocationJpaRepository;
import com.example.magnavi_springserver.support.MySqlTestConfiguration;

/** 실제 JWT·MVC·커밋되는 트랜잭션·격리 MySQL로 즐겨찾기와 기존 데이터 보호를 검증한다. */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(MySqlTestConfiguration.class)
class FavoriteApiIntegrationTests {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private MemberJpaRepository members;
    @Autowired private BuildingJpaRepository buildings;
    @Autowired private FloorJpaRepository floors;
    @Autowired private IndoorLocationJpaRepository locations;
    @Autowired private FavoriteJpaRepository favorites;
    @Autowired private FavoritePersistenceAdapter persistence;
    @Autowired private PlaceQueryService places;
    @Autowired private JwtTokenProvider tokens;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManagerFactory entityManagerFactory;

    private Long ownerId;
    private Long otherId;
    private Long buildingId;
    private Long floorId;
    private Long locationId;
    private Long hiddenId;
    private String ownerToken;
    private String otherToken;

    /** 테스트 전용 회원 둘과 활성·비활성 장소를 만든다. 운영 DB나 실제 계정을 사용하지 않는다. */
    @BeforeEach
    void prepareSyntheticData() {
        ownerId = members.saveAndFlush(new Member("즐겨찾기 A", null, null)).getId();
        otherId = members.saveAndFlush(new Member("즐겨찾기 B", null, null)).getId();
        ownerToken = tokens.issueAccessToken(ownerId);
        otherToken = tokens.issueAccessToken(otherId);
        buildingId = buildings.saveAndFlush(new Building("합성 즐겨찾기 건물", "합성 주소")).getId();
        floorId = floors.saveAndFlush(new Floor(buildingId, 2, "2층")).getId();
        locationId = newLocation("205호 앞");
        IndoorLocation hidden = new IndoorLocation(floorId, "비활성 장소", null);
        hidden.deactivate();
        hiddenId = locations.saveAndFlush(hidden).getId();
    }

    /** 이 테스트에서 만든 행만 FK 역순으로 정리해 다른 테스트의 기준 데이터와 섞이지 않게 한다. */
    @AfterEach
    void removeSyntheticData() {
        jdbc.update("delete from favorites where member_id in (?, ?)", ownerId, otherId);
        jdbc.update("delete from indoor_locations where floor_id = ?", floorId);
        jdbc.update("delete from floors where id = ?", floorId);
        jdbc.update("delete from buildings where id = ?", buildingId);
        jdbc.update("delete from members where id in (?, ?)", ownerId, otherId);
    }

    /** 서버가 ID를 생성하며 DB의 최신 장소·층·건물 정보를 반환하고 소유자 내부 정보는 숨긴다. */
    @Test
    void createsIndoorFavoriteFromAuthenticatedMemberAndDatabase() throws Exception {
        var result = mvc.perform(post("/favorites/").header("Authorization", bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON).content(body(locationId)))
                .andExpect(status().isCreated()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.type").value("INDOOR_PLACE"))
                .andExpect(jsonPath("$.indoorLocationId").value(locationId))
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.location.name").value("205호 앞"))
                .andExpect(jsonPath("$.location.buildingId").value(buildingId))
                .andExpect(jsonPath("$.location.floorNumber").value(2))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.memberId").doesNotExist())
                .andExpect(jsonPath("$.targetKey").doesNotExist()).andReturn();
        long id = json.readTree(result.getResponse().getContentAsString()).get("id").asLong();
        assertThat(favorites.findByIdAndMemberId(id, ownerId)).isPresent();
        assertThat(result.getRequest().getSession(false)).isNull();
    }

    /** 같은 장소를 각자 저장할 수 있으며 내 목록에는 다른 회원의 항목이 섞이지 않는다. */
    @Test
    void separatesOwnersEvenWhenTheySaveTheSamePlace() throws Exception {
        long mine = create(ownerToken, locationId);
        long others = create(otherToken, locationId);
        assertThat(mine).isNotEqualTo(others);
        mvc.perform(get("/favorites").header("Authorization", bearer(ownerToken)))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(mine));
        mvc.perform(get("/favorites/").header("Authorization", bearer(otherToken)))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(others));
    }

    /** 반복 등록은 409로 안내하고 실패한 요청의 추가 행은 남기지 않는다. */
    @Test
    void rejectsDuplicateRegistrationWithoutExtraRows() throws Exception {
        create(ownerToken, locationId);
        var response = mvc.perform(post("/favorites").header("Authorization", bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON).content(body(locationId)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_FAVORITE"))
                .andReturn().getResponse();
        assertThat(response.getContentAsString()).doesNotContain("uk_favorites", "SQL", "member_id");
        assertThat(ownerCount()).isEqualTo(1);
    }

    /** 최신순·동일 시각의 ID 역순을 보장하며 skip=1, limit=2도 정확한 위치에서 시작한다. */
    @Test
    void appliesExactOffsetAndStableOrderAfterTypeAndOwnerFilters() throws Exception {
        long first = create(ownerToken, locationId);
        long second = create(ownerToken, newLocation("계단"));
        long third = create(ownerToken, newLocation("복도"));
        favorites.saveAndFlush(Favorite.bus(ownerId, "synthetic", "region", "route"));
        create(otherToken, locationId);
        jdbc.update("update favorites set created_at = '2020-01-01 00:00:00' where member_id = ?", ownerId);
        mvc.perform(get("/favorites").header("Authorization", bearer(ownerToken)))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].id").value(third));
        mvc.perform(get("/favorites").header("Authorization", bearer(ownerToken)).param("skip", "1").param("limit", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id").value(second)).andExpect(jsonPath("$[1].id").value(first));
    }

    /** 내 항목이 없거나 마지막 항목을 지나면 오류 대신 빈 배열을 반환한다. */
    @Test
    void returnsEmptyListWithoutLeakingOtherOwners() throws Exception {
        create(otherToken, locationId);
        mvc.perform(get("/favorites").header("Authorization", bearer(ownerToken)))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
        create(ownerToken, locationId);
        mvc.perform(get("/favorites").header("Authorization", bearer(ownerToken)).param("skip", "10000"))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
    }

    /** 조회 때 현재 장소 정보를 사용하고 기준 데이터나 즐겨찾기 행을 수정하지 않는다. */
    @Test
    void readsCurrentPlaceInformationWithoutDatabaseWrites() throws Exception {
        create(ownerToken, locationId);
        jdbc.update("update indoor_locations set name = '변경된 장소 이름' where id = ?", locationId);
        var before = jdbc.queryForList("select * from favorites where member_id = ?", ownerId);
        mvc.perform(get("/favorites").header("Authorization", bearer(ownerToken)))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$[0].location.name").value("변경된 장소 이름"));
        assertThat(jdbc.queryForList("select * from favorites where member_id = ?", ownerId)).isEqualTo(before);
        assertThat(locations.findById(locationId).orElseThrow().getName()).isEqualTo("변경된 장소 이름");
    }

    /** 저장 후 비활성화돼도 목록과 삭제는 가능하고 공개 장소 상세는 계속 404다. */
    @Test
    void keepsUnavailableFavoritesVisibleAndDeletable() throws Exception {
        long favoriteId = create(ownerToken, locationId);
        jdbc.update("update indoor_locations set is_active = false where id = ?", locationId);
        mvc.perform(get("/favorites").header("Authorization", bearer(ownerToken)))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(favoriteId))
                .andExpect(jsonPath("$[0].indoorLocationId").value(locationId))
                .andExpect(jsonPath("$[0].available").value(false))
                .andExpect(jsonPath("$[0].location").value(nullValue()));
        mvc.perform(get("/locations/{id}", locationId)).andExpect(status().isNotFound());
        mvc.perform(delete("/favorites/{id}/", favoriteId).header("Authorization", bearer(ownerToken)))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
        assertThat(locations.findById(locationId)).isPresent();
    }

    /** 타인의 항목과 없는 항목은 같은 404이며 소유자 이름·ID를 응답하지 않는다. */
    @Test
    void rejectsDeletingOtherOwnersFavoriteAndMissingFavorite() throws Exception {
        long others = create(otherToken, locationId);
        for (long id : List.of(others, Long.MAX_VALUE)) {
            mvc.perform(delete("/favorites/{id}", id).header("Authorization", bearer(ownerToken)))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }
        assertThat(favorites.findByIdAndMemberId(others, otherId)).isPresent();
    }

    /** 내 항목만 삭제하고 재삭제는 404로 응답한다. 장소 원본과 다른 회원의 항목은 보존한다. */
    @Test
    void deletesOnlyOwnedRowAndAllowsRegisteringAgain() throws Exception {
        long mine = create(ownerToken, locationId);
        long others = create(otherToken, locationId);
        mvc.perform(delete("/favorites/{id}", mine).header("Authorization", bearer(ownerToken)))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/favorites/{id}", mine).header("Authorization", bearer(ownerToken)))
                .andExpect(status().isNotFound());
        assertThat(favorites.findById(mine)).isEmpty();
        assertThat(favorites.findById(others)).isPresent();
        assertThat(locations.findById(locationId)).isPresent();
        assertThat(create(ownerToken, locationId)).isNotEqualTo(mine);
    }

    /** 후속 단계인 버스·정류장 행은 실내 API로 조회하거나 삭제하지 않는다. */
    @Test
    void preservesUnsupportedTransportFavorites() throws Exception {
        for (Favorite target : List.of(Favorite.bus(ownerId, "synthetic", "region", "route"),
                Favorite.busStop(ownerId, "synthetic", "region", "stop"))) {
            long id = favorites.saveAndFlush(target).getId();
            mvc.perform(delete("/favorites/{id}", id).header("Authorization", bearer(ownerToken)))
                    .andExpect(status().isNotFound());
            assertThat(favorites.findById(id)).isPresent();
        }
        mvc.perform(get("/favorites").header("Authorization", bearer(ownerToken)))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
    }

    /** 세 API 모두 토큰이 없거나 위조됐으면 DB 변경 없이 401을 반환한다. */
    @Test
    void requiresJwtForEveryOperation() throws Exception {
        for (String token : List.of("", "invalid-token")) {
            for (var request : List.of(get("/favorites/"), post("/favorites/").contentType(MediaType.APPLICATION_JSON)
                    .content(body(locationId)), delete("/favorites/1"))) {
                if (!token.isEmpty()) {
                    request.header("Authorization", bearer(token));
                }
                mvc.perform(request).andExpect(status().isUnauthorized())
                        .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
            }
        }
        assertThat(ownerCount()).isZero();
    }

    /** 서명된 토큰이어도 현재 존재하지 않는 회원이면 등록·조회·삭제를 모두 거절한다. */
    @Test
    void rejectsTokensOfDeletedOrMissingMembers() throws Exception {
        members.deleteById(ownerId);
        for (String token : List.of(ownerToken, tokens.issueAccessToken(Long.MAX_VALUE))) {
            for (var request : List.of(get("/favorites"), post("/favorites").contentType(MediaType.APPLICATION_JSON)
                    .content(body(locationId)), delete("/favorites/1"))) {
                mvc.perform(request.header("Authorization", bearer(token)))
                        .andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate", "Bearer"));
            }
        }
    }

    /** 없는 장소와 비활성 장소는 모두 404이며 즐겨찾기를 만들지 않는다. */
    @Test
    void rejectsMissingOrInactivePlaces() throws Exception {
        for (long id : List.of(hiddenId, Long.MAX_VALUE)) {
            mvc.perform(post("/favorites").header("Authorization", bearer(ownerToken))
                            .contentType(MediaType.APPLICATION_JSON).content(body(id)))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }
        assertThat(ownerCount()).isZero();
    }

    /** 유형·번호 누락, 다른 유형, 범위 밖·형식 오류는 400으로 처리하며 원문을 반사하지 않는다. */
    @Test
    void rejectsInvalidCreateBodies() throws Exception {
        for (String body : List.of("{}", "null", "{", "{\"type\":\"INDOOR_PLACE\"}",
                "{\"indoorLocationId\":1}", body(0L), body(-1L),
                "{\"type\":\"BUS\",\"indoorLocationId\":1}",
                "{\"type\":\"place\",\"id\":\"legacy\",\"name\":\"old-name\"}",
                "{\"type\":\"indoor_place\",\"indoorLocationId\":1}",
                "{\"type\":\"INDOOR_PLACE\",\"indoorLocationId\":1.5}",
                "{\"type\":\"INDOOR_PLACE\",\"indoorLocationId\":true}",
                "{\"type\":\"INDOOR_PLACE\",\"indoorLocationId\":\"1\"}",
                "{\"type\":\"INDOOR_PLACE\",\"indoorLocationId\":null}",
                "{\"type\":\"INDOOR_PLACE\",\"indoorLocationId\":9223372036854775808}",
                "{\"type\":\"INDOOR_PLACE\",\"indoorLocationId\":\"invalid-number\"}")) {
            var response = mvc.perform(post("/favorites").header("Authorization", bearer(ownerToken))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                    .andReturn().getResponse();
            assertThat(response.getContentAsString()).doesNotContain("old-name", "invalid-number");
        }
        assertThat(ownerCount()).isZero();
    }

    /** 앱이 보낸 임의 소유자·ID·이름은 계약 밖 입력으로 거절한다. */
    @Test
    void neverUsesClientSuppliedOwnershipOrDisplayInformation() throws Exception {
        String payload = "{\"type\":\"INDOOR_PLACE\",\"indoorLocationId\":" + locationId
                + ",\"memberId\":" + otherId + ",\"id\":999999,\"name\":\"untrusted-name\"}";
        var response = mvc.perform(post("/favorites").header("Authorization", bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isBadRequest()).andReturn().getResponse();
        assertThat(jdbc.queryForObject("select count(*) from favorites where member_id = ?", Long.class, otherId)).isZero();
        assertThat(ownerCount()).isZero();
        assertThat(response.getContentAsString()).doesNotContain("untrusted-name");
    }

    /** 잘못된 번호·페이지·지원하지 않는 회원 필터를 거절하고 타인 선택을 허용하지 않는다. */
    @Test
    void rejectsInvalidDeleteIdsAndListParameters() throws Exception {
        for (String id : List.of("0", "-1", "not-a-number", "9223372036854775808")) {
            mvc.perform(delete("/favorites/{id}", id).header("Authorization", bearer(ownerToken)))
                    .andExpect(status().isBadRequest());
        }
        for (String skip : List.of("-1", "10001", "abc", "2147483648")) {
            mvc.perform(get("/favorites").header("Authorization", bearer(ownerToken)).param("skip", skip))
                    .andExpect(status().isBadRequest());
        }
        for (String limit : List.of("0", "-1", "101", "abc")) {
            mvc.perform(get("/favorites").header("Authorization", bearer(ownerToken)).param("limit", limit))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(get("/favorites").header("Authorization", bearer(ownerToken)).param("memberId", otherId.toString()))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/favorites").header("Authorization", bearer(ownerToken)).param("limit", "1", "2"))
                .andExpect(status().isBadRequest());
    }

    /** 구현하지 않은 상세 GET·수정과 관리 경로는 인증한 회원에게도 열리지 않는다. */
    @Test
    void keepsUnimplementedMethodsAndRoutesClosed() throws Exception {
        for (var request : List.of(get("/favorites/1"), put("/favorites/1"), patch("/favorites/1"),
                post("/favorites/1"), delete("/favorites"), get("/favorites/admin/all"))) {
            mvc.perform(request.header("Authorization", bearer(ownerToken))).andExpect(status().isForbidden());
        }
    }

    /** 기본 목록도 100개까지만 반환하고 항목 수가 늘어도 회원·즐겨찾기·장소 조회 횟수는 일정하다. */
    @Test
    void capsListsAndReadsPlaceInformationInOneBatch() throws Exception {
        create(ownerToken, locationId);
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        mvc.perform(get("/favorites").header("Authorization", bearer(ownerToken))).andExpect(status().isOk());
        long smallListQueries = statistics.getPrepareStatementCount();
        for (int i = 0; i < 104; i++) {
            persistence.createIndoor(ownerId, newLocation("추가 합성 장소 " + i));
        }
        statistics.clear();
        mvc.perform(get("/favorites").header("Authorization", bearer(ownerToken)))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(100)));
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(smallListQueries).isEqualTo(4);
    }

    /** 묶음 조회는 비활성·없는 ID를 제외하며 빈 목록·중복 ID·최대 개수와 입력을 검사한다. */
    @Test
    void boundsPublicBatchPlaceQueryAndPreservesActiveFilter() {
        assertThat(places.getLocationsByIds(List.of(locationId, locationId, hiddenId, Long.MAX_VALUE)))
                .extracting(info -> info.id()).containsExactly(locationId);
        assertThat(places.getLocationsByIds(List.of())).isEmpty();
        for (List<Long> invalid : List.of(List.of(0L), java.util.Collections.nCopies(101, locationId),
                java.util.Arrays.asList(locationId, null))) {
            assertThatThrownBy(() -> places.getLocationsByIds(invalid)).isInstanceOf(PlaceException.class);
        }
        assertThatThrownBy(() -> places.getLocationsByIds(null)).isInstanceOf(PlaceException.class);
    }

    /** 실제 HTTP 동시 요청은 하나만 201이고 나머지는 409이며 성공한 행 하나만 남는다. */
    @Test
    void handlesConcurrentHttpRegistrations() throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> concurrentCreate(start));
            var second = executor.submit(() -> concurrentCreate(start));
            start.countDown();
            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(201, 409);
        }
        assertThat(ownerCount()).isEqualTo(1);
    }

    /** 사전 중복 조회를 통과한 상황처럼 두 INSERT를 직접 경쟁시켜 DB의 최종 보호도 검증한다. */
    @Test
    void databaseConstraintResolvesConcurrentInsertsAndRollsBackLoser() throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> concurrentInsert(start));
            var second = executor.submit(() -> concurrentInsert(start));
            start.countDown();
            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("created", "DUPLICATE");
        }
        assertThat(ownerCount()).isEqualTo(1);
        // 실패 트랜잭션이 끝난 뒤 새 요청도 정상 커밋할 수 있어야 한다.
        create(ownerToken, newLocation("재시도 장소"));
        assertThat(ownerCount()).isEqualTo(2);
    }

    /** 조회 직후 대상이 사라진 경우처럼 FK를 위반해도 원인에 맞게 응답하고 행을 남기지 않는다. */
    @Test
    void translatesForeignKeyFailuresWithoutSavingBrokenReferences() {
        assertThatThrownBy(() -> persistence.createIndoor(ownerId, Long.MAX_VALUE))
                .isInstanceOfSatisfying(FavoriteException.class,
                        error -> assertThat(error.getReason()).isEqualTo(FavoriteException.Reason.NOT_FOUND));
        assertThatThrownBy(() -> persistence.createIndoor(Long.MAX_VALUE, locationId))
                .isInstanceOfSatisfying(FavoriteException.class,
                        error -> assertThat(error.getReason()).isEqualTo(FavoriteException.Reason.UNAUTHENTICATED));
        assertThat(ownerCount()).isZero();
    }

    /** 동시 삭제도 한 번만 성공하고 이미 삭제된 요청은 404가 된다. */
    @Test
    void deletesExactlyOnceUnderConcurrentRequests() throws Exception {
        long id = create(ownerToken, locationId);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> concurrentDelete(start, id));
            var second = executor.submit(() -> concurrentDelete(start, id));
            start.countDown();
            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(204, 404);
        }
        assertThat(ownerCount()).isZero();
    }

    /** 실제 등록 API로 합성 데이터를 저장하고 서버가 발급한 ID를 반환한다. */
    private long create(String token, Long id) throws Exception {
        var response = mvc.perform(post("/favorites").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(body(id)))
                .andExpect(status().isCreated()).andReturn().getResponse();
        return json.readTree(response.getContentAsString()).get("id").asLong();
    }

    /** 같은 테스트 층에 추가 장소를 만들며 종료 시 함께 정리한다. */
    private Long newLocation(String name) {
        return locations.saveAndFlush(new IndoorLocation(floorId, name, null)).getId();
    }

    /** 승인된 새 실내 등록 계약에 맞는 JSON을 만든다. */
    private String body(Long id) {
        return "{\"type\":\"INDOOR_PLACE\",\"indoorLocationId\":" + id + "}";
    }

    /** 테스트에서 실제 JWT 보안 필터를 통과하도록 인증 헤더를 만든다. */
    private String bearer(String token) {
        return "Bearer " + token;
    }

    /** 격리된 합성 회원의 저장 행만 센다. */
    private long ownerCount() {
        return jdbc.queryForObject("select count(*) from favorites where member_id = ?", Long.class, ownerId);
    }

    /** 시작 신호를 기다린 뒤 별도 요청 스레드에서 등록한다. */
    private int concurrentCreate(CountDownLatch start) throws Exception {
        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
        return mvc.perform(post("/favorites").header("Authorization", bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON).content(body(locationId)))
                .andReturn().getResponse().getStatus();
    }

    /** 사전 조회 없는 INSERT로 동시에 발생한 UNIQUE 실패 변환을 확인한다. */
    private String concurrentInsert(CountDownLatch start) throws Exception {
        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
        try {
            persistence.createIndoor(ownerId, locationId);
            return "created";
        } catch (FavoriteException exception) {
            return exception.getReason().name();
        }
    }

    /** 별도 트랜잭션에서 같은 즐겨찾기 삭제를 경쟁시킨다. */
    private int concurrentDelete(CountDownLatch start, long id) throws Exception {
        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
        return mvc.perform(delete("/favorites/{id}", id).header("Authorization", bearer(ownerToken)))
                .andReturn().getResponse().getStatus();
    }
}
