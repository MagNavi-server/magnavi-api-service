package com.example.magnavi_springserver.shared.error;

import java.util.List;

/**
 * REST와 보안 계층이 공유하는 오류 응답이다. 입력 원문과 내부 예외는 포함하지 않는다.
 */
public record ErrorResponse(String code, String message, String traceId, List<FieldViolation> errors) {

    /** 외부에서 전달한 오류 목록이 응답 생성 후 변경되지 않게 복사한다. */
    public ErrorResponse {
        errors = List.copyOf(errors);
    }

    /** HTTP 상태를 공개 가능한 공통 오류로 변환한다. 업무별 오류는 각 모듈에서 정의한다. */
    public static ErrorResponse ofStatus(int status, String traceId, List<FieldViolation> errors) {
        return switch (status) {
            case 400 -> new ErrorResponse("INVALID_REQUEST", "요청 내용을 확인해 주세요.", traceId, errors);
            case 401 -> new ErrorResponse("UNAUTHENTICATED", "인증이 필요합니다.", traceId, errors);
            case 403 -> new ErrorResponse("ACCESS_DENIED", "접근 권한이 없습니다.", traceId, errors);
            case 404 -> new ErrorResponse("NOT_FOUND", "요청한 대상을 찾을 수 없습니다.", traceId, errors);
            case 405 -> new ErrorResponse("METHOD_NOT_ALLOWED", "허용하지 않는 요청 방식입니다.", traceId, errors);
            case 406 -> new ErrorResponse("NOT_ACCEPTABLE", "요청한 응답 형식을 제공할 수 없습니다.", traceId, errors);
            case 409 -> new ErrorResponse("CONFLICT", "요청이 현재 상태와 충돌합니다.", traceId, errors);
            case 413 -> new ErrorResponse("PAYLOAD_TOO_LARGE", "요청 크기가 너무 큽니다.", traceId, errors);
            case 415 -> new ErrorResponse("UNSUPPORTED_MEDIA_TYPE", "지원하지 않는 요청 형식입니다.", traceId, errors);
            case 429 -> new ErrorResponse("TOO_MANY_REQUESTS", "요청이 너무 많습니다.", traceId, errors);
            case 503 -> new ErrorResponse("SERVICE_UNAVAILABLE", "현재 서비스를 이용할 수 없습니다.", traceId, errors);
            default -> status >= 500
                    ? new ErrorResponse("INTERNAL_ERROR", "서버에서 요청을 처리하지 못했습니다.", traceId, errors)
                    : new ErrorResponse("REQUEST_REJECTED", "요청을 처리할 수 없습니다.", traceId, errors);
        };
    }

    /** 입력 원문 없이 잘못된 필드와 안내 문구만 전달한다. */
    public record FieldViolation(String field, String message) {
    }
}
