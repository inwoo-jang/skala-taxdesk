package com.inwoo.taxdesk.domain;

/**
 * <b>연중 세무 플래닝</b>의 결과. 이 서비스의 핵심 기능이다.
 *
 * <p>지금까지의 계산은 전부 <b>순방향</b>이었다 — "얼마 받았으니 세금이 얼마다".
 * 그런데 고객이 8월에 정말 궁금한 것은 <b>역방향</b>이다:
 * <b>"앞으로 얼마까지 받아도 괜찮은가."</b> 연말 정산 안내는 이미 늦은 정보다.
 *
 * <p>두 종류의 문턱을 알려준다.
 * <pre>
 *   [분리과세 유지 중]  금융소득 1,525만
 *     → 종합과세 전환까지 474만원 여유. 배당을 474만원 넘게 더 받으면 종합과세로 전환된다
 *
 *   [이미 종합과세]     과세표준 7,015만 (24% 구간)
 *     → 다음 구간(8,800만)까지 1,784만원 여유. 넘으면 24% → 35%
 * </pre>
 *
 * <p>이 계산은 <b>LLM 이 절대 못 한다.</b> 순방향 산수도 못 미더운데 역산은 말할 것도 없다.
 * "도구가 없으면 답이 안 나오는 질문"의 가장 선명한 사례라 이 실습의 논지를 강화한다.
 *
 * <p><b>투자권유가 아니라는 점이 중요하다.</b> "배당주를 더 사세요"가 아니라
 * "세제 관점에서 여유가 474만원입니다"다. 종목도, 매수 시점도 말하지 않는다.
 * 자본시장법 울타리 안에서 <b>의사결정에 실제로 쓸모 있는 답</b>을 주는 지점이 여기다.
 *
 * @param currentIncome     현재까지 확인된 과세대상 금융소득
 * @param nextThreshold     다음 문턱 금액 (종합과세 기준 2,000만 또는 다음 누진구간 상한)
 * @param headroom          문턱까지 남은 금액. <b>음수면 이미 넘었다</b>
 * @param thresholdKind     문턱의 종류
 * @param currentRate       현재 적용 세율
 * @param nextRate          문턱을 넘었을 때 적용될 세율
 * @param taxIfCrossed      문턱을 딱 넘었을 때의 예상 결정세액 (넘는 순간의 비용)
 */
public record TaxSimulation(long currentIncome,
                            long nextThreshold,
                            long headroom,
                            Kind thresholdKind,
                            double currentRate,
                            double nextRate,
                            long taxIfCrossed) {

    public enum Kind {
        /** 2,000만원 종합과세 기준. 넘으면 분리과세 종결 → 종합과세로 성격이 바뀐다 */
        COMPREHENSIVE_ENTRY,
        /** 누진세율 구간 경계. 이미 종합과세 대상인 고객 */
        BRACKET_STEP,
        /** 최고 구간이라 더 넘을 문턱이 없다 */
        NONE
    }

    /** 이미 문턱을 넘었는가. */
    public boolean crossed() {
        return headroom < 0;
    }
}
