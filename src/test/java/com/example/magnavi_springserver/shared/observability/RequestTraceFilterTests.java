package com.example.magnavi_springserver.shared.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** 요청 성공·실패·동시 실행 때 스레드별 추적 정보가 남거나 섞이지 않는지 검증한다. */
class RequestTraceFilterTests {

    private final RequestTraceFilter filter = new RequestTraceFilter();

    /** 테스트 실패 여부와 관계없이 실행 스레드의 로그 상태를 복원한다. */
    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    /** 정상 요청이 끝나면 MDC를 지우고 다음 요청에 다른 ID를 부여한다. */
    @Test
    void clearsTraceAfterRequestAndGeneratesNewId() throws Exception {
        var firstRequest = new MockHttpServletRequest();
        var firstResponse = new MockHttpServletResponse();
        filter.doFilter(firstRequest, firstResponse, (request, response) ->
                assertThat(MDC.get("traceId")).isEqualTo(RequestTraceFilter.traceId(firstRequest)));
        assertThat(MDC.get("traceId")).isNull();

        var secondResponse = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest(), secondResponse, (request, response) -> {});
        assertThat(secondResponse.getHeader("X-Request-ID")).isNotEqualTo(firstResponse.getHeader("X-Request-ID"));
        assertThat(MDC.get("traceId")).isNull();
    }

    /** 오류가 발생해도 상위 실행 맥락의 추적 정보를 잃지 않는다. */
    @Test
    void restoresPreviousTraceEvenWhenRequestFails() {
        MDC.put("traceId", "outer-context");
        assertThatThrownBy(() -> filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(),
                (request, response) -> { throw new ServletException("test-failure"); }))
                .isInstanceOf(ServletException.class);
        assertThat(MDC.get("traceId")).isEqualTo("outer-context");
    }

    /** 동일 요청의 내부 재처리에서 추적 ID가 바뀌지 않는다. */
    @Test
    void retainsIdForTheSameRequest() throws Exception {
        var request = new MockHttpServletRequest();
        var firstResponse = new MockHttpServletResponse();
        var nextResponse = new MockHttpServletResponse();
        filter.doFilter(request, firstResponse, (input, output) -> {});
        filter.doFilter(request, nextResponse, (input, output) -> {});
        assertThat(nextResponse.getHeader("X-Request-ID")).isEqualTo(firstResponse.getHeader("X-Request-ID"));
    }

    /** 겹쳐 실행되는 요청도 각 스레드와 응답에 자기 ID만 남긴다. */
    @Test
    void isolatesConcurrentRequests() throws Exception {
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> concurrentRequest(barrier));
            var second = executor.submit(() -> concurrentRequest(barrier));
            assertThat(first.get(10, TimeUnit.SECONDS)).isNotEqualTo(second.get(10, TimeUnit.SECONDS));
        }
    }

    /** 두 요청이 필터 안에 동시에 머무른 상태에서 MDC 격리와 정리를 확인한다. */
    private String concurrentRequest(CyclicBarrier barrier) throws Exception {
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (input, output) -> {
            try {
                barrier.await(5, TimeUnit.SECONDS);
            } catch (Exception exception) {
                throw new ServletException(exception);
            }
            assertThat(MDC.get("traceId")).isEqualTo(RequestTraceFilter.traceId(request));
        });
        assertThat(MDC.get("traceId")).isNull();
        return response.getHeader("X-Request-ID");
    }
}
