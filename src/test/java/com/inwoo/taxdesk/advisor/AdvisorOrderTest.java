package com.inwoo.taxdesk.advisor;

/**
 * Phase 1·5 검증 — Advisor 체인 순서.
 *
 * <p>Audit(0) → Safety(100) → Memory(200) → QA(300) → TokenMeter(900).
 * {@code taxdesk.safety-order=250} 으로 바꾸면 차단된 문장이 기억에 남는 것까지 테스트로 보인다.
 */
class AdvisorOrderTest {

    // TODO Phase 1
}
