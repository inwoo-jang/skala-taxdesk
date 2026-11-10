package com.inwoo.taxdesk.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

/**
 * Phase 8 — 품질 기준선. 한 파일({@code src/test/resources/golden.json})에서 여러 종류를 다룬다.
 *
 * <ul>
 *   <li>{@code regulation} — 기대 조문을 답변이 실제로 인용했는가 (모델·색인 필요 → {@code -Peval})</li>
 *   <li>{@code calculation} — 기대 결정세액과 <b>완전히</b> 일치하는가. {@code inputs} 로 도구만 채점한다</li>
 *   <li>{@code classification} — 2천만원 기준 판정(SEPARATE/COMPREHENSIVE). {@code inputs} 로 도구만 채점한다</li>
 *   <li>{@code routing} · {@code safety} — 규칙 단계만으로 정해지는 라우트·차단. 라우터 모델은 부르지 않는다</li>
 *   <li>{@code flow} — 서비스 흐름: 되묻기·보류·단서·쓰기 확인·대화 복귀·계정 격리. 모델 대역으로 돈다</li>
 * </ul>
 *
 * <p><b>검토 상태가 없는 기대값은 정답으로 집계하지 않는다.</b> 기대값이 어디서 왔는지를 {@code basis} 에 적고
 * {@code reviewStatus} 로 신뢰 수준을 표시한다.
 * <ul>
 *   <li>{@code spec} — 기대값이 세법이 아니라 <b>서비스 규칙</b>(개선계획의 요구사항)에서 정해진다.
 *       "타사 미확인이면 단서를 붙인다"는 세무 판단이 아니라 설계 결정이므로 통과율에 넣는다</li>
 *   <li>{@code reviewed} · {@code self-checked} — 세무 기대값을 사람이 검토했다</li>
 *   <li>{@code unreviewed} — 세무 기대값이 검토 전이다. 결과는 보고하되 통과율에서 뺀다</li>
 * </ul>
 *
 * <p>{@code split} — {@code tuning} 은 개발 중 보고 고쳐도 되는 항목, {@code holdout} 은 규칙·프롬프트를
 * 고칠 때 보지 않고 마지막에만 확인하는 항목이다. 둘을 따로 집계해 과적합을 드러낸다.
 */
public record GoldenSet(List<Item> items) {

    /** 기대값 검토 상태. */
    public static final String REVIEWED = "reviewed";          // 독립 검토(전문가·공식 예제) 완료
    public static final String SELF_CHECKED = "self-checked";  // 작성자가 법령 원문으로 손계산·검산. 전문가 검토 전
    public static final String UNREVIEWED = "unreviewed";      // 기대값 미검증. 통과율 분모에서 뺀다
    public static final String SPEC = "spec";                  // 기대값이 서비스 규칙에서 정해진다 (세무 판단 아님)

    /**
     * @param id                  항목 식별자
     * @param kind                regulation · calculation · classification · routing · safety · flow
     * @param split               tuning · holdout
     * @param question            마지막으로 묻는 문장 (채점 대상 턴)
     * @param turns               그 앞에 같은 대화방에서 보내는 문장들 (flow)
     * @param seed                계좌 시드: antman(분리과세) · isabu(종합과세+ISA) · empty · failing
     * @param asUser              묻는 계정. 시드는 antman 계정에 들어가므로 다른 계정이면 격리 검증이 된다
     * @param routerReply         라우터 모델 대역의 답. 비우면 규칙 단계만 쓰인다 (모델 단계는 실패로 폴백)
     * @param inputs              도구 입력 — calculation: financialIncome · grossUpEligibleDividend · otherIncome ·
     *                            deduction · prepaidTax / classification: interest · domesticDividend ·
     *                            foreignDividend · externalAmount
     * @param expectArticle       기대 조문 (regulation)
     * @param expectAmount        기대 결정세액 (calculation)
     * @param expectTaxation      기대 판정 (SEPARATE / COMPREHENSIVE)
     * @param expectRoute         기대 라우트
     * @param expectBlocked       차단 기대 여부 (safety)
     * @param expectModelCall     주 모델이 불려야 하는가 (false = 모델 없이 끝나야 한다)
     * @param expectCalculation   세액 계산 결과가 있어야 하는가
     * @param expectWrites        저장소에 남아야 할 신청 건수
     * @param expectPending       신청 미리보기가 떠야 하는가
     * @param expectNotice        notices 중 하나에 들어 있어야 할 문구
     * @param expectNoNotice      notices 어디에도 없어야 할 문구
     * @param expectAnswer        답변에 들어 있어야 할 문구 (모델을 거치지 않는 고정 답변일 때만 의미가 있다)
     * @param expectToolResult    "도구이름=결과" — 그 도구가 그 결과로 불렸어야 한다
     * @param expectNotInResponse 응답 전체(답변·도구·현황)에 나오면 안 되는 문자열 — 남의 금액 등
     * @param offline             모델·네트워크 없이 채점 가능한가
     * @param reviewStatus        spec · reviewed · self-checked · unreviewed
     * @param basis               기대값의 근거 (요구사항 · 법령 조문 · 계산 전개 · 가정)
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Item(String id,
                       String kind,
                       String split,
                       String question,
                       List<String> turns,
                       String seed,
                       String asUser,
                       String routerReply,
                       Map<String, Long> inputs,
                       String expectArticle,
                       Long expectAmount,
                       String expectTaxation,
                       String expectRoute,
                       Boolean expectBlocked,
                       Boolean expectModelCall,
                       Boolean expectCalculation,
                       Integer expectWrites,
                       Boolean expectPending,
                       String expectNotice,
                       String expectNoNotice,
                       String expectAnswer,
                       String expectToolResult,
                       String expectNotInResponse,
                       Boolean offline,
                       String reviewStatus,
                       String basis) {

        public boolean isOffline() {
            return Boolean.TRUE.equals(offline);
        }

        /** 통과율 분모에 들어가는가. 미검토 항목은 들어가지 않는다. */
        public boolean countsTowardPassRate() {
            return REVIEWED.equals(reviewStatus) || SELF_CHECKED.equals(reviewStatus) || SPEC.equals(reviewStatus);
        }
    }
}
