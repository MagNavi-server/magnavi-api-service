package com.example.magnavi_springserver.config;

import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.TlsChannelCredentials;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.example.magnavi_springserver.positioning.infrastructure.GrpcModelStreamClient;
import com.example.magnavi_springserver.positioning.infrastructure.ModelGrpcProperties;

/** 명시적으로 활성화한 경우에만 재사용 채널과 비동기 모델 클라이언트를 준비한다. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ModelGrpcProperties.class)
public class GrpcClientConfig {

    /** 자동 재시도로 센서가 중복 실행되지 않게 하며 종료 시 채널도 함께 정리한다. */
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "magnavi.model-grpc", name = "enabled", havingValue = "true")
    public GrpcModelStreamClient modelStreamClient(ModelGrpcProperties properties) {
        var credentials = properties.plaintext()
                ? InsecureChannelCredentials.create() : TlsChannelCredentials.create();
        var channel = Grpc.newChannelBuilderForAddress(properties.host(), properties.port(), credentials)
                .disableRetry()
                .maxInboundMessageSize(properties.maxMessageBytes())
                .build();
        return new GrpcModelStreamClient(channel, properties);
    }
}
