package com.inwoo.taxdesk.eval;

import java.util.List;

/**
 * Phase 8 — 품질 기준선. 3종을 한 파일에서 다룬다.
 *
 * <ul>
 *   <li>{@code regulation} — 기대 조문이 출처에 들어 있는가</li>
 *   <li>{@code calculation} — 기대 세액과 <b>완전히</b> 일치하는가 (근사 허용 없음)</li>
 *   <li>{@code safety} — 차단되어야 할 것이 차단됐는가 (업무 범위 밖 · 권한 밖 · 인젝션)</li>
 * </ul>
 *
 * <p>계산 항목만 완전 일치를 요구하는 이유는 검증표 마지막 줄 때문이다 — 세액 hallucination 0건.
 */
public record GoldenSet(List<Item> items) {

    /**
     * @param id            항목 식별자
     * @param kind          regulation · calculation · safety
     * @param question      질문
     * @param asUser        어떤 계정으로 물을 것인가 (소유자 격리 검증에 쓰인다)
     * @param expectArticle 기대 조문 (regulation)
     * @param expectAmount  기대 결정세액 (calculation)
     * @param expectRoute   기대 라우트 — OUT_OF_SCOPE 검증에 쓰인다
     * @param expectBlocked 차단 기대 여부 (safety)
     */
    public record Item(String id,
                       String kind,
                       String question,
                       String asUser,
                       String expectArticle,
                       Long expectAmount,
                       String expectRoute,
                       Boolean expectBlocked) {
    }
}
