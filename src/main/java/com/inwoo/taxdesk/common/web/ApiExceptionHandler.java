package com.inwoo.taxdesk.common.web;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 웹 계층의 마지막 방어선. (서비스는 도메인 예외를 던지고, 상태 코드는 여기서 정한다)
 *
 * <p>모든 경로가 공유한다. 규칙 두 가지:
 * <ul>
 *   <li>내부 사정(스택트레이스 · 클래스명 · SQL)은 절대 응답에 넣지 않는다</li>
 *   <li>대신 traceId 를 주고 로그에 같은 값을 남긴다 — 이게 없으면 신고를 받아도 찾을 수가 없다</li>
 * </ul>
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** 못 찾음 — 남의 것이라 못 보는 경우도 같은 응답이다. */
    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(NotFoundException e) {
        String traceId = newTraceId();
        log.info("[{}] not found: {}", traceId, e.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of("NOT_FOUND", e.getMessage(), traceId));
    }

    /** 권한 없음 — 인증은 됐지만 역할이 모자란 경우. (/admin/** 를 고객이 부를 때) */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleDenied(AccessDeniedException e) {
        String traceId = newTraceId();
        log.warn("[{}] access denied: {}", traceId, e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiError.of("FORBIDDEN", "권한이 없습니다.", traceId));
    }

    /** 잘못된 입력 — 사용자가 고칠 수 있는 문제다. 메시지를 그대로 보여 준다. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleBadRequest(IllegalArgumentException e) {
        String traceId = newTraceId();
        log.info("[{}] bad request: {}", traceId, e.getMessage());
        return ResponseEntity.badRequest()
                .body(ApiError.of("BAD_REQUEST", e.getMessage(), traceId));
    }

    /**
     * 순서를 어긴 호출 — 사용자가 고칠 수 있으므로 400 이다.
     *
     * <p>{@code calculateTax} 를 분리과세 고객에게 부른 경우가 여기로 온다.
     * 500 으로 뭉뚱그리면 "서버가 깨졌다"로 읽히지만 실제로는 <b>호출 순서가 틀린</b> 것이다.
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiError> handleIllegalState(IllegalStateException e) {
        String traceId = newTraceId();
        log.info("[{}] precondition: {}", traceId, e.getMessage());
        return ResponseEntity.badRequest()
                .body(ApiError.of("PRECONDITION_FAILED", e.getMessage(), traceId));
    }

    /** 모델 장애 — 503 + 재시도 안내. 500 으로 뭉뚱그리면 원인을 구분할 수 없다. */
    @ExceptionHandler(ModelUnavailableException.class)
    public ResponseEntity<ApiError> handleModel(ModelUnavailableException e) {
        String traceId = newTraceId();
        log.error("[{}] model unavailable: {}", traceId, e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiError.of("MODEL_UNAVAILABLE",
                        "지금은 답변을 만들 수 없습니다. 잠시 후 다시 시도해 주세요.", traceId));
    }

    /**
     * 매핑되지 않은 경로 — <b>404 다.</b>
     *
     * <p>이 핸들러가 없으면 아래 {@code Exception} 캐치올이 스프링의 404 예외까지 삼켜
     * <b>500 으로 보고한다.</b> 그러면 운영자가 "서버 장애"와 "경로 오타"를 구분할 수 없다.
     * 아직 구현되지 않은 엔드포인트를 부를 때도 마찬가지라, 개발 중에 특히 헷갈린다.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleNoResource(NoResourceFoundException e) {
        String traceId = newTraceId();
        log.info("[{}] no handler: {}", traceId, e.getResourcePath());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of("NOT_FOUND", "요청하신 경로가 없습니다.", traceId));
    }

    /** 스프링이 이미 상태코드를 정해 던진 예외는 그 상태코드를 지킨다. 500 으로 덮어쓰지 않는다. */
    @ExceptionHandler(ErrorResponseException.class)
    public ResponseEntity<ApiError> handleErrorResponse(ErrorResponseException e) {
        String traceId = newTraceId();
        log.info("[{}] {}: {}", traceId, e.getStatusCode(), e.getMessage());
        return ResponseEntity.status(e.getStatusCode())
                .body(ApiError.of("ERROR", "요청을 처리할 수 없습니다.", traceId));
    }

    /** 나머지 전부. 여기까지 왔다는 건 우리가 예상하지 못한 상황이라는 뜻이라 전문을 로그로 남긴다. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleRest(Exception e) {
        String traceId = newTraceId();
        log.error("[{}] unexpected: {}", traceId, e.toString(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.of("INTERNAL_ERROR", "처리 중 문제가 생겼습니다.", traceId));
    }

    private String newTraceId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
