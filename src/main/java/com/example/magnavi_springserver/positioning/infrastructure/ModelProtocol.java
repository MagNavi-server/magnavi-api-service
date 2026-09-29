package com.example.magnavi_springserver.positioning.infrastructure;

import java.util.HashSet;

import com.example.magnavi_springserver.positioning.application.ModelStreamException;
import com.example.magnavi_springserver.positioning.domain.ModelEvent;
import com.example.magnavi_springserver.positioning.domain.SensorSample;
import com.example.magnavi_springserver.positioning.grpc.v1.StartMeasurement;
import com.example.magnavi_springserver.positioning.grpc.v1.StreamEvent;
import com.example.magnavi_springserver.positioning.grpc.v1.StreamRequest;
import com.example.magnavi_springserver.positioning.grpc.v1.Vector3;

/** protobuf 변환과 v1 응답 검증을 모은다. 모델 입력을 보정하거나 값을 추측하지 않는다. */
final class ModelProtocol {
    static final int VERSION = 1;
    static final int WINDOW_SIZE = 16;

    /** 상태가 없는 변환 도구는 인스턴스로 만들지 않는다. */
    private ModelProtocol() { }

    /** 회원 개인정보 없이 측정 연결과 모델·센서 규격만 전달한다. */
    static StreamRequest start(String sessionId, String modelKey, String modelVersion, String profile) {
        return StreamRequest.newBuilder().setStart(StartMeasurement.newBuilder()
                .setSessionId(sessionId).setContractVersion(VERSION)
                .setModelKey(modelKey).setModelVersion(modelVersion).setSensorProfileId(profile)).build();
    }

    /** 0을 포함한 모든 필수 값을 명시적으로 설정해 optional presence를 보존한다. */
    static StreamRequest sample(SensorSample sample) {
        return StreamRequest.newBuilder().setSample(
                com.example.magnavi_springserver.positioning.grpc.v1.SensorSample.newBuilder()
                        .setSequence(sample.sequence()).setElapsedNanos(sample.elapsedNanos())
                        .setMagnetic(vector(sample.magnetic())).setOrientation(vector(sample.orientation()))).build();
    }

    /** 축 순서나 단위를 임의로 변환하지 않는다. */
    private static Vector3 vector(SensorSample.Vector vector) {
        return Vector3.newBuilder().setX(vector.x()).setY(vector.y()).setZ(vector.z()).build();
    }

    /** 식별자의 대소문자·앞자리 0·공백을 그대로 보존하며 DB와 같은 문자 수 한도를 적용한다. */
    static boolean validIdentifier(String value, int maxLength) {
        return value != null && !value.isBlank() && value.codePointCount(0, value.length()) <= maxLength;
    }

    /** ready의 모든 규격이 요청과 일치해야 센서를 보내도록 한다. */
    static ModelEvent ready(StreamEvent event, String modelKey, String version, String profile) {
        var ready = event.getReady();
        require(event.hasReady() && ready.getContractVersion() == VERSION
                && ready.getModelKey().equals(modelKey) && ready.getModelVersion().equals(version)
                && ready.getSensorProfileId().equals(profile) && ready.getRequiredCount() == WINDOW_SIZE);
        return new ModelEvent.Ready(WINDOW_SIZE);
    }

    /** 입력마다 한 응답이라는 v1 약속과 워밍업·예측 필드를 검사한다. */
    static ModelEvent result(StreamEvent event, long expectedSequence, String modelKey, String version) {
        if (event.hasWarmingUp()) {
            var warming = event.getWarmingUp();
            require(warming.hasSequence() && warming.getSequence() == expectedSequence
                    && expectedSequence < WINDOW_SIZE && warming.getBufferedCount() == expectedSequence
                    && warming.getRequiredCount() == WINDOW_SIZE);
            return new ModelEvent.WarmingUp(expectedSequence, warming.getBufferedCount(), WINDOW_SIZE);
        }
        require(event.hasPrediction() && expectedSequence >= WINDOW_SIZE);
        var prediction = event.getPrediction();
        require(prediction.hasSequence() && prediction.getSequence() == expectedSequence
                && prediction.getModelKey().equals(modelKey) && prediction.getModelVersion().equals(version)
                && validIdentifier(prediction.getLocationCode(), 50) && prediction.hasScore()
                && validScore(prediction.getScore()) && prediction.getCandidatesCount() >= 1
                && prediction.getCandidatesCount() <= 3);
        var codes = new HashSet<String>();
        double previousScore = Double.POSITIVE_INFINITY;
        for (var candidate : prediction.getCandidatesList()) {
            require(validIdentifier(candidate.getLocationCode(), 50) && candidate.hasScore()
                    && validScore(candidate.getScore()) && codes.add(candidate.getLocationCode())
                    && candidate.getScore() <= previousScore);
            previousScore = candidate.getScore();
        }
        var best = prediction.getCandidates(0);
        require(best.getLocationCode().equals(prediction.getLocationCode())
                && best.getScore() == prediction.getScore());
        var candidates = prediction.getCandidatesList().stream()
                .map(value -> new ModelEvent.Candidate(value.getLocationCode(), value.getScore())).toList();
        return new ModelEvent.Prediction(expectedSequence, modelKey, version,
                prediction.getLocationCode(), prediction.getScore(), candidates);
    }

    /** 현재 모델의 점수 표현 범위만 검사한다. 통계적 확률 보정 여부를 판단하지 않는다. */
    private static boolean validScore(double score) {
        return Double.isFinite(score) && score >= 0 && score <= 1;
    }

    /** 원격 응답의 값이나 설명을 예외에 노출하지 않는다. */
    private static void require(boolean condition) {
        if (!condition) throw new ModelStreamException(ModelStreamException.Reason.PROTOCOL_ERROR);
    }
}
