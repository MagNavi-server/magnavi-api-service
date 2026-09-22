package com.example.magnavi_springserver.config;

import jakarta.servlet.DispatcherType;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;

import com.example.magnavi_springserver.shared.error.SecurityErrorResponseWriter;
import com.example.magnavi_springserver.shared.security.AuthenticatedMember;

/**
 * 가입·로그인·실내 장소 조회·상태 확인을 공개하고, 본인 정보에는 검증된 JWT를 요구한다.
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
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(token ->
                                // sub 검증이 끝난 토큰에서 내부 회원 번호만 업무 계층에 전달한다.
                                UsernamePasswordAuthenticationToken.authenticated(
                                        new AuthenticatedMember(Long.valueOf(token.getSubject())), null, List.of())))
                        .authenticationEntryPoint((request, response, exception) -> errorWriter.write(request, response, 401))
                        .accessDeniedHandler((request, response, exception) -> errorWriter.write(request, response, 403)))
                .authorizeHttpRequests(requests -> requests
                        // 컨테이너 내부 오류 전달만 허용한다. 외부의 /error 직접 요청은 계속 거절한다.
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health/liveness", "/actuator/health/readiness").permitAll()
                        .requestMatchers(HttpMethod.POST, "/users/signup", "/users/login").permitAll()
                        // 기존 장소 조회처럼 GET만 공개한다. 등록·수정·삭제나 하위 경로 전체를 열지 않는다.
                        .requestMatchers(HttpMethod.GET,
                                "/buildings", "/buildings/", "/buildings/{buildingId}", "/buildings/{buildingId}/",
                                "/buildings/{buildingId}/floors", "/buildings/{buildingId}/floors/",
                                "/floors/{floorId}", "/floors/{floorId}/",
                                "/floors/{floorId}/locations", "/floors/{floorId}/locations/",
                                "/locations", "/locations/", "/locations/{locationId}", "/locations/{locationId}/").permitAll()
                        .requestMatchers(HttpMethod.GET, "/users/me").authenticated()
                        .requestMatchers(HttpMethod.PUT, "/users/me/username").authenticated()
                        .anyRequest().denyAll())
                .build();
    }
}
