package com.example.magnavi_springserver.positioning.domain;

import java.util.List;

/** 생성된 protobuf 클래스를 업무 코드에 직접 노출하지 않는 모델 응답이다. */
public sealed interface ModelEvent {

    /** 모델과 센서 규격을 확인했고 입력을 받을 준비가 되었다는 응답이다. */
    record Ready(int requiredCount) implements ModelEvent { }

    /** 실제 예측이 아닌 센서 수집 진행 상태다. */
    record WarmingUp(long sequence, int bufferedCount, int requiredCount) implements ModelEvent { }

    /** 모델 출력 코드와 점수를 그대로 담는다. 점수를 실제 정확도로 해석하지 않는다. */
    record Prediction(long sequence, String modelKey, String modelVersion,
                      String locationCode, double score, List<Candidate> candidates) implements ModelEvent {
        /** 외부에서 후보 목록을 바꿔 이미 검증한 응답을 변경하지 못하게 한다. */
        public Prediction { candidates = List.copyOf(candidates); }

        /** 상세 위치·점수는 기본 로그에 남기지 않는다. */
        @Override
        public String toString() { return "Prediction[REDACTED]"; }
    }

    /** 최상위 결과를 포함하는 위치 후보 하나다. */
    record Candidate(String locationCode, double score) {
        /** 상세 후보를 기본 문자열 표현에서 제외한다. */
        @Override
        public String toString() { return "Candidate[REDACTED]"; }
    }
}
