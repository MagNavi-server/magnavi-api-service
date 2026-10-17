package com.example.magnavi_springserver.positioning.presentation;

import java.time.Clock;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.security.core.Authentication;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import com.example.magnavi_springserver.shared.security.AccessTokenDetails;
import com.example.magnavi_springserver.shared.security.AuthenticatedMember;

/** 보안 필터가 검증한 회원과 만료 시각만 WebSocket 측정 상태에 전달한다. */
public final class PositioningHandshakeInterceptor implements HandshakeInterceptor {
    static final String MEMBER = "positioning.member";
    static final String EXPIRY = "positioning.expiry";
    private final Clock clock;

    /** 실제 JWT 검증과 같은 시계를 사용한다. */
    public PositioningHandshakeInterceptor(Clock clock) { this.clock = clock; }

    /** URL 토큰을 받지 않으며, 인증된 GET 요청만 WebSocket으로 전환한다. */
    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler handler, Map<String, Object> attributes) {
        if (request.getURI().getRawQuery() != null) {
            response.setStatusCode(HttpStatus.BAD_REQUEST);
            return false;
        }
        if (!(request.getPrincipal() instanceof Authentication authentication) || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof AuthenticatedMember member)
                || !(authentication.getDetails() instanceof AccessTokenDetails token)
                || !token.expiresAt().isAfter(clock.instant())) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        attributes.put(MEMBER, member.memberId());
        attributes.put(EXPIRY, token.expiresAt());
        return true;
    }

    /** 연결 실패 시 별도로 저장한 인증 자원이 없어 추가 정리가 필요하지 않다. */
    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler handler, Exception exception) { }
}
