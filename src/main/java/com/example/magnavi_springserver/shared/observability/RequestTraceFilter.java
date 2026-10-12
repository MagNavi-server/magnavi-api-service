package com.example.magnavi_springserver.shared.observability;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** 보안 필터보다 먼저 추적 ID를 부여하고 요청 종료 시 스레드의 로그 정보를 복원한다. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestTraceFilter extends OncePerRequestFilter {

    public static final String RESPONSE_HEADER = "X-Request-ID";
    private static final String REQUEST_ATTRIBUTE = RequestTraceFilter.class.getName() + ".traceId";
    private static final String MDC_KEY = "traceId";
    private static final Logger log = LoggerFactory.getLogger(RequestTraceFilter.class);

    /** 오류 재디스패치에도 동일 요청 ID를 사용할 수 있게 요청 속성에 보관한다. */
    public static String traceId(HttpServletRequest request) {
        if (request.getAttribute(REQUEST_ATTRIBUTE) instanceof String existingId) {
            return existingId;
        }
        String traceId = TraceIdGenerator.generate();
        request.setAttribute(REQUEST_ATTRIBUTE, traceId);
        return traceId;
    }

    /** 입력·URL·토큰을 기록하지 않고 상태와 처리 시간만 추적 ID와 함께 남긴다. */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String previousTraceId = MDC.get(MDC_KEY);
        String traceId = traceId(request);
        long startedAt = System.nanoTime();
        MDC.put(MDC_KEY, traceId);
        response.setHeader(RESPONSE_HEADER, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            log.info("HTTP 요청 처리 종료: status={}, durationMs={}",
                    response.getStatus(), (System.nanoTime() - startedAt) / 1_000_000);
            // 서버 스레드를 다음 요청이 재사용하므로 추적 정보가 남지 않게 한다.
            if (previousTraceId == null) {
                MDC.remove(MDC_KEY);
            } else {
                MDC.put(MDC_KEY, previousTraceId);
            }
        }
    }

    /** 서블릿 오류 처리 경로에서도 기존 요청 식별자를 연결한다. */
    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }
}
