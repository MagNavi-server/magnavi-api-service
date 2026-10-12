package com.example.magnavi_springserver.place.infrastructure;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import com.example.magnavi_springserver.place.application.PlaceSearchException;

/** HTTP 본문을 조금씩 받아 최대 크기를 넘으면 중단한다. 거대한 오류 페이지도 쌓지 않는다. */
final class LimitedSearchBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {

    private static final int MAX_BYTES = 64 * 1024;
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final CompletableFuture<byte[]> result = new CompletableFuture<>();
    private Flow.Subscription subscription;

    /** HTTP 클라이언트가 본문 수신 완료 여부를 기다릴 때 사용할 결과다. */
    @Override
    public CompletionStage<byte[]> getBody() {
        return result;
    }

    /** 한 묶음씩 요청해서 읽기 속도와 중단 시점을 직접 제어한다. */
    @Override
    public void onSubscribe(Flow.Subscription subscription) {
        this.subscription = subscription;
        subscription.request(1);
    }

    /** 64KiB를 넘기기 전에 통신을 취소한다. 정상 결과 5개를 위한 응답 한도다. */
    @Override
    public void onNext(List<ByteBuffer> buffers) {
        for (ByteBuffer buffer : buffers) {
            if (buffer.remaining() > MAX_BYTES - bytes.size()) {
                subscription.cancel();
                result.completeExceptionally(new PlaceSearchException(PlaceSearchException.Reason.PROVIDER_ERROR));
                return;
            }
            byte[] chunk = new byte[buffer.remaining()];
            buffer.get(chunk);
            bytes.writeBytes(chunk);
        }
        subscription.request(1);
    }

    /** 수신 실패는 호출자가 안전한 검색 오류로 바꾼다. 이곳에서는 로그를 남기지 않는다. */
    @Override
    public void onError(Throwable error) {
        result.completeExceptionally(error);
    }

    /** 끝까지 받은 크기가 제한 안에 있을 때만 JSON 파싱에 넘긴다. */
    @Override
    public void onComplete() {
        result.complete(bytes.toByteArray());
    }
}
