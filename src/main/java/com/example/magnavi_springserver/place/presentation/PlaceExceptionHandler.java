package com.example.magnavi_springserver.place.presentation;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.example.magnavi_springserver.place.application.PlaceException;
import com.example.magnavi_springserver.shared.error.ErrorResponse;
import com.example.magnavi_springserver.shared.observability.RequestTraceFilter;

/** 예상 가능한 장소 조회 실패를 기존 공통 오류 형식에 맞춘다. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {PlaceController.class, PlaceSearchController.class})
public class PlaceExceptionHandler {

    /** 입력 오류는 400, 대상 없음은 404로 구분하고 입력값·DB 정보는 공개하지 않는다. */
    @ExceptionHandler(PlaceException.class)
    public ResponseEntity<ErrorResponse> handle(PlaceException exception, HttpServletRequest request) {
        int status = exception.getReason() == PlaceException.Reason.INVALID_INPUT ? 400 : 404;
        List<ErrorResponse.FieldViolation> errors = status == 400
                ? List.of(new ErrorResponse.FieldViolation(exception.getField(), "입력 조건을 확인해 주세요."))
                : List.of();
        return ResponseEntity.status(status)
                .body(ErrorResponse.ofStatus(status, RequestTraceFilter.traceId(request), errors));
    }
}
