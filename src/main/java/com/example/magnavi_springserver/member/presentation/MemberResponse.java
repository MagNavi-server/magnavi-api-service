package com.example.magnavi_springserver.member.presentation;

import com.fasterxml.jackson.annotation.JsonProperty;

import com.example.magnavi_springserver.member.application.MemberProfile;

/** 기존 앱의 필드 이름을 유지하면서 비밀번호·권한 등 내부 정보는 제외한다. */
public record MemberResponse(Long id, String userId, String userName, String email,
                             @JsonProperty("phone_number") String phoneNumber) {

    /** 내부 프로필 이름을 앱이 사용하는 응답 이름으로 변환한다. */
    public static MemberResponse from(MemberProfile profile) {
        return new MemberResponse(profile.id(), profile.loginId(), profile.displayName(), profile.email(), profile.phoneNumber());
    }
}
