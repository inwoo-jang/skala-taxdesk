package com.inwoo.taxdesk.domain;

/**
 * 금융소득의 종류. Gross-up 대상 판정이 여기서 갈린다.
 *
 * <p>과세 방식(비과세·분리과세·종합과세)은 여기가 아니라 {@link TaxTreatment} 가 들고 있다.
 * 소득의 <b>성격</b>과 <b>과세 방식</b>은 직교한다 — 국내배당이면서 ISA 안에 있으면 분리과세다.
 */
public enum IncomeType {

    /** 이자소득 — 예금·채권 등. Gross-up 대상이 아니다. */
    INTEREST,

    /**
     * 내국법인 배당 — 법인세가 과세된 재원의 이익배당. Gross-up 대상이 될 수 있다.
     * 단 <b>배당 전액이 아니라 2,000만원 초과분만</b>이다 ({@link GrossUp} 참조).
     */
    DOMESTIC_DIVIDEND,

    /**
     * 외국법인 배당 — Gross-up 대상이 <b>아니다.</b> 국내 법인세 이중과세 조정 구조와 무관하다.
     * 다만 원천지국에 낸 세금이 있으면 {@link TaxCredit.Foreign}(외국납부세액공제) 대상은 될 수 있다.
     * 둘을 혼동하면 "해외배당은 아무 혜택이 없다"는 틀린 안내가 나간다.
     */
    FOREIGN_DIVIDEND
}
