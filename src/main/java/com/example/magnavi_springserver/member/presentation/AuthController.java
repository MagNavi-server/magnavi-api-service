package com.example.magnavi_springserver.member.presentation;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.magnavi_springserver.member.application.LoginService;
import com.example.magnavi_springserver.member.application.MemberException;
import com.example.magnavi_springserver.member.application.SignUpService;

/** 로그인 전 사용할 수 있는 일반 회원가입·로그인 API다. */
@RestController
@RequestMapping("/users")
public class AuthController {

    private final SignUpService signUpService;
    private final LoginService loginService;

    /** HTTP 처리와 회원 업무 처리를 나누기 위해 서비스를 주입받는다. */
    public AuthController(SignUpService signUpService, LoginService loginService) {
        this.signUpService = signUpService;
        this.loginService = loginService;
    }

    /** JSON 가입 요청을 받아 회원 정보만 반환한다. 가입 직후 자동 로그인은 하지 않는다. */
    @PostMapping(value = "/signup", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<MemberResponse> signUp(@Valid @RequestBody SignUpRequest request) {
        var profile = signUpService.signUp(request.userId(), request.userName(), request.email(),
                request.phoneNumber(), request.password());
        return ResponseEntity.status(HttpStatus.CREATED).body(MemberResponse.from(profile));
    }

    /** 기존 앱의 폼 본문을 받는다. URL 쿼리에 적힌 비밀번호는 로그인 입력으로 사용하지 않는다. */
    @PostMapping(value = "/login", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<TokenResponse> login(@RequestBody MultiValueMap<String, String> form) {
        String token = loginService.login(singleFormValue(form, "username"), singleFormValue(form, "password"));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Pragma", "no-cache")
                .body(new TokenResponse(token, "bearer"));
    }

    /** 같은 필드가 여러 번 들어오는 모호한 로그인 요청은 거절한다. */
    private String singleFormValue(MultiValueMap<String, String> form, String field) {
        var values = form.get(field);
        if (values == null || values.size() != 1) {
            throw new MemberException(MemberException.Reason.INVALID_INPUT, field);
        }
        return values.getFirst();
    }

    /** 기존 가입 필드를 유지한다. 이메일·전화번호는 생략할 수 있다. */
    public record SignUpRequest(String userId, String userName, @Email String email,
                                @JsonProperty("phone_number") String phoneNumber, String password) {

        /** 선택 이메일은 빈 값이면 null로 바꾸고 입력한 주소는 앞뒤 공백만 정리한다. */
        public SignUpRequest {
            email = email == null || email.isBlank() ? null : email.strip();
        }

        /** 요청 객체를 실수로 출력해도 비밀번호와 연락처가 로그에 남지 않게 한다. */
        @Override
        public String toString() {
            return "SignUpRequest[personal data hidden]";
        }
    }

    /** 앱은 access_token을 이후 요청의 Authorization: Bearer 헤더에 넣는다. */
    public record TokenResponse(@JsonProperty("access_token") String accessToken,
                                @JsonProperty("token_type") String tokenType) {

        /** 토큰 응답 객체를 로그에 출력하는 실수를 방어한다. */
        @Override
        public String toString() {
            return "TokenResponse[token hidden]";
        }
    }
}
