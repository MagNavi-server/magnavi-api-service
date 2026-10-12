package com.example.magnavi_springserver.place.infrastructure;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** 네이버 인증 정보와 호출 한도를 서버 설정에서 읽는다. 실제 키는 환경변수로만 주입한다. */
@Validated
@ConfigurationProperties(prefix = "magnavi.naver-search")
public record NaverSearchProperties(
        boolean enabled, String clientId, String clientSecret, CoordinateFormat coordinateFormat,
        @Min(100) @Max(10000) int connectTimeoutMs,
        @Min(100) @Max(30000) int requestTimeoutMs,
        @Min(1) @Max(1000) int requestsPerMemberPerMinute,
        @Min(1) @Max(10000) int requestsPerMinute,
        @Min(1) @Max(32) int maxConcurrentRequests) {

    /** 실제 응답을 확인한 뒤 선택한다. 단위가 불명확한 좌표를 자동 추측하지 않는다. */
    public enum CoordinateFormat {
        UNCONFIRMED, DEGREES, E7
    }

    /** 키 또는 좌표 단위가 준비되지 않았으면 검색만 503으로 처리하고 다른 API는 유지한다. */
    public boolean isReady() {
        return enabled && isHeaderValue(clientId) && isHeaderValue(clientSecret)
                && coordinateFormat != null && coordinateFormat != CoordinateFormat.UNCONFIRMED;
    }

    /** 잘못 복사된 개행·공백이 HTTP 헤더 생성 예외로 이어지지 않게 확인한다. */
    private boolean isHeaderValue(String value) {
        return value != null && !value.isEmpty() && value.length() <= 1024
                && value.chars().allMatch(character -> character >= 33 && character <= 126);
    }

    /** 설정 객체가 실수로 로그에 출력되어도 비밀값을 공개하지 않는다. */
    @Override
    public String toString() {
        return "NaverSearchProperties[enabled=" + enabled + ", credentials=REDACTED]";
    }
}
