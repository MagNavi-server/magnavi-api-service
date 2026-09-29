package com.example.magnavi_springserver.positioning.domain;

/** 한 시점의 센서값이다. 단위·축 변환과 16개 윈도우 계산은 여기서 하지 않는다. */
public record SensorSample(long sequence, long elapsedNanos, Vector magnetic, Vector orientation) {

    /** 빠진 벡터, 잘못된 순번·시간을 전송 전에 거절한다. */
    public SensorSample {
        if (sequence < 1 || elapsedNanos < 0 || magnetic == null || orientation == null) {
            throw new IllegalArgumentException("Invalid sensor sample");
        }
    }

    /** 실수로 객체를 로그에 남겨도 센서 원문이 출력되지 않도록 한다. */
    @Override
    public String toString() { return "SensorSample[REDACTED]"; }

    /** 자기장 또는 방향의 세 축이다. 숫자 0은 허용하며 NaN·무한대는 거절한다. */
    public record Vector(double x, double y, double z) {
        /** 모델에 계산 불가능한 값을 보내지 않는다. */
        public Vector {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
                throw new IllegalArgumentException("Invalid sensor vector");
            }
        }

        /** 센서 원문을 기본 문자열 표현에서 제외한다. */
        @Override
        public String toString() { return "Vector[REDACTED]"; }
    }
}
