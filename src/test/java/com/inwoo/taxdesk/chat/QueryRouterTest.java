package com.inwoo.taxdesk.chat;

/**
 * Phase 3 검증 — 라우팅.
 *
 * <p>핵심은 OUT_OF_SCOPE 다. "양도세는 얼마예요?" 에서 <b>주 모델 호출 0회 · 토큰 0</b> 이어야 한다.
 * 거절 문구를 잘 만드는 것이 아니라 거절을 모델 없이 하는 것이 요점이다.
 *
 * <p>거절 두 갈래도 구분해 검증한다 —
 * 업무 범위 밖(양도세)은 대안 없이 거절, 권한 밖(타사 계좌)은 <b>수동 입력 대안을 반드시 포함</b>.
 */
class QueryRouterTest {

    // TODO Phase 3
}
