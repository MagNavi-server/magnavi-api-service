package com.example.magnavi_springserver.config;

import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

import com.example.magnavi_springserver.shared.error.SecurityErrorResponseWriter;

/**
 * 최소 상태 확인만 공개하는 초기 접근 정책이다. 실제 JWT 인증은 회원 단계에서 연결한다.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    /** 쿠키·기본 로그인 없이 동작하며, 명시적으로 허용하지 않은 요청은 거절한다. */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                  SecurityErrorResponseWriter errorWriter) throws Exception {
        return http
                // 브라우저가 자동 전송하는 쿠키·Basic 인증을 사용하지 않는다.
                // 향후 쿠키 인증을 도입하면 CSRF 정책도 함께 재설계해야 한다.
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> errorWriter.write(request, response, 401))
                        .accessDeniedHandler((request, response, exception) -> errorWriter.write(request, response, 403)))
                .authorizeHttpRequests(requests -> requests
                        // 컨테이너 내부 오류 전달만 허용한다. 외부의 /error 직접 요청은 계속 거절한다.
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health/liveness", "/actuator/health/readiness").permitAll()
                        .anyRequest().denyAll())
                .build();
    }
}
