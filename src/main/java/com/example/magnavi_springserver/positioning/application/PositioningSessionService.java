package com.example.magnavi_springserver.positioning.application;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import com.example.magnavi_springserver.place.application.ModelLocationQueryService;

/** 모델 연결과 장소 매핑을 조립한다. 앱 인증·사용자별 연결 제한은 7단계에서 연결한다. */
@Service
public class PositioningSessionService {
    private final ObjectProvider<ModelStreamClient> clients;
    private final ModelLocationQueryService places;

    /** 모델 통신이 비활성이어도 다른 업무 API를 정상적으로 기동한다. */
    public PositioningSessionService(ObjectProvider<ModelStreamClient> clients, ModelLocationQueryService places) {
        this.clients = clients;
        this.places = places;
    }

    /** 내부 호출용 기능이며 아직 인터넷에 공개한 HTTP·WebSocket 경로는 아니다. */
    public PositioningConnection open(String modelKey, String modelVersion) {
        var client = clients.getIfAvailable();
        if (client == null) throw new ModelStreamException(ModelStreamException.Reason.NOT_CONFIGURED);
        return new PositioningConnection(client.open(modelKey, modelVersion), places);
    }
}
