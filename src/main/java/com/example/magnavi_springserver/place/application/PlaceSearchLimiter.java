package com.example.magnavi_springserver.place.application;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.example.magnavi_springserver.place.infrastructure.NaverSearchProperties;

/** 서버 한 대에서 회원별·전체 호출 횟수와 동시에 진행하는 검색 수를 제한한다. */
@Component
public class PlaceSearchLimiter {

    private final NaverSearchProperties properties;
    private final Clock clock;
    private final Semaphore concurrentRequests;
    private final Map<Long, Integer> memberCounts = new HashMap<>();
    private long currentMinute = -1;
    private int requestCount;

    /** 실제 서비스에서는 UTC 시계를 사용해 매 분 호출 수를 새로 센다. */
    @Autowired
    public PlaceSearchLimiter(NaverSearchProperties properties) {
        this(properties, Clock.systemUTC());
    }

    /** 테스트에서는 기다리지 않고 시계를 이동시켜 분 경계를 검증할 수 있다. */
    PlaceSearchLimiter(NaverSearchProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        this.concurrentRequests = new Semaphore(properties.maxConcurrentRequests());
    }

    /** 대기열을 만들지 않는다. 허용된 요청만 집계하므로 회원 목록도 전체 분당 한도 이하다. */
    public synchronized void acquire(long memberId) {
        long minute = clock.instant().getEpochSecond() / 60;
        if (currentMinute != minute) {
            currentMinute = minute;
            memberCounts.clear();
            requestCount = 0;
        }
        int memberCount = memberCounts.getOrDefault(memberId, 0);
        if (memberCount >= properties.requestsPerMemberPerMinute()
                || requestCount >= properties.requestsPerMinute()) {
            throw new PlaceSearchException(PlaceSearchException.Reason.RATE_LIMITED);
        }
        if (!concurrentRequests.tryAcquire()) {
            throw new PlaceSearchException(PlaceSearchException.Reason.BUSY);
        }
        memberCounts.put(memberId, memberCount + 1);
        requestCount++;
    }

    /** 성공·실패와 관계없이 획득한 동시 실행 자리를 한 번 반환한다. */
    public void release() {
        concurrentRequests.release();
    }
}
