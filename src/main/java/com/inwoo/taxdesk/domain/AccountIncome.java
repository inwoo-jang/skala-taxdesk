package com.inwoo.taxdesk.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;

/**
 * SKALA증권 계좌에서 발생한 금융소득 한 건 (계좌 × 상품 × 귀속연도).
 *
 * <p><b>자사 계좌만 담긴다.</b> 타사 소득은 이 테이블에 들어올 수 없고, 들어올 경로도 없다.
 * "타사는 조회하지 않는다"를 프롬프트로 부탁하는 대신 데이터 모델에서 못 하게 만든 것이다.
 *
 * <p>{@code ownerId} 가 이 실습의 핵심 필드다 — 타인 계좌 조회 차단이 이 컬럼 하나에 걸려 있다.
 * 값은 절대 사용자 입력이나 모델 출력에서 오지 않는다. 인증된 세션에서만 온다.
 */
@Entity
public class AccountIncome {

    @Id
    private String id;

    /** 소유자. 프롬프트가 아니라 쿼리가 이 값을 강제한다. */
    private String ownerId;

    /** 계좌번호. 응답으로 나갈 때는 Masker 를 거친다. */
    private String accountNo;

    /** 금융기관명. 마이데이터 연동분은 자사가 아니므로 어디 것인지 밝혀야 한다. */
    private String institution;

    /**
     * 이 금액을 우리가 어떻게 알게 됐는가. 계산 전개에 그대로 표시된다 —
     * "저희가 확인한 금액"과 "고객님이 말씀하신 금액"이 답변에서 섞이면 안 된다.
     */
    @Enumerated(EnumType.STRING)
    private IncomeSource source;

    /**
     * 귀속연도. 세율표·문서 세트가 연도별로 갈린다.
     *
     * <p>컬럼명을 바꾼 이유: {@code year} 는 H2 의 <b>예약어</b>라 그대로 두면
     * {@code create table} 이 실패한다. 필드명은 도메인 언어를 지키고 컬럼명만 피한다.
     */
    @Column(name = "income_year")
    private int year;

    /** 상품명 (예: 종합매매계좌 예탁금이용료, 국내주식 배당, 해외ETF 분배금) */
    private String productName;

    @Enumerated(EnumType.STRING)
    private IncomeType type;

    /** 지급액 (원) */
    private long amount;

    /** 이미 원천징수된 세액 (원) */
    private long withheldTax;

    /**
     * 과세 방식. 비과세와 분리과세를 boolean 하나로 뭉개면 답변 문구가 틀린다 —
     * "세금이 없습니다"(비과세)와 "9%로 과세가 끝났습니다"(ISA 초과분 분리과세)는 다른 말이다.
     */
    @Enumerated(EnumType.STRING)
    private TaxTreatment treatment;

    protected AccountIncome() {
        // JPA
    }

    public AccountIncome(String id, String ownerId, String accountNo, int year,
                         String institution, IncomeSource source, String productName,
                         IncomeType type, long amount, long withheldTax, TaxTreatment treatment) {
        this.id = id;
        this.ownerId = ownerId;
        this.accountNo = accountNo;
        this.institution = institution;
        this.source = source;
        this.year = year;
        this.productName = productName;
        this.type = type;
        this.amount = amount;
        this.withheldTax = withheldTax;
        this.treatment = treatment;
    }

    public String getId() {
        return id;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public String getAccountNo() {
        return accountNo;
    }

    public int getYear() {
        return year;
    }

    public String getProductName() {
        return productName;
    }

    public IncomeType getType() {
        return type;
    }

    public long getAmount() {
        return amount;
    }

    public long getWithheldTax() {
        return withheldTax;
    }

    public String getInstitution() {
        return institution;
    }

    public IncomeSource getSource() {
        return source;
    }

    public TaxTreatment getTreatment() {
        return treatment;
    }

    /** 종합과세 판정(2,000만원 초과 여부)에 합산되는가. 비과세·분리과세는 빠진다. */
    public boolean countsTowardThreshold() {
        return treatment == TaxTreatment.TAXABLE;
    }
}
