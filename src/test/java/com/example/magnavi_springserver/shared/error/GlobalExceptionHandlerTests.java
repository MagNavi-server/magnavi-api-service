package com.example.magnavi_springserver.shared.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import com.example.magnavi_springserver.config.SecurityConfig;
import com.example.magnavi_springserver.shared.observability.RequestTraceFilter;

/** 테스트 전용 입구로 MVC 오류를 유발해 상태·헤더·본문·정보 노출을 검증한다. */
@WebMvcTest(controllers = {GlobalExceptionHandlerTests.ProbeController.class, ApiErrorController.class})
@Import({SecurityConfig.class, SecurityErrorResponseWriter.class, RequestTraceFilter.class,
        GlobalExceptionHandlerTests.ProbeSecurity.class, GlobalExceptionHandlerTests.ProbeController.class})
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class GlobalExceptionHandlerTests {

    /** 이 테스트는 MVC 오류만 다룬다. 실제 JWT 검증은 회원 통합 테스트에서 확인한다. */
    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    /** JSON 파싱 오류에 포함된 입력 원문을 응답에 노출하지 않는다. */
    @Test
    void rejectsMalformedJsonWithoutEchoingInput() throws Exception {
        var result = mockMvc.perform(post("/__test/input").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"synthetic-private-value\""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("synthetic-private-value");
    }

    /** 필드 오류에는 필드명과 안전한 안내만 포함하고 검증 메시지 원문을 그대로 쓰지 않는다. */
    @Test
    void describesValidationFieldsWithoutExposingRejectedValues() throws Exception {
        mockMvc.perform(post("/__test/input").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("name"))
                .andExpect(jsonPath("$.errors[0].message").value("입력 조건을 확인해 주세요."))
                .andExpect(jsonPath("$.errors[0].rejectedValue").doesNotExist());
    }

    /** 입력이 정상이라면 공통 예외 처리가 업무 응답을 방해하지 않는다. */
    @Test
    void preservesSuccessfulResponses() throws Exception {
        mockMvc.perform(post("/__test/input").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"테스트\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("테스트"));
    }

    /** 인증된 업무 입구 내부에서 대상이 없을 때 404를 500으로 바꾸지 않는다. */
    @Test
    void preservesNotFoundStatus() throws Exception {
        mockMvc.perform(get("/__test/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    /** 405의 Allow 헤더처럼 HTTP 의미에 필요한 헤더를 유지한다. */
    @Test
    void preservesMethodNotAllowedStatusAndHeaders() throws Exception {
        mockMvc.perform(put("/__test/input"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", "POST"))
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    /** 잘못된 Content-Type을 입력 오류로 구분한다. */
    @Test
    void preservesUnsupportedMediaTypeStatus() throws Exception {
        mockMvc.perform(post("/__test/input").contentType(MediaType.TEXT_PLAIN).content("test"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    /** 내부 상태 오류의 상세 이유를 그대로 전달하지 않는다. */
    @Test
    void sanitizesExplicitHttpErrors() throws Exception {
        var result = mockMvc.perform(get("/__test/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("synthetic-private-value");
    }

    /** 보안 필터 이후 발생한 인증·권한 오류도 같은 코드로 변환한다. */
    @Test
    void preservesAuthenticationAndAccessErrors() throws Exception {
        mockMvc.perform(get("/__test/authentication"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mockMvc.perform(get("/__test/access"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    /** 오류 JSON과 헤더의 ID를 연결하고, 위조된 입력 헤더와 내부 예외 내용을 반사하지 않는다. */
    @Test
    void correlatesSafeServerErrorsWithServerGeneratedTraceId(CapturedOutput output) throws Exception {
        var result = mockMvc.perform(get("/__test/failure").header("X-Request-ID", "client-controlled"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andReturn();
        String traceId = result.getResponse().getHeader("X-Request-ID");
        assertThat(traceId).isNotBlank().isNotEqualTo("client-controlled");
        assertThat(objectMapper.readTree(result.getResponse().getContentAsString()).get("traceId").asString())
                .isEqualTo(traceId);
        assertThat(result.getResponse().getContentAsString()).doesNotContain("synthetic-private-value", "stackTrace");
        assertThat(output.getAll()).contains(traceId).doesNotContain("synthetic-private-value");
    }

    /** 서블릿 내부 오류 디스패치는 허용하되 공통 오류 본문만 전송한다. */
    @Test
    void handlesServletErrorDispatchSafely() throws Exception {
        mockMvc.perform(get("/error").with(request -> {
                    request.setDispatcherType(DispatcherType.ERROR);
                    request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 404);
                    request.setAttribute(RequestDispatcher.ERROR_MESSAGE, "synthetic-private-value");
                    return request;
                }))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("요청한 대상을 찾을 수 없습니다."));
    }

    /** 테스트 소스에만 존재하는 경로를 열어 MVC 검증을 보안 정책 검증과 분리한다. */
    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeSecurity {

        /** 운영 접근 정책을 변경하지 않고 테스트 전용 요청만 허용한다. */
        @Bean
        @Order(0)
        SecurityFilterChain probeChain(HttpSecurity http) throws Exception {
            return http.securityMatcher("/__test/**")
                    .csrf(AbstractHttpConfigurer::disable)
                    .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                    .build();
        }
    }

    /** 배포 산출물에 포함되지 않는 오류 재현용 Controller다. */
    @RestController
    @RequestMapping("/__test")
    static class ProbeController {

        /** 정상 입력과 Bean Validation 실패를 실제 MVC 역직렬화 경로로 검증한다. */
        @PostMapping("/input")
        Input input(@Valid @RequestBody Input input) {
            return input;
        }

        /** 공개하면 안 되는 상세 정보가 포함된 내부 오류를 재현한다. */
        @GetMapping("/failure")
        void failure() {
            throw new IllegalStateException("synthetic-private-value");
        }

        /** 명시한 HTTP 상태가 보존되는지 검증하기 위한 충돌을 재현한다. */
        @GetMapping("/conflict")
        void conflict() {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "synthetic-private-value");
        }

        /** 업무 흐름 내부의 인증 실패를 재현한다. */
        @GetMapping("/authentication")
        void authentication() {
            throw new BadCredentialsException("synthetic-private-value");
        }

        /** 업무 흐름 내부의 권한 부족을 재현한다. */
        @GetMapping("/access")
        void access() {
            throw new AccessDeniedException("synthetic-private-value");
        }
    }

    /** 실제 회원 API와 별개인 테스트용 검증 입력이다. */
    record Input(@NotBlank String name) {
    }
}
