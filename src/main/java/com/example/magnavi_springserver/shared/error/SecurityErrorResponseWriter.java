package com.example.magnavi_springserver.shared.error;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import com.example.magnavi_springserver.shared.observability.RequestTraceFilter;

/** Controller를 거치지 않는 보안 실패를 MVC와 같은 JSON 규격으로 기록한다. */
@Component
public class SecurityErrorResponseWriter {

    private final ObjectMapper objectMapper;

    /** 애플리케이션 공통 JSON 설정을 그대로 사용한다. */
    public SecurityErrorResponseWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 리다이렉트나 서블릿 오류 페이지 대신 상태 코드와 안전한 오류 JSON을 전송한다. */
    public void write(HttpServletRequest request, HttpServletResponse response, int status) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(status);
        if (status == 401) {
            response.setHeader("WWW-Authenticate", "Bearer");
        }
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(),
                ErrorResponse.ofStatus(status, RequestTraceFilter.traceId(request), List.of()));
    }
}
