package com.example.magnavi_springserver.positioning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.example.magnavi_springserver.config.GrpcClientConfig;
import com.example.magnavi_springserver.place.application.ModelLocationQueryService;
import com.example.magnavi_springserver.positioning.application.ModelStreamClient;
import com.example.magnavi_springserver.positioning.application.ModelStreamException;
import com.example.magnavi_springserver.positioning.application.PositioningSessionService;

/** 실제 환경변수 바인딩과 기본 비활성 정책을 모델·DB 없이 검증한다. */
class ModelGrpcConfigurationTests {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(GrpcClientConfig.class);

    /** 외부 모델이 없어도 업무 서버를 기동하고 모델 호출에만 준비 안 됨을 알린다. */
    @Test
    void disablesModelByDefault() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(ModelStreamClient.class);
            var service = new PositioningSessionService(context.getBeanProvider(ModelStreamClient.class),
                    mock(ModelLocationQueryService.class));
            var error = catchThrowableOfType(ModelStreamException.class, () -> service.open("model", "v1"));
            assertThat(error.getReason()).isEqualTo(ModelStreamException.Reason.NOT_CONFIGURED);
        });
    }

    /** 센서 규격이 비어 있으면 실제 모델 연결을 활성화하지 않는다. */
    @Test
    void requiresExplicitSensorProfile() {
        runner.withPropertyValues("magnavi.model-grpc.enabled=true", "magnavi.model-grpc.host=127.0.0.1")
                .run(context -> assertThat(context).hasFailed());
    }

    /** 무제한 대기열을 만드는 0 설정을 거절한다. */
    @Test
    void rejectsInvalidQueueLimit() {
        runner.withPropertyValues("magnavi.model-grpc.max-pending-samples=0")
                .run(context -> assertThat(context).hasFailed());
    }

    /** 원격 서버에 센서를 평문으로 보내는 설정을 거절한다. */
    @Test
    void refusesRemotePlaintext() {
        runner.withPropertyValues("magnavi.model-grpc.enabled=true", "magnavi.model-grpc.host=model.internal",
                        "magnavi.model-grpc.sensor-profile-id=synthetic-v1", "magnavi.model-grpc.plaintext=true")
                .run(context -> assertThat(context).hasFailed());
    }
}
