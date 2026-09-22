package com.example.magnavi_springserver.shared.validation;

/** 저장 객체에서 공통으로 사용하는 값의 존재·길이·양수 조건만 검사한다. */
public final class DomainValues {

    /** 상태 없는 검증 도구의 인스턴스 생성을 막는다. */
    private DomainValues() {
    }

    /** 식별자의 대소문자나 공백을 임의로 바꾸지 않고 필수 문자열의 문자 수를 검사한다. */
    public static String requiredText(String value, int maxLength, String field) {
        if (value == null || value.isBlank() || value.codePointCount(0, value.length()) > maxLength) {
            throw new IllegalArgumentException(field + " 값이 없거나 허용 길이를 초과했습니다.");
        }
        return value;
    }

    /** 선택 문자열은 null을 허용하고, 제공된 값에는 필수 문자열과 같은 검사를 적용한다. */
    public static String optionalText(String value, int maxLength, String field) {
        return value == null ? null : requiredText(value, maxLength, field);
    }

    /** 다른 행의 식별자로 사용할 양수만 허용하며 실제 존재 여부는 FK가 보호한다. */
    public static Long positiveId(Long value, String field) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(field + " 값은 양수여야 합니다.");
        }
        return value;
    }
}
