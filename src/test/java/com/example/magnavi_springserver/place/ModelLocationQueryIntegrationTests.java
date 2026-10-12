package com.example.magnavi_springserver.place;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.util.Arrays;
import java.util.List;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.example.magnavi_springserver.place.application.IndoorLocationInfo;
import com.example.magnavi_springserver.place.application.ModelLocationQueryService;
import com.example.magnavi_springserver.place.application.PlaceException;
import com.example.magnavi_springserver.place.domain.Building;
import com.example.magnavi_springserver.place.domain.Floor;
import com.example.magnavi_springserver.place.domain.IndoorLocation;
import com.example.magnavi_springserver.place.domain.ModelLocationMapping;
import com.example.magnavi_springserver.place.infrastructure.persistence.BuildingJpaRepository;
import com.example.magnavi_springserver.place.infrastructure.persistence.FloorJpaRepository;
import com.example.magnavi_springserver.place.infrastructure.persistence.IndoorLocationJpaRepository;
import com.example.magnavi_springserver.place.infrastructure.persistence.ModelLocationMappingJpaRepository;
import com.example.magnavi_springserver.support.MySqlTestConfiguration;

/** 가상의 매핑을 임시 MySQL에 넣고 실제 서비스 호출로 모델·버전 격리와 실패 처리를 검증한다. */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(MySqlTestConfiguration.class)
@Transactional
class ModelLocationQueryIntegrationTests {

    @Autowired private ModelLocationQueryService service;
    @Autowired private BuildingJpaRepository buildings;
    @Autowired private FloorJpaRepository floors;
    @Autowired private IndoorLocationJpaRepository locations;
    @Autowired private ModelLocationMappingJpaRepository mappings;
    @Autowired private EntityManager entityManager;
    @Autowired private EntityManagerFactory entityManagerFactory;

    private Building building;
    private Floor floor;
    private IndoorLocation firstLocation;
    private IndoorLocation otherLocation;
    private IndoorLocation hiddenLocation;

    /** 같은 코드가 모델·버전에 따라 다른 장소를 가리키도록 준비하며 테스트 종료 시 롤백한다. */
    @BeforeEach
    void prepareSyntheticMappings() {
        building = buildings.save(new Building("매핑 테스트 건물", "합성 주소 A"));
        var otherBuilding = buildings.save(new Building("매핑 테스트 건물", "합성 주소 B"));
        floor = floors.save(new Floor(building.getId(), -1, "지하 1층"));
        var otherFloor = floors.save(new Floor(otherBuilding.getId(), 2, "2층"));
        firstLocation = locations.save(new IndoorLocation(floor.getId(), "엘리베이터 앞", "합성 장소 설명"));
        otherLocation = locations.save(new IndoorLocation(otherFloor.getId(), "계단 앞", null));
        hiddenLocation = locations.save(new IndoorLocation(floor.getId(), "비활성 장소", null));
        hiddenLocation.deactivate();

        addMapping("model-a", "v1", "7", firstLocation);
        addMapping("model-a", "v2", "7", otherLocation);
        addMapping("model-b", "v1", "7", otherLocation);
        addMapping("model-a", "v1", "closed", hiddenLocation);
        addMapping("model-a", "v2", "closed", firstLocation);
        flushAndClear();
    }

    /** 장소와 연결된 건물·층 정보를 두 번의 SELECT로 읽고 DB 변경 SQL은 실행하지 않는다. */
    @Test
    void returnsPlaceBuildingAndFloorWithoutWritingData() {
        var statistics = statistics();
        statistics.clear();

        IndoorLocationInfo result = service.findLocation("model-a", "v1", "7");

        assertThat(result).isEqualTo(new IndoorLocationInfo(firstLocation.getId(), building.getId(),
                "매핑 테스트 건물", "합성 주소 A", floor.getId(), -1, "지하 1층", "엘리베이터 앞", "합성 장소 설명"));
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
        assertThat(statistics.getEntityInsertCount()).isZero();
        assertThat(statistics.getEntityUpdateCount()).isZero();
        assertThat(statistics.getEntityDeleteCount()).isZero();
    }

    /** 같은 버전·코드라도 모델 키가 다르면 서로 다른 건물의 장소를 반환한다. */
    @Test
    void separatesModelsWithTheSameCode() {
        assertLocation("model-a", "v1", "7", firstLocation);
        assertLocation("model-b", "v1", "7", otherLocation);
    }

    /** 새 버전이 등록되어 있어도 요청한 이전 버전의 장소 연결을 유지한다. */
    @Test
    void separatesVersionsWithTheSameCode() {
        assertLocation("model-a", "v1", "7", firstLocation);
        assertLocation("model-a", "v2", "7", otherLocation);
    }

    /** 실제 MySQL에서 세 식별자 모두 대소문자가 다른 매핑을 별도로 저장하고 조회한다. */
    @Test
    void distinguishesCaseInEveryIdentifier() {
        addMapping("Model-a", "v1", "7", otherLocation);
        addMapping("model-a", "V1", "7", otherLocation);
        addMapping("model-a", "v1", "Gate", firstLocation);
        addMapping("model-a", "v1", "gate", otherLocation);
        flushAndClear();

        assertLocation("model-a", "v1", "7", firstLocation);
        assertLocation("Model-a", "v1", "7", otherLocation);
        assertLocation("model-a", "V1", "7", otherLocation);
        assertLocation("model-a", "v1", "Gate", firstLocation);
        assertLocation("model-a", "v1", "gate", otherLocation);
        assertNotFound("MODEL-A", "v1", "7");
        assertNotFound("model-a", "v1", "GATE");
    }

    /** 모델 코드는 숫자처럼 보여도 문자열이므로 앞자리 0을 제거하지 않는다. */
    @Test
    void preservesLeadingZerosInCodes() {
        addMapping("model-a", "v1", "007", otherLocation);
        flushAndClear();
        assertLocation("model-a", "v1", "7", firstLocation);
        assertLocation("model-a", "v1", "007", otherLocation);
        assertNotFound("model-a", "v1", "07");
    }

    /** 저장 정책과 동일하게 식별자 앞뒤 공백도 보존해 임의 정규화로 다른 장소에 연결하지 않는다. */
    @Test
    void preservesSpacesInEveryIdentifier() {
        addMapping("model-a ", "v1", "7", otherLocation);
        addMapping("model-a", " v1", "7", otherLocation);
        addMapping("model-a", "v1", "7 ", otherLocation);
        flushAndClear();
        assertLocation("model-a", "v1", "7", firstLocation);
        assertLocation("model-a ", "v1", "7", otherLocation);
        assertLocation("model-a", " v1", "7", otherLocation);
        assertLocation("model-a", "v1", "7 ", otherLocation);
        assertNotFound(" model-a", "v1", "7");
    }

    /** 문자열 코드를 쿼리의 일부로 실행하지 않고 값으로 취급한다. */
    @Test
    void treatsQuotedAndNonNumericCodesAsValues() {
        String code = "구역-' OR '1'='1";
        addMapping("model-a", "v1", code, otherLocation);
        flushAndClear();
        assertLocation("model-a", "v1", code, otherLocation);
        assertNotFound("model-a", "v1", "' OR '1'='1");
    }

    /** null·빈 문자열·공백만 있는 입력과 길이 초과는 DB를 조회하기 전에 거절한다. */
    @Test
    void rejectsInvalidIdentifiersBeforeQuerying() {
        String[] fields = {"modelKey", "modelVersion", "locationCode"};
        int[] limits = {128, 64, 50};
        var statistics = statistics();
        for (int fieldIndex = 0; fieldIndex < fields.length; fieldIndex++) {
            for (String invalid : Arrays.asList(null, "", " \t\n", "\u2003", "x".repeat(limits[fieldIndex] + 1))) {
                String[] input = {"model-a", "v1", "7"};
                input[fieldIndex] = invalid;
                statistics.clear();
                var error = catchThrowableOfType(PlaceException.class,
                        () -> service.findLocation(input[0], input[1], input[2]));
                assertThat(error).isNotNull();
                assertThat(error.getReason()).isEqualTo(PlaceException.Reason.INVALID_INPUT);
                assertThat(error.getField()).isEqualTo(fields[fieldIndex]);
                assertThat(error.getMessage()).isEqualTo("INVALID_INPUT");
                assertThat(statistics.getPrepareStatementCount()).isZero();
            }
        }
    }

    /** UTF-16 길이가 아닌 실제 문자 수로 검사해 utf8mb4 컬럼의 최대 길이와 맞춘다. */
    @Test
    void acceptsMaximumUnicodeLengths() {
        String modelKey = "🚀".repeat(128);
        String version = "🚀".repeat(64);
        String code = "🚀".repeat(50);
        addMapping(modelKey, version, code, firstLocation);
        flushAndClear();
        assertLocation(modelKey, version, code, firstLocation);
    }

    /** 세 조건 중 하나라도 없으면 다른 모델·버전·코드로 대체하지 않는다. */
    @Test
    void rejectsMissingMappingsWithoutFallback() {
        assertNotFound("missing-model", "v1", "7");
        assertNotFound("model-a", "missing-version", "7");
        assertNotFound("model-a", "v1", "missing-code");
        assertNotFound("model-b", "v2", "7");
    }

    /** 코드 문자열이 실제 장소 ID와 같아도 연결 행이 없으면 장소를 반환하지 않는다. */
    @Test
    void neverUsesModelCodeAsPlaceId() {
        assertNotFound("unmapped-model", "v1", firstLocation.getId().toString());
    }

    /** 비활성 장소의 매핑을 삭제하거나 다른 버전의 활성 장소로 바꾸지 않는다. */
    @Test
    void rejectsInactivePlaceAndPreservesItsMapping() {
        assertNotFound("model-a", "v1", "closed");
        var mapping = mappings.findByModelKeyAndModelVersionAndLocationCode("model-a", "v1", "closed").orElseThrow();
        assertThat(mapping.getIndoorLocationId()).isEqualTo(hiddenLocation.getId());
        assertThat(locations.findById(hiddenLocation.getId()).orElseThrow().isActive()).isFalse();
    }

    /** 여러 출력 코드가 하나의 장소에 연결된 경우에도 각 연결을 그대로 사용할 수 있다. */
    @Test
    void allowsMultipleCodesForOnePlace() {
        for (String code : List.of("north", "south")) {
            addMapping("model-a", "v1", code, firstLocation);
        }
        flushAndClear();
        assertLocation("model-a", "v1", "north", firstLocation);
        assertLocation("model-a", "v1", "south", firstLocation);
    }

    /** 자체 캐시가 없어 다음 호출은 현재 DB의 활성 상태를 확인한다. */
    @Test
    void observesDeactivationOnTheNextRead() {
        assertLocation("model-a", "v1", "7", firstLocation);
        locations.findById(firstLocation.getId()).orElseThrow().deactivate();
        flushAndClear();
        assertNotFound("model-a", "v1", "7");
    }

    /** 실제 모델과 무관한 테스트용 연결을 만든다. 실서비스 DB에는 등록하지 않는다. */
    private void addMapping(String modelKey, String version, String code, IndoorLocation location) {
        mappings.save(new ModelLocationMapping(modelKey, version, code, location.getId()));
    }

    /** 메모리의 엔티티 대신 DB에 저장된 값을 읽도록 준비한다. */
    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    /** 해당 코드가 정확히 지정한 장소로 연결되는지 확인한다. */
    private void assertLocation(String modelKey, String version, String code, IndoorLocation expected) {
        assertThat(service.findLocation(modelKey, version, code).id()).isEqualTo(expected.getId());
    }

    /** 대상 없음 오류에는 입력 코드·모델 정보 등 상세 원문을 담지 않는다. */
    private void assertNotFound(String modelKey, String version, String code) {
        var error = catchThrowableOfType(PlaceException.class, () -> service.findLocation(modelKey, version, code));
        assertThat(error).isNotNull();
        assertThat(error.getReason()).isEqualTo(PlaceException.Reason.NOT_FOUND);
        assertThat(error.getField()).isEmpty();
        assertThat(error.getMessage()).isEqualTo("NOT_FOUND");
    }

    /** 실제 SQL 실행 수와 데이터 변경 여부를 확인할 Hibernate 통계를 제공한다. */
    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }
}
