package com.example.magnavi_springserver.place.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import com.example.magnavi_springserver.place.infrastructure.NaverLocalSearchClient;
import com.example.magnavi_springserver.place.infrastructure.NaverSearchProperties;
import com.example.magnavi_springserver.shared.security.AuthenticatedMember;
import com.example.magnavi_springserver.member.application.MemberService;

/** 외부 호출 전 검증과 한도·실패 후 자원 반환을 실제 시간 대기 없이 확인한다. */
class PlaceSearchFlowTests {

    /** 검색 조건이 틀리면 외부 호출이나 한도 소비가 없어야 한다. */
    @Test
    void validatesBeforeAnyProviderCall() {
        var client = mock(NaverLocalSearchClient.class);
        var limiter = mock(PlaceSearchLimiter.class);
        var service = new PlaceSearchService(client, limiter, mock(MemberService.class));
        for (String query : List.of("", "  ", "a".repeat(101), "카페\n비공개")) {
            assertThatThrownBy(() -> service.search(new AuthenticatedMember(1L), query, 5, "random"))
                    .isInstanceOf(PlaceException.class);
        }
        for (int display : List.of(0, 6)) {
            assertThatThrownBy(() -> service.search(new AuthenticatedMember(1L), "카페", display, "random"))
                    .isInstanceOf(PlaceException.class);
        }
        assertThatThrownBy(() -> service.search(new AuthenticatedMember(1L), "카페", 5, "invalid"))
                .isInstanceOf(PlaceException.class);
        verifyNoInteractions(client, limiter);
    }

    /** 앞뒤 공백만 정리하고 최대 길이의 보조 평면 문자도 자르지 않는다. */
    @Test
    void preservesQueryMeaningAndUnicodeBoundary() {
        var client = mock(NaverLocalSearchClient.class);
        var service = new PlaceSearchService(client, mock(PlaceSearchLimiter.class), mock(MemberService.class));
        service.search(new AuthenticatedMember(1L), "  Cafe A  B  ", 5, "random");
        service.search(new AuthenticatedMember(1L), "😀".repeat(100), 1, "comment");
        verify(client).search("Cafe A  B", 5, "random");
        verify(client).search("😀".repeat(100), 1, "comment");
    }

    /** 회원 A의 한도 때문에 회원 B까지 차단하지 않고 새 분에는 횟수를 초기화한다. */
    @Test
    void isolatesMembersAndResetsAtMinuteBoundary() {
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenReturn(Instant.ofEpochSecond(60));
        var limiter = new PlaceSearchLimiter(properties(1, 3, 2), clock);
        limiter.acquire(1);
        limiter.release();
        assertLimited(limiter, 1, PlaceSearchException.Reason.RATE_LIMITED);
        limiter.acquire(2);
        limiter.release();
        when(clock.instant()).thenReturn(Instant.ofEpochSecond(120));
        limiter.acquire(1);
        limiter.release();
    }

    /** 서로 다른 회원이 요청해도 서버 전체 호출 한도를 넘지 못한다. */
    @Test
    void enforcesGlobalLimit() {
        var limiter = new PlaceSearchLimiter(properties(10, 2, 1), Clock.fixed(Instant.EPOCH, java.time.ZoneOffset.UTC));
        limiter.acquire(1);
        limiter.release();
        limiter.acquire(2);
        limiter.release();
        assertLimited(limiter, 3, PlaceSearchException.Reason.RATE_LIMITED);
    }

    /** 통신 실패 후 실행 자리가 반환되어 다른 회원의 다음 검색이 진행된다. */
    @Test
    void releasesConcurrencySlotOnFailure() {
        var client = mock(NaverLocalSearchClient.class);
        when(client.search("카페", 5, "random"))
                .thenThrow(new PlaceSearchException(PlaceSearchException.Reason.TIMEOUT))
                .thenReturn(new PlaceSearchResult("NAVER", List.of()));
        var service = new PlaceSearchService(client, new PlaceSearchLimiter(properties(10, 20, 1)), mock(MemberService.class));
        assertThatThrownBy(() -> service.search(new AuthenticatedMember(1L), "카페", 5, "random"))
                .isInstanceOf(PlaceSearchException.class);
        assertThat(service.search(new AuthenticatedMember(2L), "카페", 5, "random").items()).isEmpty();
    }

    /** 실제로 첫 검색이 진행 중일 때 다음 요청을 대기열에 쌓지 않고 즉시 거절한다. */
    @Test
    void boundsConcurrentSearches() throws Exception {
        var started = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        var limiter = new PlaceSearchLimiter(properties(10, 20, 1));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var running = executor.submit(() -> {
                limiter.acquire(1);
                started.countDown();
                try {
                    return finish.await(5, TimeUnit.SECONDS);
                } finally {
                    limiter.release();
                }
            });
            try {
                assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
                assertLimited(limiter, 2, PlaceSearchException.Reason.BUSY);
            } finally {
                finish.countDown();
            }
            assertThat(running.get(2, TimeUnit.SECONDS)).isTrue();
            limiter.acquire(2);
            limiter.release();
        }
    }

    /** 요청 한도 오류와 실행 자리 부족을 명시적으로 구분한다. */
    private void assertLimited(PlaceSearchLimiter limiter, long memberId, PlaceSearchException.Reason reason) {
        assertThatThrownBy(() -> limiter.acquire(memberId)).isInstanceOfSatisfying(PlaceSearchException.class,
                error -> assertThat(error.getReason()).isEqualTo(reason));
    }

    /** 테스트에 필요한 한도만 변경하고 실제 인증 정보는 사용하지 않는다. */
    private NaverSearchProperties properties(int perMember, int global, int concurrent) {
        return new NaverSearchProperties(false, "", "", NaverSearchProperties.CoordinateFormat.UNCONFIRMED,
                1000, 3000, perMember, global, concurrent);
    }
}
