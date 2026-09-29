package com.example.magnavi_springserver.positioning.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.example.magnavi_springserver.place.application.ModelLocationQueryService;
import com.example.magnavi_springserver.positioning.domain.ModelEvent;

/** 장소 조회 중 종료·DB 장애처럼 통신 바깥에서 생기는 경계 조건을 검증한다. */
class PositioningConnectionTests {
    /** DB 조회가 진행 중이어도 취소를 전달하고 조회 완료 뒤 결과를 반환하지 않는다. */
    @Test
    void discardsResultWhenCancelledDuringLookup() throws Exception {
        ModelStream stream = mock(ModelStream.class);
        var places = mock(ModelLocationQueryService.class);
        when(stream.next(any())).thenReturn(Optional.of(prediction()));
        var entered = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        when(places.findLocation("model-a", "v1", "007")).thenAnswer(invocation -> {
            entered.countDown();
            if (!resume.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("Test did not resume");
            return null;
        });
        var connection = new PositioningConnection(stream, places);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var read = executor.submit(() -> catchThrowableOfType(ModelStreamException.class,
                    () -> connection.next(Duration.ofSeconds(1))));
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            connection.close();
            verify(stream).close();
            doThrow(new ModelStreamException(ModelStreamException.Reason.CANCELLED)).when(stream).checkOpen();
            resume.countDown();
            assertThat(read.get(3, TimeUnit.SECONDS).getReason()).isEqualTo(ModelStreamException.Reason.CANCELLED);
        }
    }

    /** DB 장애 상세를 숨기고 모델 호출도 정리한다. */
    @Test
    void sanitizesDatabaseFailureAndCancelsStream() {
        ModelStream stream = mock(ModelStream.class);
        var places = mock(ModelLocationQueryService.class);
        when(stream.next(any())).thenReturn(Optional.of(prediction()));
        when(places.findLocation("model-a", "v1", "007")).thenThrow(new IllegalStateException("private DB detail"));
        var connection = new PositioningConnection(stream, places);
        var error = catchThrowableOfType(ModelStreamException.class, () -> connection.next(Duration.ZERO));
        assertThat(error.getMessage()).isEqualTo("INTERNAL");
        assertThat(error.getCause()).isNull();
        verify(stream).close();
    }

    /** 실제 사용자 위치 대신 고정된 테스트용 응답을 만든다. */
    private static ModelEvent.Prediction prediction() {
        return new ModelEvent.Prediction(16, "model-a", "v1", "007", 0.8,
                List.of(new ModelEvent.Candidate("007", 0.8)));
    }
}
