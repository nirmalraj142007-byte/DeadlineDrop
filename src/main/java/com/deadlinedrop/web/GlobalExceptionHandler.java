package com.deadlinedrop.web;

import com.deadlinedrop.api.ApiException;
import com.deadlinedrop.api.ErrorCodes;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Global exception handler mapping all application and framework exceptions to RFC 9457 ProblemDetail.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    public record ValidationError(String field, String message) {}

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ProblemDetail> handleApiException(ApiException ex, HttpServletRequest request) {
        ProblemDetail pd = buildProblemDetail(
                ex.getStatus(),
                ex.getCode(),
                ex.getDetail(),
                resolvePath(request)
        );
        return ResponseEntity.status(ex.getStatus())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(pd);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        List<ValidationError> errors = new ArrayList<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            errors.add(new ValidationError(fe.getField(), fe.getDefaultMessage() != null ? fe.getDefaultMessage() : "invalid"));
        }
        for (ObjectError ge : ex.getBindingResult().getGlobalErrors()) {
            errors.add(new ValidationError(ge.getObjectName(), ge.getDefaultMessage() != null ? ge.getDefaultMessage() : "invalid"));
        }

        ProblemDetail pd = buildProblemDetail(
                HttpStatus.BAD_REQUEST,
                ErrorCodes.VALIDATION_FAILED,
                "Validation failed",
                resolvePath(request)
        );
        pd.setProperty("errors", errors);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(pd);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        List<ValidationError> errors = new ArrayList<>();
        for (ParameterValidationResult result : ex.getParameterValidationResults()) {
            String paramName = result.getMethodParameter().getParameterName();
            for (MessageSourceResolvable error : result.getResolvableErrors()) {
                String field = error instanceof FieldError fe
                        ? fe.getField()
                        : (paramName != null ? paramName : "parameter");
                String message = error.getDefaultMessage() != null ? error.getDefaultMessage() : "invalid";
                errors.add(new ValidationError(field, message));
            }
        }
        for (MessageSourceResolvable crossError : ex.getCrossParameterValidationResults()) {
            errors.add(new ValidationError("parameters", crossError.getDefaultMessage() != null ? crossError.getDefaultMessage() : "invalid"));
        }

        ProblemDetail pd = buildProblemDetail(
                HttpStatus.BAD_REQUEST,
                ErrorCodes.VALIDATION_FAILED,
                "Validation failed",
                resolvePath(request)
        );
        pd.setProperty("errors", errors);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(pd);
    }

    @Override
    protected ResponseEntity<Object> handleServletRequestBindingException(
            ServletRequestBindingException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        if (ex instanceof MissingRequestHeaderException headerEx) {
            return handleMissingRequestHeader(headerEx, request);
        }
        ProblemDetail pd = buildProblemDetail(
                HttpStatus.BAD_REQUEST,
                ErrorCodes.VALIDATION_FAILED,
                ex.getMessage(),
                resolvePath(request)
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(pd);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<Object> handleMissingRequestHeader(
            MissingRequestHeaderException ex,
            WebRequest request) {
        String code = "Idempotency-Key".equalsIgnoreCase(ex.getHeaderName())
                ? ErrorCodes.MISSING_IDEMPOTENCY_KEY
                : ErrorCodes.VALIDATION_FAILED;
        ProblemDetail pd = buildProblemDetail(
                HttpStatus.BAD_REQUEST,
                code,
                ex.getMessage(),
                resolvePath(request)
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(pd);
    }

    @Override
    protected ResponseEntity<Object> handleMaxUploadSizeExceededException(
            MaxUploadSizeExceededException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        ProblemDetail pd = buildProblemDetail(
                HttpStatus.PAYLOAD_TOO_LARGE,
                ErrorCodes.FILE_TOO_LARGE,
                ex.getMessage(),
                resolvePath(request)
        );
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(pd);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMediaTypeNotSupported(
            HttpMediaTypeNotSupportedException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        ProblemDetail pd = buildProblemDetail(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                ErrorCodes.TYPE_NOT_ALLOWED,
                ex.getMessage(),
                resolvePath(request)
        );
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(pd);
    }

    @Override
    protected ResponseEntity<Object> handleNoResourceFoundException(
            NoResourceFoundException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        ProblemDetail pd = buildProblemDetail(
                HttpStatus.NOT_FOUND,
                ErrorCodes.NOT_FOUND,
                ex.getMessage(),
                resolvePath(request)
        );
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(pd);
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ProblemDetail> handleOptimisticLocking(
            ObjectOptimisticLockingFailureException ex,
            HttpServletRequest request) {
        ProblemDetail pd = buildProblemDetail(
                HttpStatus.CONFLICT,
                ErrorCodes.VERSION_CONFLICT,
                ex.getMessage(),
                resolvePath(request)
        );
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(pd);
    }

    @ExceptionHandler({AccessDeniedException.class, AuthorizationDeniedException.class})
    public ResponseEntity<ProblemDetail> handleAccessDenied(
            Exception ex,
            HttpServletRequest request) {
        ProblemDetail pd = buildProblemDetail(
                HttpStatus.FORBIDDEN,
                ErrorCodes.FORBIDDEN,
                ex.getMessage(),
                resolvePath(request)
        );
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(pd);
    }

    @ExceptionHandler({
            CannotGetJdbcConnectionException.class,
            DataAccessResourceFailureException.class,
            TransientDataAccessResourceException.class
    })
    public ResponseEntity<ProblemDetail> handleDatabaseUnavailable(
            Exception ex,
            HttpServletRequest request) {
        ProblemDetail pd = buildProblemDetail(
                HttpStatus.SERVICE_UNAVAILABLE,
                ErrorCodes.DATABASE_UNAVAILABLE,
                ex.getMessage(),
                resolvePath(request)
        );
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(pd);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnhandledException(
            Exception ex,
            HttpServletRequest request) {
        log.error("Unhandled exception processing request [{}]:",
                request != null ? request.getRequestURI() : "unknown", ex);
        ProblemDetail pd = buildProblemDetail(
                HttpStatus.INTERNAL_SERVER_ERROR,
                ErrorCodes.INTERNAL,
                "Unexpected error, quote the request id",
                resolvePath(request)
        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(pd);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex,
            Object body,
            HttpHeaders headers,
            HttpStatusCode statusCode,
            WebRequest request) {
        HttpStatus status = HttpStatus.resolve(statusCode.value());
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        String code = switch (status) {
            case BAD_REQUEST -> ErrorCodes.VALIDATION_FAILED;
            case NOT_FOUND -> ErrorCodes.NOT_FOUND;
            case METHOD_NOT_ALLOWED, UNSUPPORTED_MEDIA_TYPE -> ErrorCodes.TYPE_NOT_ALLOWED;
            case PAYLOAD_TOO_LARGE -> ErrorCodes.FILE_TOO_LARGE;
            case CONFLICT -> ErrorCodes.VERSION_CONFLICT;
            case FORBIDDEN -> ErrorCodes.FORBIDDEN;
            case UNAUTHORIZED -> ErrorCodes.UNAUTHENTICATED;
            case SERVICE_UNAVAILABLE -> ErrorCodes.DATABASE_UNAVAILABLE;
            default -> ErrorCodes.INTERNAL;
        };
        ProblemDetail pd;
        if (body instanceof ProblemDetail existing) {
            pd = existing;
        } else {
            pd = ProblemDetail.forStatus(status);
            pd.setDetail(ex.getMessage() != null ? ex.getMessage() : status.getReasonPhrase());
        }
        pd.setType(URI.create("https://deadlinedrop.dev/problems/" + toLowerKebab(code)));
        pd.setTitle(status.getReasonPhrase());
        pd.setInstance(URI.create(resolvePath(request)));
        pd.setProperty("code", code);
        pd.setProperty("requestId", resolveRequestId());

        HttpHeaders responseHeaders = new HttpHeaders();
        if (headers != null) {
            responseHeaders.putAll(headers);
        }
        responseHeaders.setContentType(MediaType.APPLICATION_PROBLEM_JSON);

        return ResponseEntity.status(statusCode).headers(responseHeaders).body(pd);
    }

    private ProblemDetail buildProblemDetail(
            HttpStatus status,
            String code,
            String detail,
            String path) {
        ProblemDetail pd = ProblemDetail.forStatus(status);
        pd.setType(URI.create("https://deadlinedrop.dev/problems/" + toLowerKebab(code)));
        pd.setTitle(status.getReasonPhrase());
        pd.setDetail(detail != null ? detail : status.getReasonPhrase());
        if (path != null && !path.isBlank()) {
            pd.setInstance(URI.create(path));
        }
        pd.setProperty("code", code);
        pd.setProperty("requestId", resolveRequestId());
        return pd;
    }

    private String toLowerKebab(String code) {
        return code.toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private String resolveRequestId() {
        String id = MDC.get(RequestIdFilter.MDC_KEY);
        if (id != null && !id.isBlank()) {
            return id;
        }
        return UUID.randomUUID().toString();
    }

    private String resolvePath(WebRequest request) {
        if (request instanceof ServletWebRequest swr) {
            return swr.getRequest().getRequestURI();
        }
        return "/";
    }

    private String resolvePath(HttpServletRequest request) {
        return request != null && request.getRequestURI() != null ? request.getRequestURI() : "/";
    }
}
