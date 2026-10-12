package com.example.magnavi_springserver.positioning;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import com.example.magnavi_springserver.place.domain.Building;
import com.example.magnavi_springserver.place.domain.Floor;
import com.example.magnavi_springserver.place.domain.IndoorLocation;
import com.example.magnavi_springserver.place.domain.ModelLocationMapping;
import com.example.magnavi_springserver.place.infrastructure.persistence.BuildingJpaRepository;
import com.example.magnavi_springserver.place.infrastructure.persistence.FloorJpaRepository;
import com.example.magnavi_springserver.place.infrastructure.persistence.IndoorLocationJpaRepository;
import com.example.magnavi_springserver.place.infrastructure.persistence.ModelLocationMappingJpaRepository;
import com.example.magnavi_springserver.positioning.application.PositioningResult;
import com.example.magnavi_springserver.positioning.application.PositioningSessionService;
import com.example.magnavi_springserver.positioning.domain.ModelEvent;
import com.example.magnavi_springserver.positioning.domain.SensorSample;
import com.example.magnavi_springserver.positioning.support.TestModelServer;
import com.example.magnavi_springserver.support.MySqlTestConfiguration;

/** Spring 설정·실제 gRPC·격리 MySQL 매핑을 한 흐름으로 검증한다. 실제 모델과 앱은 사용하지 않는다. */
@SpringBootTest
@ActiveProfiles("test")
@Import(MySqlTestConfiguration.class)
@Transactional
class PositioningMappingIntegrationTests {
    private static final TestModelServer MODEL = startModel();
    @Autowired private PositioningSessionService service;
    @Autowired private BuildingJpaRepository buildings;
    @Autowired private FloorJpaRepository floors;
    @Autowired private IndoorLocationJpaRepository locations;
    @Autowired private ModelLocationMappingJpaRepository mappings;
    private IndoorLocation first;
    private IndoorLocation second;

    /** 운영 설정 없이 테스트 전용 포트와 가상의 센서 규격을 Spring에 주입한다. */
    @DynamicPropertySource
    static void configureModel(DynamicPropertyRegistry registry) {
        registry.add("magnavi.model-grpc.enabled", () -> true);
        registry.add("magnavi.model-grpc.host", () -> "127.0.0.1");
        registry.add("magnavi.model-grpc.port", MODEL::port);
        registry.add("magnavi.model-grpc.plaintext", () -> true);
        registry.add("magnavi.model-grpc.sensor-profile-id", () -> "synthetic-v1");
    }

    /** 같은 모델 코드가 버전에 따라 다른 장소를 가리키는 합성 데이터를 만든다. */
    @BeforeEach
    void prepareMappings() {
        var building = buildings.save(new Building("gRPC 테스트 건물", "합성 주소"));
        var floor = floors.save(new Floor(building.getId(), 1, "1층"));
        first = locations.save(new IndoorLocation(floor.getId(), "첫 번째 장소", null));
        second = locations.save(new IndoorLocation(floor.getId(), "다른 버전 장소", null));
        mappings.save(new ModelLocationMapping("model-a", "v1", "007", first.getId()));
        mappings.save(new ModelLocationMapping("model-a", "v2", "007", second.getId()));
        mappings.save(new ModelLocationMapping("model-a", "v1", "7", second.getId()));
        mappings.flush();
    }

    /** gRPC 코드의 앞자리 0을 보존하고 지정한 버전의 장소를 조회한다. */
    @Test
    void mapsReceivedCodeUsingExactModelVersion() {
        long before = mappings.count();
        var v1 = predict("model-a", "v1");
        var v2 = predict("model-a", "v2");
        assertThat(v1.mappingStatus()).isEqualTo(PositioningResult.MappingStatus.FOUND);
        assertThat(v1.location().id()).isEqualTo(first.getId());
        assertThat(v2.location().id()).isEqualTo(second.getId());
        assertThat(mappings.count()).isEqualTo(before);
        assertThat(locations.count()).isEqualTo(2);
    }

    /** 모델의 예측이 성공해도 DB 매핑이 없으면 사용 가능한 장소인 것처럼 응답하지 않는다. */
    @Test
    void reportsMissingMappingWithoutFallback() {
        var result = predict("model-a", "missing-version");
        assertThat(result.mappingStatus()).isEqualTo(PositioningResult.MappingStatus.NOT_FOUND);
        assertThat(result.location()).isNull();
        assertThat(((ModelEvent.Prediction) result.event()).locationCode()).isEqualTo("007");
    }

    /** 비활성 장소는 예전 매핑을 유지하되 현재 사용 가능한 장소로 표시하지 않는다. */
    @Test
    void reportsInactiveLocationWithoutChangingIt() {
        first.deactivate();
        locations.flush();
        var result = predict("model-a", "v1");
        assertThat(result.mappingStatus()).isEqualTo(PositioningResult.MappingStatus.NOT_FOUND);
        assertThat(result.location()).isNull();
        assertThat(mappings.findByModelKeyAndModelVersionAndLocationCode("model-a", "v1", "007")).isPresent();
    }

    /** 실제 gRPC로 준비·워밍업을 거쳐 예측 하나를 받으며 연결은 반드시 닫는다. */
    private PositioningResult predict(String model, String version) {
        try (var connection = service.open(model, version)) {
            var ready = connection.next(Duration.ofSeconds(3)).orElseThrow();
            assertThat(ready.event()).isEqualTo(new ModelEvent.Ready(16));
            assertThat(ready.mappingStatus()).isEqualTo(PositioningResult.MappingStatus.NOT_REQUESTED);
            var vector = new SensorSample.Vector(0, 0, 0);
            PositioningResult result = ready;
            for (int i = 1; i <= 16; i++) {
                connection.send(new SensorSample(i, (i - 1) * 100, vector, vector));
                result = connection.next(Duration.ofSeconds(3)).orElseThrow();
                if (i < 16) assertThat(result.mappingStatus()).isEqualTo(PositioningResult.MappingStatus.NOT_REQUESTED);
            }
            return result;
        }
    }

    /** 테스트 클래스 초기화 실패가 모델 준비 성공으로 숨겨지지 않게 한다. */
    private static TestModelServer startModel() {
        try { return new TestModelServer(); }
        catch (IOException error) { throw new ExceptionInInitializerError(error); }
    }

    /** 서버 포트를 테스트 종료 시 반납한다. Spring이 소유한 클라이언트는 컨텍스트에서 닫는다. */
    @AfterAll
    static void stopModel() throws Exception { MODEL.close(); }
}
