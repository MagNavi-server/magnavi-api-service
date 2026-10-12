package com.example.magnavi_springserver.favorite.presentation;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.example.magnavi_springserver.favorite.application.FavoriteException;
import com.example.magnavi_springserver.shared.error.ErrorResponse;
import com.example.magnavi_springserver.shared.observability.RequestTraceFilter;

/** 즐겨찾기의 예상 실패를 공통 형식으로 변환한다. 소유자·DB 정보는 응답에 넣지 않는다. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = FavoriteController.class)
public class FavoriteExceptionHandler {

    /** 중복은 409, 잘못된 입력은 400, 없는 회원은 401, 없는 대상과 타인 항목은 404로 처리한다. */
    @ExceptionHandler(FavoriteException.class)
    public ResponseEntity<ErrorResponse> handle(FavoriteException exception, HttpServletRequest request) {
        int status = switch (exception.getReason()) {
            case INVALID_INPUT -> 400;
            case UNAUTHENTICATED -> 401;
            case NOT_FOUND -> 404;
            case DUPLICATE -> 409;
        };
        String traceId = RequestTraceFilter.traceId(request);
        var errors = status == 400
                ? List.of(new ErrorResponse.FieldViolation(exception.getField(), "입력 조건을 확인해 주세요."))
                : List.<ErrorResponse.FieldViolation>of();
        var body = status == 409
                ? new ErrorResponse("DUPLICATE_FAVORITE", "이미 등록한 즐겨찾기입니다.", traceId, errors)
                : ErrorResponse.ofStatus(status, traceId, errors);
        var response = ResponseEntity.status(status).cacheControl(CacheControl.noStore());
        if (status == 401) {
            response.header(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        }
        return response.body(body);
    }
}
