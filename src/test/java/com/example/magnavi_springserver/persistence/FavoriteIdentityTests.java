package com.example.magnavi_springserver.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.example.magnavi_springserver.favorite.domain.Favorite;

/** 외부 식별자 결합 시 구분자·길이·대소문자를 잘못 처리해 중복 대상을 만들지 않는지 확인한다. */
class FavoriteIdentityTests {

    /** 대상 키는 회원에 무관하고 동일한 대상 정보로 결정되며 실제 ID의 대소문자를 보존한다. */
    @Test
    void generatesStableKeysFromScopedIdentities() {
        String key = Favorite.bus(1L, "provider", "region", "Route").getTargetKey();
        assertThat(Favorite.bus(2L, "provider", "region", "Route").getTargetKey()).isEqualTo(key);
        assertThat(Favorite.bus(1L, "provider", "region", "route").getTargetKey()).isNotEqualTo(key);
        assertThat(Favorite.bus(1L, "provider", "another-region", "Route").getTargetKey()).isNotEqualTo(key);
        assertThat(Favorite.busStop(1L, "provider", "region", "Route").getTargetKey()).isNotEqualTo(key);
    }

    /** 구분자가 들어간 서로 다른 식별 조합과 최대 길이 유니코드 ID를 안전하게 처리한다. */
    @Test
    void separatesDelimiterAmbiguityAndBoundsKeyLength() {
        assertThat(Favorite.bus(1L, "a:b", "c", "d").getTargetKey())
                .isNotEqualTo(Favorite.bus(1L, "a", "b:c", "d").getTargetKey());
        Favorite longest = Favorite.bus(1L, "p".repeat(32), "범".repeat(128), "🚏".repeat(255));
        assertThat(longest.getTargetKey()).hasSizeLessThanOrEqualTo(255);
        assertThat(longest.getExternalId()).isEqualTo("🚏".repeat(255));
    }

    /** 불완전한 식별자와 잘못된 참조 번호는 DB 연결 전에 거절한다. */
    @Test
    void rejectsIncompleteTargets() {
        assertThatThrownBy(() -> Favorite.indoor(1L, 0L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Favorite.bus(1L, "provider", null, "id")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Favorite.busStop(-1L, "provider", "scope", "id")).isInstanceOf(IllegalArgumentException.class);
    }
}
