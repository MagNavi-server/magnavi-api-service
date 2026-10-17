package com.example.magnavi_springserver.shared.security;

import java.time.Instant;
import java.util.Objects;

/** 토큰 원문 없이 장시간 연결의 만료를 확인할 시각만 보관한다. */
public record AccessTokenDetails(Instant expiresAt) {
    /** 만료 시각이 없는 인증 정보를 연결에 사용하지 않는다. */
    public AccessTokenDetails { Objects.requireNonNull(expiresAt); }
}
