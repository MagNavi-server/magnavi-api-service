package com.example.magnavi_springserver.member.presentation;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.example.magnavi_springserver.member.application.MemberException;
import com.example.magnavi_springserver.shared.error.ErrorResponse;
import com.example.magnavi_springserver.shared.observability.RequestTraceFilter;

/** 회원 업무의 예상 실패를 공통 JSON 형식으로 바꾼다. 예외 원문은 공개하지 않는다. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {AuthController.class, MemberController.class})
public class MemberExceptionHandler {

    /** 실패 이유에 맞는 상태·안내를 정하고 비밀번호나 DB 내용을 응답에서 제외한다. */
    @ExceptionHandler(MemberException.class)
    public ResponseEntity<ErrorResponse> handle(MemberException exception, HttpServletRequest request) {
        String traceId = RequestTraceFilter.traceId(request);
        int status;
        ErrorResponse body;
        switch (exception.getReason()) {
            case INVALID_INPUT -> {
                status = 400;
                body = ErrorResponse.ofStatus(status, traceId,
                        List.of(new ErrorResponse.FieldViolation(exception.getField(), "입력 조건을 확인해 주세요.")));
            }
            case DUPLICATE_LOGIN_ID -> {
                status = 409;
                body = new ErrorResponse("DUPLICATE_LOGIN_ID", "이미 사용 중인 아이디입니다.", traceId, List.of());
            }
            case INVALID_CREDENTIALS -> {
                status = 401;
                body = new ErrorResponse("INVALID_CREDENTIALS", "아이디 또는 비밀번호가 올바르지 않습니다.", traceId, List.of());
            }
            default -> {
                status = 401;
                body = ErrorResponse.ofStatus(status, traceId, List.of());
            }
        }
        var response = ResponseEntity.status(status);
        if (status == 401) {
            response.header(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        }
        return response.body(body);
    }
}
