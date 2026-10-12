package com.example.magnavi_springserver.config;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.example.magnavi_springserver.place.infrastructure.NaverSearchProperties;

/** 네이버 검색 전용 HTTP 연결을 재사용하고 서버 종료 때 정리한다. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(NaverSearchProperties.class)
public class NaverSearchConfig {

    /** 다른 주소로 인증 헤더가 전달되지 않도록 리다이렉트를 따라가지 않는다. */
    @Bean(destroyMethod = "close")
    public HttpClient naverSearchHttpClient(NaverSearchProperties properties) {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.connectTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }
}
