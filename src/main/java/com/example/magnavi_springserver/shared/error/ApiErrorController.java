package com.example.magnavi_springserver.shared.error;

import java.util.List;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.magnavi_springserver.shared.observability.RequestTraceFilter;

/** MVC 예외 처리 밖에서 발생한 서블릿 오류도 같은 안전한 응답 형식으로 변환한다. */
@RestController
public class ApiErrorController implements ErrorController {

    /** 컨테이너가 설정한 오류 상태만 사용하고 내부 예외·요청 경로는 공개하지 않는다. */
    @RequestMapping("/error")
    public ResponseEntity<ErrorResponse> error(HttpServletRequest request) {
        Object originalStatus = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        int status = originalStatus instanceof Integer value && value >= 400 && value <= 599 ? value : 500;
        return ResponseEntity.status(status)
                .body(ErrorResponse.ofStatus(status, RequestTraceFilter.traceId(request), List.of()));
    }
}
