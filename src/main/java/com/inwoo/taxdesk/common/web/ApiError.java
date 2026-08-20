package com.inwoo.taxdesk.common.web;

import java.time.Instant;

/**
 * 바깥으로 나가는 오류 표현. 스택트레이스도, 내부 클래스명도 나가지 않는다.
 *
 * @param code    분류 코드 (클라이언트가 분기하는 값)
 * @param message 사람이 읽는 한 줄 — 존재 여부 같은 정보를 흘리지 않게 쓴다
 * @param traceId 로그와 이어 붙일 열쇠. 사용자가 이 값만 알려 주면 로그를 찾을 수 있다
 */
public record ApiError(String code, String message, String traceId, Instant at) {

    public static ApiError of(String code, String message, String traceId) {
        return new ApiError(code, message, traceId, Instant.now());
    }
}
