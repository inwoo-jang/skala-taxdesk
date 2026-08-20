package com.inwoo.taxdesk.tools;

import java.util.Map;
import org.springframework.ai.chat.model.ToolContext;

/**
 * Phase 7 — 도구가 쓸 "나"를 꺼낸다. <b>모델이 지어낸 ID 를 쓰지 않기 위한 장치다.</b>
 *
 * <p>사용자 ID 를 도구 파라미터로 받으면 모델이 아무 값이나 채울 수 있다.
 * 그래서 파라미터가 아니라 {@link ToolContext} 로 전달하고, 그 값은 서비스가
 * <b>인증 세션에서</b> 꺼내 넣는다. 모델은 이 값에 손댈 수 없다.
 */
public final class ToolUser {

    /** {@code ToolContext} 안에서 사용자 ID 가 들어 있는 자리. */
    public static final String KEY = "userId";

    private ToolUser() {
    }

    public static Map<String, Object> contextOf(String userId) {
        return Map.of(KEY, userId);
    }

    /**
     * @throws IllegalStateException 컨텍스트에 사용자가 없을 때. 익명으로 남의 계좌를 조회하는
     *                               경로를 만들지 않기 위해 조용히 넘어가지 않고 터뜨린다
     */
    public static String of(ToolContext context) {
        Object v = context == null ? null : context.getContext().get(KEY);
        if (v == null || v.toString().isBlank()) {
            throw new IllegalStateException("도구 호출에 사용자 컨텍스트가 없습니다.");
        }
        return v.toString();
    }
}
