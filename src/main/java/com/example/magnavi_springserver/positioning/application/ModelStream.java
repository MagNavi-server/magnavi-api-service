package com.example.magnavi_springserver.positioning.application;

import java.time.Duration;
import java.util.Optional;

import com.example.magnavi_springserver.positioning.domain.ModelEvent;
import com.example.magnavi_springserver.positioning.domain.SensorSample;

/** 한 측정의 송신·수신·취소를 묶는다. 결과는 한 소비자가 순서대로 읽는다. */
public interface ModelStream extends AutoCloseable {
    /** 회원 ID와 별개이며 재연결 때 새로 만들어지는 연결 식별자다. */
    String sessionId();

    /** Ready 이후 입력을 보낸다. 대기열이 가득 차면 조용히 버리지 않고 호출을 종료한다. */
    void send(SensorSample sample);

    /** 최대 지정 시간만 기다린다. 빈 결과는 아직 응답이 없다는 뜻이며 오류·종료는 예외다. */
    Optional<ModelEvent> next(Duration wait);

    /** 장소 조회 뒤 늦은 결과를 전달하기 전에 연결이 아직 유효한지 확인한다. */
    void checkOpen();

    /** 여러 번 호출해도 한 번만 취소하고 대기 중인 읽기를 깨운다. */
    @Override
    void close();
}
