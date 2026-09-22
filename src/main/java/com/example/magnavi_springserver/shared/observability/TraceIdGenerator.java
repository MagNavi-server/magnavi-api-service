package com.example.magnavi_springserver.shared.observability;

import java.util.UUID;

/**
 * 회원·장소 정보와 관계없는 서버 내부 요청 식별자를 생성한다.
 */
public final class TraceIdGenerator {

    /** 상태가 없는 생성 도구이므로 인스턴스 생성을 허용하지 않는다. */
    private TraceIdGenerator() {
    }

    /** 외부 요청의 헤더 값을 재사용하지 않고 새 UUID를 발급한다. */
    public static String generate() {
        return UUID.randomUUID().toString();
    }
}
