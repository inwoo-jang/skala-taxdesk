package com.inwoo.taxdesk.rag;

import java.util.HashMap;
import java.util.Map;

/**
 * Phase 2 — 청크 하나의 출처. <b>파일명만으로는 출처가 아니다.</b>
 *
 * <p>practice3 는 "shipping-policy.md 참고"로 충분했다. 세법은 다르다.
 * "소득세법 §14③ (2026 귀속)"까지 가야 고객이 확인할 수 있는 출처가 된다.
 * 거기에 <b>어느 시행본을 언제 대조했는가</b>까지 붙어야 "지금도 맞는 말인가"를 따질 수 있다.
 *
 * @param doc           문서 파일명
 * @param title         문서 제목
 * @param article       조문. 예: {@code 소득세법 §14③}
 * @param effectiveYear 귀속연도. 연도별 문서 세트 전환의 필터 키
 * @param kind          {@code LAW}(법령) 또는 {@code INTERNAL}(자체 안내). 거절 근거를 인용할 때 갈린다
 * @param lawVersion    법령 버전 (예: 소득세법 법률 제21221호, 시행 2026-07-01). 자체 안내면 빈 문자열
 * @param sourceUrl     원문 위치 (국가법령정보센터 등). 자체 안내면 빈 문자열
 * @param verifiedOn    원문과 대조한 날짜. 모르면 빈 문자열 — 지어내지 않는다
 */
public record DocumentMeta(String doc, String title, String article, int effectiveYear, String kind,
                           String lawVersion, String sourceUrl, String verifiedOn) {

    public static final String DOC = "doc";
    public static final String TITLE = "title";
    public static final String ARTICLE = "article";
    public static final String YEAR = "effectiveYear";
    public static final String KIND = "kind";
    public static final String LAW_VERSION = "lawVersion";
    public static final String SOURCE_URL = "sourceUrl";
    public static final String VERIFIED_ON = "verifiedOn";

    public static final String KIND_LAW = "LAW";
    public static final String KIND_INTERNAL = "INTERNAL";

    public DocumentMeta(String doc, String title, String article, int effectiveYear, String kind) {
        this(doc, title, article, effectiveYear, kind, "", "", "");
    }

    public DocumentMeta {
        lawVersion = lawVersion == null ? "" : lawVersion;
        sourceUrl = sourceUrl == null ? "" : sourceUrl;
        verifiedOn = verifiedOn == null ? "" : verifiedOn;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new HashMap<>();
        m.put(DOC, doc);
        m.put(TITLE, title);
        m.put(ARTICLE, article);
        m.put(YEAR, effectiveYear);
        m.put(KIND, kind);
        m.put(LAW_VERSION, lawVersion);
        m.put(SOURCE_URL, sourceUrl);
        m.put(VERIFIED_ON, verifiedOn);
        return m;
    }

    public static DocumentMeta from(Map<String, Object> m) {
        return new DocumentMeta(
                str(m, DOC), str(m, TITLE), str(m, ARTICLE),
                m.get(YEAR) instanceof Number n ? n.intValue() : 0, str(m, KIND),
                str(m, LAW_VERSION), str(m, SOURCE_URL), str(m, VERIFIED_ON));
    }

    public boolean isLaw() {
        return KIND_LAW.equals(kind);
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
