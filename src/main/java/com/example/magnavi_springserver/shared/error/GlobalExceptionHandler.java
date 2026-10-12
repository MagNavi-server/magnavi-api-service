package com.example.magnavi_springserver.shared.error;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.example.magnavi_springserver.shared.observability.RequestTraceFilter;

/** Spring MVC의 상태·헤더를 보존하면서 내부 오류 본문을 공통 응답으로 교체한다. */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 검증 실패 필드만 공개하며 입력값을 포함할 수 있는 예외·검증 메시지는 반사하지 않는다. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<ErrorResponse.FieldViolation> errors = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new ErrorResponse.FieldViolation(error.getField(), "입력 조건을 확인해 주세요."))
                .distinct()
                .toList();
        return response(status, headers, request, errors);
    }

    /** JSON·경로·메서드 등 프레임워크 오류를 원래 상태 코드와 헤더로 반환한다. */
    @Override
    protected @Nullable ResponseEntity<Object> handleExceptionInternal(Exception exception, @Nullable Object body,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        var servletResponse = ((ServletWebRequest) request).getResponse();
        if (servletResponse != null && servletResponse.isCommitted()) {
            return null;
        }
        if (status.is5xxServerError()) {
            log.error("MVC 요청 처리 실패: exceptionType={}", exception.getClass().getSimpleName());
        }
        return response(status, headers, request, List.of());
    }

    /** Controller 실행 중 발생한 인증 실패도 보안 필터와 같은 의미로 응답한다. */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleAuthentication(AuthenticationException exception,
                                                            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ErrorResponse.ofStatus(401, RequestTraceFilter.traceId(request), List.of()));
    }

    /** Controller 실행 중의 권한 오류를 서버 내부 오류로 오인하지 않도록 변환한다. */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException exception,
                                                         HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ErrorResponse.ofStatus(403, RequestTraceFilter.traceId(request), List.of()));
    }

    /** 예외 메시지에 DB·토큰 등이 들어갈 수 있어 종류와 추적 ID만 진단 정보로 남긴다. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception exception, HttpServletRequest request) {
        log.error("예상하지 못한 요청 처리 실패: exceptionType={}", exception.getClass().getSimpleName());
        return ResponseEntity.internalServerError()
                .body(ErrorResponse.ofStatus(500, RequestTraceFilter.traceId(request), List.of()));
    }

    /** HTTP 의미를 유지하고 공통 오류 객체를 생성한다. */
    private ResponseEntity<Object> response(HttpStatusCode status, HttpHeaders headers, WebRequest request,
                                            List<ErrorResponse.FieldViolation> errors) {
        HttpServletRequest servletRequest = ((ServletWebRequest) request).getRequest();
        return new ResponseEntity<>(ErrorResponse.ofStatus(status.value(),
                RequestTraceFilter.traceId(servletRequest), errors), headers, status);
    }

}
