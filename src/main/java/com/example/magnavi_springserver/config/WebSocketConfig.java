package com.example.magnavi_springserver.config;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;
import com.example.magnavi_springserver.positioning.application.PositioningSessionService;
import com.example.magnavi_springserver.positioning.infrastructure.ModelGrpcProperties;
import com.example.magnavi_springserver.positioning.infrastructure.PositioningWebSocketProperties;
import com.example.magnavi_springserver.positioning.presentation.PositioningHandshakeInterceptor;
import com.example.magnavi_springserver.positioning.presentation.PositioningWebSocketHandler;

/** 앱 연결을 명시적으로 활성화했을 때만 WebSocket 경로와 작업자를 등록한다. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PositioningWebSocketProperties.class)
public class WebSocketConfig {

    /** 일반 업무 API 실행에는 WebSocket 컨테이너와 모델 연결을 요구하지 않는다. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "magnavi.positioning-websocket", name = "enabled", havingValue = "true")
    @EnableWebSocket
    static class Endpoint {
        /** 기존 gRPC 호출과 공유 시계를 사용하며 컨텍스트 종료 시 연결도 정리한다. */
        @Bean(destroyMethod = "close")
        PositioningWebSocketHandler positioningWebSocketHandler(PositioningSessionService service,
                PositioningWebSocketProperties properties, ModelGrpcProperties model, Clock jwtClock) {
            return new PositioningWebSocketHandler(service, properties, model, jwtClock);
        }

        /** 헤더로 인증한 단일 경로만 열고, 브라우저 출처는 명시한 목록으로 제한한다. */
        @Bean
        WebSocketConfigurer positioningWebSocketConfigurer(PositioningWebSocketHandler handler,
                PositioningWebSocketProperties properties, Clock jwtClock) {
            return new WebSocketConfigurer() {
                /** 같은 출처 또는 허용한 출처의 요청만 업그레이드한다. */
                @Override
                public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
                    registry.addHandler(handler, "/ws/positioning")
                            .addInterceptors(new PositioningHandshakeInterceptor(jwtClock))
                            .setAllowedOrigins(properties.allowedOrigins().toArray(String[]::new));
                }
            };
        }

        /** 핸들러에 도달하기 전에도 컨테이너가 과도한 메시지와 유휴 연결을 제한한다. */
        @Bean
        ServletServerContainerFactoryBean positioningWebSocketContainer(PositioningWebSocketProperties properties) {
            var container = new ServletServerContainerFactoryBean();
            container.setMaxTextMessageBufferSize(properties.maxMessageBytes());
            container.setMaxBinaryMessageBufferSize(properties.maxMessageBytes());
            container.setAsyncSendTimeout(properties.sendTimeout().toMillis());
            container.setMaxSessionIdleTimeout(properties.idleTimeout().plusSeconds(1).toMillis());
            return container;
        }
    }
}
