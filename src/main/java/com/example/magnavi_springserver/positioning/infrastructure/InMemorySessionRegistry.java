package com.example.magnavi_springserver.positioning.infrastructure;

import java.util.HashMap;
import java.util.Map;
import com.example.magnavi_springserver.positioning.domain.PositioningException;
import com.example.magnavi_springserver.positioning.domain.PositioningException.Reason;

/** 한 Spring 인스턴스의 전체·회원별 연결 슬롯을 원자적으로 예약하고 반환한다. */
public final class InMemorySessionRegistry {
    private final Map<String, Long> membersByConnection = new HashMap<>();
    private final int totalLimit;
    private final int memberLimit;

    /** 여러 인스턴스에 걸친 전역 제한이 아니라 현재 프로세스의 한도다. */
    public InMemorySessionRegistry(int totalLimit, int memberLimit) {
        this.totalLimit = totalLimit;
        this.memberLimit = memberLimit;
    }

    /** 동시에 연결해도 한도를 넘지 않으며 실패한 예약은 상태를 남기지 않는다. */
    public synchronized void reserve(String connectionId, long memberId) {
        long memberCount = membersByConnection.values().stream().filter(id -> id == memberId).count();
        if (membersByConnection.containsKey(connectionId) || membersByConnection.size() >= totalLimit
                || memberCount >= memberLimit) throw new PositioningException(Reason.OVERLOADED);
        membersByConnection.put(connectionId, memberId);
    }

    /** 중복 종료에서도 다른 연결의 슬롯을 빼앗지 않는다. */
    public synchronized void release(String connectionId) { membersByConnection.remove(connectionId); }

    /** 테스트·상태 점검에서 현재 자원 사용을 확인한다. */
    public synchronized int size() { return membersByConnection.size(); }
}
