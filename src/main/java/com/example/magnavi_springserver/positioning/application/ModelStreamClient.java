package com.example.magnavi_springserver.positioning.application;

/** 위치 측정 업무가 사용할 모델 호출 경계다. 실제 gRPC 구현은 infrastructure에 둔다. */
public interface ModelStreamClient {
    /** 모델·버전별 새 측정 스트림을 열고 연결마다 새로운 세션 ID를 발급한다. */
    ModelStream open(String modelKey, String modelVersion);
}
