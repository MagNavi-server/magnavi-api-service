package com.example.magnavi_springserver.positioning.application;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import com.example.magnavi_springserver.place.application.ModelLocationQueryService;
import com.example.magnavi_springserver.place.application.PlaceException;
import com.example.magnavi_springserver.positioning.domain.ModelEvent;
import com.example.magnavi_springserver.positioning.domain.SensorSample;

/** 이후 WebSocket 계층이 사용할 한 측정의 핸들이다. 긴 DB 트랜잭션을 만들지 않는다. */
public final class PositioningConnection implements AutoCloseable {
    private final ModelStream stream;
    private final ModelLocationQueryService places;
    private final AtomicBoolean reading = new AtomicBoolean();

    /** 통신과 장소 조회는 각각의 공개 기능으로 협력한다. */
    PositioningConnection(ModelStream stream, ModelLocationQueryService places) {
        this.stream = stream;
        this.places = places;
    }

    /** 사용자 ID가 아닌 이번 측정 연결 번호를 제공한다. */
    public String sessionId() { return stream.sessionId(); }

    /** 입력 검증과 전송 순서는 해당 모델 스트림에 맡긴다. */
    public void send(SensorSample sample) { stream.send(sample); }

    /** gRPC 콜백 밖의 호출자 스레드에서 한 결과를 읽고 필요할 때만 장소를 조회한다. */
    public Optional<PositioningResult> next(Duration wait) {
        if (!reading.compareAndSet(false, true)) {
            throw new ModelStreamException(ModelStreamException.Reason.INVALID_INPUT);
        }
        try {
            var event = stream.next(wait);
            if (event.isEmpty()) return Optional.empty();
            var result = resolve(event.get());
            // DB 조회 중 종료됐다면 늦은 결과를 앱에 돌려주지 않는다.
            stream.checkOpen();
            return Optional.of(result);
        } finally {
            reading.set(false);
        }
    }

    /** 매핑 누락은 다른 장소로 대체하지 않고 별도 상태로 전달한다. */
    private PositioningResult resolve(ModelEvent event) {
        if (!(event instanceof ModelEvent.Prediction prediction)) {
            return new PositioningResult(event, PositioningResult.MappingStatus.NOT_REQUESTED, null);
        }
        try {
            var location = places.findLocation(prediction.modelKey(), prediction.modelVersion(), prediction.locationCode());
            return new PositioningResult(event, PositioningResult.MappingStatus.FOUND, location);
        } catch (PlaceException error) {
            if (error.getReason() == PlaceException.Reason.NOT_FOUND) {
                return new PositioningResult(event, PositioningResult.MappingStatus.NOT_FOUND, null);
            }
            stream.close();
            throw new ModelStreamException(ModelStreamException.Reason.INTERNAL);
        } catch (RuntimeException ignored) {
            stream.close();
            throw new ModelStreamException(ModelStreamException.Reason.INTERNAL);
        }
    }

    /** 결과 조회와 별개로 취소할 수 있어 느린 DB 조회 중에도 모델 호출을 중단한다. */
    @Override
    public void close() { stream.close(); }
}
