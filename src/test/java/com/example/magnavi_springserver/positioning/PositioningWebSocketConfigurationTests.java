package com.example.magnavi_springserver.positioning;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import com.example.magnavi_springserver.config.WebSocketConfig;
import com.example.magnavi_springserver.positioning.infrastructure.PositioningWebSocketProperties;
import com.example.magnavi_springserver.positioning.presentation.PositioningWebSocketHandler;

/** 기존 업무 서버는 기본 설정으로 실시간 연결을 열지 않으며 잘못된 한도도 허용하지 않는다. */
class PositioningWebSocketConfigurationTests {
    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(WebSocketConfig.class);

    /** 모델·서블릿 컨테이너 없이도 기본 비활성 설정을 읽을 수 있다. */
    @Test void disabledByDefault() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(PositioningWebSocketHandler.class);
            assertThat(context.getBean(PositioningWebSocketProperties.class).enabled()).isFalse();
        });
    }

    /** 무제한 자원·과도한 시간 제한·모든 브라우저 허용 설정을 거절한다. */
    @ParameterizedTest
    @ValueSource(strings = {"max-connections=0", "max-connections-per-member=33", "max-message-bytes=0",
            "max-messages-per-second=0", "send-buffer-bytes=0", "send-timeout=0s", "idle-timeout=1h", "allowed-origins=*"})
    void rejectsUnsafeLimits(String property) {
        runner.withPropertyValues("magnavi.positioning-websocket." + property)
                .run(context -> assertThat(context).hasFailed());
    }
}
