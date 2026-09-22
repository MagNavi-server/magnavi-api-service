package com.example.magnavi_springserver.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

import com.example.magnavi_springserver.member.application.MemberException;
import com.example.magnavi_springserver.member.domain.Member;
import com.example.magnavi_springserver.member.infrastructure.JwtProperties;
import com.example.magnavi_springserver.member.infrastructure.JwtTokenProvider;
import com.example.magnavi_springserver.member.infrastructure.MemberPersistenceAdapter;
import com.example.magnavi_springserver.member.infrastructure.PasswordHashAdapter;
import com.example.magnavi_springserver.member.infrastructure.persistence.LocalCredentialJpaRepository;
import com.example.magnavi_springserver.member.infrastructure.persistence.MemberJpaRepository;
import com.example.magnavi_springserver.support.MySqlTestConfiguration;

/** 임시 MySQL과 실제 서명 키로 가입부터 보호 API 접근까지 전체 요청 흐름을 검증한다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(MySqlTestConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class MemberApiIntegrationTests {

    private static final String PASSWORD = "synthetic-password-for-tests";
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private LocalCredentialJpaRepository credentials;
    @Autowired private MemberJpaRepository members;
    @Autowired private MemberPersistenceAdapter persistence;
    @Autowired private PasswordHashAdapter passwords;
    @Autowired private JwtTokenProvider tokens;
    @Autowired private JwtEncoder encoder;
    @Autowired private JwtDecoder decoder;
    @Autowired private JwtProperties jwtProperties;

    /** 기존 요청·응답 이름, 201 상태, 해시 저장, 내부 번호 기반 30분 토큰을 함께 확인한다. */
    @Test
    void signsUpLogsInAndReadsOwnProfile(CapturedOutput output) throws Exception {
        String loginId = uniqueId();
        var signup = signup(loginId, PASSWORD).andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value(loginId))
                .andExpect(jsonPath("$.userName").value("테스트 회원"))
                .andExpect(jsonPath("$.email").value(nullValue()))
                .andExpect(jsonPath("$.phone_number").value(nullValue()))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist()).andReturn();
        long memberId = json.readTree(signup.getResponse().getContentAsString()).get("id").asLong();
        String hash = credentials.findByLoginId(loginId).orElseThrow().getPasswordHash();
        assertThat(hash).startsWith("$2b$12$").isNotEqualTo(PASSWORD);
        assertThat(passwords.matches(PASSWORD, hash)).isTrue();

        String token = loginToken(loginId, PASSWORD);
        var decoded = decoder.decode(token);
        assertThat(decoded.getSubject()).isEqualTo(Long.toString(memberId));
        assertThat(decoded.getClaimAsString("iss")).isEqualTo(jwtProperties.issuer());
        assertThat(decoded.getAudience()).containsExactly(jwtProperties.audience());
        assertThat(decoded.getExpiresAt()).isEqualTo(decoded.getIssuedAt().plusSeconds(1800));
        assertThat(decoded.getClaims()).doesNotContainKeys("password", "passwordHash", "email", "userId");
        var profile = mvc.perform(get("/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(memberId))
                .andExpect(jsonPath("$.userId").value(loginId)).andReturn();
        assertThat(profile.getRequest().getSession(false)).isNull();
        assertThat(output.getAll()).doesNotContain(PASSWORD, hash, token);
    }

    /** 아이디 앞뒤 공백을 정리하지만 대소문자는 서로 다른 회원으로 구분한다. */
    @Test
    void keepsCaseSensitiveIdsAndTrimsOnlyLoginId() throws Exception {
        String lowerId = uniqueId();
        String upperId = lowerId.toUpperCase();
        signup("  " + lowerId + "  ", PASSWORD).andExpect(status().isCreated());
        signup(upperId, PASSWORD).andExpect(status().isCreated());
        String lowerToken = loginToken("  " + lowerId + "  ", PASSWORD);
        String upperToken = loginToken(upperId, PASSWORD);
        assertThat(decoder.decode(lowerToken).getSubject()).isNotEqualTo(decoder.decode(upperToken).getSubject());
        signup(lowerId, PASSWORD).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_LOGIN_ID"));
    }

    /** 선택 연락처를 받아 저장하며 같은 이메일을 사용하는 별도 가입을 허용한다. */
    @Test
    void acceptsOptionalAndRepeatedContactInformation() throws Exception {
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/users/signup").contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("userId", uniqueId(), "userName", "연락처 테스트",
                                    "password", PASSWORD, "email", "shared@example.test", "phone_number", "01000000000"))))
                    .andExpect(status().isCreated()).andExpect(jsonPath("$.email").value("shared@example.test"))
                    .andExpect(jsonPath("$.phone_number").value("01000000000"));
        }
        mvc.perform(post("/users/signup").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("userId", uniqueId(), "userName", "연락처 없음",
                                "password", PASSWORD, "email", "   ", "phone_number", ""))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.email").value(nullValue()))
                .andExpect(jsonPath("$.phone_number").value(nullValue()));
    }

    /** 잘못된 길이·빈 이름·이메일은 DB 저장 전에 공통 400으로 응답한다. */
    @Test
    void rejectsInvalidSignupInputsWithoutEchoingValues() throws Exception {
        List<Map<String, String>> invalidRequests = List.of(
                Map.of("userId", " ", "userName", "회원", "password", PASSWORD),
                Map.of("userId", "a".repeat(51), "userName", "회원", "password", PASSWORD),
                Map.of("userId", uniqueId(), "userName", " ", "password", PASSWORD),
                Map.of("userId", uniqueId(), "userName", "회원", "password", "short"),
                Map.of("userId", uniqueId(), "userName", "회원", "password", "한".repeat(25)),
                Map.of("userId", uniqueId(), "userName", "회원", "password", PASSWORD, "email", "invalid-email"));
        for (var request : invalidRequests) {
            var result = mvc.perform(post("/users/signup").contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(request)))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                    .andExpect(jsonPath("$.errors[0].field").exists()).andReturn();
            assertThat(result.getResponse().getContentAsString()).doesNotContain(PASSWORD, "invalid-email");
        }
    }

    /** 비밀번호를 자르거나 공백을 지우지 않으며 정확히 72바이트인 입력을 허용한다. */
    @Test
    void preservesPasswordSpacesAndByteBoundary() throws Exception {
        String loginId = uniqueId();
        String password = " " + "한".repeat(23) + "  ";
        signup(loginId, password).andExpect(status().isCreated());
        loginToken(loginId, password);
        login(loginId, password.strip()).andExpect(status().isUnauthorized());
        login(loginId, "a".repeat(73)).andExpect(status().isBadRequest());
    }

    /** 없는 아이디와 틀린 비밀번호는 동일한 안내로 처리한다. */
    @Test
    void doesNotRevealWhichCredentialWasWrong() throws Exception {
        String loginId = uniqueId();
        signup(loginId, PASSWORD).andExpect(status().isCreated());
        for (String id : List.of(loginId, uniqueId())) {
            login(id, "wrong-password").andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                    .andExpect(jsonPath("$.message").value("아이디 또는 비밀번호가 올바르지 않습니다."))
                    .andExpect(header().string("WWW-Authenticate", "Bearer"));
        }
    }

    /** 로그인은 폼 본문만 받으며 JSON·URL 비밀번호·중복 필드로 우회할 수 없다. */
    @Test
    void requiresUnambiguousFormBodyForLogin() throws Exception {
        mvc.perform(post("/users/login").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnsupportedMediaType());
        mvc.perform(post("/users/login").queryParam("username", "user").queryParam("password", PASSWORD)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED).content("grant_type=password"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/users/login").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("username=one&username=two&password=test-password"))
                .andExpect(status().isBadRequest());
    }

    /** A의 토큰으로 본문에 B의 번호를 넣어도 B의 이름·권한·아이디는 바뀌지 않는다. */
    @Test
    void changesOnlyTheAuthenticatedMembersDisplayName() throws Exception {
        String firstId = uniqueId();
        String otherId = uniqueId();
        signup(firstId, PASSWORD).andExpect(status().isCreated());
        var otherSignup = signup(otherId, PASSWORD).andExpect(status().isCreated()).andReturn();
        long otherMemberId = json.readTree(otherSignup.getResponse().getContentAsString()).get("id").asLong();
        String token = loginToken(firstId, PASSWORD);
        mvc.perform(put("/users/me/username").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                                "userName", "바뀐 이름", "id", otherMemberId, "userId", otherId, "role", "ADMIN"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.userName").value("바뀐 이름"))
                .andExpect(jsonPath("$.userId").value(firstId));
        assertThat(members.findById(otherMemberId).orElseThrow().getDisplayName()).isEqualTo("테스트 회원");
        var first = members.findById(Long.valueOf(decoder.decode(token).getSubject())).orElseThrow();
        assertThat(first.getRole().name()).isEqualTo("USER");
        mvc.perform(get("/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.userName").value("바뀐 이름"));
    }

    /** 이름 검증 실패 시 기존 이름을 보존한다. */
    @Test
    void rejectsEmptyOrTooLongDisplayName() throws Exception {
        String loginId = uniqueId();
        signup(loginId, PASSWORD).andExpect(status().isCreated());
        String token = loginToken(loginId, PASSWORD);
        for (String name : List.of("", " ", "가".repeat(51))) {
            mvc.perform(put("/users/me/username").header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("userName", name))))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(get("/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.userName").value("테스트 회원"));
    }

    /** 소셜 전용 회원 프로필 조회는 가능하지만 일반 로그인 수단을 임의로 생성하지 않는다. */
    @Test
    void readsProfileWithoutLocalCredential() throws Exception {
        Member member = members.saveAndFlush(new Member("일반 로그인 없는 회원", null, null));
        mvc.perform(get("/users/me").header("Authorization", "Bearer " + tokens.issueAccessToken(member.getId())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.userId").value(nullValue()));
        assertThat(credentials.findById(member.getId())).isEmpty();
    }

    /** 존재하지 않는 회원을 가리키는 정상 서명 토큰도 본인 API에서 거절한다. */
    @Test
    void rejectsTokenForDeletedMember() throws Exception {
        Member member = members.saveAndFlush(new Member("삭제 테스트", null, null));
        String token = tokens.issueAccessToken(member.getId());
        members.deleteById(member.getId());
        mvc.perform(get("/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    /** 서명을 변조하거나 형식이 깨진 토큰은 컨트롤러에 도달하지 못한다. */
    @Test
    void rejectsTamperedMalformedAndMissingTokens() throws Exception {
        String valid = tokens.issueAccessToken(1L);
        String[] parts = valid.split("\\.");
        String changedSignature = (parts[2].startsWith("A") ? "B" : "A") + parts[2].substring(1);
        for (String token : List.of(parts[0] + "." + parts[1] + "." + changedSignature, "malformed-token")) {
            assertRejectedToken(token);
        }
        mvc.perform(get("/users/me")).andExpect(status().isUnauthorized());
        mvc.perform(put("/users/me/username").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/users/me").queryParam("access_token", valid)).andExpect(status().isUnauthorized());
    }

    /** 같은 키로 서명해도 만료·발급자·대상·필수 값이 틀리면 거절한다. */
    @Test
    void rejectsInvalidClaimsEvenWithCorrectSignature() throws Exception {
        assertRejectedToken(sign(claims().issuedAt(Instant.now().minusSeconds(120)).expiresAt(Instant.now().minusSeconds(60))));
        assertRejectedToken(sign(claims().issuer("another-issuer")));
        assertRejectedToken(sign(claims().audience(List.of("another-service"))));
        assertRejectedToken(sign(claims().notBefore(Instant.now().plusSeconds(120))));
        assertRejectedToken(sign(claims().issuedAt(Instant.now().plusSeconds(120))));
        for (String claim : List.of("sub", "iat", "exp", "iss", "aud")) {
            assertRejectedToken(sign(claims().claims(values -> values.remove(claim))));
        }
        for (String subject : List.of("0", "-1", "old-login-id", "01", "9999999999999999999")) {
            assertRejectedToken(sign(claims().subject(subject)));
        }
    }

    /** 인증을 해도 이번에 열지 않은 API·관리 경로는 403으로 거절한다. */
    @Test
    void keepsUnimplementedAndManagementRoutesClosed() throws Exception {
        String token = tokens.issueAccessToken(1L);
        for (String route : List.of("/favorites", "/actuator/env", "/users/1", "/error")) {
            mvc.perform(get(route).header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        }
    }

    /** 동시에 가입해도 한 요청만 성공하고 실패 요청이 만든 회원 행은 남지 않는다. */
    @Test
    void concurrentDuplicateSignupLeavesExactlyOneCompleteMember() throws Exception {
        String loginId = uniqueId();
        long before = members.count();
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> concurrentSignup(start, loginId));
            var second = executor.submit(() -> concurrentSignup(start, loginId));
            start.countDown();
            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(201, 409);
        }
        assertThat(members.count()).isEqualTo(before + 1);
        assertThat(credentials.findByLoginId(loginId)).isPresent();
    }

    /** 사전 확인을 통과한 경합을 강제로 재현해 두 번째 저장 실패의 전체 롤백을 확인한다. */
    @Test
    void rollsBackNewMemberWhenCredentialInsertConflicts() throws Exception {
        String loginId = uniqueId();
        signup(loginId, PASSWORD).andExpect(status().isCreated());
        String hash = credentials.findByLoginId(loginId).orElseThrow().getPasswordHash();
        long before = members.count();
        assertThatThrownBy(() -> persistence.createLocalMember(loginId, "남으면 안 되는 회원", null, null, hash))
                .isInstanceOfSatisfying(MemberException.class,
                        error -> assertThat(error.getReason()).isEqualTo(MemberException.Reason.DUPLICATE_LOGIN_ID));
        assertThat(members.count()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM members WHERE display_name = ?", Long.class,
                "남으면 안 되는 회원")).isZero();
    }

    /** 두 작업이 같은 시점에 HTTP 가입을 시작하게 한다. */
    private int concurrentSignup(CountDownLatch start, String loginId) throws Exception {
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("동시 요청 시작을 기다리다 실패했습니다.");
        }
        return signup(loginId, PASSWORD).andReturn().getResponse().getStatus();
    }

    /** 테스트마다 고유 아이디를 사용해 다른 검증의 데이터를 건드리지 않는다. */
    private String uniqueId() {
        return "member-" + UUID.randomUUID();
    }

    /** 실제 앱과 같은 JSON 형식으로 가입 요청을 만든다. */
    private org.springframework.test.web.servlet.ResultActions signup(String loginId, String password) throws Exception {
        return mvc.perform(post("/users/signup").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("userId", loginId, "userName", "테스트 회원", "password", password))));
    }

    /** 실제 폼 본문을 전송해 요청 파싱과 비밀번호의 공백 보존까지 확인한다. */
    private org.springframework.test.web.servlet.ResultActions login(String loginId, String password) throws Exception {
        String body = "username=" + URLEncoder.encode(loginId, StandardCharsets.UTF_8)
                + "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8);
        return mvc.perform(post("/users/login").contentType(MediaType.APPLICATION_FORM_URLENCODED).content(body));
    }

    /** 로그인 API가 실제 발급한 토큰을 다음 요청에서 사용한다. */
    private String loginToken(String loginId, String password) throws Exception {
        MvcResult result = login(loginId, password).andExpect(status().isOk())
                .andExpect(jsonPath("$.token_type").value("bearer"))
                .andExpect(header().string("Cache-Control", "no-store")).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("access_token").asString();
    }

    /** 검증할 값 하나를 바꾸기 위한 정상 토큰 내용의 기준을 만든다. */
    private JwtClaimsSet.Builder claims() {
        return JwtClaimsSet.builder().issuer(jwtProperties.issuer()).audience(List.of(jwtProperties.audience()))
                .subject("1").issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300));
    }

    /** 테스트용 비정상 claim에도 실제 서명을 붙여 claim 검증을 우회할 수 없는지 확인한다. */
    private String sign(JwtClaimsSet.Builder claims) {
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims.build())).getTokenValue();
    }

    /** 보안 필터의 오류에도 공통 코드·추적 ID·인증 헤더가 있고 토큰 원문은 없는지 확인한다. */
    private void assertRejectedToken(String token) throws Exception {
        var result = mvc.perform(get("/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(header().string("WWW-Authenticate", "Bearer")).andReturn();
        var body = json.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("traceId").asString()).isEqualTo(result.getResponse().getHeader("X-Request-ID"));
        assertThat(result.getResponse().getContentAsString()).doesNotContain(token);
    }
}
