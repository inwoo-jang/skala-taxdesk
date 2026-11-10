package com.inwoo.taxdesk.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

/**
 * Phase 8 — 품질 기준선. 한 파일({@code src/test/resources/golden.json})에서 여러 종류를 다룬다.
 *
 * <ul>
 *   <li>{@code regulation} — 기대 조문이 출처에 들어 있는가 (모델 필요)</li>
 *   <li>{@code calculation} — 기대 세액과 <b>완전히</b> 일치하는가 (근사 허용 없음). {@code inputs} 가 있으면
 *       모델 없이 도구만으로도 채점한다</li>
 *   <li>{@code routing} — 기대 라우트. {@code offline=true} 면 규칙 단계만으로 결정되는 항목이다</li>
 *   <li>{@code safety} — 차단되어야 할 것이 차단됐는가 (업무 범위 밖 · 권한 밖 · 인젝션)</li>
 * </ul>
 *
 * <p>계산 항목만 완전 일치를 요구하는 이유는 검증표 마지막 줄 때문이다 — 세액 hallucination 0건.
 *
 * <p><b>검토 상태가 없는 기대값은 정답으로 집계하지 않는다.</b> 기대값이 어디서 왔는지(법령 원문 · 손계산 ·
 * 공식 예제 · 전문가 검토)를 {@code basis} 에 적고, {@code reviewStatus} 로 신뢰 수준을 표시한다.
 * "구현이 뱉은 값을 기대값으로 받아 적은" 항목은 {@code unreviewed} 로 두어 통과율에서 뺀다.
 */
public record GoldenSet(List<Item> items) {

    /** 기대값 검토 상태. */
    public static final String REVIEWED = "reviewed";          // 독립 검토(전문가·공식 예제) 완료
    public static final String SELF_CHECKED = "self-checked";  // 작성자가 법령 원문으로 손계산·검산. 전문가 검토 전
    public static final String UNREVIEWED = "unreviewed";      // 기대값 미검증. 통과율 분모에서 뺀다

    /**
     * @param id            항목 식별자
     * @param kind          regulation · calculation · routing · safety
     * @param question      질문
     * @param asUser        어떤 계정으로 물을 것인가 (소유자 격리 검증에 쓰인다)
     * @param inputs        계산 항목의 도구 입력 (financialIncome · grossUpEligibleDividend · otherIncome · deduction · prepaidTax).
     *                      있으면 모델 없이 {@code TaxCalcTools} 로 직접 채점한다
     * @param expectArticle 기대 조문 (regulation)
     * @param expectAmount  기대 결정세액 (calculation). 분리과세 종결이면 {@code null} 에 {@code expectTaxation=SEPARATE}
     * @param expectTaxation 기대 판정 (SEPARATE / COMPREHENSIVE)
     * @param expectRoute   기대 라우트 — OUT_OF_SCOPE 검증에 쓰인다
     * @param expectBlocked 차단 기대 여부 (safety)
     * @param offline       모델 없이 결정적으로 채점 가능한가 (규칙 라우팅 · 도구 계산 · 인젝션 차단)
     * @param reviewStatus  reviewed · self-checked · unreviewed
     * @param basis         기대값의 근거 (법령 조문 · 문서 · 가정)
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Item(String id,
                       String kind,
                       String question,
                       String asUser,
                       Map<String, Long> inputs,
                       String expectArticle,
                       Long expectAmount,
                       String expectTaxation,
                       String expectRoute,
                       Boolean expectBlocked,
                       Boolean offline,
                       String reviewStatus,
                       String basis) {

        public boolean isOffline() {
            return Boolean.TRUE.equals(offline);
        }

        /** 통과율 분모에 들어가는가. 미검토 항목은 들어가지 않는다. */
        public boolean countsTowardPassRate() {
            return REVIEWED.equals(reviewStatus) || SELF_CHECKED.equals(reviewStatus);
        }
    }
}
