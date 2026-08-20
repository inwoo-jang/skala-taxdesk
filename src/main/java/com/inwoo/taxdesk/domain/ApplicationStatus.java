package com.inwoo.taxdesk.domain;

/**
 * 절세상품 가입 자격 확인 신청의 상태. 도구가 만들 수 있는 것은 {@link #PENDING} 뿐이다.
 *
 * <p><b>{@link #REJECTED} 가 예외가 아니라 정상 경로라는 점이 중요하다.</b>
 * 비과세종합저축은 자격 요건이 열거돼 있어(조특법 §88의2①) 해당하지 않는 고객이 훨씬 많다.
 * 승인만 있고 거절이 없는 게이트는 게이트가 아니라 결재 도장이다.
 */
public enum ApplicationStatus {

    /** 접수됨. 도구가 만들 수 있는 유일한 상태. */
    PENDING,

    /** 담당자가 서류로 자격을 확인함. 가입 안내가 나간다. */
    APPROVED,

    /** 자격 미달. 사유와 함께 안내한다 — 거절도 응대다. */
    REJECTED
}
