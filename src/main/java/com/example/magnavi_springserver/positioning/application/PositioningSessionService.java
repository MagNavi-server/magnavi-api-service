package com.example.magnavi_springserver.positioning.application;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import com.example.magnavi_springserver.place.application.ModelLocationQueryService;

/** 모델 연결과 장소 매핑을 조립한다. 앱 인증·연결 제한은 WebSocket 입구에서 처리한다. */
@Service
public class PositioningSessionService {
    private final ObjectProvider<ModelStreamClient> clients;
    private final ModelLocationQueryService places;

    /** 모델 통신이 비활성이어도 다른 업무 API를 정상적으로 기동한다. */
    public PositioningSessionService(ObjectProvider<ModelStreamClient> clients, ModelLocationQueryService places) {
        this.clients = clients;
        this.places = places;
    }

    /** 검증된 측정 시작 요청마다 독립적인 모델 스트림을 연다. */
    public PositioningConnection open(String modelKey, String modelVersion) {
        var client = clients.getIfAvailable();
        if (client == null) throw new ModelStreamException(ModelStreamException.Reason.NOT_CONFIGURED);
        return new PositioningConnection(client.open(modelKey, modelVersion), places);
    }
}
