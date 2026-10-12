package com.example.magnavi_springserver.member.presentation;

import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.magnavi_springserver.member.application.MemberService;
import com.example.magnavi_springserver.shared.security.AuthenticatedMember;

/** 검증된 JWT의 회원 번호를 사용해 본인의 프로필만 조회·변경한다. */
@RestController
@RequestMapping("/users/me")
public class MemberController {

    private final MemberService memberService;

    /** 본인 정보 처리 서비스를 연결한다. */
    public MemberController(MemberService memberService) {
        this.memberService = memberService;
    }

    /** 인증 주체는 Spring Security가 전달한다. 앱이 보낸 회원 번호는 받지 않는다. */
    @GetMapping
    public MemberResponse getMyProfile(@AuthenticationPrincipal(errorOnInvalidType = true) AuthenticatedMember currentMember) {
        return MemberResponse.from(memberService.getMyProfile(currentMember));
    }

    /** 변경할 값은 표시 이름 하나뿐이며 대상 회원은 토큰으로 결정한다. */
    @PutMapping(value = "/username", consumes = MediaType.APPLICATION_JSON_VALUE)
    public MemberResponse changeMyDisplayName(
            @AuthenticationPrincipal(errorOnInvalidType = true) AuthenticatedMember currentMember,
            @RequestBody ChangeNameRequest request) {
        return MemberResponse.from(memberService.changeMyDisplayName(currentMember, request.userName()));
    }

    /** 이름 변경에 필요한 입력만 받는다. */
    public record ChangeNameRequest(String userName) {
    }
}
