package com.inwoo.taxdesk.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import java.time.LocalDateTime;

/**
 * 절세상품 가입 <b>자격 확인</b> 신청.
 *
 * <p>이 실습의 승인 게이트다. 챗봇이 "가입 가능하십니다"라고 확정하면 안 되는 이유가
 * 설계 취향이 아니라 <b>법</b>에 있다 — 조특법 §88의2③ 은 "가입대상의 확인"을 별도 절차로 둔다.
 * 자격 요건(65세 이상 기초연금 수급자 · 등록 장애인 · 국가유공자 …)은 전부 <b>서류로</b>
 * 확인해야 하고, 챗봇은 그 서류를 볼 수 없다.
 *
 * <p>도구는 {@link ApplicationStatus#PENDING} 까지만 만든다.
 * {@code APPROVED} / {@code REJECTED} 는 ADMIN 경로를 거쳐야 한다.
 *
 * <p>왜 세무사 상담 예약이 아니라 이것인가 — 증권사가 세무사 상담을 중개하는 것은
 * 실제 업무가 아니다. 반면 <b>자사 절세상품의 가입 자격 확인</b>은 증권사 창구가 매일 하는 일이고,
 * 자격 미달 시 <b>거절</b>이 정상적으로 발생한다. 게이트가 실제로 작동하는 모습을 보여줄 수 있다.
 */
@Entity
public class ProductApplication {

    @Id
    private String id;

    /** 신청자. 프롬프트가 아니라 쿼리가 이 값을 강제한다. */
    private String ownerId;

    @Enumerated(EnumType.STRING)
    private TaxSaverProduct product;

    /** 신청 사유 (고객 문장 요약). 상담 창구가 맥락을 알고 시작하게 한다. */
    private String reason;

    /** 신청 시점에 안내했던 예상 결정세액 — 왜 절세를 문의했는지의 근거. */
    private long estimatedTax;

    @Enumerated(EnumType.STRING)
    private ApplicationStatus status;

    /** 거절 사유. {@link ApplicationStatus#REJECTED} 일 때만 채워진다 — 거절에도 이유를 준다. */
    private String decisionNote;

    private LocalDateTime requestedAt;

    protected ProductApplication() {
        // JPA
    }

    public ProductApplication(String id, String ownerId, TaxSaverProduct product,
                              String reason, long estimatedTax, LocalDateTime requestedAt) {
        this.id = id;
        this.ownerId = ownerId;
        this.product = product;
        this.reason = reason;
        this.estimatedTax = estimatedTax;
        this.status = ApplicationStatus.PENDING;
        this.requestedAt = requestedAt;
    }

    /** ADMIN 경로에서만 호출된다. 도구에는 이 메서드로 가는 길이 없다. */
    public void decide(ApplicationStatus decision, String note) {
        if (decision == ApplicationStatus.PENDING) {
            throw new IllegalArgumentException("PENDING 으로는 되돌릴 수 없다");
        }
        this.status = decision;
        this.decisionNote = note;
    }

    public String getId() {
        return id;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public TaxSaverProduct getProduct() {
        return product;
    }

    public String getReason() {
        return reason;
    }

    public long getEstimatedTax() {
        return estimatedTax;
    }

    public ApplicationStatus getStatus() {
        return status;
    }

    public String getDecisionNote() {
        return decisionNote;
    }

    public LocalDateTime getRequestedAt() {
        return requestedAt;
    }
}
