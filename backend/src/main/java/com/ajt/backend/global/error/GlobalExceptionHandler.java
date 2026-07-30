package com.ajt.backend.global.error;

import com.ajt.backend.domain.document.api.DocumentUploadValidationException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * 전역 예외 처리기.
 *
 * <p>표준 Spring MVC 예외(405·415·413·400·404·406 등)는 {@link ResponseEntityExceptionHandler}가
 * 이미 올바른 상태코드로 매핑해 준다. 여기서는 {@link #handleExceptionInternal}만 오버라이드해
 * 그 결과를 공통 오류 envelope({@link ErrorResponse})로 렌더링한다. 예외마다 핸들러를 하나씩
 * 추가하다 누락하던 방식(500으로 새던 문제)을 구조적으로 방지한다.
 *
 * <p>도메인/비표준 예외(비즈니스 예외, DB 제약 위반, 업로드 검증 등)만 개별 핸들러로 처리한다.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ErrorResponse> handleBusinessException(
            BusinessException exception,
            HttpServletRequest request
    ) {
        ErrorCode errorCode = exception.errorCode();
        return ResponseEntity
                .status(errorCode.status())
                .body(ErrorResponse.of(errorCode, exception.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(DocumentUploadValidationException.class)
    ResponseEntity<ErrorResponse> handleDocumentUploadValidationException(
            DocumentUploadValidationException exception,
            HttpServletRequest request
    ) {
        ErrorCode errorCode = ErrorCode.INVALID_DOCUMENT_UPLOAD;
        return ResponseEntity
                .status(errorCode.status())
                .body(ErrorResponse.of(
                        errorCode,
                        errorCode.message(),
                        request.getRequestURI(),
                        List.of(new FieldErrorResponse(exception.field(), exception.getMessage()))
                ));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ErrorResponse> handleConstraintViolationException(
            ConstraintViolationException exception,
            HttpServletRequest request
    ) {
        List<FieldErrorResponse> fieldErrors = exception.getConstraintViolations()
                .stream()
                .map(violation -> new FieldErrorResponse(
                        violation.getPropertyPath().toString(),
                        violation.getMessage()
                ))
                .toList();

        ErrorCode errorCode = ErrorCode.INVALID_REQUEST;
        return ResponseEntity
                .status(errorCode.status())
                .body(ErrorResponse.of(errorCode, errorCode.message(), request.getRequestURI(), fieldErrors));
    }

    // 수정: 신규 추가. 동시성 등으로 DB 유니크/FK 제약 위반이 사전 체크를 빠져나가면 500 대신 409로 변환한다.
    //       내부 메시지(SQL·제약조건명)는 노출하지 않고 고정 문구만 반환한다(REST 컨벤션 §6.5).
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ErrorResponse> handleDataIntegrityViolationException(
            DataIntegrityViolationException exception,
            HttpServletRequest request
    ) {
        ErrorCode errorCode = ErrorCode.RESOURCE_CONFLICT;
        return ResponseEntity
                .status(errorCode.status())
                .body(ErrorResponse.of(errorCode, request.getRequestURI()));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> handleException(Exception exception, HttpServletRequest request) {
        ErrorCode errorCode = ErrorCode.INTERNAL_SERVER_ERROR;
        // 처리되지 않은 예외는 원인 추적이 가능하도록 스택트레이스와 함께 ERROR로 남긴다.
        log.error("처리되지 않은 예외: {} {}", request.getMethod(), request.getRequestURI(), exception);
        return ResponseEntity
                .status(errorCode.status())
                .body(ErrorResponse.of(errorCode, request.getRequestURI()));
    }

    // 수정: 신규 추가. ResponseEntityExceptionHandler가 처리하는 모든 표준 MVC 예외의
    //       최종 렌더링 지점. 상태코드를 공통 ErrorCode로 매핑해 동일한 오류 envelope로 반환한다.
    //       405의 Allow 헤더 등 base가 채운 헤더는 그대로 유지한다.
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception exception,
            Object body,
            HttpHeaders headers,
            HttpStatusCode statusCode,
            WebRequest request
    ) {
        ErrorCode errorCode = toErrorCode(statusCode);
        if (errorCode == ErrorCode.INTERNAL_SERVER_ERROR) {
            // 표준 MVC 예외 중 500(응답 직렬화 실패 등 서버측 오류)은 원인을 남긴다.
            log.error("처리되지 않은 MVC 예외: {}", requestUri(request), exception);
        }
        ErrorResponse errorResponse = ErrorResponse.of(
                errorCode,
                errorCode.message(),
                requestUri(request),
                extractFieldErrors(exception)
        );
        return new ResponseEntity<>(errorResponse, headers, errorCode.status());
    }

    // 표준 MVC 예외가 도달시킨 상태코드를 프로젝트 공통 ErrorCode로 매핑한다.
    // 도달 가능한 4xx는 전용 코드로, 그 외(500류·미도달 5xx)는 INTERNAL_SERVER_ERROR로 수렴한다.
    private ErrorCode toErrorCode(HttpStatusCode statusCode) {
        if (statusCode.isSameCodeAs(HttpStatus.BAD_REQUEST)) {
            return ErrorCode.INVALID_REQUEST;
        }
        if (statusCode.isSameCodeAs(HttpStatus.NOT_FOUND)) {
            return ErrorCode.NOT_FOUND;
        }
        if (statusCode.isSameCodeAs(HttpStatus.METHOD_NOT_ALLOWED)) {
            return ErrorCode.METHOD_NOT_ALLOWED;
        }
        if (statusCode.isSameCodeAs(HttpStatus.NOT_ACCEPTABLE)) {
            return ErrorCode.NOT_ACCEPTABLE;
        }
        if (statusCode.isSameCodeAs(HttpStatus.PAYLOAD_TOO_LARGE)) {
            return ErrorCode.PAYLOAD_TOO_LARGE;
        }
        if (statusCode.isSameCodeAs(HttpStatus.UNSUPPORTED_MEDIA_TYPE)) {
            return ErrorCode.UNSUPPORTED_MEDIA_TYPE;
        }
        return ErrorCode.INTERNAL_SERVER_ERROR;
    }

    // 검증 예외는 필드 오류 목록을 함께 담는다. 그 외 예외는 빈 목록.
    private List<FieldErrorResponse> extractFieldErrors(Exception exception) {
        if (exception instanceof MethodArgumentNotValidException manv) {
            return manv.getBindingResult()
                    .getFieldErrors()
                    .stream()
                    .map(this::toFieldErrorResponse)
                    .toList();
        }
        if (exception instanceof HandlerMethodValidationException hmv) {
            return hmv.getParameterValidationResults()
                    .stream()
                    .flatMap(result -> result.getResolvableErrors().stream()
                            .map(error -> new FieldErrorResponse(
                                    result.getMethodParameter().getParameterName(),
                                    error.getDefaultMessage()
                            )))
                    .toList();
        }
        return List.of();
    }

    private String requestUri(WebRequest request) {
        if (request instanceof ServletWebRequest servletWebRequest) {
            return servletWebRequest.getRequest().getRequestURI();
        }
        return "";
    }

    private FieldErrorResponse toFieldErrorResponse(FieldError fieldError) {
        return new FieldErrorResponse(fieldError.getField(), fieldError.getDefaultMessage());
    }
}
