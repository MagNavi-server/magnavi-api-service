package com.example.magnavi_springserver.member.application;

import com.example.magnavi_springserver.member.domain.LocalCredential;

/** HTTP 형식과 별개로 회원 업무에 필요한 길이·공백 규칙을 확인한다. */
public final class MemberInput {

    /** 상태를 보관하지 않는 도구이므로 객체를 만들지 않는다. */
    private MemberInput() {
    }

    /** 가입과 로그인에 같은 ID 정리 규칙을 적용하며 대소문자는 유지한다. */
    public static String loginId(String value) {
        try {
            return LocalCredential.normalizeLoginId(value);
        } catch (IllegalArgumentException exception) {
            throw new MemberException(MemberException.Reason.INVALID_INPUT, "userId");
        }
    }

    /** 표시 이름은 비어 있을 수 없으며 DB가 허용하는 50자까지만 받는다. */
    public static String displayName(String value) {
        if (value == null || value.isBlank() || value.codePointCount(0, value.length()) > 50) {
            throw new MemberException(MemberException.Reason.INVALID_INPUT, "userName");
        }
        return value;
    }

    /** 선택 연락처의 빈 값은 null로 통일하고, 입력한 값은 앞뒤 공백을 제거한다. */
    public static String optionalContact(String value, int maxLength, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.strip();
        if (trimmed.codePointCount(0, trimmed.length()) > maxLength) {
            throw new MemberException(MemberException.Reason.INVALID_INPUT, field);
        }
        return trimmed;
    }
}
