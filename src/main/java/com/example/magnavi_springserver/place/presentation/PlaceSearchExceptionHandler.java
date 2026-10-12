package com.example.magnavi_springserver.place.presentation;

import java.util.List;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import com.example.magnavi_springserver.place.application.PlaceSearchException;
import com.example.magnavi_springserver.shared.error.ErrorResponse;
import com.example.magnavi_springserver.shared.observability.RequestTraceFilter;

/** 검색 장애를 빈 목록으로 숨기지 않고 원인을 구분할 수 있는 안전한 오류로 반환한다. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = PlaceSearchController.class)
public class PlaceSearchExceptionHandler {

    /** 제공자의 인증 오류는 앱의 JWT 오류가 아니므로 401로 그대로 전달하지 않는다. */
    @ExceptionHandler(PlaceSearchException.class)
    public ResponseEntity<ErrorResponse> handle(PlaceSearchException exception, HttpServletRequest request) {
        int status = switch (exception.getReason()) {
            case RATE_LIMITED -> 429;
            case TIMEOUT -> 504;
            case PROVIDER_ERROR -> 502;
            default -> 503;
        };
        String message = switch (exception.getReason()) {
            case NOT_CONFIGURED -> "장소 검색이 아직 준비되지 않았습니다.";
            case RATE_LIMITED -> "검색 요청이 많습니다. 잠시 후 다시 시도해 주세요.";
            case BUSY -> "검색 요청을 처리 중입니다. 잠시 후 다시 시도해 주세요.";
            case PROVIDER_LIMITED -> "외부 검색 서비스의 사용 한도에 도달했습니다.";
            case PROVIDER_ERROR -> "외부 검색 서비스에서 올바른 응답을 받지 못했습니다.";
            case TIMEOUT -> "외부 검색 서비스의 응답 시간이 초과되었습니다.";
        };
        return ResponseEntity.status(status).body(new ErrorResponse(
                "PLACE_SEARCH_" + exception.getReason().name(), message,
                RequestTraceFilter.traceId(request), List.of()));
    }
}
