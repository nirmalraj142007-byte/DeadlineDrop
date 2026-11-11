package com.deadlinedrop.web;

import com.deadlinedrop.api.ApiException;
import com.deadlinedrop.api.ErrorCodes;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = GlobalExceptionHandlerTest.TestController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({GlobalExceptionHandler.class, GlobalExceptionHandlerTest.TestController.class, com.deadlinedrop.config.ClockConfig.class})
class GlobalExceptionHandlerTest {

    private final MockMvc mockMvc;

    public GlobalExceptionHandlerTest(@org.springframework.beans.factory.annotation.Autowired MockMvc mockMvc) {
        this.mockMvc = mockMvc;
    }

    record SampleBody(@NotBlank String name) {}

    @RestController
    static class TestController {

        @GetMapping("/test/api-exception")
        public void throwApiException() {
            throw ApiException.notFound(ErrorCodes.ASSIGNMENT_NOT_FOUND, "Assignment 42 not found");
        }

        @PostMapping("/test/validation-body")
        public void throwValidationBody(@Valid @RequestBody SampleBody body) {
        }

        @GetMapping("/test/validation-param")
        public void throwValidationParam() throws NoSuchMethodException {
            java.lang.reflect.Method method = TestController.class.getDeclaredMethod("throwValidationParam");
            org.springframework.core.MethodParameter param = new org.springframework.core.MethodParameter(method, -1);
            throw new org.springframework.web.method.annotation.HandlerMethodValidationException(
                    new org.springframework.validation.method.MethodValidationResult() {
                        @Override
                        public Object getTarget() { return this; }
                        @Override
                        public java.lang.reflect.Method getMethod() { return method; }
                        @Override
                        public boolean isForReturnValue() { return false; }
                        @Override
                        public java.util.List<org.springframework.validation.method.ParameterValidationResult> getParameterValidationResults() {
                            return java.util.List.of(new org.springframework.validation.method.ParameterValidationResult(
                                    param,
                                    "badVal",
                                    java.util.List.of(new org.springframework.validation.FieldError("sample", "param", "must not be empty")),
                                    null, null, null, null
                            ));
                        }
                        @Override
                        public java.util.List<org.springframework.context.MessageSourceResolvable> getCrossParameterValidationResults() {
                            return java.util.List.of();
                        }
                    }
            );
        }

        @GetMapping("/test/missing-idempotency-key")
        public void throwMissingIdempotencyKey(@RequestHeader("Idempotency-Key") String key) {
        }

        @GetMapping("/test/missing-other-header")
        public void throwMissingOtherHeader(@RequestHeader("X-Other") String other) {
        }

        @GetMapping("/test/file-too-large")
        public void throwFileTooLarge() {
            throw new MaxUploadSizeExceededException(5000000);
        }

        @GetMapping("/test/type-not-allowed")
        public void throwTypeNotAllowed() throws HttpMediaTypeNotSupportedException {
            throw new HttpMediaTypeNotSupportedException("application/xml");
        }

        @GetMapping("/test/not-found")
        public void throwNotFound() throws NoResourceFoundException {
            throw new NoResourceFoundException(HttpMethod.GET, "/test/not-found", "Resource not found");
        }

        @GetMapping("/test/version-conflict")
        public void throwVersionConflict() {
            throw new ObjectOptimisticLockingFailureException("Submission", 1L);
        }

        @GetMapping("/test/access-denied")
        public void throwAccessDenied() {
            throw new AccessDeniedException("Forbidden action");
        }

        @GetMapping("/test/authorization-denied")
        public void throwAuthorizationDenied() {
            throw new AuthorizationDeniedException("Authorization refused", () -> false);
        }

        @GetMapping("/test/cannot-get-jdbc-connection")
        public void throwCannotGetJdbcConnection() {
            throw new CannotGetJdbcConnectionException("Connection refused", new SQLException("timeout"));
        }

        @GetMapping("/test/data-access-failure")
        public void throwDataAccessFailure() {
            throw new DataAccessResourceFailureException("DB failure");
        }

        @GetMapping("/test/transient-data-access")
        public void throwTransientDataAccess() {
            throw new TransientDataAccessResourceException("Lock timeout");
        }

        @GetMapping("/test/internal-error")
        public void throwInternalError() {
            throw new RuntimeException("Unexpected NPE");
        }
    }

    @Test
    void apiExceptionReturnsConfiguredStatusAndProblemDetail() throws Exception {
        mockMvc.perform(get("/test/api-exception"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Content-Type", startsWith("application/problem+json")))
                .andExpect(jsonPath("$.code").value(ErrorCodes.ASSIGNMENT_NOT_FOUND))
                .andExpect(jsonPath("$.type").value("https://deadlinedrop.dev/problems/assignment-not-found"))
                .andExpect(jsonPath("$.title").value("Not Found"))
                .andExpect(jsonPath("$.detail").value("Assignment 42 not found"))
                .andExpect(jsonPath("$.instance").value("/test/api-exception"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void methodArgumentNotValidReturns400WithErrorsList() throws Exception {
        mockMvc.perform(post("/test/validation-body")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Content-Type", startsWith("application/problem+json")))
                .andExpect(jsonPath("$.code").value(ErrorCodes.VALIDATION_FAILED))
                .andExpect(jsonPath("$.type").value("https://deadlinedrop.dev/problems/validation-failed"))
                .andExpect(jsonPath("$.errors[0].field").value("name"))
                .andExpect(jsonPath("$.errors[0].message").isNotEmpty())
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void handlerMethodValidationExceptionReturns400WithErrorsList() throws Exception {
        mockMvc.perform(get("/test/validation-param"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Content-Type", startsWith("application/problem+json")))
                .andExpect(jsonPath("$.code").value(ErrorCodes.VALIDATION_FAILED))
                .andExpect(jsonPath("$.type").value("https://deadlinedrop.dev/problems/validation-failed"))
                .andExpect(jsonPath("$.errors").isArray())
                .andExpect(jsonPath("$.errors[0].field").value("param"))
                .andExpect(jsonPath("$.errors[0].message").isNotEmpty())
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void missingIdempotencyKeyHeaderReturns400MissingIdempotencyKey() throws Exception {
        mockMvc.perform(get("/test/missing-idempotency-key"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Content-Type", startsWith("application/problem+json")))
                .andExpect(jsonPath("$.code").value(ErrorCodes.MISSING_IDEMPOTENCY_KEY))
                .andExpect(jsonPath("$.type").value("https://deadlinedrop.dev/problems/missing-idempotency-key"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void missingOtherHeaderReturns400ValidationFailed() throws Exception {
        mockMvc.perform(get("/test/missing-other-header"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Content-Type", startsWith("application/problem+json")))
                .andExpect(jsonPath("$.code").value(ErrorCodes.VALIDATION_FAILED))
                .andExpect(jsonPath("$.type").value("https://deadlinedrop.dev/problems/validation-failed"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void maxUploadSizeExceededReturns413FileTooLarge() throws Exception {
        mockMvc.perform(get("/test/file-too-large"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(header().string("Content-Type", startsWith("application/problem+json")))
                .andExpect(jsonPath("$.code").value(ErrorCodes.FILE_TOO_LARGE))
                .andExpect(jsonPath("$.type").value("https://deadlinedrop.dev/problems/file-too-large"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void httpMediaTypeNotSupportedReturns415TypeNotAllowed() throws Exception {
        mockMvc.perform(get("/test/type-not-allowed"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(header().string("Content-Type", startsWith("application/problem+json")))
                .andExpect(jsonPath("$.code").value(ErrorCodes.TYPE_NOT_ALLOWED))
                .andExpect(jsonPath("$.type").value("https://deadlinedrop.dev/problems/type-not-allowed"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void noResourceFoundReturns404NotFound() throws Exception {
        mockMvc.perform(get("/test/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Content-Type", startsWith("application/problem+json")))
                .andExpect(jsonPath("$.code").value(ErrorCodes.NOT_FOUND))
                .andExpect(jsonPath("$.type").value("https://deadlinedrop.dev/problems/not-found"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void objectOptimisticLockingFailureReturns409VersionConflict() throws Exception {
        mockMvc.perform(get("/test/version-conflict"))
                .andExpect(status().isConflict())
                .andExpect(header().string("Content-Type", startsWith("application/problem+json")))
                .andExpect(jsonPath("$.code").value(ErrorCodes.VERSION_CONFLICT))
                .andExpect(jsonPath("$.type").value("https://deadlinedrop.dev/problems/version-conflict"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void accessDeniedReturns403Forbidden() throws Exception {
        mockMvc.perform(get("/test/access-denied"))
                .andExpect(status().isForbidden())
                .andExpect(header().string("Content-Type", startsWith("application/problem+json")))
                .andExpect(jsonPath("$.code").value(ErrorCodes.FORBIDDEN))
                .andExpect(jsonPath("$.type").value("https://deadlinedrop.dev/problems/forbidden"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void authorizationDeniedReturns403Forbidden() throws Exception {
        mockMvc.perform(get("/test/authorization-denied"))
                .andExpect(status().isForbidden())
                .andExpect(header().string("Content-Type", startsWith("application/problem+json")))
                .andExpect(jsonPath("$.code").value(ErrorCodes.FORBIDDEN))
                .andExpect(jsonPath("$.type").value("https://deadlinedrop.dev/problems/forbidden"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void cannotGetJdbcConnectionReturns503DatabaseUnavailableWithRetryAfter() throws Exception {
        mockMvc.perform(get("/test/cannot-get-jdbc-connection"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Content-Type", startsWith("application/problem+json")))
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.code").value(ErrorCodes.DATABASE_UNAVAILABLE))
                .andExpect(jsonPath("$.type").value("https://deadlinedrop.dev/problems/database-unavailable"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void dataAccessResourceFailureReturns503DatabaseUnavailableWithRetryAfter() throws Exception {
        mockMvc.perform(get("/test/data-access-failure"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Content-Type", startsWith("application/problem+json")))
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.code").value(ErrorCodes.DATABASE_UNAVAILABLE))
                .andExpect(jsonPath("$.type").value("https://deadlinedrop.dev/problems/database-unavailable"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void transientDataAccessReturns503DatabaseUnavailableWithRetryAfter() throws Exception {
        mockMvc.perform(get("/test/transient-data-access"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Content-Type", startsWith("application/problem+json")))
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.code").value(ErrorCodes.DATABASE_UNAVAILABLE))
                .andExpect(jsonPath("$.type").value("https://deadlinedrop.dev/problems/database-unavailable"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void unhandledExceptionReturns500InternalWithSanitizedDetail() throws Exception {
        mockMvc.perform(get("/test/internal-error"))
                .andExpect(status().isInternalServerError())
                .andExpect(header().string("Content-Type", startsWith("application/problem+json")))
                .andExpect(jsonPath("$.code").value(ErrorCodes.INTERNAL))
                .andExpect(jsonPath("$.type").value("https://deadlinedrop.dev/problems/internal"))
                .andExpect(jsonPath("$.detail").value("Unexpected error, quote the request id"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }
}
