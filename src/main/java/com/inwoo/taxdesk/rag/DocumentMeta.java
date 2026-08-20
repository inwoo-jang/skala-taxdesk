package com.inwoo.taxdesk.rag;

import java.util.Map;

/**
 * Phase 2 — 청크 하나의 출처. <b>파일명만으로는 출처가 아니다.</b>
 *
 * <p>practice3 는 "shipping-policy.md 참고"로 충분했다. 세법은 다르다.
 * "소득세법 §14③ (2026 귀속)"까지 가야 고객이 확인할 수 있는 출처가 된다.
 *
 * @param doc           문서 파일명
 * @param title         문서 제목
 * @param article       조문. 예: {@code 소득세법 §14③}
 * @param effectiveYear 귀속연도. 연도별 문서 세트 전환의 필터 키
 * @param kind          {@code LAW}(법령) 또는 {@code INTERNAL}(자체 안내). 거절 근거를 인용할 때 갈린다
 */
public record DocumentMeta(String doc, String title, String article, int effectiveYear, String kind) {

    public static final String DOC = "doc";
    public static final String TITLE = "title";
    public static final String ARTICLE = "article";
    public static final String YEAR = "effectiveYear";
    public static final String KIND = "kind";

    public Map<String, Object> toMap() {
        return Map.of(DOC, doc, TITLE, title, ARTICLE, article, YEAR, effectiveYear, KIND, kind);
    }

    public static DocumentMeta from(Map<String, Object> m) {
        return new DocumentMeta(
                str(m, DOC), str(m, TITLE), str(m, ARTICLE),
                m.get(YEAR) instanceof Number n ? n.intValue() : 0, str(m, KIND));
    }

    /** 답변 말미에 붙는 형태. 조문이 없으면 붙이지 않는다 — 없는 출처를 지어내지 않기 위해서다. */
    public String citation() {
        return article == null || article.isBlank()
                ? title
                : "%s (%d 귀속)".formatted(article, effectiveYear);
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? "" : v.toString();
    }
}
