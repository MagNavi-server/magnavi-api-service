package com.example.magnavi_springserver.favorite.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.example.magnavi_springserver.shared.persistence.GeneratedIdEntity;
import com.example.magnavi_springserver.shared.validation.DomainValues;

/** 회원별 대상 식별 정보만 보관한다. 모듈 간 연결은 엔티티 객체 대신 내부 번호로 표현한다. */
@Entity
@Table(name = "favorites")
public class Favorite extends GeneratedIdEntity {

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "target_type", nullable = false, length = 32)
    private FavoriteTargetType targetType;

    @Column(name = "target_key", nullable = false, length = 255)
    private String targetKey;

    @Column(name = "indoor_location_id")
    private Long indoorLocationId;

    @Column(name = "provider", length = 32)
    private String provider;

    @Column(name = "provider_scope", length = 128)
    private String providerScope;

    @Column(name = "external_id", length = 255)
    private String externalId;

    /** JPA가 저장된 행을 복원할 때 사용한다. */
    protected Favorite() {
    }

    /** 외부에서 임의 대상 키를 주입하지 못하도록 검증된 팩터리 내부에서만 생성한다. */
    private Favorite(Long memberId, FavoriteTargetType targetType) {
        this.memberId = DomainValues.positiveId(memberId, "회원 번호");
        this.targetType = targetType;
    }

    /** 실내 장소의 FK 값으로만 대상 키를 만들어 중복 판별 기준을 통일한다. */
    public static Favorite indoor(Long memberId, Long indoorLocationId) {
        Favorite favorite = new Favorite(memberId, FavoriteTargetType.INDOOR_PLACE);
        favorite.indoorLocationId = DomainValues.positiveId(indoorLocationId, "실내 장소 번호");
        favorite.targetKey = "indoor:" + favorite.indoorLocationId;
        return favorite;
    }

    /** 제공처·식별 범위·실제 노선 ID가 확인된 교통 대상을 만든다. 표시 번호로 대신하지 않는다. */
    public static Favorite bus(Long memberId, String provider, String providerScope, String externalId) {
        return transport(memberId, FavoriteTargetType.BUS, provider, providerScope, externalId);
    }

    /** 정류장의 실제 식별자와 범위를 저장한다. 제공처별 검증·허용 정책은 호출자가 먼저 확인한다. */
    public static Favorite busStop(Long memberId, String provider, String providerScope, String externalId) {
        return transport(memberId, FavoriteTargetType.BUS_STOP, provider, providerScope, externalId);
    }

    /** 교통 대상의 필수 식별 요소를 보존하고 길이가 일정한 서버 키를 만든다. */
    private static Favorite transport(Long memberId, FavoriteTargetType type,
            String provider, String providerScope, String externalId) {
        Favorite favorite = new Favorite(memberId, type);
        favorite.provider = DomainValues.requiredText(provider, 32, "교통 제공처");
        favorite.providerScope = DomainValues.requiredText(providerScope, 128, "교통 식별 범위");
        favorite.externalId = DomainValues.requiredText(externalId, 255, "교통 대상 ID");
        // 길이 접두어로 값 안의 구분자에 의한 충돌을 방지한다. 원본 조합에도 DB UNIQUE를 둔다.
        String identity = lengthPrefixed(favorite.provider)
                + lengthPrefixed(favorite.providerScope) + lengthPrefixed(favorite.externalId);
        favorite.targetKey = type.name() + ":" + digest(identity);
        return favorite;
    }

    /** 식별자 안의 구분 문자를 허용하면서 서로 다른 세 값이 같은 문자열로 합쳐지지 않게 한다. */
    private static String lengthPrefixed(String value) {
        return value.length() + ":" + value;
    }

    /** 긴 외부 식별자를 고정 길이 비교 키로 바꾸며 원본 식별자는 별도 컬럼에 보존한다. */
    private static String digest(String identity) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Java 런타임에서 SHA-256을 사용할 수 없습니다.", exception);
        }
    }

    /** 즐겨찾기의 소유 회원 번호를 반환한다. 인증 주체와의 비교는 업무 계층에서 수행한다. */
    public Long getMemberId() {
        return memberId;
    }

    /** 필수·금지 컬럼 조합을 결정하는 대상 유형을 반환한다. */
    public FavoriteTargetType getTargetType() {
        return targetType;
    }

    /** 서버가 생성한 중복 판별 키를 반환한다. 외부 제공처의 공식 ID가 아니다. */
    public String getTargetKey() {
        return targetKey;
    }

    /** 실내 유형일 때만 설정되는 실제 장소 FK를 반환한다. */
    public Long getIndoorLocationId() {
        return indoorLocationId;
    }

    /** 교통 유형일 때만 설정되는 제공처 식별자를 반환한다. */
    public String getProvider() {
        return provider;
    }

    /** 지역·데이터셋 등 외부 ID의 유효 범위를 반환한다. 실제 값 규칙은 제공처 계약으로 정한다. */
    public String getProviderScope() {
        return providerScope;
    }

    /** 표시 이름과 구분되는 실제 외부 ID를 대소문자 변경 없이 반환한다. */
    public String getExternalId() {
        return externalId;
    }
}
