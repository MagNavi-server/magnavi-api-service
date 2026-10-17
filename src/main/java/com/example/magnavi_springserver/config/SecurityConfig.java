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
import com.example.magnavi_springserver.shared.security.AccessTokenDetails;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * 가입·로그인·실내 장소 조회·상태 확인을 공개하고, 본인 정보·외부 검색·즐겨찾기·WebSocket에는 JWT를 요구한다.
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
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(this::memberAuthentication))
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
                        // WebSocket 최초 HTTP 연결도 같은 JWT 검증을 거친다.
                        .requestMatchers(HttpMethod.GET, "/ws/positioning").authenticated()
                        .requestMatchers(HttpMethod.GET, "/places/search", "/places/search/").authenticated()
                        .requestMatchers(HttpMethod.PUT, "/users/me/username").authenticated()
                        // 구현한 세 가지 동작만 연다. 소유권과 현재 회원 확인은 즐겨찾기 서비스가 담당한다.
                        .requestMatchers(HttpMethod.GET, "/favorites", "/favorites/").authenticated()
                        .requestMatchers(HttpMethod.POST, "/favorites", "/favorites/").authenticated()
                        .requestMatchers(HttpMethod.DELETE, "/favorites/{favoriteId}", "/favorites/{favoriteId}/").authenticated()
                        .anyRequest().denyAll())
                .build();
    }

    /** 검증된 회원 번호와 만료 시각만 전달하며 JWT 원문은 보관하지 않는다. */
    private UsernamePasswordAuthenticationToken memberAuthentication(Jwt token) {
        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                new AuthenticatedMember(Long.valueOf(token.getSubject())), null, List.of());
        authentication.setDetails(new AccessTokenDetails(token.getExpiresAt()));
        return authentication;
    }
}
